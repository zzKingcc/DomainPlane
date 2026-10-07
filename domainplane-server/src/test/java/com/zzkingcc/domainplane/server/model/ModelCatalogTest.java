package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型目录：只读提供商元数据，<b>按 OpenAI 规范对齐，但不猜语义</b>。
 *
 * <p>这里覆盖三种元数据形态（OpenRouter 带 architecture / 硅基流动式 sub_type 过滤 /
 * 只有 id 的通用 OpenAI 规范），最容易出错的保护 —— 提供商不认识 {@code sub_type}
 * 时会忽略参数回全量清单，若不识别就会把<b>所有</b>端点族都标上 —— 以及 OpenAI 规范化本身：
 * 模型名按「去空白 + 忽略大小写 + 忽略 vendor/ 前缀」匹配（对应多条即报歧义）、
 * 地址缺 {@code /v1} 时补一次重试。</p>
 *
 * <p>另外钉住三条"结论表达"的规矩：读不到的能力必须在说明里点名；sub_type 给的模态是
 * <b>基线值</b>不能当完整枚举（否则一次「读取」会抹掉用户为 VLM 手填的 image）；
 * 只有向量档案才值得为维度多发一次请求。</p>
 *
 * <p>不联网：解析与决策都是纯函数（{@link ModelCatalog#fromArchitecture}、
 * {@link ModelCatalog#decideBySubType}、{@link ModelCatalog#matchByName}、
 * {@link ModelCatalog#appendV1}），只有失败路径才碰一次必然被拒的本地端口。</p>
 *
 * @author zzkingcc
 */
class ModelCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== OpenRouter 风格 ====================

    @Test
    void 元数据带reasoning对象时读出思考能力() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"id":"qwen/qwen3-8b",
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                 "supported_parameters":["temperature","tools"],
                 "reasoning":{"mandatory":false,"default_enabled":true}}
                """));

        assertTrue(r.capabilities().contains(ModelProfile.CAP_REASONING),
                "带 reasoning 对象就是支持思考（qwen3-8b 实测就是这个形状），实际 " + r.capabilities());
        assertTrue(r.capabilities().contains(ModelProfile.CAP_TOOLS),
                "思考与工具是两项独立能力，不能互相顶掉，实际 " + r.capabilities());
    }

    @Test
    void 元数据reasoning为null时不标思考能力() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"id":"some/plain-model",
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                 "supported_parameters":["temperature"],
                 "reasoning":null}
                """));

        assertFalse(r.capabilities().contains(ModelProfile.CAP_REASONING),
                "reasoning 为 null = 这家明确写了「没有思考」；字段压根缺失同理。两种都不标 —— "
                        + "管控台据此不显示思考开关，而不是显示成「已关闭」让用户以为关掉了，"
                        + "实际 " + r.capabilities());
    }

    @Test
    void 带模态数组时按数组判定并读出工具能力() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"id":"qwen/qwen3.8-27b:free",
                 "architecture":{"input_modalities":["text","image","video"],"output_modalities":["text"]},
                 "supported_parameters":["temperature","tools","top_p"]}
                """));

        assertEquals(List.of(ModelProfile.EP_CHAT), r.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE, ModelProfile.MOD_VIDEO), r.input(),
                "输入模态要完整 —— 旧探测只试图片，视频永远探不到");
        assertEquals(List.of(ModelProfile.MOD_TEXT), r.output());
        assertEquals(List.of(ModelProfile.CAP_TOOLS), r.capabilities());
        assertNull(r.dimension(), "向量维度元数据不提供，应为 null 而不是编一个");
    }

    @Test
    void 只给modality字符串时从箭头两侧解析() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{"modality":"text+image+video->text"}}
                """));

        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE, ModelProfile.MOD_VIDEO), r.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), r.output());
        assertEquals(List.of(ModelProfile.EP_CHAT), r.endpoints());
    }

    @Test
    void 多模态输出的对话模型仍归对话端点() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{"input_modalities":["text"],"output_modalities":["text","image"]}}
                """));

        assertEquals(List.of(ModelProfile.EP_CHAT), r.endpoints(),
                "有文本输出就是对话端点；输出还能出图只是它的额外能力，不该另立生图端点");
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), r.output());
    }

    @Test
    void 只输出图片的模型归生图端点而不是对话() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{"input_modalities":["text"],"output_modalities":["image"]}}
                """));

        assertEquals(List.of(ModelProfile.EP_IMAGES), r.endpoints());
        assertFalse(r.endpoints().contains(ModelProfile.EP_CHAT), "没有文本输出就不该判成对话端点");
    }

    @Test
    void 输出向量的模型归向量端点() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{"input_modalities":["text"],"output_modalities":["embeddings"]}}
                """));

        assertEquals(List.of(ModelProfile.EP_EMBEDDING), r.endpoints());
        assertEquals(List.of(ModelProfile.MOD_EMBEDDING), r.output());
    }

    @Test
    void 不认识的模态词直接丢弃而不是猜() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{"input_modalities":["text","hologram"],"output_modalities":["text"]}}
                """));

        assertEquals(List.of(ModelProfile.MOD_TEXT), r.input(), "hologram 不认识就该丢，不能塞个原样字符串");
        assertEquals(List.of(ModelProfile.MOD_TEXT), r.output());
    }

    /**
     * 输出分数的模型归重排端点。
     *
     * <p>变异验证点：把 {@code endpointsOf} 里判分数输出的那一行删掉，
     * {@link #输出分数的模型归重排端点()} 就会变红 —— 分数既不是文本也不是向量，
     * 不单独判的话端点族会退化成"未声明"。</p>
     */
    @Test
    void 输出分数的模型归重排端点() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{"input_modalities":["text"],"output_modalities":["score"]}}
                """));
        assertEquals(List.of(ModelProfile.EP_RERANK), r.endpoints(),
                "分数输出唯一对应重排端点；没有文本 / 向量输出时不能判成对话或向量");
        assertEquals(List.of(ModelProfile.MOD_SCORE), r.output());
    }

    /** 服务商响应里的字段名 {@code relevance_score} 也要认，否则同一件事会被当成读不到 */
    @Test
    void relevanceScore也算分数模态() {
        assertEquals(ModelProfile.MOD_SCORE, ModelCatalog.modOf("relevance_score"));
        assertEquals(ModelProfile.MOD_SCORE, ModelCatalog.modOf("Score"));
    }

    @Test
    void 没有tools声明时能力留空而不是假设支持() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                 "supported_parameters":["temperature"]}
                """));

        assertTrue(r.capabilities().isEmpty(), "未声明就是未声明，实际 " + r.capabilities());
    }

    @Test
    void architecture里什么都没有时端点族留空() {
        ModelCatalog.CatalogResult r = ModelCatalog.fromArchitecture(json("""
                {"architecture":{}}
                """));

        assertTrue(r.endpoints().isEmpty(), "读不到类型就留空，交给用户声明");
        assertTrue(r.input().isEmpty() && r.output().isEmpty());
    }

    // ==================== 过滤式（硅基流动这类） ====================

    private static Map<String, Set<String>> subTypes(Set<String> chat, Set<String> embedding,
                                                      Set<String> textToImage) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("chat", chat);
        m.put("embedding", embedding);
        m.put("reranker", Set.of());
        m.put("text-to-image", textToImage);
        m.put("image-to-image", Set.of());
        m.put("speech-to-text", Set.of());
        m.put("text-to-video", Set.of());
        return m;
    }

    @Test
    void 过滤器被忽略时整体作废而不是把所有端点都标上() {
        Set<String> all = Set.of("m1", "m2", "m3");
        /* 提供商不认识 sub_type：每个子类型都回全量清单 */
        Map<String, Set<String>> ignored = subTypes(all, all, all);
        ignored.put("reranker", all);
        ignored.put("image-to-image", all);
        ignored.put("speech-to-text", all);
        ignored.put("text-to-video", all);

        assertNull(ModelCatalog.decideBySubType("m1", all, ignored),
                "过滤没生效时必须返回 null（读不到），否则会把所有端点族都标上");
    }

    @Test
    void 过滤生效时按子类型判定端点族并按端点规范填模态() {
        Set<String> all = Set.of("chat1", "embed1", "img1");
        Map<String, Set<String>> sub = subTypes(Set.of("chat1"), Set.of("embed1"), Set.of("img1"));

        ModelCatalog.CatalogResult chat = ModelCatalog.decideBySubType("chat1", all, sub);
        assertNotNull(chat);
        assertEquals(List.of(ModelProfile.EP_CHAT), chat.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT), chat.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), chat.output(),
                "已判定走 /chat/completions，按端点规范其产出就是文本 —— 留空则连普通对话模型"
                        + "都要人去补一遍，而清单里绝大多数模型恰恰是普通的");

        ModelCatalog.CatalogResult embed = ModelCatalog.decideBySubType("embed1", all, sub);
        assertNotNull(embed);
        assertEquals(List.of(ModelProfile.EP_EMBEDDING), embed.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT), embed.input(), "/embeddings 的入参是文本");
        assertEquals(List.of(ModelProfile.MOD_EMBEDDING), embed.output(),
                "embedding 子类型名本身就说明了产出是向量");

        ModelCatalog.CatalogResult img = ModelCatalog.decideBySubType("img1", all, sub);
        assertNotNull(img);
        assertEquals(List.of(ModelProfile.EP_IMAGES), img.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT), img.input(), "/images/generations 收的是文本提示词");
        assertEquals(List.of(ModelProfile.MOD_IMAGE), img.output());
    }

    @Test
    void 过滤生效但一个子类型都没命中时返回null() {
        Set<String> all = Set.of("chat1", "embed1");
        Map<String, Set<String>> sub = subTypes(Set.of("chat1"), Set.of("embed1"), Set.of());

        assertNull(ModelCatalog.decideBySubType("ghost", all, sub));
    }

    @Test
    void 同时命中多个子类型时端点族可多选() {
        Set<String> all = Set.of("multi", "other");
        Map<String, Set<String>> sub = subTypes(Set.of("multi"), Set.of(), Set.of("multi"));

        ModelCatalog.CatalogResult r = ModelCatalog.decideBySubType("multi", all, sub);
        assertNotNull(r);
        assertTrue(r.endpoints().contains(ModelProfile.EP_CHAT) && r.endpoints().contains(ModelProfile.EP_IMAGES),
                "多模态模型会命中多个端点族，实际 " + r.endpoints());
    }

    // ==================== OpenAI 规范化：模型名与地址 ====================

    @Test
    void 模型名先精确匹配() {
        JsonNode data = json("[{\"id\":\"Qwen/Qwen3-8B\"}]");
        ModelCatalog.NameMatch m = ModelCatalog.matchByName(data, "  Qwen/Qwen3-8B  ");
        assertNotNull(m.entry());
        assertFalse(m.normalized(), "只有空白差异时仍算精确匹配 —— 用户填的 id 是对的，不该报'规范化对上'");
    }

    @Test
    void 模型名忽略大小写与vendor前缀后仍能命中() {
        JsonNode data = json("[{\"id\":\"Qwen/Qwen3-8B\"}]");
        assertTrue(ModelCatalog.matchByName(data, "qwen/qwen3-8b").normalized(), "大小写不同");
        assertTrue(ModelCatalog.matchByName(data, "Qwen3-8B").normalized(), "少写 vendor/ 前缀");
        assertNotNull(ModelCatalog.matchByName(data, "Qwen3-8B").entry());
    }

    @Test
    void 规范化后对应多个模型时报歧义而不是挑一条() {
        JsonNode data = json("[{\"id\":\"a/Qwen3-8B\"},{\"id\":\"b/Qwen3-8B\"}]");
        ModelCatalog.NameMatch m = ModelCatalog.matchByName(data, "Qwen3-8B");
        assertNull(m.entry(), "挑一条等于悄悄绑到别的模型上");
        assertTrue(m.ambiguous());
        assertEquals(List.of("a/Qwen3-8B", "b/Qwen3-8B"), m.candidates(), "候选要如实报出来");
    }

    @Test
    void 一个都没命中时不算歧义() {
        JsonNode data = json("[{\"id\":\"a/x\"}]");
        ModelCatalog.NameMatch m = ModelCatalog.matchByName(data, "y");
        assertNull(m.entry());
        assertFalse(m.ambiguous(), "0 条命中是'没找到'，不是歧义 —— 两者要说不同的话");
    }

    @Test
    void 地址缺v1时补一次且不重复补() {
        assertEquals("https://api.example.com/v1", ModelCatalog.appendV1("https://api.example.com"));
        assertEquals("https://api.example.com/v1", ModelCatalog.appendV1("https://api.example.com/"));
        assertEquals("https://api.example.com/openai/v1", ModelCatalog.appendV1("https://api.example.com/openai"));
        assertNull(ModelCatalog.appendV1("https://api.example.com/v1"));
        assertNull(ModelCatalog.appendV1("https://api.example.com/v1/"), "已经是 v1 就该没有别的候选可试");
    }

    // ==================== 规范化后的结论：说清读到什么、没读到什么 ====================

    private static ModelCatalog.CatalogResult chatResult() {
        return ModelCatalog.decideBySubType("chat1", Set.of("chat1", "embed1"),
                subTypes(Set.of("chat1"), Set.of("embed1"), Set.of()));
    }

    private static ModelCatalog.CatalogResult embeddingResult() {
        return ModelCatalog.decideBySubType("embed1", Set.of("chat1", "embed1"),
                subTypes(Set.of("chat1"), Set.of("embed1"), Set.of()));
    }

    @Test
    void 读不到的能力必须在说明里点名而不是沉默留空() {
        ModelCatalog.CatalogResult r = chatResult();
        assertTrue(r.capabilities().isEmpty());
        assertTrue(r.note().contains("streaming") && r.note().contains("tools"),
                "布尔能力读不到必须点名，否则一次什么都没变的'读取'看起来就像按钮坏了；实际：" + r.note());
        assertTrue(r.note().contains("保持未声明"),
                "还要说清这些项会保持未声明 —— 表单已无手工入口，"
                        + "文案指一条走不通的路比不说更糟；实际：" + r.note());
        assertFalse(r.note().contains("手工勾选"),
                "不该再让人去手工勾选：管控台已取消手工声明入口；实际：" + r.note());
    }

    /**
     * 每个子类型的模态必须照<b>端点主入参 / 主产出</b>给，不能一律填文本。
     *
     * <p>变异验证点：把 {@code speech-to-text} 的输入映射改回 {@code MOD_TEXT}，
     * {@link #每个子类型的模态按端点主入参给而不是一律文本()} 就会变红 ——
     * 这正是把语音识别模型显示成「文本 → 文本」的那个真实缺陷。</p>
     */
    @Test
    void 每个子类型的模态按端点主入参给而不是一律文本() {
        /* sub_type → 期望的 端点族 / 输入 / 输出（输出为空 = 该端点产出什么本系统词表里没有） */
        Map<String, List<List<String>>> expected = new LinkedHashMap<>();
        expected.put("chat", List.of(List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT)));
        expected.put("embedding", List.of(List.of(ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING)));
        expected.put("reranker", List.of(List.of(ModelProfile.EP_RERANK),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_SCORE)));
        expected.put("text-to-image", List.of(List.of(ModelProfile.EP_IMAGES),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_IMAGE)));
        expected.put("image-to-image", List.of(List.of(ModelProfile.EP_IMAGES),
                List.of(ModelProfile.MOD_IMAGE), List.of(ModelProfile.MOD_IMAGE)));
        expected.put("speech-to-text", List.of(List.of(ModelProfile.EP_ASR),
                List.of(ModelProfile.MOD_AUDIO), List.of(ModelProfile.MOD_TEXT)));
        expected.put("text-to-video", List.of(List.of(ModelProfile.EP_VIDEO),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_VIDEO)));

        for (Map.Entry<String, List<List<String>>> e : expected.entrySet()) {
            String subType = e.getKey();
            String model = "m-" + subType;
            Map<String, Set<String>> sub = new LinkedHashMap<>();
            for (String key : List.of("chat", "embedding", "reranker", "text-to-image",
                    "image-to-image", "speech-to-text", "text-to-video")) {
                sub.put(key, key.equals(subType) ? Set.of(model) : Set.of());
            }

            ModelCatalog.CatalogResult r = ModelCatalog.decideBySubType(
                    model, Set.of(model, "other"), sub);
            assertNotNull(r, subType + " 应当能被判定出来");
            assertEquals(e.getValue().get(0), r.endpoints(), subType + " 的端点族");
            assertEquals(e.getValue().get(1), r.input(),
                    subType + " 的输入模态要按端点主入参给，不能一律 text");
            assertEquals(e.getValue().get(2), r.output(), subType + " 的输出模态");
        }
    }

    /**
     * 语音识别：{@code audio → text}。
     *
     * <p>这条单独写，因为它是真实缺陷的现场：{@code /audio/transcriptions} 的主体入参是
     * <b>音频文件</b>，{@code prompt} 只是可选辅助参数。当初把"它能带一个文本参数"当成了
     * "它的输入模态是文本"，于是 XingChenASR 这类模型在管控台上显示成「文本 → 文本」。</p>
     */
    @Test
    void 语音识别的输入是音频而不是文本() {
        Map<String, Set<String>> sub = new LinkedHashMap<>();
        for (String key : List.of("chat", "embedding", "reranker", "text-to-image",
                "image-to-image", "speech-to-text", "text-to-video")) {
            sub.put(key, key.equals("speech-to-text") ? Set.of("asr-1") : Set.of());
        }

        ModelCatalog.CatalogResult r = ModelCatalog.decideBySubType("asr-1", Set.of("asr-1", "other"), sub);
        assertNotNull(r);
        assertEquals(List.of(ModelProfile.EP_ASR), r.endpoints());
        assertEquals(List.of(ModelProfile.MOD_AUDIO), r.input(),
                "语音识别的输入是音频 —— 显示成 text 就是把 prompt 当成了主入参");
        assertEquals(List.of(ModelProfile.MOD_TEXT), r.output(), "语音识别产出文本");
    }

    /**
     * 变异验证点：把 {@code concat} 换回裸 {@code base.note() + suffix}，
     * {@link #说明里不能出现字面量null()} 就会变红。
     */
    @Test
    void 说明里不能出现字面量null() {
        assertFalse(chatResult().note().startsWith("null"),
                "说明直接拼在 null 后面会在界面上留下字面量 null；实际：" + chatResult().note());
        assertFalse(embeddingResult().note().startsWith("null"),
                "实际：" + embeddingResult().note());
        assertFalse(ModelCatalog.fromArchitecture(json(
                "{\"architecture\":{\"output_modalities\":[\"text\"]}}")).note().startsWith("null"));
        assertFalse(ModelCatalog.withNote(chatResult(), "（补充）").note().contains("null（补充）"),
                "原说明为空时不能拼成 null（补充）");
    }

    @Test
    void 基线模态不能被当成完整枚举() {
        assertFalse(chatResult().modalitiesAuthoritative(),
                "sub_type 给的是端点规范基线值；当成完整枚举会抹掉用户为 VLM 声明的 image");
        ModelCatalog.CatalogResult openRouter = ModelCatalog.fromArchitecture(json(
                "{\"architecture\":{\"input_modalities\":[\"text\",\"image\"],\"output_modalities\":[\"text\"]}}"));
        assertTrue(openRouter.modalitiesAuthoritative(),
                "input_modalities 是完整枚举，读到就该覆盖档案里过时的声明");
    }

    @Test
    void 只有向量档案才需要实测维度() {
        assertTrue(embeddingResult().needsDimensionProbe(),
                "GET /models 按 OpenAI 规范压根不给维度，维度是向量链路最硬的契约，必须另测");
        assertFalse(chatResult().needsDimensionProbe(),
                "对话档案发 embeddings 请求只会白花一次往返");
    }

    @Test
    void 标注元数据形态以便说清是谁不给() {
        assertEquals(ModelCatalog.Flavor.SUB_TYPE_FILTER, chatResult().flavor());
        assertEquals(ModelCatalog.Flavor.OPENROUTER_METADATA, ModelCatalog.fromArchitecture(json(
                "{\"architecture\":{\"output_modalities\":[\"text\"]}}")).flavor());
    }

    // ==================== 失败路径 ====================

    @Test
    void 三个入参任何一个缺失都直接报错() {
        ModelCatalog catalog = new ModelCatalog();
        assertBlank("地址空", "", "sk-1", "m");
        assertBlank("Key 空", "https://api.example.com/v1", " ", "m");
        assertBlank("模型名空", "https://api.example.com/v1", "sk-1", null);
    }

    @Test
    void 连不上服务商时抛异常且说清原因() {
        /* 用必然拒绝连接的本地端口，避免用例真的去连外网（慢且不稳） */
        ModelCatalog catalog = new ModelCatalog();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> catalog.inspect("http://127.0.0.1:1/v1", "sk-1", "m"));
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank(), "失败原因要说清，实际：" + e.getMessage());
    }

    private void assertBlank(String what, String baseUrl, String apiKey, String model) {
        ModelCatalog catalog = new ModelCatalog();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> catalog.inspect(baseUrl, apiKey, model), what + "时应当抛异常");
        assertTrue(e.getMessage().contains("不能为空"),
                what + "时的提示应点名缺什么，实际：" + e.getMessage());
    }
}
