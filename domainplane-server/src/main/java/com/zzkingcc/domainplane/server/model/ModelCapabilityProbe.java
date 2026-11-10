package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 模型能力探测 —— 用一组「最小请求」把一个模型的端点族与输入 / 输出模态实测出来。
 *
 * 为什么要有它：{@code ModelMetadataReader} 只读服务商 {@code GET /models} 清单（零 token），
 * 但标准 OpenAI 规范的服务商（OpenAI / DeepSeek 官方、各类中转）清单里不给类型，档案就停在
 * 「未识别」（端点族为空），既绑不了域、也当不了向量模型。这份探测是那些档案**手动补识别**
 * 的唯一手段：花几个 token，换回确定的类型与模态。
 *
 * 设计口径：
 *
 *   • **全部模态都判**。输入 text/image/audio/video/file 走 {@code /chat/completions} 的
 *     content 数组；输出 text/image/audio/embedding/score 走各自端点。唯一例外是**输出 video**
 *     OpenAI 规范下没有统一的视频生成端点（各家形态不一），探针无法确认，只能留待元数据
 *     或用户手动声明。
 *   • **最小 token**。文本探针 {@code max_tokens=16}（填 1 会被部分服务商按非法值拒，思考模型下
 *     content 还可能为空）、提示词 {@code "hi"}；媒体用最小可解析样本
 *     （内联 base64 的 PNG / 0.1s 静音 WAV / 1 页 PDF）。真正花钱的只有生图与 TTS 两条，已压到最小。
 *   • **请求体兼容回退**。只对两类明确的 400 回退（content 只接受数组 / 只认
 *     {@code max_completion_tokens}），每次只修一项、最多 3 次；别的 400 原样交出去 ——
 *     拿不准就别多发请求。回退次数写进诊断，排障时能分清"模型不行"与"请求体不被接受"。
 *   • **超时按端点分级**（见 {@link Timeouts}）：一个 20 秒常量会把生图、TTS 一律截成「未确认」。
 *   • **每个探针都留诊断**。状态码、失败归类、脱敏后的回执摘要都随结果返回页面，
 *     不只写日志 —— 否则管理员面对的永远只有「未确认」三个字。
 *   • **样本必须自身合法，一律现场生成，不抄现成的 base64 串**。这条是踩过坑之后加的硬约束：
 *     曾经硬编码一串广为流传的「1×1 透明 PNG」，而**那串本身就是坏的**（IDAT 的 CRC 对不上、
 *     chunk 结构错乱），服务商直接 400 拒收，于是**所有视觉模型都探不出 image 输入**、全程无报错。
 *     现在图片样本由 {@link #pngSample()} 现场编码并校验 CRC；尺寸取 64×64 而非 1×1，
 *     边长有真实门槛（DeepSeek-OCR 明确要求 {@code height/width must be larger than 28}）。
 *   • **不依赖模型自述**。不发「请列出你的能力」这类提示让模型自我陈述，幻觉会让廉价模型谎称
 *     不支持，全部走结构化端点实测。
 *   • **2xx 不等于该端点产出了对应形态的结果**。聚合网关常把 {@code /embeddings} 接到默认
 *     模型或兜底回 200，只看状态码会把纯对话模型判成「输出向量」，并集写回后还改不回来。
 *     向量探针必须验 {@code data[].embedding} 是否真存在；形状不符降为「未确认」，不写「不支持」。
 *     对话探针同理必须验 {@code choices}：有的中转对「没有 HTTP 对话能力」的模型（如实时语音模型）
 *     回 {@code 200 {"status_message":"Success."}}，只看状态码就会得到一份「全模态对话模型」的假档案。
 *   • **实时语音模型（模型名带 realtime）不归 HTTP 探针管**。它走 WebSocket / WebRTC 的持久连接 +
 *     事件流（音频在 {@code response.audio.delta} 里），既不是 {@code /chat/completions}
 *     也不是 {@code /audio/speech}。探测会如实报「未确认」，并在结论里说明这是协议缺口。
 *   • **只增不减**。失败只记「未确认」，**不写「不支持」**：一次网络抖动或样本格式被挑剔都会失败，
 *     那是"这次没确认到"，不是"确定没有"。写回由 {@link ModelProfile#withConfirmed} 取并集。
 *   • **失败要能看见，但不进弹窗**。服务商回执摘要留在服务端日志里（见下方 {@code log.info}），
 *     返回给页面的 {@code note} 只列「支持 / 不支持 / 未确认」三行清单 ——
 *     弹窗是给人看结论的，不是给人读 JSON 的。
 *
 * 探针默认**串行**：这是低频手动动作，串行免触发廉价档位的限流，也便于逐条定位失败原因。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ModelCapabilityProbe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 各端点的请求总超时。
     *
     * <p>一个常量管全部是错的：chat 首字节通常 1–3 秒，而生图要真跑一次推理、TTS 要合成整段音频，
     * 20 秒会把它们一律截成「未确认」——实测里生图模型 30 秒还没回。反过来给 chat 也放宽到 3 分钟
     * 会让一个填错的 baseUrl 把整次探测拖到分钟级。</p>
     *
     * <p>做成可注入：单测要验「超过超时记为未确认」，不能真等 3 分钟。</p>
     */
    public record Timeouts(Duration chat, Duration embedding, Duration images,
                           Duration tts, Duration asr, Duration other) {

        /** 生产默认值。images 给足：排队 + 出图，180 秒仍可能不够，但再长用户会以为页面卡死 */
        public static final Timeouts DEFAULT = new Timeouts(
                Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(180),
                Duration.ofSeconds(120), Duration.ofSeconds(60), Duration.ofSeconds(30));

        public Timeouts {
            chat = chat == null ? Duration.ofSeconds(60) : chat;
            embedding = embedding == null ? Duration.ofSeconds(20) : embedding;
            images = images == null ? Duration.ofSeconds(180) : images;
            tts = tts == null ? Duration.ofSeconds(120) : tts;
            asr = asr == null ? Duration.ofSeconds(60) : asr;
            other = other == null ? Duration.ofSeconds(30) : other;
        }
    }

    /** chat 探针的输出上限。填 1 会被部分服务商按「非法值」拒，思考模型下 content 还可能为空 */
    private static final int PROBE_MAX_TOKENS = 16;

    /**
     * 请求兼容回退的尝试上限（含第一次）。
     *
     * <p>3 次是为了覆盖「两个兼容问题同时存在」：新 OpenAI 模型不认 {@code max_tokens}，
     * 且该网关只吃数组 content。每次回退只修一项，修过的不再回头，所以不会循环。</p>
     */
    private static final int MAX_COMPAT_ATTEMPTS = 3;

    /** 探针键（{@link ProbeResult#perModality()} 的键，也是诊断文案里的名字） */
    private static final String P_CHAT_TEXT = "chat-text";
    private static final String P_IMAGE_IN = "chat-image-in";
    private static final String P_AUDIO_IN = "chat-audio-in";
    private static final String P_VIDEO_IN = "chat-video-in";
    private static final String P_FILE_IN = "chat-file-in";
    private static final String P_IMAGES = "images-gen";
    private static final String P_TTS = "tts";
    private static final String P_ASR = "asr";
    private static final String P_EMBEDDING = "embedding";
    private static final String P_RERANK = "rerank";

    /** 探针键 → 中文名（给人看的结论用；日志与 perModality 仍用探针键，便于对账） */
    private static final Map<String, String> PROBE_LABEL = Map.of(
            P_CHAT_TEXT, "对话（文本）",
            P_IMAGE_IN, "图片输入",
            P_AUDIO_IN, "音频输入",
            P_VIDEO_IN, "视频输入",
            P_FILE_IN, "文件输入",
            P_IMAGES, "生图",
            P_TTS, "语音合成",
            P_ASR, "语音识别",
            P_EMBEDDING, "向量化",
            P_RERANK, "重排");

    /** 图片样本的边长：必须 > 28（DeepSeek-OCR 的硬门槛），取 64 兼顾通用 VLM 与图 token 开销 */
    private static final int SAMPLE_SIDE = 64;

    /** 失败原因摘要的截断长度 —— 够看清服务商说了什么，又不至于让 note 变成一堵墙 */
    private static final int DETAIL_MAX = 160;

    /**
     * 实时语音模型的说明。
     *
     * <p>这类模型（{@code qwen-audio-3.1-realtime-*}、OpenAI Realtime 等）是**持久连接 + 事件流**
     * 的交互形态：官方只给 WebSocket / WebRTC（{@code …/api-ws/v1/realtime}），
     * 音频是 {@code response.audio.delta} 事件里的 PCM 分片，既不走 {@code /chat/completions}，
     * 也不走 {@code /audio/speech}。HTTP 探针没有任何一条路径能问出它的能力 ——
     * 这是**协议层**的缺口，不是模型能力问题，也不是请求体没写对。</p>
     */
    private static final String REALTIME_HINT = "注意：该模型是实时双工语音模型，官方只提供 WebSocket / "
            + "WebRTC 接入（…/api-ws/v1/realtime），音频在流事件里返回；HTTP 兼容层没有它的对话能力。"
            + "HTTP 探针判定不了它的能力，平台目前也没有实时语音通道 —— 这个档案绑到域上会调不通。";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final Timeouts timeouts;

    public ModelCapabilityProbe() {
        this(Timeouts.DEFAULT);
    }

    /** 包可见：单测注入短超时，验证「超过该端点超时 → 未确认」而不必真等 */
    ModelCapabilityProbe(Timeouts timeouts) {
        this.timeouts = timeouts == null ? Timeouts.DEFAULT : timeouts;
    }

    /**
     * 每个模态的探测结论。
     *
     * <p>四态：{@code Boolean} 装不下「未确认」与「不支持」的区别；{@code NOT_EXECUTED} 是
     * 「因为连不上服务商，这条压根没发」，与「发了但没确认到」必须分开 —— 否则用户会以为
     * 平台已经试过所有能力，只是都没成功。</p>
     */
    public enum ProbeState { CONFIRMED, UNSUPPORTED, UNCONFIRMED, NOT_EXECUTED }

    /**
     * 逐探针的诊断信息 —— 页面的「诊断详情」读它，{@code note} 仍是给人看的三行清单。
     *
     * <p>把「为什么没确认到」带到页面，是这次改造的一半目的：回执只在日志里时，
     * 管理员看到的永远只有「未确认」，分不清是模型不行、地址写错、还是请求体不被接受。</p>
     *
     * @param state      三态结论
     * @param label      给人看的探针名（如「对话（文本）」），词表只在本类维护一份
     * @param statusCode 最后一条请求的 HTTP 状态码；{@code null} = 没拿到 HTTP 响应（超时 / 连不上）
     * @param category   失败归类（OK / AUTH / RATE_LIMIT / UPSTREAM / TIMEOUT / NETWORK /
     *                   CONFIG / REQUEST_FORMAT / RESPONSE_SHAPE / UNSUPPORTED）
     * @param message    服务商回执摘要：已脱敏、已压成单行、已截断
     * @param attempts   实际请求次数（走了兼容回退的就是 2 或 3）
     */
    public record Diagnostic(String state, String label, Integer statusCode, String category,
                             String message, int attempts) {
    }

    /**
     * 探测结果。
     *
     * @param endpoints   实测确认可用的端点族
     * @param input       实测确认支持的输入模态
     * @param output      实测确认支持的输出模态
     * @param perModality 逐探针结论（三态）
     * @param diagnostics 逐探针诊断（状态码 / 归类 / 摘要 / 尝试次数）
     * @param note        人话结论清单：实测确认到什么、哪些不支持、哪些没确认到（不带服务商原话）
     */
    public record ProbeResult(List<String> endpoints, List<String> input, List<String> output,
                              Map<String, ProbeState> perModality,
                              Map<String, Diagnostic> diagnostics, String note) {
        public ProbeResult {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
            perModality = perModality == null ? Map.of() : Map.copyOf(perModality);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }

        /**
         * 明确「端点 / 类型不存在」的探针所对应的端点族 —— 只有这类结论才配用来撤销旧声明。
         *
         * <p>目前只映射向量：{@code /embeddings} 是 OpenAI 规范里的标准端点，返回
         * 404 / 405 / 415 / 501 就是这家服务商没有它，档案上那条 embedding 必然是误标。
         * 重排刻意不映射：{@code /rerank} 本就不是 OpenAI 规范路径，多数服务商 404 是常态，
         * 拿它撤销用户手工声明的重排能力等于制造新的误判；生图 / 语音同理，各家路径不一。</p>
         *
         * <p>400 / 422 / 5xx / 网络失败都归 {@code UNCONFIRMED}，不会出现在结果里：
         * "这次没确认到"不能当成"确定没有"，撤销旧声明比保留一条可能不准的声明危险得多。</p>
         */
        public List<String> unsupportedFamilies() {
            return perModality.get(P_EMBEDDING) == ProbeState.UNSUPPORTED
                    ? List.of(ModelProfile.EP_EMBEDDING) : List.of();
        }
    }

    /**
     * 跑完整探针电池，实测该模型支持的端点族与模态。
     *
     * @throws IllegalArgumentException 必填项缺失
     */
    public ProbeResult probeCapabilities(String baseUrl, String apiKey, String modelName) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            throw new IllegalArgumentException("服务商地址、API Key、模型名都不能为空");
        }
        String base = trimSlash(baseUrl);

        Probes probes = new Probes();
        List<String> endpoints = new ArrayList<>();
        List<String> input = new ArrayList<>();
        List<String> output = new ArrayList<>();

        /* #1 chat-text —— 对话端点的主判据。走兼容回退（content 字符串/数组、
           max_tokens/max_completion_tokens）：实测有服务商对字符串 content 直接 400，
           那是**请求体形态**问题，不是模型不会对话。 */
        boolean chat = recordChat(probes, P_CHAT_TEXT, base, apiKey, modelName, null);
        if (chat) {
            add(endpoints, ModelProfile.EP_CHAT);
            add(input, ModelProfile.MOD_TEXT);
            add(output, ModelProfile.MOD_TEXT);
        } else if (looksLikeRealtimeModel(modelName)) {
            /* 实时语音模型只提供 WebSocket / WebRTC（官方路径 …/api-ws/v1/realtime），
               HTTP 兼容层没有它的对话能力。这类档案注定一条都探不出来，只说「未确认」
               会让人反复点探测，所以把原因直接写进结论清单。 */
            probes.hint = REALTIME_HINT;
            reclassify(probes, P_CHAT_TEXT, "PROTOCOL");
        }

        /* 连不上服务商地址时，后面九条必然是同一个结果 —— 全打一遍只是把同一个错重复九次，
           还会把一次误配渲染成"这个模型什么都不会"。只对 NETWORK 短路：
           401 / 配置错都可能只影响某一个端点（网关按端点授权、某些路由不存在），
           一刀切会把真实能力一起掩盖。 */
        if ("NETWORK".equals(categoryOf(probes, P_CHAT_TEXT))) {
            markSkipped(probes, List.of(P_IMAGE_IN, P_AUDIO_IN, P_VIDEO_IN, P_FILE_IN,
                    P_IMAGES, P_TTS, P_ASR, P_EMBEDDING, P_RERANK), "连不上服务商地址，未执行");
            return finish(endpoints, input, output, probes, modelName);
        }

        /* #2–#5 输入模态：各自独立实测，**不因 chat-text 失败而跳过**。chat 失败常常只是
           请求体 / 权限 / 模型名的问题，图片输入未必不通。
           但结论只在 chat 端点确认时才写进 input：输入模态挂在对话端点上，
           端点都没确认就谈"能吃图片"没有语义。

           注意图片输入只有这一条通道，生图模型的「图生图」走的是 /images/generations 的
           image 参数，不在这里，也不由本探针判定（见类注释与 ModelProfile#input 的语义）。 */
        if (recordChat(probes, P_IMAGE_IN, base, apiKey, modelName,
                imageUrlPart("data:image/png;base64," + pngSampleBase64())) && chat) {
            add(input, ModelProfile.MOD_IMAGE);
        }
        if (recordChat(probes, P_AUDIO_IN, base, apiKey, modelName,
                inputAudioPart(Base64.getEncoder().encodeToString(silentWav()))) && chat) {
            add(input, ModelProfile.MOD_AUDIO);
        }
        if (recordChat(probes, P_VIDEO_IN, base, apiKey, modelName,
                videoPart("data:video/mp4;base64," + Base64.getEncoder().encodeToString(minimalMp4()))) && chat) {
            add(input, ModelProfile.MOD_VIDEO);
        }
        if (recordChat(probes, P_FILE_IN, base, apiKey, modelName,
                filePart("data:application/pdf;base64," + Base64.getEncoder().encodeToString(minimalPdf()),
                        "sample.pdf")) && chat) {
            add(input, ModelProfile.MOD_FILE);
        }

        /* #6 生图：故意不带 size，用服务商默认，避免「不支持该尺寸」被误当成「不支持生图」。
           代价是可能生成一张默认尺寸图，这是确认「输出 image」无法回避的最小费用。 */
        if (mark(probes, P_IMAGES, postJson(base, apiKey, "/images/generations", imagesBody(modelName)))) {
            add(endpoints, ModelProfile.EP_IMAGES);
            add(output, ModelProfile.MOD_IMAGE);
        }

        /* #7 语音合成：voice 用最通用的 alloy；个别服务商要求特定音色会被 400 拒，记为未确认 */
        if (mark(probes, P_TTS, postJson(base, apiKey, "/audio/speech", ttsBody(modelName)))) {
            add(endpoints, ModelProfile.EP_TTS);
            add(output, ModelProfile.MOD_AUDIO);
        }

        /* #8 语音识别：multipart 上传一段静音 WAV，确认 ASR 端点族（音频输入由 #3 另行确认） */
        if (mark(probes, P_ASR, postMultipart(base, apiKey, "/audio/transcriptions",
                modelName, silentWav()))) {
            add(endpoints, ModelProfile.EP_ASR);
        }

        /* #9 向量：2xx 之外还要验响应体真是一个向量，见类注释「2xx 不等于产出」。 */
        if (mark(probes, P_EMBEDDING,
                postJson(base, apiKey, "/embeddings", embedBody(modelName)),
                this::hasEmbeddingVector)) {
            add(endpoints, ModelProfile.EP_EMBEDDING);
            add(output, ModelProfile.MOD_EMBEDDING);
        }

        /* #10 重排：非 OpenAI 规范路径，多数服务商 404 → 未确认，不影响其余结论 */
        if (mark(probes, P_RERANK, postJson(base, apiKey, "/rerank", rerankBody(modelName)))) {
            add(endpoints, ModelProfile.EP_RERANK);
            add(output, ModelProfile.MOD_SCORE);
        }

        return finish(endpoints, input, output, probes, modelName);
    }

    /** 收尾：人话清单 + 两行日志 + 结果。短路与正常路径共用，避免漏掉任何一条出口。 */
    private ProbeResult finish(List<String> endpoints, List<String> input, List<String> output,
                               Probes probes, String modelName) {
        String note = buildNote(endpoints, input, output, probes.state);
        if (probes.hint != null) {
            note = note + "\n" + probes.hint;
        }
        log.info("[模型探测] model={} 确认端点族={} 输入={} 输出={}", modelName, endpoints, input, output);
        if (!probes.details.isEmpty()) {
            log.info("[模型探测] model={} 未确认探针及服务商回执：{}", modelName, probes.details);
        }
        return new ProbeResult(endpoints, input, output, probes.state, probes.diagnostics, note);
    }

    /** 某条探针的失败归类；没跑过则为 {@code null} */
    private static String categoryOf(Probes probes, String key) {
        Diagnostic d = probes.diagnostics.get(key);
        return d == null ? null : d.category();
    }

    /**
     * 模型名是不是实时语音模型。
     *
     * <p>只用来选一段解释文案，**不用来判定能力** —— 名字里带 realtime 也可能被网关代理成
     * 普通 HTTP 对话（那时 chat 探针会确认，这段话根本不会出现）。</p>
     */
    private static boolean looksLikeRealtimeModel(String modelName) {
        String s = modelName.toLowerCase(java.util.Locale.ROOT);
        return s.contains("realtime") || s.contains("real-time");
    }

    /** 把某条探针的失败归类换成更贴切的一类（目前只用于把实时模型的 chat 失败标成 PROTOCOL） */
    private static void reclassify(Probes probes, String key, String category) {
        Diagnostic d = probes.diagnostics.get(key);
        if (d != null) {
            probes.diagnostics.put(key, new Diagnostic(d.state(), d.label(), d.statusCode(),
                    category, d.message(), d.attempts()));
        }
    }

    /**
     * 把一批探针标为「未执行」。
     *
     * <p>跳过的探针**必须有结论**：{@code perModality} 里缺键、页面又不解释，用户只会以为
     * 这些能力"不支持"。{@code attempts=0} 表明一条请求都没发。</p>
     */
    private static void markSkipped(Probes probes, List<String> keys, String reason) {
        for (String key : keys) {
            probes.state.put(key, ProbeState.NOT_EXECUTED);
            probes.diagnostics.put(key, new Diagnostic(ProbeState.NOT_EXECUTED.name(),
                    PROBE_LABEL.getOrDefault(key, key), null, "NOT_EXECUTED", reason, 0));
        }
    }

    // ==================== 请求体 ====================

    /**
     * chat 请求体。
     *
     * @param mediaPart    媒体片段；{@code null} = 纯文本探针
     * @param arrayContent 文本用数组形态（{@code [{"type":"text"...}]}）而不是裸字符串。
     *                     有些网关对部分模型只接受数组，裸字符串直接 400
     * @param tokenParam   输出上限的参数名：{@code max_tokens} 或 {@code max_completion_tokens}
     */
    private static Map<String, Object> chatBody(String model, Map<String, Object> mediaPart,
                                                boolean arrayContent, String tokenParam) {
        Object content;
        if (mediaPart != null) {
            /* 带媒体时必然是数组：文本片段 + 媒体片段 */
            content = List.of(Map.of("type", "text", "text", "hi"), mediaPart);
        } else if (arrayContent) {
            content = List.of(Map.of("type", "text", "text", "hi"));
        } else {
            content = "hi";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put(tokenParam, PROBE_MAX_TOKENS);
        return body;
    }

    private static Map<String, Object> imageUrlPart(String dataUri) {
        return Map.of("type", "image_url", "image_url", Map.of("url", dataUri));
    }

    private static Map<String, Object> inputAudioPart(String base64) {
        return Map.of("type", "input_audio", "input_audio", Map.of("data", base64, "format", "wav"));
    }

    private static Map<String, Object> videoPart(String dataUri) {
        return Map.of("type", "video_url", "video_url", Map.of("url", dataUri));
    }

    private static Map<String, Object> filePart(String dataUri, String filename) {
        return Map.of("type", "file", "file", Map.of("filename", filename, "file_data", dataUri));
    }

    private static Map<String, Object> imagesBody(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", "x");
        body.put("n", 1);
        return body;
    }

    private static Map<String, Object> ttsBody(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", "a");
        body.put("voice", "alloy");
        return body;
    }

    private static Map<String, Object> embedBody(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", "hi");
        return body;
    }

    private static Map<String, Object> rerankBody(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", "hi");
        body.put("documents", List.of("a"));
        return body;
    }

    // ==================== HTTP ====================

    /**
     * 一次探针的原始回执。
     *
     * @param status HTTP 状态码；{@code -1} = 连不上 / 超时 / 构造请求就失败
     * @param body   响应体原文（可能为空）；非 2xx 时用来解释"服务商说了什么"
     */
    private record Reply(int status, String body) {
    }

    /**
     * 一次 chat 探针的最终回执与尝试过程。
     *
     * @param reply    最终回执
     * @param attempts 实际请求次数（1 = 一次就过）
     * @param compat   触发过的兼容回退说明；没有回退时为 {@code null}
     */
    private record ChatAttempt(Reply reply, int attempts, String compat) {
    }

    /**
     * 发一条 chat 探针，必要时按**明确的格式错误**做有界回退。
     *
     * <p>只对两类 400 回退，别的 400（模型名错、鉴权、限流、服务端错）一次就返回 ——
     * 拿不准就不重试，把原始回执交出去比多发一次请求有用。</p>
     *
     * <p>两类分别是：① content 只接受数组；② 输出上限只认 {@code max_completion_tokens}。
     * 每次回退只修一项、修过的项不再回头，请求次数封顶 {@link #MAX_COMPAT_ATTEMPTS}，
     * 不会出现「两个回退互相触发」的循环。</p>
     */
    private ChatAttempt postChatCompat(String base, String apiKey, String model,
                                       Map<String, Object> mediaPart) {
        boolean arrayContent = false;
        String tokenParam = "max_tokens";
        String compat = null;
        int attempts = 0;
        Reply reply = null;
        while (attempts < MAX_COMPAT_ATTEMPTS) {
            attempts++;
            reply = postJson(base, apiKey, "/chat/completions",
                    chatBody(model, mediaPart, arrayContent, tokenParam));
            if (reply.status() != 400) {
                break;
            }
            if (!arrayContent && looksLikeContentShapeError(reply.body())) {
                arrayContent = true;
                compat = "content 改为数组";
                continue;
            }
            if ("max_tokens".equals(tokenParam) && looksLikeTokenParamError(reply.body())) {
                tokenParam = "max_completion_tokens";
                compat = (compat == null ? "" : compat + " + ") + "改用 max_completion_tokens";
                continue;
            }
            break;
        }
        return new ChatAttempt(reply, attempts, compat);
    }

    /**
     * 一次探测的收集器：三态结论、日志摘要、页面诊断。
     *
     * <p>三份数据必须同源生成 —— 各写各的迟早会出现「页面说未确认、日志说 404」这种对不上的情况。</p>
     */
    private static final class Probes {
        final Map<String, ProbeState> state = new LinkedHashMap<>();
        final Map<String, String> details = new LinkedHashMap<>();
        final Map<String, Diagnostic> diagnostics = new LinkedHashMap<>();

        /**
         * 结论清单之外的补充说明（目前只有「实时语音模型」一条）。
         *
         * <p>它不属于某个探针的失败原因，而是「这次探测为什么注定测不出来」——
         * 放在 {@code note} 末尾，用户不必展开诊断就能看懂。</p>
         */
        String hint;
    }

    /** chat 系探针的统一入口：发请求（含兼容回退）→ 验收回执形状 → 归类 → 记录尝试次数。 */
    private boolean recordChat(Probes probes, String key, String base, String apiKey,
                               String model, Map<String, Object> mediaPart) {
        ChatAttempt attempt = postChatCompat(base, apiKey, model, mediaPart);
        /* 2xx 也要验收：网关对「不支持 HTTP 的模型」照样回 200 空壳，见 hasChatCompletion 的说明 */
        boolean ok = mark(probes, key, attempt.reply(), this::hasChatCompletion, attempt.attempts());
        if (attempt.attempts() > 1) {
            log.info("[模型探测] {} 共尝试 {} 次（兼容回退：{}），最终 HTTP {}",
                    key, attempt.attempts(), attempt.compat(), attempt.reply().status());
            if (!ok) {
                /* 回退过程本身就是排障信息，别只留最后一次的原始回执 */
                String prefix = "兼容回退 " + attempt.attempts() + " 次（" + attempt.compat() + "）仍失败；";
                probes.details.computeIfPresent(key, (k, v) -> prefix + v);
                probes.diagnostics.computeIfPresent(key, (k, d) -> new Diagnostic(
                        d.state(), d.label(), d.statusCode(), d.category(),
                        clip(prefix + d.message()), d.attempts()));
            }
        }
        return ok;
    }

    /**
     * 回执是不是在说「content 必须是数组」。
     *
     * <p>实测样本：{@code InvalidParameter: Input should be a valid list: input.messages.0.content}。
     * 判定要求 content 与形状词同时出现：只出现 content 的 400（内容策略、空内容）不该重试，
     * 换形态也不会变。</p>
     */
    private static boolean looksLikeContentShapeError(String body) {
        if (body == null) {
            return false;
        }
        String s = body.toLowerCase(java.util.Locale.ROOT);
        if (!s.contains("content")) {
            return false;
        }
        return s.contains("valid list") || s.contains("must be a list") || s.contains("should be a list")
                || s.contains("expected array") || s.contains("not of type 'array'")
                || s.contains("not of type array");
    }

    /**
     * 回执是不是在说「{@code max_tokens} 这个参数名不被接受」。
     *
     * <p>新版 OpenAI 模型（o 系 / gpt-5）只认 {@code max_completion_tokens}，传旧名直接
     * {@code unsupported_parameter}。这是**参数名**问题，不是模型不支持这个端点。</p>
     */
    private static boolean looksLikeTokenParamError(String body) {
        if (body == null) {
            return false;
        }
        String s = body.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("max_completion_tokens")) {
            return true;
        }
        return s.contains("max_tokens")
                && (s.contains("unsupported") || s.contains("not supported")
                    || s.contains("unknown") || s.contains("unrecognized") || s.contains("invalid"));
    }

    /**
     * 端点 → 请求总超时。按路径前缀取，不要求调用方各自传：超时和"探什么"是同一件事，
     * 分散到每个探针里写数字最容易漏。
     */
    Duration timeoutFor(String path) {
        if (path.startsWith("/chat")) {
            return timeouts.chat();
        }
        if (path.startsWith("/embeddings")) {
            return timeouts.embedding();
        }
        if (path.startsWith("/images")) {
            return timeouts.images();
        }
        if (path.equals("/audio/speech")) {
            return timeouts.tts();
        }
        if (path.equals("/audio/transcriptions")) {
            return timeouts.asr();
        }
        return timeouts.other();
    }

    private Reply postJson(String base, String apiKey, String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(timeoutFor(path))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            /* 用 ofString 而不是 discarding：非 2xx 时响应体是唯一的失败线索，
               丢掉它等于让用户只能看到「未确认」三个字 */
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(-1, null);
        } catch (java.net.http.HttpTimeoutException e) {
            /* 超时不是 HTTP 码：状态置 -2，诊断里要能和「服务商回 4xx」区分开 */
            return new Reply(-2, e.getMessage());
        } catch (Exception e) {
            log.debug("[模型探测] {} 失败：{}", path, e.getMessage());
            return new Reply(-1, e.getMessage());
        }
    }

    /** multipart/form-data 上传（语音识别用）：file 段 + model 段 */
    private Reply postMultipart(String base, String apiKey, String path, String model, byte[] file) {
        String boundary = "----DomainPlaneProbe" + System.nanoTime();
        try {
            byte[] payload = multipartBody(boundary, model, file);
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(timeoutFor(path))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(-1, null);
        } catch (java.net.http.HttpTimeoutException e) {
            return new Reply(-2, e.getMessage());
        } catch (Exception e) {
            log.debug("[模型探测] {} multipart 失败：{}", path, e.getMessage());
            return new Reply(-1, e.getMessage());
        }
    }

    private static byte[] multipartBody(String boundary, String model, byte[] file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ==================== 结论归类 ====================

    /**
     * 记录一条探针结论并回报是否 2xx；非 2xx 时把服务商回执摘要收进 {@code details}。
     *
     * 归类刻意保守：只有 404 / 405 / 415 / 501 这类「端点或类型不存在」才记 {@code UNSUPPORTED}；
     * 400 / 422（可能是样本格式被挑剔）、5xx、429、网络异常一律记 {@code UNCONFIRMED}，
     * "这次没确认到"不等于"确定没有"。
     */
    private boolean mark(Probes probes, String key, Reply reply) {
        return mark(probes, key, reply, null, 1);
    }

    private boolean mark(Probes probes, String key, Reply reply, Predicate<String> acceptBody) {
        return mark(probes, key, reply, acceptBody, 1);
    }

    /**
     * 记录一条探针结论：三态归位 + 日志摘要 + 页面诊断。
     *
     * @param acceptBody 可选的响应体验收：2xx 只说明「这个地址通了」，不等于「产出了对应形态的结果」。
     *                   验收不过降为 {@code UNCONFIRMED}（是"这次没确认到"，绝不反向写「不支持」）
     * @param attempts   实际请求次数（走了兼容回退的就是 2 或 3）
     */
    private boolean mark(Probes probes, String key, Reply reply,
                         Predicate<String> acceptBody, int attempts) {
        ProbeState s = classify(reply.status());
        String category = categoryOf(reply, s);
        if (s == ProbeState.CONFIRMED && acceptBody != null && !acceptBody.test(reply.body())) {
            s = ProbeState.UNCONFIRMED;
            category = "RESPONSE_SHAPE";
            probes.details.put(key, "2xx 但响应体不是该端点的产出：" + snippet(reply.body()));
        }
        probes.state.put(key, s);
        if (s != ProbeState.CONFIRMED) {
            String detail = snippet(reply.body());
            if (!detail.isEmpty()) {
                /* 验收失败那段说明比原始回执更能指出问题，别被覆盖掉 */
                probes.details.putIfAbsent(key, detail);
            }
        }
        probes.diagnostics.put(key, new Diagnostic(s.name(),
                PROBE_LABEL.getOrDefault(key, key),
                reply.status() > 0 ? reply.status() : null, category, snippet(reply.body()), attempts));
        return s == ProbeState.CONFIRMED;
    }

    /**
     * 失败归类 —— 页面据此显示「配置错 / 格式不兼容 / 限流 / 超时」，而不是一律「未确认」。
     *
     * <p>{@code CONFIG} 是实测里独立出来的一类：{@code /embeddings} 回 400 {@code url error}
     * 说明 baseUrl 路径不对（少了 {@code /v1}、或这家没有该路由），那既不是"模型能力没确认到"，
     * 也不该在将来被当成「不支持」而撤销声明。</p>
     */
    private static String categoryOf(Reply reply, ProbeState state) {
        int status = reply.status();
        if (status == -2) {
            return "TIMEOUT";
        }
        if (status == -1) {
            return "NETWORK";
        }
        if (state == ProbeState.CONFIRMED) {
            return "OK";
        }
        if (status == 401 || status == 403) {
            return "AUTH";
        }
        if (status == 429) {
            return "RATE_LIMIT";
        }
        if (status >= 500) {
            return "UPSTREAM";
        }
        if (status == 404 || status == 405 || status == 415 || status == 501) {
            return "UNSUPPORTED";
        }
        if (status == 400 || status == 422) {
            if (looksLikeConfigError(reply.body())) {
                return "CONFIG";
            }
            if (looksLikeContentShapeError(reply.body()) || looksLikeTokenParamError(reply.body())) {
                return "REQUEST_FORMAT";
            }
            return "UPSTREAM";
        }
        return "UPSTREAM";
    }

    /** 回执是不是在说「地址 / 路由 / 模型名配错了」 —— 这类错重试与换形态都没用 */
    private static boolean looksLikeConfigError(String body) {
        if (body == null) {
            return false;
        }
        String s = body.toLowerCase(java.util.Locale.ROOT);
        return s.contains("url error") || s.contains("check url") || s.contains("invalid url")
                || s.contains("model_not_found") || s.contains("model not found")
                || s.contains("unknown model") || s.contains("invalid model")
                || s.contains("does not exist");
    }

    /**
     * 向量端点的产出必须是 {@code data[].embedding} 这样的非空数组。
     * 空对象、chat completion 回执、网关兜底的 200 都过不了这一关。
     */
    private boolean hasEmbeddingVector(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode data = mapper.readTree(body).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return false;
            }
            JsonNode vector = data.get(0).path("embedding");
            return vector.isArray() && !vector.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 对话回执必须真的是「一次对话的产出」：{@code choices} 非空，首项带
     * {@code message} / {@code delta} / {@code text} 之一。
     *
     * <p>这条验收来自一次真实误判：某中转对 {@code qwen-audio-3.1-realtime-flash} 的
     * {@code /chat/completions} 回 {@code 200 {"status_message":"Success.","status_name":"Success"}}——
     * 没有 {@code choices}，也没有一个字的回复。只看状态码的探针会把它判成「对话可用」，
     * 再连带把图片 / 视频 / 文件输入全标上（媒体探针拿到的也是同一个空壳），于是卡片上出现
     * 一个「输入 text+image+audio+video+file、输出 text」的模型，而它实际连一句话都回不出来。</p>
     *
     * <p>不校验 {@code content} 非空：思考模型在极小的输出上限下可能只产出
     * {@code reasoning_content}，{@code content} 为空不代表对话不通。</p>
     */
    private boolean hasChatCompletion(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode choices = mapper.readTree(body).path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return false;
            }
            JsonNode first = choices.get(0);
            return first.path("message").isObject() || first.path("delta").isObject()
                    || first.has("text");
        } catch (Exception e) {
            return false;
        }
    }

    private static ProbeState classify(int status) {
        if (status / 100 == 2) {
            return ProbeState.CONFIRMED;
        }
        if (status == 404 || status == 405 || status == 415 || status == 501) {
            return ProbeState.UNSUPPORTED;
        }
        return ProbeState.UNCONFIRMED;
    }

    /**
     * 响应体摘要：压成一行 → 脱敏 → 截断。
     *
     * <p>服务商回执是**不可信内容**：它可能原样回显 Authorization，也可能带上用户填的 Key。
     * 摘要既要给页面看，也会进日志，所以脱敏放在这里 —— 只做一次，两个出口都覆盖到。</p>
     */
    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return clip(redact(body.replaceAll("\\s+", " ").trim()));
    }

    /** 截断到 {@link #DETAIL_MAX}：够看清服务商说了什么，又不至于把诊断区撑成一堵墙 */
    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= DETAIL_MAX ? s : s.substring(0, DETAIL_MAX) + "…";
    }

    /**
     * 脱敏：`Bearer xxx`、`sk-...`、以及 {@code api_key / authorization / access_token} 后面的值。
     *
     * <p>规则刻意从宽（宁可多抹几个字符）：回执进页面之前，误伤几个普通词远比泄漏一把密钥便宜。</p>
     */
    static String redact(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = s.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+", "$1***");
        out = out.replaceAll("sk-[A-Za-z0-9._\\-]{6,}", "sk-***");
        out = out.replaceAll(
                "(?i)(\"?(?:api[_-]?key|authorization|access[_-]?token)\"?\\s*[:=]\\s*\"?)[^\",}\\s]+", "$1***");
        return out;
    }

    // ==================== 最小媒体样本 ====================

    /**
     * 图片输入探针的样本：**现场生成**的 {@link #SAMPLE_SIDE}×{@link #SAMPLE_SIDE} 中灰 PNG。
     *
     * 不做成硬编码 base64 常量，是因为"抄来的那串到底合不合法"没人验得出来，曾经就是抄了一串
     * 广为流传的 1×1 透明 PNG，而它的 IDAT CRC 本就是错的，服务商一律 400 拒收，导致**全部视觉
     * 模型都探不出 image 输入**且不报错。现场编码后由单测校验 chunk CRC，"样本合法"这件事才可自证。
     *
     * 尺寸取 64 而非 1：有服务商对边长设了硬门槛（DeepSeek-OCR 要求 &gt; 28），1×1 必被拒。
     */
    static byte[] pngSample() {
        int side = SAMPLE_SIDE;
        byte[] raw = new byte[side * (1 + side * 3)];
        for (int y = 0; y < side; y++) {
            int row = y * (1 + side * 3);
            /* 行首是 filter 字节（0 = None），后续是 RGB 三元组。
               取中灰而不是全黑/全白：个别服务商会把纯色判成"空图"再退回一次额外校验 */
            for (int x = 0; x < side; x++) {
                int p = row + 1 + x * 3;
                raw[p] = (byte) 0x80;
                raw[p + 1] = (byte) 0x80;
                raw[p + 2] = (byte) 0x80;
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeIntBE(ihdr, side);
        writeIntBE(ihdr, side);
        ihdr.write(8);   // 位深
        ihdr.write(2);   // 颜色类型 2 = 真彩色 RGB
        ihdr.write(0);   // 压缩方法（0 = deflate，唯一合法值）
        ihdr.write(0);   // 滤波方法
        ihdr.write(0);   // 隔行扫描（0 = 不隔行）

        writePngChunk(out, "IHDR", ihdr.toByteArray());
        writePngChunk(out, "IDAT", zlibDeflate(raw));
        writePngChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** 图片样本的 base64 —— 每次探测都要用，算一次就够 */
    static String pngSampleBase64() {
        return Base64.getEncoder().encodeToString(pngSample());
    }

    /** PNG chunk = 长度(4, BE) + 类型(4) + 数据 + CRC32(类型 + 数据)。CRC 写错服务商直接判"坏文件"。 */
    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeIntBE(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeIntBE(out, (int) crc.getValue());
    }

    /** PNG 的 IDAT 要的是 zlib 包装的 deflate 流（{@link Deflater} 默认正是这个格式） */
    private static byte[] zlibDeflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** 0.1s 静音单声道 8kHz 16-bit WAV（约 1.6KB，可被标准解析器识别） */
    static byte[] silentWav() {
        int sampleRate = 8000;
        int samples = 800;
        int dataSize = samples * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);
        writeShortLE(out, 1);
        writeIntLE(out, sampleRate);
        writeIntLE(out, sampleRate * 2);
        writeShortLE(out, 2);
        writeShortLE(out, 16);
        writeAscii(out, "data");
        writeIntLE(out, dataSize);
        out.writeBytes(new byte[dataSize]);
        return out.toByteArray();
    }

    /**
     * 最小可解析 MP4 容器（ftyp + 空 moov + 空 mdat）。
     *
     * 刻意只保证**容器结构合法**，不含真正的视频轨道：视频输入探测本就是近似，各家对
     * base64 视频的支持差异很大，样本再完美也常被判 400。失败会记为「未确认」，方向是安全的。
     */
    static byte[] minimalMp4() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] ftyp = concat(boxHeader(28, "ftyp"),
                "isom".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0, 0, 0, 0x20},
                "isomiso2mp41".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ftyp);
        out.writeBytes(boxHeader(8, "moov"));
        out.writeBytes(boxHeader(8, "mdat"));
        return out.toByteArray();
    }

    /** 一页空白 PDF（含最小目录结构；xref 从简，多数服务商按文件名 / content-type 识别）。 */
    static byte[] minimalPdf() {
        String pdf = "%PDF-1.4\n"
                + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
                + "trailer<</Root 1 0 R/Size 4>>\n"
                + "%%EOF\n";
        return pdf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] boxHeader(int size, String type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeIntBE(out, size);
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.writeBytes(a);
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeIntBE(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    // ==================== 杂项 ====================

    private static void add(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    /**
     * 人话结论：**只给清单，不给理由**。
     *
     * <p>早先这里把每个没探到的探针都附上服务商原话（JSON 回执），一屏几百个字，
     * 管理员得自己从里面挑「到底支持什么」。现在只列三行：支持 / 不支持 / 未确认，
     * 一眼扫完就知道这个模型能干什么。服务商原话不是丢了，是回到服务端日志里
     * （见上面两行 {@code log.info}）—— 要追具体哪次探测被拒，翻日志比读弹窗快。
     *
     * <p>「不支持」只给 404/405/415/501 这类「端点确实不存在」；
     * 400/5xx/限流/网络抖动一律算「未确认」：那只是这次没探到，
     * 写成「不支持」等于把一次抖动记成模型的缺陷。
     */
    private static String buildNote(List<String> endpoints, List<String> input, List<String> output,
                                    Map<String, ProbeState> state) {
        List<String> supported = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<String> unconfirmed = new ArrayList<>();
        List<String> notExecuted = new ArrayList<>();
        state.forEach((key, s) -> {
            String label = PROBE_LABEL.getOrDefault(key, key);
            if (s == ProbeState.CONFIRMED) {
                supported.add(label);
            } else if (s == ProbeState.UNSUPPORTED) {
                unsupported.add(label);
            } else if (s == ProbeState.NOT_EXECUTED) {
                notExecuted.add(label);
            } else {
                unconfirmed.add(label);
            }
        });

        StringBuilder sb = new StringBuilder("实测确认：端点族 ")
                .append(endpoints.isEmpty() ? "（无）" : endpoints)
                .append("；输入 ").append(input.isEmpty() ? "（无）" : input)
                .append(" → 输出 ").append(output.isEmpty() ? "（无）" : output).append("。")
                .append("\n支持：").append(supported.isEmpty() ? "无" : String.join("、", supported));
        if (!unsupported.isEmpty()) {
            sb.append("\n不支持：").append(String.join("、", unsupported));
        }
        if (!unconfirmed.isEmpty()) {
            sb.append("\n未确认：").append(String.join("、", unconfirmed));
        }
        if (!notExecuted.isEmpty()) {
            sb.append("\n未执行：").append(String.join("、", notExecuted))
                    .append("（连不上服务商地址，一条请求都没发）");
        }
        /* 输出 video 与输出 image 不是一回事：OpenAI 规范下没有统一的视频生成端点，
           探针探不了，这句是免得把「输出无 video」误读成「模型不支持视频输出」。 */
        sb.append("\n（输出视频无通用探测端点，需手动声明。）");
        return sb.toString();
    }

    private static String trimSlash(String s) {
        String v = s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
