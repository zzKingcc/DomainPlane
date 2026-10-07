package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模型目录 —— 只读提供商自己给出的元数据，按 OpenAI 规范解析，不发任何试探请求。
 *
 * 为什么读元数据而不试探：试探靠"发一个真实请求看回不回 2xx"推断能力，成败受配额 / 限流 /
 * 网络抖动影响，而输出模态根本试不出来（旧实现干脆写死成 text）。元数据是提供商的声明，
 * 比试探准，也不花钱。
 *
 * OpenAI 规范里 {@code GET /models} 给了什么、没给什么 —— 这是本类的全部依据：
 *
 *   • 给了：{@code data[].id/object/created/owned_by}，即「这个 Key 能用到哪些模型」；
 *   • 没给：端点族、输入 / 输出模态、布尔能力、上下文长度、向量维度 —— 一项都没有。
 *
 *
 * 于是按元数据形态分三种，由强到弱依次尝试（见 {@link Flavor}）：
 *
 *   • OpenRouter 风格：清单每项额外带 {@code architecture}（输入 / 输出模态）与
 *       {@code supported_parameters}（{@code tools}）—— 模态与工具调用都能直接读到；
 *   • 过滤式（硅基流动这类）：清单本身只有 id，但支持 {@code ?sub_type=chat|embedding|…}
 *       过滤，靠"该模型出现在哪个子类型列表里"反推端点族，并按该端点的 OpenAI 规范语义填输入 /
 *       输出模态（{@code /chat/completions} 的输入是文本消息、产出是文本；{@code /embeddings}
 *       的是文本进、向量出）；
 *   • 标准 OpenAI 规范（vLLM / LM Studio / 各类中转）：只有那四个字段，{@code sub_type}
 *       也不认 —— 除"模型存在"外一律未声明。
 *
 *
 * 两条规范化，只做"对齐规范"不做"猜"：
 *
 *   • 模型名匹配：先精确，再按「去空白 + 忽略大小写 + 忽略 {@code vendor/} 前缀」匹配；
 *       规范化后命中多条即判为歧义并如实报出候选，绝不挑一条 —— 挑错等于绑到别的模型上。
 *       只放宽匹配，不放宽语义。
 *   • 地址：尾斜杠容忍；地址漏了版本段（{@code /v1}）而 {@code /models} 返回 404 时补一次
 *       {@code /v1} 重试。只有 404 值得重试 —— 401 / 403 / 5xx 换地址也不会变好。
 *
 *
 * 读不到就如实留空，绝不猜。端点族为空表示"未声明"，档案就用不了 ——
 * 历史上把它默认成 chat，会让向量模型与生图模型被当成对话模型（能绑到域、却当不了向量模型），
 * 而且全程没有报错。管控台表单没有手工声明入口，类型与模态一律以本接口的识别结果为准；
 * 但"留空"不等于"沉默"：{@link CatalogResult#note()} 一律把读到了什么、读不到什么
 * 说全，避免出现"点了读取、什么都没变"却查不出原因。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ModelCatalog {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 过滤式提供商的 {@code sub_type} → 端点族（取值见硅基流动 {@code GET /models} 的 OpenAPI） */
    private static final Map<String, String> SUB_TYPE_ENDPOINT = subTypeEndpoints();

    /**
     * 子类型 → 输入模态。
     *
     * 按该端点实际吃什么给基线值，不是猜：{@code /chat/completions} 的
     * {@code messages[].content} 是文本、{@code /embeddings} 与 {@code /rerank} 的入参是文本、
     * {@code /images/generations} 与 {@code /videos} 收文本提示词，而
     * {@code /audio/transcriptions} 吃的是音频文件（多部分表单里的 {@code file}），
     * {@code image-to-image} 吃的是图片。
     *
     * 基线值必须照着端点的主入参给：{@code speech-to-text} 的主体入参是音频，
     * {@code /audio/transcriptions} 的 {@code prompt} 只是可选辅助参数，不能因为它能带文本就填 text。
     *
     * 基线 ≠ 全集：能吃图片的对话模型（VLM）在 {@code sub_type} 里仍然是 {@code chat}，
     * 这里只会给出 {@code text}；ASR 的入参也不会写成 {@code text+audio} ——
     * 那会暗示可以只发文本，而实际不行。
     */
    private static final Map<String, String> SUB_TYPE_INPUT = Map.of(
            "chat", ModelProfile.MOD_TEXT,
            "embedding", ModelProfile.MOD_TEXT,
            "reranker", ModelProfile.MOD_TEXT,
            "speech-to-text", ModelProfile.MOD_AUDIO,
            "text-to-image", ModelProfile.MOD_TEXT,
            "text-to-video", ModelProfile.MOD_TEXT,
            "image-to-image", ModelProfile.MOD_IMAGE);

    /**
     * 子类型 → 输出模态，同样按端点规范给基线值。
 *
     * 刻意保留 {@code chat → text}：子类型既然已经判定"走 {@code /chat/completions}"，
     * 那么按 OpenAI 规范该端点的产出就是文本，填 {@code text} 是对齐规范而不是猜测。
 * 产出多模态的对话模型在这里会得到基线值 {@code text}，额外模态读不出来 —— 留空则连
 * "普通对话模型"都要用户自己勾一遍，而列表里绝大多数模型恰恰是普通的。
     *
     * {@code reranker → score}：重排产出的是与传入 documents 一一对应的相关性分数
     * （服务商字段名 {@code relevance_score}）。原先这一格是空的，于是重排模型显示成
     * 「端点族=重排、输入=文本、输出=（空）」，看着像识别失败；空值在这里等于"没答上来"，
     * 而答案明明是确定的。
     */
    private static final Map<String, String> SUB_TYPE_OUTPUT = Map.of(
            "chat", ModelProfile.MOD_TEXT,
            "embedding", ModelProfile.MOD_EMBEDDING,
            "reranker", ModelProfile.MOD_SCORE,
            "text-to-image", ModelProfile.MOD_IMAGE,
            "image-to-image", ModelProfile.MOD_IMAGE,
            "text-to-video", ModelProfile.MOD_VIDEO,
            "speech-to-text", ModelProfile.MOD_TEXT);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /**
     * 提供商的元数据形态 —— 决定"哪些字段有资格被自动填"，也直接显示给用户。
     *
     * 探测"读不出东西"本身就是一种结论，必须能说清是谁不给，否则用户无从判断
     * 是自己填错了模型名、是 Key 没权限，还是服务商压根不发这些字段。
     */
    public enum Flavor {

        /** OpenRouter 风格：清单每项带 {@code architecture} 与 {@code supported_parameters} */
        OPENROUTER_METADATA("OpenRouter 风格元数据（清单带 architecture / supported_parameters）"),

        /** 过滤式：清单只有 id，靠 {@code ?sub_type=} 反推端点族 */
        SUB_TYPE_FILTER("sub_type 过滤式清单（清单只有 id，靠子类型过滤反推端点族）"),

        /** 标准 OpenAI 规范：只有 id/object/created/owned_by，sub_type 也不认 */
        OPENAI_PLAIN("标准 OpenAI 规范清单（只有 id/object/created/owned_by，无类型 / 模态 / 能力字段）"),

        /** 提供商的清单里没有这个模型 */
        NOT_LISTED("提供商清单里没有这个模型");

        private final String label;

        Flavor(String label) {
            this.label = label;
        }

        /** 人话说明（管控台直接展示） */
        public String label() {
            return label;
        }
    }

    /**
     * 读取结果。
     *
     * @param flavor       提供商元数据形态（说清"是谁不给"）
     * @param endpoints    端点族；空 = 提供商没给，需要用户声明
     * @param input        输入模态；空 = 未声明
     * @param output       输出模态；空 = 未声明
     * @param capabilities 布尔能力（streaming / tools）；空 = 未声明
     * @param dimension    向量维度；元数据从不提供，由 {@code AdminModelProfileController} 用一条
     *                     极短请求实测补上，仍为 {@code null} 表示实测也没成
     * @param note         这次读到了什么 / 为什么读不到，直接展示给用户
     */
    public record CatalogResult(Flavor flavor,
                                List<String> endpoints, List<String> input, List<String> output,
                                List<String> capabilities, Integer dimension, String note) {

        /** 是否还需要用一条极短请求去实测向量维度 */
        public boolean needsDimensionProbe() {
            return dimension == null && endpoints.contains(ModelProfile.EP_EMBEDDING);
        }

        /**
         * 读到的 input / output 是不是完整枚举。
         *
         * 只有 OpenRouter 风格的 {@code architecture} 是完整枚举（{@code input_modalities}
         * 列全了它能吃什么），读到就可以覆盖档案里过时的声明。
         *
         * sub_type 路径给的是端点规范基线值（{@code /chat/completions} → text→text）：
         * 能吃图片的对话模型在清单里仍然是 {@code chat}，基线值里没有 image。所以它只能并进
         * 已有声明，绝不能覆盖 —— 否则用户为 PaddleOCR-VL 之类手填的 {@code image} 会被一次
         * 「读取」悄悄抹掉，而且卡片上看不出任何变化。
         */
        public boolean modalitiesAuthoritative() {
            return flavor == Flavor.OPENROUTER_METADATA;
        }
    }

    /**
     * 读取一个模型在提供商处的元数据。
     *
     * @throws IllegalStateException 入参缺失，或连不上 / 鉴权失败 / 响应不是预期的 JSON
     */
    public CatalogResult inspect(String baseUrl, String apiKey, String modelName) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            throw new IllegalStateException("服务商地址、API Key、模型名都不能为空");
        }

        Feed feed = resolveFeed(baseUrl, apiKey);
        String addressNote = feed.correctedBase() == null ? ""
                : "（地址 " + baseUrl + " 下没有 /models，已自动按 OpenAI 规范补上 /v1）";

        NameMatch match = matchByName(feed.data(), modelName);
        String idNote = addressNote + (match.normalized()
                ? "（清单里的 id 是 " + match.id() + "，与你填的名字在大小写或 vendor/ 前缀上不同，已按规范化匹配对上）"
                : "");

        if (match.ambiguous()) {
            return new CatalogResult(Flavor.NOT_LISTED, List.of(), List.of(), List.of(), List.of(), null,
                    "按规范化匹配，" + modelName + " 在清单里对应多条模型：" + match.candidates()
                            + "。\n不同服务商的同名模型无法只凭 id 区分，请把模型名改成清单里的完整 id。"
                            + idNote);
        }
        if (match.entry() == null) {
            return new CatalogResult(Flavor.NOT_LISTED, List.of(), List.of(), List.of(), List.of(), null,
                    "提供商的模型清单里没有 " + modelName
                            + "（已先精确匹配、再按「忽略大小写与 vendor/ 前缀」规范化匹配，均未命中）。"
                            + "\n有些服务商不列出微调 / 自定义模型。识别不到就无法确定类型，"
                            + "该档案将保持未声明（绑不了域、也不能作为向量模型）。" + idNote);
        }

        if (match.entry().has("architecture")) {
            return withNote(fromArchitecture(match.entry()), idNote);
        }

        CatalogResult bySubType = classifyBySubType(feed.base(), apiKey, modelName, feed.data());
        if (bySubType != null) {
            return withNote(bySubType, idNote);
        }
        return openaiPlain(modelName, idNote);
    }

    // ==================== OpenRouter 风格：清单自带 architecture ====================

    /** package-private：不联网的纯解析，便于单测直接喂 JSON */
    static CatalogResult fromArchitecture(JsonNode entry) {
        JsonNode arch = entry.path("architecture");
        List<String> in = mapModalities(rawArray(arch.path("input_modalities")));
        List<String> out = mapModalities(rawArray(arch.path("output_modalities")));
        /* modality 形如 text+image+video->text；个别提供商只给这一项而不给上面两个数组 */
        if (in.isEmpty() && out.isEmpty()) {
            String modality = arch.path("modality").asText("");
            int arrow = modality.indexOf("->");
            if (arrow >= 0) {
                in = mapModalities(rawSplit(modality.substring(0, arrow)));
                out = mapModalities(rawSplit(modality.substring(arrow + 2)));
            }
        }

        Set<String> endpoints = endpointsOf(out);

        List<String> caps = new ArrayList<>();
        for (JsonNode p : entry.path("supported_parameters")) {
            if (ModelProfile.CAP_TOOLS.equalsIgnoreCase(p.asText(""))) {
                caps.add(ModelProfile.CAP_TOOLS);
            }
        }
        /* 思考能力单独判：它不在 supported_parameters 的字符串项里，而是清单每项上的一个
           对象（{@code {mandatory, default_enabled, supported_efforts…}}）——
           不支持思考的模型该字段是 JSON null（实测 466 条里 134 条为 null）。
           与 tools 一样是"读到了才加"，读不到就不加标记：管控台据此不显示思考开关，
           而不是显示成"已关闭"——后者会让用户以为关掉了，实际每轮还在烧思考 token。 */
        if (entry.hasNonNull("reasoning")) {
            caps.add(ModelProfile.CAP_REASONING);
        }

        CatalogResult result = new CatalogResult(Flavor.OPENROUTER_METADATA,
                List.copyOf(endpoints), in, out, caps, null, null);
        return withNote(result,
                "来自提供商元数据（OpenRouter 风格）：输入 " + orNone(in) + " → 输出 " + orNone(out)
                        + "；端点族 " + (endpoints.isEmpty() ? "（未能判定）" : endpoints)
                        + "；布尔能力 " + (caps.isEmpty() ? "（未声明）" : caps)
                        + "。流式能力元数据从不提供（OpenRouter 的 supported_parameters 不列 stream），"
                        + "读不到就保持未声明；向量维度同理，由实测补。" + undeclaredTail(result));
    }

    /**
     * 由输出模态判定端点族。
     *
     * 输出文本 → 对话端点；输出向量 → 向量端点。输出图片 / 音频而没有文本输出时，
     * 那是一个专门的内容生成端点（{@code /images/generations}、{@code /audio/speech}），
     * 与对话端点不是一回事；若同时有文本输出，它只是"多模态输出的对话模型"，仍归对话。
     */
    static Set<String> endpointsOf(List<String> out) {
        Set<String> endpoints = new LinkedHashSet<>();
        boolean textOut = out.contains(ModelProfile.MOD_TEXT);
        if (out.contains(ModelProfile.MOD_EMBEDDING)) {
            endpoints.add(ModelProfile.EP_EMBEDDING);
        }
        /* 分数输出单独判：重排既没有文本输出也没有向量输出，靠上面几条一条都命中不了，
           不补这一条就会退化成"端点族未声明"。 */
        if (out.contains(ModelProfile.MOD_SCORE)) {
            endpoints.add(ModelProfile.EP_RERANK);
        }
        if (textOut) {
            endpoints.add(ModelProfile.EP_CHAT);
        }
        if (out.contains(ModelProfile.MOD_IMAGE) && !textOut) {
            endpoints.add(ModelProfile.EP_IMAGES);
        }
        if (out.contains(ModelProfile.MOD_AUDIO) && !textOut) {
            endpoints.add(ModelProfile.EP_TTS);
        }
        if (out.contains(ModelProfile.MOD_VIDEO)) {
            endpoints.add(ModelProfile.EP_VIDEO);
        }
        return endpoints;
    }

    // ==================== 过滤式：清单只有 id，但支持 sub_type 过滤 ====================

    /**
     * 靠 {@code ?sub_type=…} 过滤反推端点族。
     *
     * 关键保护：提供商不认识 {@code sub_type} 时会忽略该参数并回全量清单。
     * 若不识别这种情况，每个子类型都会"命中"该模型，于是所有端点族都会被标上。
     * 因此只有当至少一个子类型返回了真子集（证明过滤确实生效）才采信。
     *
     * @return 判定结果；过滤未生效 / 一个都没命中时返回 {@code null}，交由调用方如实说"读不到"
     */
    private CatalogResult classifyBySubType(String baseUrl, String apiKey, String modelName, JsonNode full) {
        Set<String> fullIds = idsOf(full);

        Map<String, Set<String>> bySubType = new LinkedHashMap<>();
        for (String subType : SUB_TYPE_ENDPOINT.keySet()) {
            try {
                bySubType.put(subType, idsOf(fetchModels(baseUrl, apiKey, subType)));
            } catch (IllegalStateException e) {
                log.debug("[模型目录] sub_type={} 过滤请求失败，跳过：{}", subType, e.getMessage());
            }
        }
        return decideBySubType(modelName, fullIds, bySubType);
    }

    /**
     * 纯决策（不联网，便于单测）：从各子类型清单判定该模型属于哪些端点族。
     *
     * @param fullIds   不带过滤时的全量 id，用来识别"过滤器被忽略"
     * @param bySubType 子类型 → 该子类型清单里的 id
     * @return 判定结果；过滤未生效 / 一个都没命中时返回 {@code null}，交由调用方如实说"读不到"
     */
    static CatalogResult decideBySubType(String modelName, Set<String> fullIds, Map<String, Set<String>> bySubType) {
        boolean filterWorks = bySubType.values().stream()
                .anyMatch(ids -> !normalizedIds(ids).equals(normalizedIds(fullIds)));
        if (!filterWorks) {
            return null;
        }

        String want = normalizeModelId(modelName);
        Set<String> endpoints = new LinkedHashSet<>();
        List<String> input = new ArrayList<>();
        List<String> output = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : bySubType.entrySet()) {
            if (!normalizedIds(e.getValue()).contains(want)) {
                continue;
            }
            matched.add(e.getKey());
            String endpoint = SUB_TYPE_ENDPOINT.get(e.getKey());
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
            String in = SUB_TYPE_INPUT.get(e.getKey());
            if (in != null && !input.contains(in)) {
                input.add(in);
            }
            String out = SUB_TYPE_OUTPUT.get(e.getKey());
            if (out != null && !output.contains(out)) {
                output.add(out);
            }
        }
        if (endpoints.isEmpty()) {
            return null;
        }

        CatalogResult result = new CatalogResult(Flavor.SUB_TYPE_FILTER,
                List.copyOf(endpoints), List.copyOf(input), List.copyOf(output), List.of(), null, null);
        return withNote(result,
                "来自提供商元数据（sub_type 过滤式）：" + modelName + " 命中子类型 " + matched
                        + "，据此判定端点族 " + endpoints
                        + "；输入 / 输出模态按该端点的规范填为 " + orNone(input) + " → " + orNone(output)
                        + "（这是基线值：子类型只说明「怎么调」，它能吃什么、产出什么是按端点主入参 / "
                        + "主产出给的；例如语音识别是 audio → text，不吃文本）"
                        + "。" + undeclaredTail(result));
    }

    // ==================== 标准 OpenAI 规范：清单什么都不给 ====================

    /**
     * 清单只有 {@code id/object/created/owned_by} 且 {@code sub_type} 不生效时的结论。
     *
     * 唯一被证实的事实是"这个 Key 的清单里有这个模型"，其余一律未声明。端点族不自动补
     * chat：OpenAI 规范的清单里同样列着向量模型，补错就是"能绑到域、却当不了向量模型"的
     * 那种全程不报错的错。这类提供商（vLLM / LM Studio / 各类中转）在管控台上识别不出类型，
     * 档案会保持未声明、无法使用 —— 这是已知缺口，如实说明比猜一个类型强。
     */
    private static CatalogResult openaiPlain(String modelName, String idNote) {
        CatalogResult result = new CatalogResult(Flavor.OPENAI_PLAIN,
                List.of(), List.of(), List.of(), List.of(), null, null);
        return withNote(result,
                "该服务商的模型清单是标准 OpenAI 规范：只有 id / object / created / owned_by，"
                        + "既没有类型、模态与能力字段，也不认 sub_type 过滤参数。"
                        + "\n已证实：" + modelName + " 在这个 Key 的清单里存在（仅此一项）。"
                        + undeclaredTail(result)
                        + "\n因此无法判定它属于哪一类：不自动补成对话模型，是因为向量模型与生图模型"
                        + "也列在同一份清单里，猜错会让它们能被绑到域却用不了，且全程不报错。"
                        + "这类提供商目前建不出可用的档案。" + idNote);
    }

    // ==================== 结果拼装 ====================

    /** 追加一段说明（规范化说明、地址纠正等接在原 note 之后） */
    public static CatalogResult withNote(CatalogResult base, String suffix) {
        if (isBlank(suffix)) {
            return base;
        }
        return new CatalogResult(base.flavor(), base.endpoints(), base.input(), base.output(),
                base.capabilities(), base.dimension(), concat(base.note(), suffix));
    }

    /** 补上实测出来的向量维度，并把实测说明并进 note */
    public static CatalogResult withDimension(CatalogResult base, int dimension, String suffix) {
        return new CatalogResult(base.flavor(), base.endpoints(), base.input(), base.output(),
                base.capabilities(), dimension, concat(base.note(), suffix));
    }

    /**
     * 拼说明 —— 原说明为空时不能直接用 {@code null + "..."}，那会在界面上留下字面量 "null"。
     */
    private static String concat(String base, String suffix) {
        return isBlank(base) ? suffix : base + suffix;
    }

    /**
     * 未声明项清单 —— 把"没读到"如实点名。
     *
     * 把"没读到"逐项点名：否则四个字段全空时，用户会以为读取按钮坏了。
     *
     * 不说"请手工勾选"：管控台表单已经取消手工声明入口，类型与模态一律以识别结果
     * 为准。文案只能如实说明"这些项没读到、会保持未声明"，指一条走不通的路比不说更糟。
     */
    static String undeclaredTail(CatalogResult result) {
        List<String> missing = new ArrayList<>();
        if (result.endpoints().isEmpty()) {
            missing.add("端点族");
        }
        if (result.input().isEmpty()) {
            missing.add("输入模态");
        }
        if (result.output().isEmpty()) {
            missing.add("输出模态");
        }
        if (result.capabilities().isEmpty()) {
            missing.add("布尔能力 streaming / tools");
        }
        if (result.needsDimensionProbe()) {
            missing.add("向量维度（清单不给，由一条极短请求实测）");
        }
        if (missing.isEmpty()) {
            return "";
        }
        return "\n以下几项服务商没给，会保持未声明：" + String.join("、", missing)
                + "。未读到不等于「不支持」—— 两者必须分开，否则把模型换成便宜档、"
                + "工具静默失效也无从发现。";
    }

    // ==================== 匹配与地址规范化 ====================

    /**
     * 模型名匹配结果。
     *
     * @param entry      命中的条目；{@code null} 表示没定位到
     * @param id         命中的真实 id（规范化匹配时与用户填的名字可能不同）
     * @param normalized 是否是靠规范化匹配上的
     * @param candidates 规范化后对应到多条时的候选 id（歧义）
     */
    record NameMatch(JsonNode entry, String id, boolean normalized, List<String> candidates) {

        /** 规范化后对应到多条：必须如实报歧义，不能挑一条 */
        boolean ambiguous() {
            return !candidates.isEmpty();
        }
    }

    /**
     * 在清单里定位条目：先精确匹配，再按规范化匹配。
     *
     * 规范化 = 去空白 + 忽略大小写 + 忽略 {@code vendor/} 前缀。放宽的只有写法，
     * 语义一个字没放宽；规范化后命中多条即判歧义（{@code providerA/Qwen3-8B} 与
     * {@code providerB/Qwen3-8B} 同时存在时挑一条等于悄悄绑到别的模型上）。
     */
    static NameMatch matchByName(JsonNode data, String modelName) {
        String want = modelName == null ? "" : modelName.trim();
        for (JsonNode item : data) {
            if (want.equals(item.path("id").asText("").trim())) {
                return new NameMatch(item, item.path("id").asText(""), false, List.of());
            }
        }

        String normalized = normalizeModelId(want);
        List<String> hits = new ArrayList<>();
        JsonNode hit = null;
        for (JsonNode item : data) {
            if (normalizeModelId(item.path("id").asText("")).equals(normalized)) {
                hits.add(item.path("id").asText(""));
                hit = item;
            }
        }
        if (hits.size() == 1) {
            return new NameMatch(hit, hits.get(0), true, List.of());
        }
        return new NameMatch(null, null, false, List.copyOf(hits));
    }

    /** 模型名规范化：去空白 → 转小写 → 去掉 {@code vendor/} 前缀 */
    static String normalizeModelId(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int slash = s.indexOf('/');
        return slash >= 0 && slash < s.length() - 1 ? s.substring(slash + 1) : s;
    }

    private static Set<String> normalizedIds(Set<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            out.add(normalizeModelId(id));
        }
        return out;
    }

    /**
     * 读一次全量清单，顺带把地址规范化。
     *
     * 地址漏了版本段时（{@code https://api.example.com}）{@code /models} 会 404；
     * 只有这种情况补一次 {@code /v1} 重试 —— 401 / 403 / 5xx 换地址也一样不成，
     * 重试只是白白多一次往返。补成功后沿用补好的地址去查子类型，避免同样的 404 连撞七次。
     */
    private Feed resolveFeed(String baseUrl, String apiKey) {
        try {
            return new Feed(baseUrl, fetchModels(baseUrl, apiKey, null), null);
        } catch (HttpFailure e) {
            String withV1 = appendV1(baseUrl);
            if (e.status() != 404 || withV1 == null) {
                throw e;
            }
            log.info("[模型目录] {} 下没有 /models，按 OpenAI 规范补上 /v1 重读", baseUrl);
            return new Feed(withV1, fetchModels(withV1, apiKey, null), withV1);
        }
    }

    /**
     * 补 {@code /v1} 后的地址；已经是 {@code .../v1} 时返回 {@code null}（没有别的候选可试）
     */
    static String appendV1(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.endsWith("/v1")) {
            return null;
        }
        return s + "/v1";
    }

    /**
     * @param base            实际使用的地址（补过 /v1 时是新地址）
     * @param data            全量清单
     * @param correctedBase   被纠正过的原地址；{@code null} 表示原地址就对
     */
    private record Feed(String base, JsonNode data, String correctedBase) {
    }

    // ==================== HTTP ====================

    /** {@code GET {baseUrl}/models}，返回 {@code data} 数组 */
    private JsonNode fetchModels(String baseUrl, String apiKey, String subType) {
        String url = endpoint(baseUrl, "/models") + (subType == null ? "" : "?sub_type=" + subType);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取模型清单被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求 " + url + " 失败: " + e.getMessage(), e);
        }

        int code = resp.statusCode();
        if (code == 401 || code == 403) {
            throw new HttpFailure(code, "读取模型清单被拒绝（HTTP " + code + "）：请检查 API Key");
        }
        if (code / 100 != 2) {
            throw new HttpFailure(code, "读取模型清单失败（HTTP " + code + "）：" + snippet(resp.body()));
        }
        try {
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("模型清单响应里没有 data 数组：" + snippet(resp.body()));
            }
            return data;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型清单不是合法 JSON：" + snippet(resp.body()), e);
        }
    }

    /** 带 HTTP 状态码的失败 —— 「补 /v1 重试」只对 404 有意义，其余状态码重试没有意义 */
    static final class HttpFailure extends IllegalStateException {

        private final int status;

        HttpFailure(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private static Set<String> idsOf(JsonNode data) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : data) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static List<String> rawArray(JsonNode array) {
        List<String> raw = new ArrayList<>();
        for (JsonNode n : array) {
            raw.add(n.asText(""));
        }
        return raw;
    }

    private static List<String> rawSplit(String joined) {
        List<String> raw = new ArrayList<>();
        Collections.addAll(raw, joined.split("\\+"));
        return raw;
    }

    /** 把提供商词表翻译成本项目的 MOD_*；不认识的词直接丢弃（不猜） */
    static List<String> mapModalities(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String piece : raw) {
            String mapped = modOf(piece);
            if (mapped != null && !out.contains(mapped)) {
                out.add(mapped);
            }
        }
        return List.copyOf(out);
    }

    static String modOf(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase()) {
            case "text" -> ModelProfile.MOD_TEXT;
            case "image" -> ModelProfile.MOD_IMAGE;
            case "audio" -> ModelProfile.MOD_AUDIO;
            case "video" -> ModelProfile.MOD_VIDEO;
            case "file" -> ModelProfile.MOD_FILE;
            case "embedding", "embeddings" -> ModelProfile.MOD_EMBEDDING;
            /* 重排产出的分数。收录两个真实用词：服务商响应里的字段名是 relevance_score，
               模态词表里通常写 score */
            case "score", "relevance_score" -> ModelProfile.MOD_SCORE;
            default -> null;
        };
    }

    private static String orNone(List<String> list) {
        return list.isEmpty() ? "（未声明）" : String.join("+", list);
    }

    /** 容忍地址末尾多打斜杠 */
    private static String endpoint(String baseUrl, String path) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + path;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "(空响应)";
        }
        String one = body.replaceAll("\\s+", " ").trim();
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }

    private static Map<String, String> subTypeEndpoints() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("chat", ModelProfile.EP_CHAT);
        m.put("embedding", ModelProfile.EP_EMBEDDING);
        m.put("reranker", ModelProfile.EP_RERANK);
        m.put("text-to-image", ModelProfile.EP_IMAGES);
        m.put("image-to-image", ModelProfile.EP_IMAGES);
        m.put("speech-to-text", ModelProfile.EP_ASR);
        m.put("text-to-video", ModelProfile.EP_VIDEO);
        return Collections.unmodifiableMap(m);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}