package com.zzkingcc.domainplane.server.model;

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
 *   • **最小 token**。文本请求 {@code max_tokens=1}、提示词 {@code "hi"}；媒体用最小可解析样本
 *     （内联 base64 的 PNG / 0.1s 静音 WAV / 1 页 PDF）。真正花钱的只有生图与 TTS 两条，已压到最小。
 *   • **样本必须自身合法，一律现场生成，不抄现成的 base64 串**。这条是踩过坑之后加的硬约束：
 *     曾经硬编码一串广为流传的「1×1 透明 PNG」，而**那串本身就是坏的**（IDAT 的 CRC 对不上、
 *     chunk 结构错乱），服务商直接 400 拒收，于是**所有视觉模型都探不出 image 输入**、全程无报错。
 *     现在图片样本由 {@link #pngSample()} 现场编码并校验 CRC；尺寸取 64×64 而非 1×1，
 *     边长有真实门槛（DeepSeek-OCR 明确要求 {@code height/width must be larger than 28}）。
 *   • **不依赖模型自述**。不发「请列出你的能力」这类提示让模型自我陈述，幻觉会让廉价模型谎称
 *     不支持，全部走结构化端点实测。
 *   • **只增不减**。失败只记「未确认」，**不写「不支持」**：一次网络抖动或样本格式被挑剔都会失败，
 *     那是"这次没确认到"，不是"确定没有"。写回由 {@link ModelProfile#withConfirmed} 取并集。
 *   • **失败要能看见**。非 2xx 时把服务商响应体摘要带进 {@code note}（见 {@link #buildNote}），
 *     只报「未确认」等于没说，"服务商回了一句什么"才是可行动的信息。
 *
 * 探针默认**串行**：这是低频手动动作，串行免触发廉价档位的限流，也便于逐条定位失败原因。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ModelCapabilityProbe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

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

    /** 图片样本的边长：必须 > 28（DeepSeek-OCR 的硬门槛），取 64 兼顾通用 VLM 与图 token 开销 */
    private static final int SAMPLE_SIDE = 64;

    /** 失败原因摘要的截断长度 —— 够看清服务商说了什么，又不至于让 note 变成一堵墙 */
    private static final int DETAIL_MAX = 160;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /** 每个模态的探测结论：三态 —— {@code Boolean} 装不下「未确认」与「不支持」的区别。 */
    public enum ProbeState { CONFIRMED, UNSUPPORTED, UNCONFIRMED }

    /**
     * 探测结果。
     *
     * @param endpoints   实测确认可用的端点族
     * @param input       实测确认支持的输入模态
     * @param output      实测确认支持的输出模态
     * @param perModality 逐探针结论（诊断 / 卡片回显用）
     * @param note        人话说明：确认了什么、哪些没确认、没确认时服务商原话是什么
     */
    public record ProbeResult(List<String> endpoints, List<String> input, List<String> output,
                              Map<String, ProbeState> perModality, String note) {
        public ProbeResult {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
            perModality = perModality == null ? Map.of() : Map.copyOf(perModality);
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

        Map<String, ProbeState> state = new LinkedHashMap<>();
        Map<String, String> details = new LinkedHashMap<>();
        List<String> endpoints = new ArrayList<>();
        List<String> input = new ArrayList<>();
        List<String> output = new ArrayList<>();

        /* #1 chat-text —— 对话端点的主判据。它不通，媒体输入（也走 /chat/completions）必然不通，
           为省调用直接跳过 #2–#5。 */
        boolean chat = record(state, details, P_CHAT_TEXT, postJson(base, apiKey, "/chat/completions",
                chatTextBody(modelName)));
        if (chat) {
            add(endpoints, ModelProfile.EP_CHAT);
            add(input, ModelProfile.MOD_TEXT);
            add(output, ModelProfile.MOD_TEXT);

            /* #2–#5 输入模态：同一个 chat 端点，content 数组里各塞一种最小媒体样本。
               注意图片输入只有这一条通道，生图模型的「图生图」走的是 /images/generations 的
               image 参数，不在这里，也不由本探针判定（见类注释与 ModelProfile#input 的语义）。 */
            if (record(state, details, P_IMAGE_IN, postJson(base, apiKey, "/chat/completions",
                    chatMediaBody(modelName, imageUrlPart("data:image/png;base64," + pngSampleBase64()))))) {
                add(input, ModelProfile.MOD_IMAGE);
            }
            if (record(state, details, P_AUDIO_IN, postJson(base, apiKey, "/chat/completions",
                    chatMediaBody(modelName, inputAudioPart(
                            Base64.getEncoder().encodeToString(silentWav())))))) {
                add(input, ModelProfile.MOD_AUDIO);
            }
            if (record(state, details, P_VIDEO_IN, postJson(base, apiKey, "/chat/completions",
                    chatMediaBody(modelName, videoPart(
                            "data:video/mp4;base64," + Base64.getEncoder().encodeToString(minimalMp4())))))) {
                add(input, ModelProfile.MOD_VIDEO);
            }
            if (record(state, details, P_FILE_IN, postJson(base, apiKey, "/chat/completions",
                    chatMediaBody(modelName, filePart(
                            "data:application/pdf;base64," + Base64.getEncoder().encodeToString(minimalPdf()),
                            "sample.pdf"))))) {
                add(input, ModelProfile.MOD_FILE);
            }
        }

        /* #6 生图：故意不带 size，用服务商默认，避免「不支持该尺寸」被误当成「不支持生图」。
           代价是可能生成一张默认尺寸图，这是确认「输出 image」无法回避的最小费用。 */
        if (record(state, details, P_IMAGES, postJson(base, apiKey, "/images/generations", imagesBody(modelName)))) {
            add(endpoints, ModelProfile.EP_IMAGES);
            add(output, ModelProfile.MOD_IMAGE);
        }

        /* #7 语音合成：voice 用最通用的 alloy；个别服务商要求特定音色会被 400 拒，记为未确认 */
        if (record(state, details, P_TTS, postJson(base, apiKey, "/audio/speech", ttsBody(modelName)))) {
            add(endpoints, ModelProfile.EP_TTS);
            add(output, ModelProfile.MOD_AUDIO);
        }

        /* #8 语音识别：multipart 上传一段静音 WAV，确认 ASR 端点族（音频输入由 #3 另行确认） */
        if (record(state, details, P_ASR, postMultipart(base, apiKey, "/audio/transcriptions",
                modelName, silentWav()))) {
            add(endpoints, ModelProfile.EP_ASR);
        }

        /* #9 向量 */
        if (record(state, details, P_EMBEDDING, postJson(base, apiKey, "/embeddings", embedBody(modelName)))) {
            add(endpoints, ModelProfile.EP_EMBEDDING);
            add(output, ModelProfile.MOD_EMBEDDING);
        }

        /* #10 重排：非 OpenAI 规范路径，多数服务商 404 → 未确认，不影响其余结论 */
        if (record(state, details, P_RERANK, postJson(base, apiKey, "/rerank", rerankBody(modelName)))) {
            add(endpoints, ModelProfile.EP_RERANK);
            add(output, ModelProfile.MOD_SCORE);
        }

        String note = buildNote(endpoints, input, output, state, details);
        log.info("[模型探测] model={} 确认端点族={} 输入={} 输出={}", modelName, endpoints, input, output);
        if (!details.isEmpty()) {
            log.info("[模型探测] model={} 未确认探针及服务商回执：{}", modelName, details);
        }
        return new ProbeResult(endpoints, input, output, state, note);
    }

    // ==================== 请求体 ====================

    private static Map<String, Object> chatTextBody(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        body.put("max_tokens", 1);
        return body;
    }

    /** content 数组：文本 + 一个媒体片段。媒体片段决定这次探的是哪种输入模态。 */
    private static Map<String, Object> chatMediaBody(String model, Map<String, Object> mediaPart) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user",
                "content", List.of(Map.of("type", "text", "text", "hi"), mediaPart))));
        body.put("max_tokens", 1);
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

    private Reply postJson(String base, String apiKey, String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(REQUEST_TIMEOUT)
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
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(-1, null);
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
    private static boolean record(Map<String, ProbeState> state, Map<String, String> details,
                                  String key, Reply reply) {
        ProbeState s = classify(reply.status());
        state.put(key, s);
        if (s != ProbeState.CONFIRMED) {
            String detail = snippet(reply.body());
            if (!detail.isEmpty()) {
                details.put(key, detail);
            }
        }
        return s == ProbeState.CONFIRMED;
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

    /** 响应体摘要：压成一行再截断。只服务于"这次为什么没确认到"，不做任何解析。 */
    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= DETAIL_MAX ? s : s.substring(0, DETAIL_MAX) + "…";
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

    private static String buildNote(List<String> endpoints, List<String> input, List<String> output,
                                    Map<String, ProbeState> state, Map<String, String> details) {
        List<String> unconfirmed = new ArrayList<>();
        state.forEach((k, v) -> {
            if (v != ProbeState.CONFIRMED) {
                String d = details.get(k);
                unconfirmed.add(d == null || d.isEmpty() ? k + "=" + v : k + "=" + v + "（服务商回：" + d + "）");
            }
        });
        StringBuilder sb = new StringBuilder("实测确认：端点族 ")
                .append(endpoints.isEmpty() ? "（无）" : endpoints)
                .append("；输入 ").append(input.isEmpty() ? "（无）" : input)
                .append(" → 输出 ").append(output.isEmpty() ? "（无）" : output).append("。");
        if (!unconfirmed.isEmpty()) {
            sb.append("\n未确认（不等于不支持，可能是网络 / 样本格式 / 服务商差异）：")
                    .append(String.join("、", unconfirmed)).append("。");
        }
        sb.append("\n输出 video 无通用探测端点，不在探测范围内（须由元数据或手动声明）。");
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
