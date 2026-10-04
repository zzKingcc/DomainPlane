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
import java.util.Map;
import java.util.Set;

/**
 * 模型目录 —— <b>只读提供商自己给出的元数据，不再发任何试探性请求</b>。
 *
 * <p>为什么不再试探：试探靠"发一个真实请求看回不回 2xx"推断能力，有三处说不清 ——
 * 成败受配额 / 限流 / 网络抖动影响；为探图片输入得真构造多模态请求；而<b>输出模态根本试不出来</b>
 * （旧实现干脆把它写死成 text）。元数据是提供商的声明，比试探准，也不花钱。</p>
 *
 * <p>元数据的可得性差别很大，这里按<b>能力从强到弱</b>依次尝试：</p>
 * <ol>
 *   <li><b>OpenRouter 风格</b>：{@code GET /models} 每项带 {@code architecture}
 *       （{@code input_modalities} / {@code output_modalities} / {@code modality}）与
 *       {@code supported_parameters} —— 模态与工具调用都能直接读到；</li>
 *   <li><b>过滤式</b>（硅基流动这类）：清单本身只有 id，但支持
 *       {@code ?sub_type=chat|embedding|text-to-image|…} 过滤，靠"该模型出现在哪个子类型列表里"
 *       反推端点族；</li>
 *   <li><b>仅清单</b>（标准 OpenAI 规范，如 vLLM / LM Studio / 各类中转）：只有
 *       {@code id/object/created/owned_by}，拿不到任何类型信息。</li>
 * </ol>
 *
 * <p><b>拿不到就如实留空，绝不猜。</b>端点族为空表示"未声明"，由用户在管控台手工声明 ——
 * 历史上把它默认成 chat，会让向量模型与生图模型被当成对话模型（能绑到域、却当不了向量模型），
 * 而且全程没有报错。</p>
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
     * 子类型<b>名字本身就说明了产出什么</b>的那几个，才据此填输出模态。
     *
     * <p>刻意不给 {@code chat} 猜输出：chat 只说明"怎么调"，不说明产出什么 ——
     * 多模态输出的对话模型是存在的，猜成 text 就会和以前写死一样错。</p>
     */
    private static final Map<String, String> SUB_TYPE_OUTPUT = Map.of(
            "embedding", ModelProfile.MOD_EMBEDDING,
            "text-to-image", ModelProfile.MOD_IMAGE,
            "image-to-image", ModelProfile.MOD_IMAGE,
            "text-to-video", ModelProfile.MOD_VIDEO,
            "speech-to-text", ModelProfile.MOD_TEXT);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /**
     * 读取结果。
     *
     * @param endpoints    端点族；<b>空 = 提供商没给，需要用户声明</b>
     * @param input        输入模态；空 = 未声明
     * @param output       输出模态；空 = 未声明
     * @param capabilities 布尔能力（streaming / tools）；空 = 未声明
     * @param dimension    向量维度；元数据从不提供，恒为 {@code null}（需声明或由实际模型兜底）
     * @param note         这次读到了什么 / 为什么读不到，直接展示给用户
     */
    public record CatalogResult(List<String> endpoints, List<String> input, List<String> output,
                         List<String> capabilities, Integer dimension, String note) {
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

        JsonNode data = fetchModels(baseUrl, apiKey, null);
        JsonNode entry = findByName(data, modelName);
        if (entry == null) {
            return new CatalogResult(List.of(), List.of(), List.of(), List.of(), null,
                    "提供商的模型清单里没有 " + modelName
                            + "（有些服务商不列出微调 / 自定义模型）。类型与模态请手工声明。");
        }
        if (entry.has("architecture")) {
            return fromArchitecture(entry);
        }
        CatalogResult bySubType = classifyBySubType(baseUrl, apiKey, modelName, data);
        if (bySubType != null) {
            return bySubType;
        }
        return new CatalogResult(List.of(), List.of(), List.of(), List.of(), null,
                "提供商只给出了模型清单，OpenAI 规范里没有类型 / 模态字段，无法自动判定。"
                        + "请在下面手工声明端点族与模态。");
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

        String note = "来自提供商元数据：输入 " + orNone(in) + " → 输出 " + orNone(out)
                + "；端点族 " + (endpoints.isEmpty() ? "（未能判定）" : endpoints)
                + "；能力 " + (caps.isEmpty() ? "（未声明）" : caps)
                /* 流式刻意不填：OpenRouter 的 supported_parameters 不列 stream（人人都支持），
                   与其替提供商断言，不如留空由用户声明 —— 它只影响展示。 */
                + "。流式能力与向量维度元数据不提供，需手工声明（维度也可由实际模型兜底）。";
        return new CatalogResult(List.copyOf(endpoints), in, out, caps, null, note);
    }

    /**
     * 由输出模态判定端点族。
     *
     * <p>输出文本 → 对话端点；输出向量 → 向量端点。输出图片 / 音频而<b>没有</b>文本输出时，
     * 那是一个专门的内容生成端点（{@code /images/generations}、{@code /audio/speech}），
     * 与对话端点不是一回事；若同时有文本输出，它只是"多模态输出的对话模型"，仍归对话。</p>
     */
    static Set<String> endpointsOf(List<String> out) {
        Set<String> endpoints = new LinkedHashSet<>();
        boolean textOut = out.contains(ModelProfile.MOD_TEXT);
        if (out.contains(ModelProfile.MOD_EMBEDDING)) {
            endpoints.add(ModelProfile.EP_EMBEDDING);
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
     * <p><b>关键保护</b>：提供商不认识 {@code sub_type} 时会<b>忽略该参数并回全量清单</b>。
     * 若不识别这种情况，每个子类型都会"命中"该模型，于是所有端点族都会被标上。
     * 因此只有当至少一个子类型返回了<b>真子集</b>（证明过滤确实生效）才采信。</p>
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
        boolean filterWorks = bySubType.values().stream().anyMatch(ids -> !ids.equals(fullIds));
        if (!filterWorks) {
            return null;
        }

        Set<String> endpoints = new LinkedHashSet<>();
        List<String> output = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : bySubType.entrySet()) {
            if (!e.getValue().contains(modelName)) {
                continue;
            }
            matched.add(e.getKey());
            String endpoint = SUB_TYPE_ENDPOINT.get(e.getKey());
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
            String out = SUB_TYPE_OUTPUT.get(e.getKey());
            if (out != null && !output.contains(out)) {
                output.add(out);
            }
        }
        if (endpoints.isEmpty()) {
            return null;
        }

        String note = "来自提供商元数据：按 sub_type 过滤判定 " + modelName + " 属于 " + matched
                + "，端点族 " + endpoints
                + (output.isEmpty()
                    ? "；输出模态需手工声明（该子类型名未说明产出什么）"
                    : "；输出模态按子类型语义填为 " + output);
        return new CatalogResult(List.copyOf(endpoints), List.of(), output, List.of(), null, note);
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
            throw new IllegalStateException("读取模型清单被拒绝（HTTP " + code + "）：请检查 API Key");
        }
        if (code / 100 != 2) {
            throw new IllegalStateException("读取模型清单失败（HTTP " + code + "）：" + snippet(resp.body()));
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

    private static JsonNode findByName(JsonNode data, String modelName) {
        for (JsonNode item : data) {
            if (modelName.equals(item.path("id").asText(""))) {
                return item;
            }
        }
        return null;
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
