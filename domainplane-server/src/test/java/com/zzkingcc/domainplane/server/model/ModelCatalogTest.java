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
 * 模型目录：只读提供商元数据，且<b>读不到就留空，绝不猜</b>。
 *
 * <p>这里覆盖三种元数据形态（OpenRouter 带 architecture / 硅基流动式 sub_type 过滤 /
 * 只有 id 的通用 OpenAI 规范），以及那个最容易出错的保护 —— 提供商不认识 {@code sub_type}
 * 时会忽略参数回全量清单，若不识别就会把<b>所有</b>端点族都标上。</p>
 *
 * <p>不联网：解析与决策都是纯函数（{@link ModelCatalog#fromArchitecture}、
 * {@link ModelCatalog#decideBySubType}），只有失败路径才碰一次必然被拒的本地端口。</p>
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
    void 过滤生效时按子类型判定端点族() {
        Set<String> all = Set.of("chat1", "embed1", "img1");
        Map<String, Set<String>> sub = subTypes(Set.of("chat1"), Set.of("embed1"), Set.of("img1"));

        ModelCatalog.CatalogResult chat = ModelCatalog.decideBySubType("chat1", all, sub);
        assertNotNull(chat);
        assertEquals(List.of(ModelProfile.EP_CHAT), chat.endpoints());
        assertTrue(chat.output().isEmpty(),
                "chat 只说明怎么调，不说明产出什么 —— 输出模态必须留空由用户声明，不能猜成文本");

        ModelCatalog.CatalogResult embed = ModelCatalog.decideBySubType("embed1", all, sub);
        assertNotNull(embed);
        assertEquals(List.of(ModelProfile.EP_EMBEDDING), embed.endpoints());
        assertEquals(List.of(ModelProfile.MOD_EMBEDDING), embed.output(),
                "embedding 子类型名本身就说明了产出是向量");

        ModelCatalog.CatalogResult img = ModelCatalog.decideBySubType("img1", all, sub);
        assertNotNull(img);
        assertEquals(List.of(ModelProfile.EP_IMAGES), img.endpoints());
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
