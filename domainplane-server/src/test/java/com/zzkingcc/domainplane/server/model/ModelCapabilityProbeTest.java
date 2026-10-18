package com.zzkingcc.domainplane.server.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力探测的实测路径 —— 用本机桩覆盖「chat 通 / embedding 通 / image 输入通 / 音频·视频·文件输入不通 /
 * 生图·语音·重排端点不存在」这一组合，验证探针电池把它们如实归到端点族与模态上。
 *
 * 桩按请求体判定媒体类型（content 数组里出现哪个关键字），从而只对确认支持的探针回 2xx。
 *
 * @author zzkingcc
 */
class ModelCapabilityProbeTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        /* chat：纯文本与 image_url 回 200（支持）；input_audio 回 404（不支持）；video_url 回 400、
           file_data 回 415（未确认 / 不支持，方向都是"没确认到"） */
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("image_url")) {
                respond(ex, 200, "{}");
            } else if (body.contains("input_audio")) {
                respond(ex, 404, "{}");
            } else if (body.contains("video_url")) {
                respond(ex, 400, "{}");
            } else if (body.contains("file_data")) {
                respond(ex, 415, "{}");
            } else {
                respond(ex, 200, "{}");
            }
        });
        server.createContext("/v1/embeddings", ex -> { drain(ex); respond(ex, 200, "{}"); });
        server.createContext("/v1/images/generations", ex -> { drain(ex); respond(ex, 404, "{}"); });
        server.createContext("/v1/audio/speech", ex -> { drain(ex); respond(ex, 404, "{}"); });
        server.createContext("/v1/audio/transcriptions", ex -> { drain(ex); respond(ex, 404, "{}"); });
        server.createContext("/v1/rerank", ex -> { drain(ex); respond(ex, 404, "{}"); });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static void drain(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 探测把确认支持的端点族与模态如实归位() {
        ModelCapabilityProbe probe = new ModelCapabilityProbe();

        ModelCapabilityProbe.ProbeResult r =
                probe.probeCapabilities("http://127.0.0.1:" + port + "/v1", "sk-test", "m");

        assertTrue(r.endpoints().contains(ModelProfile.EP_CHAT), "chat 通了");
        assertTrue(r.endpoints().contains(ModelProfile.EP_EMBEDDING), "embedding 通了");
        assertFalse(r.endpoints().contains(ModelProfile.EP_IMAGES), "生图端点 404 → 不记");
        assertFalse(r.endpoints().contains(ModelProfile.EP_TTS), "TTS 端点 404 → 不记");
        assertFalse(r.endpoints().contains(ModelProfile.EP_ASR), "ASR 端点 404 → 不记");
        assertFalse(r.endpoints().contains(ModelProfile.EP_RERANK), "重排端点 404 → 不记");

        assertTrue(r.input().contains(ModelProfile.MOD_TEXT), "文本输入");
        assertTrue(r.input().contains(ModelProfile.MOD_IMAGE), "图片输入通");
        assertFalse(r.input().contains(ModelProfile.MOD_AUDIO), "音频输入 404 → 不记");
        assertFalse(r.input().contains(ModelProfile.MOD_VIDEO), "视频输入 400 → 未确认，不记");

        assertTrue(r.output().contains(ModelProfile.MOD_TEXT), "文本输出");
        assertTrue(r.output().contains(ModelProfile.MOD_EMBEDDING), "向量输出");
        assertFalse(r.output().contains(ModelProfile.MOD_IMAGE), "生图不通 → 无 image 输出");
    }

    @Test
    void 三态归类_2xx确认_404不支持_400与415未确认() {
        ModelCapabilityProbe probe = new ModelCapabilityProbe();

        Map<String, ModelCapabilityProbe.ProbeState> state =
                probe.probeCapabilities("http://127.0.0.1:" + port + "/v1", "sk-test", "m").perModality();

        assertEquals(ModelCapabilityProbe.ProbeState.CONFIRMED, state.get("chat-text"));
        assertEquals(ModelCapabilityProbe.ProbeState.UNSUPPORTED, state.get("chat-audio-in"),
                "404 记「不支持」");
        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, state.get("chat-video-in"),
                "400 可能是样本格式问题 → 记「未确认」，不武断判不支持");
        assertEquals(ModelCapabilityProbe.ProbeState.UNSUPPORTED, state.get("chat-file-in"),
                "415 记「不支持」");
    }

    @Test
    void 必填项缺失直接拒绝() {
        ModelCapabilityProbe probe = new ModelCapabilityProbe();
        assertThrows(IllegalArgumentException.class,
                () -> probe.probeCapabilities("", "sk", "m"));
    }

    @Test
    void 最小样本结构合法() {
        byte[] wav = ModelCapabilityProbe.silentWav();
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        assertTrue(wav.length > 44, "含 WAV 头 + 采样数据");

        byte[] pdf = ModelCapabilityProbe.minimalPdf();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        byte[] mp4 = ModelCapabilityProbe.minimalMp4();
        assertEquals("ftyp", new String(mp4, 4, 4, StandardCharsets.US_ASCII));
    }

    /**
     * 图片样本必须**自己就是合法 PNG**，且边长足够大。
     *
     * 这条断言来自一次真实事故：曾经硬编码一串广为流传的「1×1 透明 PNG」，而它的 IDAT CRC 本就是
     * 错的：服务商一律 400 拒收（"broken PNG file (bad header checksum in b'IDAT')"），
     * 而 400 在探针里只记「未确认」，于是**所有视觉模型都探不出 image 输入**且全程无报错。
     * 所以这里逐个 chunk 校验 CRC：样本合法必须是可自证的事实，不能靠"这串是网上抄的"。
     *
     * 边长另有一条硬门槛：DeepSeek-OCR 要求 {@code height/width must be larger than 28}，1×1 必被拒。
     */
    @Test
    void 图片样本是合法PNG且边长足够() throws Exception {
        byte[] png = ModelCapabilityProbe.pngSample();

        assertEquals((byte) 0x89, png[0]);
        assertEquals("PNG", new String(png, 1, 3, StandardCharsets.US_ASCII));

        int off = 8;
        String ihdrType = null;
        int width = 0;
        int height = 0;
        boolean sawIdat = false;
        boolean sawIend = false;
        while (off + 12 <= png.length) {
            int len = readIntBE(png, off);
            String type = new String(png, off + 4, 4, StandardCharsets.US_ASCII);
            byte[] data = java.util.Arrays.copyOfRange(png, off + 8, off + 8 + len);
            long stored = readIntBE(png, off + 8 + len) & 0xFFFFFFFFL;
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(type.getBytes(StandardCharsets.US_ASCII));
            crc.update(data);
            assertEquals(crc.getValue(), stored, type + " chunk 的 CRC 必须正确（CRC 错就等于服务商判「坏文件」）");

            if ("IHDR".equals(type)) {
                ihdrType = type;
                width = readIntBE(data, 0);
                height = readIntBE(data, 4);
            } else if ("IDAT".equals(type)) {
                sawIdat = true;
            } else if ("IEND".equals(type)) {
                sawIend = true;
            }
            off += 12 + len;
        }
        assertEquals(png.length, off, "chunk 长度必须首尾对齐，不留残尾");
        assertNotNull(ihdrType, "必须有 IHDR");
        assertTrue(sawIdat, "必须有 IDAT");
        assertTrue(sawIend, "必须有 IEND");
        assertTrue(width > 28 && height > 28, "边长必须大于 28（DeepSeek-OCR 的硬门槛），实际 "
                + width + "x" + height);
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 非 2xx 时服务商的原话要进 note：「未确认」三个字本身不提供任何可行动信息 */
    @Test
    void 未确认的探针把服务商回执带进说明() {
        ModelCapabilityProbe probe = new ModelCapabilityProbe();

        String note = probe.probeCapabilities("http://127.0.0.1:" + port + "/v1", "sk-test", "m").note();

        assertTrue(note.contains("chat-video-in=UNCONFIRMED"), "视频输入 400 → 未确认，要列进说明");
        assertTrue(note.contains("服务商回：{}"), "非 2xx 的响应体摘要要带上（本桩回的就是 {}）");
        assertFalse(note.contains("chat-image-in"),
                "图片输入在本桩里是通的（2xx），不该出现在未确认清单里");
    }
}
