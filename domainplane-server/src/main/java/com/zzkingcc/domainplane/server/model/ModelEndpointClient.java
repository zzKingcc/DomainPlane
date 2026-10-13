package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.code.ErrorCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 多模态端点客户端 —— 生图 / 重排 / 语音合成 / 语音识别四族，直接走 JDK HttpClient。
 *
 * 为什么不在 langchain4j 里做：langchain4j 只覆盖对话（ChatModel）与向量（EmbeddingModel），
 * 这四族它没有抽象，强行塞进 ChatModel 适配器只会把请求体结构拼错。所以单独成一个客户端，
 * 请求体自己按各家协议拼，响应自己解析。
 *
 * 连接基底统一为 OpenAI 兼容：四族端点全部走 OpenAI 兼容那套路径
 * （/images/generations、/rerank、/audio/speech、/audio/transcriptions），
 * 档案上不再有协议分派 —— 要接新能力在这个格式上扩即可。
 *
 * 无缓存：这四族都是一次性请求，不像对话那样会反复复用同一个客户端，所以本类保持无状态。
 *
 * @author zzkingcc
 */
public class ModelEndpointClient {

    private final HttpClient http;
    private final ObjectMapper mapper;

    public ModelEndpointClient() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .build(),
                new ObjectMapper());
    }

    public ModelEndpointClient(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    // ==================== 生图 ====================

    /**
     * 生图（OpenAI 兼容：{@code POST {baseUrl}/images/generations}）。
     *
     * @param profile 档案（取 baseUrl / apiKey / modelName）
     * @param req     生图请求（prompt 必填；size / n / responseFormat 可取档案的 images.* 参数）
     */
    public ImageResult generateImage(ModelProfile profile, ImageRequest req) {
        if (req == null || isBlank(req.prompt())) {
            throw new ModelEndpointException(ErrorCode.INVALID_PARAMETER, "生图请求缺少 prompt");
        }
        var body = mapper.createObjectNode();
        body.put("model", profile.modelName());
        body.put("prompt", req.prompt());
        putIfPresent(body, "size", req.size());
        putIntIfPresent(body, "n", req.n());
        putIfPresent(body, "response_format", req.responseFormat());

        String raw = postJson(endpoint(profile, "images/generations"), profile.apiKey(), body);
        JsonNode root = readTree(raw);
        JsonNode data = root.get("data");
        List<GeneratedImage> items = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                String url = item.has("url") ? item.get("url").asText(null) : null;
                String b64 = item.has("b64_json") ? item.get("b64_json").asText(null) : null;
                items.add(new GeneratedImage(url, b64));
            }
        }
        return new ImageResult(List.copyOf(items));
    }

    // ==================== 重排 ====================

    /**
     * 重排（OpenAI 兼容形态：{@code POST {baseUrl}/rerank}）。
     *
     * 重排没有像 Chat Completions 那样统一的规范，各家字段名不同：这里采用
     * 硅基流动 / Cohere 风格（query + documents + top_n），也是目前登记表里唯一登记了
     * rerank 端点的服务商用的形态。解析时同时认 {@code relevance_score} 与 {@code score} 两种键，
     * 因为不同服务商返回字段名不一致。
     *
     * @param profile 档案（取 baseUrl / apiKey / modelName）
     * @param req     重排请求（query / documents 必填；topN / returnDocuments 可取 rerank.* 参数）
     */
    public RerankResult rerank(ModelProfile profile, RerankRequest req) {
        if (req == null || isBlank(req.query()) || req.documents() == null || req.documents().isEmpty()) {
            throw new ModelEndpointException(ErrorCode.INVALID_PARAMETER,
                    "重排请求缺少 query 或 documents");
        }
        var body = mapper.createObjectNode();
        body.put("model", profile.modelName());
        body.put("query", req.query());
        var docs = body.putArray("documents");
        for (String doc : req.documents()) {
            docs.add(doc);
        }
        putIntIfPresent(body, "top_n", req.topN());
        if (req.returnDocuments() != null) {
            body.put("return_documents", req.returnDocuments());
        }

        String raw = postJson(endpoint(profile, "rerank"), profile.apiKey(), body);
        JsonNode root = readTree(raw);
        JsonNode results = root.get("results");
        List<RerankItem> items = new ArrayList<>();
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                int index = r.has("index") ? r.get("index").asInt(-1) : -1;
                double score = scoreOf(r);
                String doc = r.has("document") ? r.get("document").asText(null) : null;
                items.add(new RerankItem(index, score, doc));
            }
        }
        return new RerankResult(List.copyOf(items));
    }

    // ==================== 语音合成 ====================

    /**
     * 语音合成 / TTS（OpenAI 兼容：{@code POST {baseUrl}/audio/speech}）。
     *
     * 响应是二进制音频（audio/mpeg 等），直接返回字节，由调用方决定落盘还是转 base64。
     *
     * @param profile 档案（取 baseUrl / apiKey / modelName）
     * @param req     合成请求（input 必填；voice / speed / responseFormat 可取 tts.* 参数）
     */
    public SpeechResult textToSpeech(ModelProfile profile, SpeechRequest req) {
        if (req == null || isBlank(req.input())) {
            throw new ModelEndpointException(ErrorCode.INVALID_PARAMETER, "语音合成请求缺少 input 文本");
        }
        var body = mapper.createObjectNode();
        body.put("model", profile.modelName());
        body.put("input", req.input());
        putIfPresent(body, "voice", req.voice());
        if (req.speed() != null) {
            body.put("speed", req.speed());
        }
        putIfPresent(body, "response_format", req.responseFormat());

        byte[] audio = postBytes(endpoint(profile, "audio/speech"), profile.apiKey(), body);
        return new SpeechResult(audio, req.responseFormat());
    }

    // ==================== 语音识别 ====================

    /**
     * 语音识别 / ASR（OpenAI 兼容：{@code POST {baseUrl}/audio/transcriptions}）。
     *
     * 走 multipart/form-data：音频作为 file 字段，model / language / prompt / response_format 作普通字段。
     * 响应通常是 JSON（{@code {"text": "..."}}）或纯文本，两种都认。
     *
     * @param profile 档案（取 baseUrl / apiKey / modelName）
     * @param req     识别请求（audio 字节必填；language / prompt / responseFormat 可取 asr.* 参数）
     */
    public TranscriptionResult transcribe(ModelProfile profile, TranscriptionRequest req) {
        if (req == null || req.audio() == null || req.audio().length == 0) {
            throw new ModelEndpointException(ErrorCode.INVALID_PARAMETER, "语音识别请求缺少音频数据");
        }
        String filename = isBlank(req.filename()) ? "audio.wav" : req.filename().trim();
        String language = req.language();
        String prompt = req.prompt();
        String responseFormat = req.responseFormat();

        String mediaType = inferAudioMediaType(filename);
        List<Part> parts = new ArrayList<>();
        parts.add(new Part("file", filename, mediaType, req.audio()));
        parts.add(new Part("model", null, null, profile.modelName().getBytes(StandardCharsets.UTF_8)));
        if (language != null) {
            parts.add(new Part("language", null, null, language.getBytes(StandardCharsets.UTF_8)));
        }
        if (prompt != null) {
            parts.add(new Part("prompt", null, null, prompt.getBytes(StandardCharsets.UTF_8)));
        }
        if (responseFormat != null) {
            parts.add(new Part("response_format", null, null,
                    responseFormat.getBytes(StandardCharsets.UTF_8)));
        }

        byte[] multipart = buildMultipart(parts);
        HttpResponse<byte[]> resp = exchange(endpoint(profile, "audio/transcriptions"),
                profile.apiKey(), HttpRequest.BodyPublishers.ofByteArray(multipart),
                "multipart/form-data; boundary=" + BOUNDARY);
        String raw = new String(resp.body(), StandardCharsets.UTF_8);
        if (isJson(resp) && raw.trim().startsWith("{")) {
            JsonNode root = readTree(raw);
            String text = root.has("text") ? root.get("text").asText(null) : null;
            String lang = root.has("language") ? root.get("language").asText(null) : null;
            return new TranscriptionResult(text != null ? text : raw, lang, raw);
        }
        return new TranscriptionResult(raw, null, raw);
    }

    // ==================== HTTP ====================

    private String postJson(String url, String apiKey, com.fasterxml.jackson.databind.node.ObjectNode body) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new ModelEndpointException(ErrorCode.SYSTEM_ERROR, "生图/重排请求体序列化失败", e);
        }
        HttpResponse<byte[]> resp = exchange(url, apiKey,
                HttpRequest.BodyPublishers.ofByteArray(payload), "application/json");
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    private byte[] postBytes(String url, String apiKey, com.fasterxml.jackson.databind.node.ObjectNode body) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new ModelEndpointException(ErrorCode.SYSTEM_ERROR, "语音合成请求体序列化失败", e);
        }
        HttpResponse<byte[]> resp = exchange(url, apiKey,
                HttpRequest.BodyPublishers.ofByteArray(payload), "application/json");
        return resp.body();
    }

    /** 发请求并校验状态：非 2xx 一律抛 {@link ModelEndpointException}（带服务商返回的具体错误） */
    private HttpResponse<byte[]> exchange(String url, String apiKey,
                                          HttpRequest.BodyPublisher body, String contentType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .header("Accept", "application/json, */*")
                .header("Content-Type", contentType == null ? "application/json" : contentType);
        HttpRequest request = builder.POST(body).build();
        try {
            HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String detail = describeError(new String(resp.body(), StandardCharsets.UTF_8), resp.statusCode());
                throw new ModelEndpointException(ErrorCode.LLM_UNAVAILABLE,
                        "多模态端点调用失败（HTTP " + resp.statusCode() + "）：" + detail);
            }
            return resp;
        } catch (ModelEndpointException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelEndpointException(ErrorCode.LLM_UNAVAILABLE,
                    "多模态端点调用失败：" + e.getMessage(), e);
        }
    }

    /** 从服务商的错误体里尽量提取可读信息（OpenAI 的 error.message / 国内服务商的 detail / message） */
    private String describeError(String body, int status) {
        if (body == null || body.isBlank()) {
            return "HTTP " + status;
        }
        try {
            JsonNode root = readTree(body);
            if (root.has("error") && root.get("error").isObject()) {
                JsonNode msg = root.get("error").get("message");
                if (msg != null && !msg.asText().isBlank()) {
                    return msg.asText();
                }
            }
            if (root.has("detail")) {
                JsonNode detail = root.get("detail");
                if (detail.isTextual()) {
                    return detail.asText();
                }
                return detail.toString();
            }
            if (root.has("message") && root.get("message").isTextual()) {
                return root.get("message").asText();
            }
        } catch (Exception ignored) {
            // 不是 JSON，原样截断返回
        }
        int max = Math.min(body.length(), 500);
        return body.substring(0, max) + (body.length() > max ? "…" : "");
    }

    /** 拼接端点地址：baseUrl 可能带尾斜杠，统一归一后再拼路径段 */
    private static String endpoint(ModelProfile profile, String suffix) {
        String base = profile.baseUrl() == null ? "" : profile.baseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + suffix;
    }

    private JsonNode readTree(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new ModelEndpointException(ErrorCode.SYSTEM_ERROR, "解析多模态端点响应失败", e);
        }
    }

    private static boolean isJson(HttpResponse<?> resp) {
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        return ct.contains("json");
    }

    private static String inferAudioMediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        return "application/octet-stream";
    }

    private static final String BOUNDARY = "----DomainPlaneModelEndpointBoundary9f3c";

    /** 一个 multipart 片段：name 必填，filename / mediaType 只给带文件的字段，content 是原始字节 */
    private record Part(String name, String filename, String mediaType, byte[] content) {
    }

    /**
     * 手写 multipart/form-data 字节。本机 Java 编译器级别低于 16，没有
     * {@code HttpRequest.MimePart} / {@code BodyPublishers.ofMimeMultipart}（Java 16+ 才有），
     * 所以自己拼 RFC 7578 的字节：每个字段一段 {@code --BOUNDARY\r\n} + 头部 + 空行 + 内容，
     * 结尾 {@code --BOUNDARY--\r\n}。
     */
    private static byte[] buildMultipart(List<Part> parts) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] dash = ("--" + BOUNDARY).getBytes(StandardCharsets.US_ASCII);
            for (Part p : parts) {
                out.write(dash);
                out.write(crlf);
                StringBuilder disp = new StringBuilder();
                disp.append("Content-Disposition: form-data; name=\"").append(p.name()).append("\"");
                if (p.filename() != null) {
                    disp.append("; filename=\"").append(p.filename()).append("\"");
                }
                out.write(disp.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(crlf);
                if (p.mediaType() != null) {
                    out.write(("Content-Type: " + p.mediaType()).getBytes(StandardCharsets.US_ASCII));
                    out.write(crlf);
                }
                out.write("Content-Transfer-Encoding: binary".getBytes(StandardCharsets.US_ASCII));
                out.write(crlf);
                out.write(crlf);
                out.write(p.content());
                out.write(crlf);
            }
            out.write(dash);
            out.write("--".getBytes(StandardCharsets.US_ASCII));
            out.write(crlf);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ModelEndpointException(ErrorCode.SYSTEM_ERROR, "构造 multipart 请求体失败", e);
        }
    }

    private static double scoreOf(JsonNode r) {
        JsonNode s = r.has("relevance_score") ? r.get("relevance_score")
                : r.has("score") ? r.get("score") : null;
        return s != null ? s.asDouble(0.0) : 0.0;
    }

    // ==================== 小工具 ====================

    private static void putIfPresent(com.fasterxml.jackson.databind.node.ObjectNode body,
                                     String field, String value) {
        if (value != null && !value.isBlank()) {
            body.put(field, value);
        }
    }

    private static void putIntIfPresent(com.fasterxml.jackson.databind.node.ObjectNode body,
                                        String field, Integer value) {
        if (value != null) {
            body.put(field, value);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ==================== 请求 / 结果 DTO ====================

    /** 生图请求（prompt 必填） */
    public record ImageRequest(String prompt, String size, Integer n, String responseFormat) {
    }

    /** 单张图（url 与 b64Json 二选一，取决于 response_format） */
    public record GeneratedImage(String url, String b64Json) {
    }

    /** 生图结果 */
    public record ImageResult(List<GeneratedImage> items) {
    }

    /** 重排请求（query / documents 必填） */
    public record RerankRequest(String query, List<String> documents, Integer topN, Boolean returnDocuments) {
    }

    /** 单条重排结果（index 为原 documents 的下标） */
    public record RerankItem(int index, double score, String document) {
    }

    /** 重排结果 */
    public record RerankResult(List<RerankItem> results) {
    }

    /** 语音合成请求（input 必填） */
    public record SpeechRequest(String input, String voice, Double speed, String responseFormat) {
    }

    /** 语音合成结果（音频字节 + 格式） */
    public record SpeechResult(byte[] audio, String format) {
    }

    /** 语音识别请求（audio 字节必填） */
    public record TranscriptionRequest(byte[] audio, String filename, String language,
                                       String prompt, String responseFormat) {
    }

    /** 语音识别结果（text 为主，language / raw 为辅） */
    public record TranscriptionResult(String text, String language, String raw) {
    }
}
