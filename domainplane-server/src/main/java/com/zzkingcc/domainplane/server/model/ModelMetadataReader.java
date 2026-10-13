package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.CompletableFuture;

/**
 * 模型元数据读取 —— 只读提供商的 {@code GET {baseUrl}/models}，一个推理请求都不发。
 *
 * 为什么改成读元数据而不是发试探请求：试探靠"发一条真实请求看回不回 2xx"推断能力，会消耗额度，
 * 成败还受限流与网络抖动影响；而输出模态根本试不出来。元数据是提供商的声明，不花钱、也更准。
 * 本类**只发 GET**，"不花 token"因此是结构上成立的，不是靠"请求写得很小"来保证的。
 *
 * OpenAI 规范里 {@code GET /models} 给了什么、没给什么，这是本类的全部依据：
 *
 *   • 给了：{@code data[].id/object/created/owned_by}，即「这个 Key 能用到哪些模型」；
 *   • 没给：端点族、输入 / 输出模态、向量维度，一项都没有。
 *
 * 于是按元数据形态由强到弱依次尝试：
 *
 *   • OpenRouter 风格：清单每项额外带 {@code architecture}（输入 / 输出模态），模态直接读到，
 *       且是**完整枚举**，可以覆盖档案里过时的声明；
 *   • 过滤式（硅基流动这类）：清单本身只有 id，但支持 {@code ?sub_type=chat|embedding|…} 过滤，
 *       靠"该模型出现在哪个子类型列表里"反推端点族，并按该端点的 OpenAI 规范语义填输入 / 输出模态
 *       （{@code /chat/completions} 是文本进、文本出；{@code /embeddings} 是文本进、向量出）。
 *       这一路给的是**基线值**，只能并进已有声明，绝不能覆盖，能吃图片的对话模型在清单里
 *       同样是 {@code chat}，覆盖会把用户手填的 {@code image} 悄悄抹掉；
 *   • 标准 OpenAI 规范（OpenAI / DeepSeek 官方、vLLM、LM Studio、各类中转）：只有那四个字段，
 *       {@code sub_type} 也不认，除"模型存在"外一律未声明。
 *
 *
 * 两条规范化，只做"对齐规范"不改语义：
 *
 *   • 模型名匹配：先精确，再按「去空白 + 忽略大小写 + 忽略 {@code vendor/} 前缀」匹配；
 *       规范化后命中多条即判为歧义并如实报出候选，绝不挑一条，挑错等于绑到别的模型上。
 *   • 地址：尾斜杠容忍；地址漏了版本段（{@code /v1}）而 {@code /models} 返回 404 时补一次
 *       {@code /v1} 重试。只有 404 / 405 值得重试，401 / 403 / 5xx 换地址也不会变好。
 *
 *
 * 读不到就如实留空，绝不猜。端点族为空表示"未声明"，档案仍可保存（管控台会标出「未声明类型」），
 * 但绑不了域、也当不了向量模型。硬失败（连不上 / 鉴权被拒 / 非 JSON）则抛
 * {@link MetadataException} 由调用方拦下，那是用户能改的错（地址、Key），不该被静默存进一份
 * 用不了的档案；而"服务商不给这些字段"不是用户的错，如实留空即可。
 *
 * @author zzkingcc
 */
public class ModelMetadataReader {

    private static final Logger log = LoggerFactory.getLogger(ModelMetadataReader.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 过滤式提供商的 {@code sub_type} → 端点族（取值见硅基流动 {@code GET /models} 的 OpenAPI）。
     */
    private static final Map<String, String> SUB_TYPE_ENDPOINT = subTypeEndpoints();

    /**
     * 子类型 → 输入模态。
     *
     * 按该端点实际吃什么给基线值，不是猜：{@code /chat/completions} 的 {@code messages[].content}
     * 是文本，{@code /embeddings} 与 {@code /rerank} 的入参是文本，
     * {@code /images/generations} 收文本提示词，而 {@code /audio/transcriptions} 吃的是音频文件。
     *
     * 基线值必须照着端点的主入参给：语音识别的主体入参是音频，它的 {@code prompt} 只是可选辅助参数，
     * 不能因为它能带文本就填 text。
     *
     * 基线 ≠ 全集：能吃图片的对话模型（VLM）在 {@code sub_type} 里仍然是 {@code chat}，
     * 这里只会给出 {@code text}。
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
     * 刻意保留 {@code chat → text}：子类型既然已判定"走 {@code /chat/completions}"，
     * 按 OpenAI 规范该端点的产出就是文本，填 {@code text} 是对齐规范而不是猜测。
     *
     * {@code reranker → score}：重排产出的是与传入 documents 一一对应的相关性分数
     * （服务商字段名 {@code relevance_score}）。原先这一格是空的，于是重排模型在卡片上显示成
     * 「端点族=重排、输入=文本、输出=（空）」，看着像识别失败。
     */
    private static final Map<String, String> SUB_TYPE_OUTPUT = Map.of(
            "chat", ModelProfile.MOD_TEXT,
            "embedding", ModelProfile.MOD_EMBEDDING,
            "reranker", ModelProfile.MOD_SCORE,
            "text-to-image", ModelProfile.MOD_IMAGE,
            "image-to-image", ModelProfile.MOD_IMAGE,
            "text-to-video", ModelProfile.MOD_VIDEO,
            "speech-to-text", ModelProfile.MOD_TEXT);

    /**
     * 端点族 → 基线模态。由 {@code architecture} 推端点族、或由子类型判定端点族之后，
     * 用它把该端点规范的输入 / 输出模态补齐。
     *
     * 与 SUB_TYPE_* 两张表的区别：那两张按 {@code sub_type} 组织，而 {@code text-to-image}
     * 与 {@code image-to-image} 同归 {@code images} 端点族，反查会有冲突；这里直接按端点族写，
     * 一个端点一条，没有歧义。
     */
    private static final Map<String, String> EP_BASELINE_INPUT = Map.of(
            ModelProfile.EP_CHAT, ModelProfile.MOD_TEXT,
            ModelProfile.EP_EMBEDDING, ModelProfile.MOD_TEXT,
            ModelProfile.EP_RERANK, ModelProfile.MOD_TEXT,
            ModelProfile.EP_IMAGES, ModelProfile.MOD_TEXT,
            ModelProfile.EP_ASR, ModelProfile.MOD_AUDIO,
            ModelProfile.EP_TTS, ModelProfile.MOD_TEXT,
            ModelProfile.EP_VIDEO, ModelProfile.MOD_TEXT);

    private static final Map<String, String> EP_BASELINE_OUTPUT = Map.of(
            ModelProfile.EP_CHAT, ModelProfile.MOD_TEXT,
            ModelProfile.EP_EMBEDDING, ModelProfile.MOD_EMBEDDING,
            ModelProfile.EP_RERANK, ModelProfile.MOD_SCORE,
            ModelProfile.EP_IMAGES, ModelProfile.MOD_IMAGE,
            ModelProfile.EP_ASR, ModelProfile.MOD_TEXT,
            ModelProfile.EP_TTS, ModelProfile.MOD_AUDIO,
            ModelProfile.EP_VIDEO, ModelProfile.MOD_VIDEO);

    private final ObjectMapper mapper;
    private final HttpClient http;

    public ModelMetadataReader() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), new ObjectMapper());
    }

    public ModelMetadataReader(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * 读取一个模型在提供商处的元数据。
     *
     * @throws MetadataException 硬失败：入参缺失、连不上、鉴权被拒、响应不是预期的 JSON。
     *                           服务商不提供清单（404 / 405）不在此列，那是"读不到"，
     *                           返回端点族为空的正常结果，由调用方如实标成未声明。
     */
    public MetadataResult read(String baseUrl, String apiKey, String modelName) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            throw new MetadataException(0, "服务商地址、API Key、模型名都不能为空");
        }

        String base = baseUrl.trim();
        String addressNote = "";
        JsonNode data = fetchModelList(base, apiKey, null);
        if (data == null) {
            /* 只有 404 / 405 才补 /v1 重试：401 / 403 / 5xx 换地址也不会变好 */
            String withV1 = appendV1(base);
            if (withV1 != null) {
                data = fetchModelList(withV1, apiKey, null);
                if (data != null) {
                    addressNote = "（地址 " + base + " 下没有 /models，已按 OpenAI 规范补上 /v1）";
                    base = withV1;
                }
            }
        }
        if (data == null) {
            return MetadataResult.undeclared("服务商不提供模型清单（GET /models 返回 404），"
                    + "读取不到类型与模态，该档案保持未声明。");
        }

        NameMatch match = matchByName(data, modelName);
        String idNote = addressNote + (match.normalized()
                ? "（清单里的 id 是 " + match.id() + "，与你填的名字在大小写或 vendor/ 前缀上不同，"
                        + "已按规范化匹配对上）"
                : "");

        if (match.ambiguous()) {
            return MetadataResult.undeclared("按规范化匹配，" + modelName + " 在清单里对应多条模型："
                    + match.candidates() + "。\n不同服务商的同名模型无法只凭 id 区分，"
                    + "请把模型名改成清单里的完整 id。" + idNote);
        }
        if (match.entry() == null) {
            return MetadataResult.undeclared("提供商的模型清单里没有 " + modelName
                    + "（已先精确匹配、再按「忽略大小写与 vendor/ 前缀」规范化匹配，均未命中）。"
                    + "\n有些服务商不列出微调 / 自定义模型。识别不到就无法确定类型，"
                    + "该档案会保持未声明（绑不了域、也不能作为向量模型）。" + idNote);
        }

        if (match.entry().has("architecture")) {
            return withNote(fromArchitecture(match.entry()), idNote);
        }

        SubTypeOutcome bySubType = classifyBySubType(base, apiKey, modelName, data);
        if (bySubType.result() != null) {
            return withNote(bySubType.result(), idNote);
        }
        if (bySubType.filterWorks()) {
            /* 过滤确实生效、这个模型也在全量清单里，却没有落进任何一个已知子类型，
               这不是"服务商不认 sub_type"，如实说清，别把两件事混成一句 */
            return withNote(unlistedInSubTypes(modelName), idNote);
        }

        return withNote(openaiPlain(modelName), idNote);
    }

    // ==================== OpenRouter 风格：清单自带 architecture ====================

    /**
     * 从 {@code architecture} 解析输入 / 输出模态，并由输出模态推端点族。
     *
     * 这一路读到的模态是**完整枚举**（{@code input_modalities} 列全了它能吃什么），
     * 因此 {@link MetadataResult#authoritative()} 为 {@code true}，可以覆盖档案里过时的声明。
     */
    static MetadataResult fromArchitecture(JsonNode entry) {
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
        return new MetadataResult(List.copyOf(endpoints), in, out, true,
                "来自提供商元数据（OpenRouter 风格 architecture）：输入 " + orNone(in)
                        + " → 输出 " + orNone(out) + "；端点族 "
                        + (endpoints.isEmpty() ? "（未能判定）" : endpoints) + "。"
                        + undeclaredTail(endpoints, in, out));
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
     * 过滤式：靠 {@code ?sub_type=…} 过滤反推端点族。
     *
     * 七个子类型并行问一次：串行会把"保存一份档案"拖到七次往返那么长，而每个请求都只是读清单，
     * 并行不改变语义、也不多花钱。
     *
     * @return 两件事分开报：{@code filterWorks} 是"过滤器确实生效"（决定读不到时该说哪句话），
     *         {@code result} 是判定结果（{@code null} = 没判出来）
     */
    private SubTypeOutcome classifyBySubType(String baseUrl, String apiKey, String modelName,
                                             JsonNode full) {
        Set<String> fullIds = idsOf(full);

        Map<String, CompletableFuture<Set<String>>> pending = new LinkedHashMap<>();
        for (String subType : SUB_TYPE_ENDPOINT.keySet()) {
            pending.put(subType, http.sendAsync(modelListRequest(baseUrl, apiKey, subType),
                            HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> resp.statusCode() / 100 == 2
                            ? idsOf(parseData(resp.body())) : Set.<String>of())
                    .exceptionally(e -> {
                        log.debug("[模型元数据] sub_type={} 过滤请求失败，跳过：{}", subType, e.getMessage());
                        return Set.of();
                    }));
        }
        Map<String, Set<String>> bySubType = new LinkedHashMap<>();
        pending.forEach((subType, future) -> bySubType.put(subType, future.join()));

        boolean filterWorks = bySubType.values().stream()
                .anyMatch(ids -> !normalizedIds(ids).equals(normalizedIds(fullIds)));
        return new SubTypeOutcome(filterWorks, decideBySubType(modelName, fullIds, bySubType));
    }

    /**
     * @param filterWorks 过滤器是否确实生效（至少一个子类型回了真子集）
     * @param result      判定结果；{@code null} = 没判出端点族
     */
    private record SubTypeOutcome(boolean filterWorks, MetadataResult result) {
    }

    /**
     * 纯决策（不联网，便于单测）：从各子类型清单判定该模型属于哪些端点族。
     *
     * 关键保护：提供商不认识 {@code sub_type} 时会忽略该参数并回全量清单。
     * 若不识别这种情况，每个子类型都会"命中"该模型，于是所有端点族都会被标上。
     * 因此只有当至少一个子类型返回了真子集（证明过滤确实生效）才采信。
     *
     * @param fullIds   不带过滤时的全量 id，用来识别"过滤器被忽略"
     * @param bySubType 子类型 → 该子类型清单里的 id
     * @return 判定结果；过滤未生效 / 一个都没命中时返回 {@code null}，交由调用方如实说"读不到"
     */
    static MetadataResult decideBySubType(String modelName, Set<String> fullIds,
                                          Map<String, Set<String>> bySubType) {
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

        /* authoritative = false：这一路给的是端点规范的基线值（chat → text → text），
           它能吃图片也照样只写 text，所以只能并进已有声明，绝不能覆盖 */
        return new MetadataResult(List.copyOf(endpoints), List.copyOf(input), List.copyOf(output), false,
                "来自提供商元数据（sub_type 过滤式）：" + modelName + " 命中子类型 " + matched
                        + "，据此判定端点族 " + endpoints
                        + "；输入 / 输出模态按该端点的规范填为 " + orNone(input) + " → " + orNone(output)
                        + "（基线值：子类型只说明「怎么调」，它能吃什么、产出什么按端点主入参 / 主产出给，"
                        + "例如语音识别是 audio → text，不吃文本）。"
                        + undeclaredTail(endpoints, input, output));
    }

    // ==================== 标准 OpenAI 规范：清单什么都不给 ====================

    /**
     * 清单支持 {@code sub_type} 过滤、模型也在清单里，却没落进任何一个已知子类型时的结论。
     *
     * 与 {@link #openaiPlain} 严格分开：那种情况下说"服务商不认 sub_type"是错的，
     * 过滤是生效的，只是该模型属于我们不认识的子类型（服务商加了新区分类，或这是个新模态）。
     * 两者的下一步完全不同：前者无解，后者是"把子类型表补全就能读到"，说错就把排查方向带偏。
     */
    private static MetadataResult unlistedInSubTypes(String modelName) {
        return MetadataResult.undeclared("该服务商的清单支持 sub_type 过滤，但 " + modelName
                + " 没有出现在任何一个已知子类型（" + String.join(" / ", SUB_TYPE_ENDPOINT.keySet())
                + "）的清单里。\n服务商可能新增了子类型分类。因此无法判定它属于哪一类，"
                + "该档案保持未声明；不猜一个类型 —— 猜错会让它能被绑到域却用不了，且全程不报错。");
    }

    /**
     * 清单只有 {@code id/object/created/owned_by} 且 {@code sub_type} 不生效时的结论。
     *
     * 唯一被证实的事实是"这个 Key 的清单里有这个模型"，其余一律未声明。端点族不自动补
     * chat：OpenAI 规范的清单里同样列着向量模型，补错就是"能绑到域、却当不了向量模型"的那种
     * 全程不报错的错。这类提供商（OpenAI / DeepSeek 官方、vLLM / LM Studio / 各类中转）
     * 在管控台上识别不出类型，档案保持未声明，这是零成本识别的固有边界，
     * 如实说明比猜一个类型强。
     */
    private static MetadataResult openaiPlain(String modelName) {
        return MetadataResult.undeclared("该服务商的模型清单是标准 OpenAI 规范：只有 id / object / "
                + "created / owned_by，既没有类型与模态字段，也不认 sub_type 过滤参数。"
                + "\n已证实：" + modelName + " 在这个 Key 的清单里存在（仅此一项）。"
                + "\n因此无法判定它属于哪一类：不自动补成对话模型，是因为向量模型与生图模型"
                + "也列在同一份清单里，猜错会让它们能被绑到域却用不了，且全程不报错。"
                + "\n不花 token 就读不出类型与模态 —— 这是元数据读取的边界，不是识别失败。");
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

    // ==================== 词表翻译 ====================

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
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
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

    // ==================== 结果拼装 ====================

    /** 追加一段说明（规范化说明、地址纠正等接在原 note 之后） */
    static MetadataResult withNote(MetadataResult base, String suffix) {
        if (isBlank(suffix)) {
            return base;
        }
        return new MetadataResult(base.endpoints(), base.input(), base.output(), base.authoritative(),
                (isBlank(base.note()) ? "" : base.note()) + suffix);
    }

    /**
     * 未声明项清单 —— 把"没读到"如实点名。
     *
     * 四个字段全空时，用户会以为读取按钮坏了；逐项点名才说得清。
     * 不说"请手工勾选"：管控台表单没有手工声明入口，指一条走不通的路比不说更糟。
     */
    static String undeclaredTail(Set<String> endpoints, List<String> input, List<String> output) {
        List<String> missing = new ArrayList<>();
        if (endpoints.isEmpty()) {
            missing.add("端点族");
        }
        if (input.isEmpty()) {
            missing.add("输入模态");
        }
        if (output.isEmpty()) {
            missing.add("输出模态");
        }
        if (missing.isEmpty()) {
            return "";
        }
        return "\n以下几项服务商没给，会保持未声明：" + String.join("、", missing)
                + "。未读到不等于「不支持」—— 两者必须分开，否则把模型换成便宜档也无从发现。";
    }

    private static String orNone(List<String> list) {
        return list.isEmpty() ? "（未声明）" : String.join("+", list);
    }

    // ==================== HTTP ====================

    /** {@code GET {baseUrl}/models}，返回 {@code data} 数组；不存在该端点（404/405）返回 {@code null} */
    private JsonNode fetchModelList(String baseUrl, String apiKey, String subType) {
        HttpResponse<String> resp;
        try {
            resp = http.send(modelListRequest(baseUrl, apiKey, subType),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataException(0, "读取模型清单被中断");
        } catch (Exception e) {
            throw new MetadataException(0, "请求 " + endpoint(baseUrl, "/models") + " 失败: "
                    + e.getMessage());
        }

        int code = resp.statusCode();
        if (code == 401 || code == 403) {
            throw new MetadataException(code, "读取模型清单被拒绝（HTTP " + code + "）：请检查 API Key");
        }
        if (code == 404 || code == 405) {
            return null;
        }
        if (code / 100 != 2) {
            throw new MetadataException(code, "读取模型清单失败（HTTP " + code + "）："
                    + snippet(resp.body()));
        }
        return parseData(resp.body());
    }

    private HttpRequest modelListRequest(String baseUrl, String apiKey, String subType) {
        String url = endpoint(baseUrl, "/models") + (subType == null ? "" : "?sub_type=" + subType);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    /** 解析清单响应体，取 {@code data} 数组；不是预期形状一律抛（由调用方翻译成用户可读原因） */
    private JsonNode parseData(String body) {
        JsonNode data;
        try {
            data = mapper.readTree(body).path("data");
        } catch (Exception e) {
            throw new MetadataException(0, "模型清单不是合法 JSON：" + snippet(body));
        }
        if (!data.isArray()) {
            throw new MetadataException(0, "模型清单响应里没有 data 数组：" + snippet(body));
        }
        return data;
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

    // ==================== 结果与异常 ====================

    /**
     * 读取结果。
     *
     * @param endpoints     端点族；空 = 提供商没给（档案将保持未声明）
     * @param input         输入模态；空 = 未声明
     * @param output        输出模态；空 = 未声明
     * @param authoritative 读到的模态是不是**完整枚举**。只有 OpenRouter 风格的 {@code architecture}
     *                      是；{@code sub_type} 给的是端点规范基线值，只能并进已有声明。
     *                      覆盖与并集的分岔见 {@link ModelProfile#withDetected}
     * @param note          这次读到了什么 / 为什么读不到（供日志与接口诊断，界面不直接展示）
     */
    public record MetadataResult(List<String> endpoints, List<String> input, List<String> output,
                                 boolean authoritative, String note) {

        public MetadataResult {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
        }

        /** 什么都没读到 —— 档案保持未声明，note 说明为什么 */
        static MetadataResult undeclared(String note) {
            return new MetadataResult(List.of(), List.of(), List.of(), false, note);
        }
    }

    /**
     * 元数据读取的硬失败 —— 用户能改的错（地址写错、Key 失效、服务商 5xx）。
     *
     * 与"读不到"严格分开：读不到照样保存（如实标未声明），硬失败则拒绝保存，
     * 免得把一份连不上的档案静默存进去。
     */
    public static class MetadataException extends RuntimeException {

        private final int status;

        MetadataException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** HTTP 状态码；{@code 0} 表示网络 / 解析层面的失败 */
        public int status() {
            return status;
        }
    }
}
