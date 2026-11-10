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
import java.time.Duration;
import java.util.List;
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

    /** 合法的向量回执：data[].embedding 必须是非空数组，光有 200 不算 */
    private static final String VALID_EMBEDDING_BODY =
            "{\"object\":\"list\",\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}";

    /** 正常的对话回执：探针会验 choices，**光有 200 不算确认**（见 CHAT_STUB_BODY） */
    private static final String CHAT_OK_BODY =
            "{\"id\":\"c1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                    + "\"finish_reason\":\"stop\"}]}";

    /**
     * 中转对「没有 HTTP 对话能力」的模型回的 200 空壳 —— 实测抓到的原样回执
     * （{@code qwen-audio-3.1-realtime-flash} 经某中转）。
     *
     * <p>它既没有 {@code choices} 也没有一个字，只看状态码会把它判成「对话可用」。</p>
     */
    private static final String CHAT_STUB_BODY =
            "{\"status_message\":\"Success.\",\"status_name\":\"Success\"}";

    /** 每个用例都能改这一路的回执，用同一个桩覆盖「真向量 / 兜底 200 / 端点不存在」 */
    private int embeddingsStatus = 200;
    private String embeddingsBody = VALID_EMBEDDING_BODY;

    /** 严格模式：只接受数组 content，收到裸字符串回 400 并点名 content（实测某中转的行为） */
    private boolean strictArrayContent = false;

    /** 严格模式：只认 max_completion_tokens，收到 max_tokens 回 400 unsupported_parameter */
    private boolean onlyMaxCompletionTokens = false;

    /** 非 0 时覆盖 chat 端点的返回，用于「普通 400 不重试」这类用例 */
    private int chatStatusOverride = 0;
    private String chatBodyOverride;

    /** images 端点的人为延迟，用来验证「超时按端点分级」 */
    private int imagesDelayMs = 0;
    /** images 端点的状态码（默认 404 = 这家没有生图端点） */
    private int imagesStatus = 404;
    /** 总把 chat 打回 400（且文案仍是 content 形态），用来验证回退次数有上限、不循环 */
    private boolean alwaysRejectChat = false;

    /** 所有 chat 请求都回 200 空壳（无 choices）：模拟「模型没有 HTTP 对话能力，网关仍回 200」 */
    private boolean emptyChatStub = false;

    /** chat 端点实际收到的请求体：断言"发了几次、每次是什么形态"，比只看结论有力 */
    private final List<String> chatRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

    private String baseUrl() {
        return "http://127.0.0.1:" + port + "/v1";
    }

    /**
     * 只数「纯文本」chat 探针发出的请求。
     *
     * <p>媒体探针走同一个端点，混在一起会把"这次回退了几次"算错 —— S5 之后 chat 失败也会
     * 继续跑媒体探针，总数不再是 1。</p>
     */
    private List<String> textProbeRequests() {
        List<String> only = new java.util.ArrayList<>();
        for (String body : chatRequests) {
            if (!body.contains("image_url") && !body.contains("input_audio")
                    && !body.contains("video_url") && !body.contains("file_data")) {
                only.add(body);
            }
        }
        return only;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        /* chat：纯文本与 image_url 回 200（支持）；input_audio 回 404（不支持）；video_url 回 400、
           file_data 回 415（未确认 / 不支持，方向都是"没确认到"） */
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            chatRequests.add(body);
            if (chatStatusOverride != 0) {
                respond(ex, chatStatusOverride, chatBodyOverride == null ? "{}" : chatBodyOverride);
                return;
            }
            if (emptyChatStub) {
                respond(ex, 200, CHAT_STUB_BODY);
                return;
            }
            if (alwaysRejectChat) {
                respond(ex, 400, "{\"code\":\"InvalidParameter\","
                        + "\"message\":\"Input should be a valid list: input.messages.0.content\"}");
                return;
            }
            if (strictArrayContent && !body.contains("\"content\":[")) {
                respond(ex, 400, "{\"code\":\"InvalidParameter\","
                        + "\"message\":\"Input should be a valid list: input.messages.0.content\"}");
                return;
            }
            if (onlyMaxCompletionTokens && body.contains("\"max_tokens\"")) {
                respond(ex, 400, "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens'. "
                        + "Use 'max_completion_tokens' instead.\",\"code\":\"unsupported_parameter\"}}");
                return;
            }
            if (body.contains("image_url")) {
                respond(ex, 200, CHAT_OK_BODY);
            } else if (body.contains("input_audio")) {
                respond(ex, 404, "{}");
            } else if (body.contains("video_url")) {
                respond(ex, 400, "{}");
            } else if (body.contains("file_data")) {
                respond(ex, 415, "{}");
            } else {
                respond(ex, 200, CHAT_OK_BODY);
            }
        });
        server.createContext("/v1/embeddings", ex -> {
            drain(ex);
            respond(ex, embeddingsStatus, embeddingsBody);
        });
        server.createContext("/v1/images/generations", ex -> {
            drain(ex);
            if (imagesDelayMs > 0) {
                try {
                    Thread.sleep(imagesDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(ex, imagesStatus, "{}");
        });
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

    /** 客户端可能已因超时断开，写回执会抛 broken pipe —— 那是超时用例的预期路径，不当失败 */
    private static void respond(HttpExchange ex, int status, String body) {
        try {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
        } catch (IOException ignored) {
            // 对端已断开
        } finally {
            ex.close();
        }
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
        assertEquals(ModelCapabilityProbe.ProbeState.CONFIRMED, state.get("embedding"),
                "合法向量回执才算确认");
    }

    @Test
    void 向量端点回2xx但不是向量响应时不确认() {
        embeddingsBody = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";
        ModelCapabilityProbe probe = new ModelCapabilityProbe();

        ModelCapabilityProbe.ProbeResult r =
                probe.probeCapabilities("http://127.0.0.1:" + port + "/v1", "sk-test", "m");

        assertFalse(r.endpoints().contains(ModelProfile.EP_EMBEDDING),
                "网关兜底的 200 不能把对话模型标成向量模型");
        assertFalse(r.output().contains(ModelProfile.MOD_EMBEDDING));
        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, r.perModality().get("embedding"),
                "形状不符只算这次没确认到，不是「不支持」");
        assertTrue(r.unsupportedFamilies().isEmpty(), "未确认不能用来撤销旧声明");
    }

    @Test
    void 向量端点明确不存在时才允许撤销旧声明() {
        embeddingsStatus = 404;
        ModelCapabilityProbe probe = new ModelCapabilityProbe();

        ModelCapabilityProbe.ProbeResult r =
                probe.probeCapabilities("http://127.0.0.1:" + port + "/v1", "sk-test", "m");

        assertEquals(List.of(ModelProfile.EP_EMBEDDING), r.unsupportedFamilies());
        assertEquals(ModelCapabilityProbe.ProbeState.UNSUPPORTED, r.perModality().get("embedding"));
    }

    @Test
    void 必填项缺失直接拒绝() {
        ModelCapabilityProbe probe = new ModelCapabilityProbe();
        assertThrows(IllegalArgumentException.class,
                () -> probe.probeCapabilities("", "sk", "m"));
    }

    // ==================== 请求兼容回退（S1） ====================

    @Test
    void content只接受数组时自动回退为数组并确认() {
        strictArrayContent = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertTrue(r.endpoints().contains(ModelProfile.EP_CHAT), "回退后应确认 chat");
        assertTrue(chatRequests.get(0).contains("\"content\":\"hi\""),
                "第一次必须是裸字符串：" + chatRequests.get(0));
        assertTrue(chatRequests.get(1).contains("\"content\":["),
                "回退后必须是数组：" + chatRequests.get(1));
        assertEquals(ModelCapabilityProbe.ProbeState.CONFIRMED, r.perModality().get("chat-text"));
    }

    @Test
    void 只认max_completion_tokens时换参数名重试() {
        onlyMaxCompletionTokens = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertTrue(r.endpoints().contains(ModelProfile.EP_CHAT), "换参数名后应确认 chat");
        assertTrue(chatRequests.get(0).contains("\"max_tokens\":"),
                "第一次用 max_tokens：" + chatRequests.get(0));
        assertTrue(chatRequests.get(1).contains("\"max_completion_tokens\":"),
                "回退后用 max_completion_tokens：" + chatRequests.get(1));
    }

    @Test
    void 两个兼容问题同时存在时三次内解决() {
        strictArrayContent = true;
        onlyMaxCompletionTokens = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertTrue(r.endpoints().contains(ModelProfile.EP_CHAT));
        assertTrue(chatRequests.get(0).contains("\"content\":\"hi\"")
                        && chatRequests.get(0).contains("\"max_tokens\":"),
                "第 1 次：字符串 + max_tokens");
        assertTrue(chatRequests.get(1).contains("\"content\":[")
                        && chatRequests.get(1).contains("\"max_tokens\":"),
                "第 2 次只修 content：" + chatRequests.get(1));
        assertTrue(chatRequests.get(2).contains("\"content\":[")
                        && chatRequests.get(2).contains("\"max_completion_tokens\":"),
                "第 3 次再修参数名：" + chatRequests.get(2));
    }

    @Test
    void 普通400不触发回退只发一次() {
        chatStatusOverride = 400;
        chatBodyOverride = "{\"error\":{\"message\":\"Model not found\",\"code\":\"model_not_found\"}}";

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertEquals(1, textProbeRequests().size(), "普通 400 重发也不会变，只该发一次");
        assertFalse(r.endpoints().contains(ModelProfile.EP_CHAT));
    }

    @Test
    void 鉴权限流与服务端错误都不触发兼容回退() {
        for (int status : new int[]{401, 403, 429, 500}) {
            chatRequests.clear();
            chatStatusOverride = status;
            chatBodyOverride = "{\"error\":{\"message\":\"nope\"}}";

            new ModelCapabilityProbe().probeCapabilities(baseUrl(), "sk-test", "m");

            assertEquals(1, textProbeRequests().size(), status + " 不该触发兼容回退");
        }
    }

    @Test
    void 回退次数有上限不会循环() {
        alwaysRejectChat = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertTrue(textProbeRequests().size() >= 2 && textProbeRequests().size() <= 3,
                "最多 3 次尝试，实际 " + textProbeRequests().size());
        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, r.perModality().get("chat-text"));
    }

    // ==================== 诊断详情（S3） ====================

    @Test
    void 诊断带状态码与归类_成功项标OK() {
        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        Map<String, ModelCapabilityProbe.Diagnostic> d = r.diagnostics();
        assertEquals("OK", d.get("chat-text").category());
        assertEquals(200, d.get("chat-text").statusCode());
        assertEquals("对话（文本）", d.get("chat-text").label(), "探针中文名由服务端下发，前端不另存词表");
        assertEquals("UNSUPPORTED", d.get("chat-audio-in").category(), "404 = 端点不存在");
        assertEquals(404, d.get("chat-audio-in").statusCode());
        assertEquals("UPSTREAM", d.get("chat-video-in").category(), "400 但回执没有线索 → 上游错误，不猜");
    }

    @Test
    void 诊断把回执里的密钥抹掉() {
        chatStatusOverride = 400;
        chatBodyOverride = "{\"error\":{\"message\":\"bad key sk-abcdef123456 sent with Bearer abc.def\","
                + "\"code\":\"invalid_api_key\"}}";

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        String msg = r.diagnostics().get("chat-text").message();
        assertFalse(msg.contains("sk-abcdef123456"), "回执里的密钥必须脱敏：" + msg);
        assertFalse(msg.contains("Bearer abc.def"), "Bearer 后面的值必须脱敏：" + msg);
        assertTrue(msg.contains("***"), "脱敏后应留下掩码，别整段消失：" + msg);
    }

    @Test
    void 诊断摘要压成单行并截断() {
        chatStatusOverride = 500;
        chatBodyOverride = "{\"a\":\"x\ny\",\"b\":\"" + "长".repeat(400) + "\"}";

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        String msg = r.diagnostics().get("chat-text").message();
        assertFalse(msg.contains("\n"), "多行回执必须压成一行，否则页面会被撑开");
        assertTrue(msg.length() <= 161, "摘要要截断，实际长度 " + msg.length());
    }

    @Test
    void 地址与模型名配错归为配置类而不是未确认() {
        embeddingsStatus = 400;
        embeddingsBody = "{\"error\":{\"message\":\"url error, please check url！\","
                + "\"code\":\"invalid_parameter_error\"}}";

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertEquals("CONFIG", r.diagnostics().get("embedding").category(),
                "url error 是地址/路由配错，不是「模型没这个能力」");
        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, r.perModality().get("embedding"));
        assertTrue(r.unsupportedFamilies().isEmpty(), "配置错绝不能用来撤销旧声明");
    }

    @Test
    void 兼容回退成功的探针记录尝试次数() {
        strictArrayContent = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertEquals(2, r.diagnostics().get("chat-text").attempts(),
                "回退过一次就该记 2 次，排障时能看出是请求体问题而不是模型问题");
        assertEquals("CONFIRMED", r.diagnostics().get("chat-text").state());
    }

    // ==================== 探针独立结算（S5） ====================

    @Test
    void chat失败也继续跑媒体探针且各自独立结算() {
        chatStatusOverride = 400;
        chatBodyOverride = "{\"error\":{\"message\":\"Model not found\"}}";

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertEquals(5, chatRequests.size(),
                "chat-text 失败不该跳过 4 条媒体探针：总数应为 1+4，实际 " + chatRequests.size());
        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, r.perModality().get("chat-text"));
        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, r.perModality().get("chat-image-in"),
                "媒体探针各自有结论，不因前一条失败而缺失");
        assertFalse(r.endpoints().contains(ModelProfile.EP_CHAT));
        assertFalse(r.input().contains(ModelProfile.MOD_TEXT), "chat 端点没确认，不写输入模态");
    }

    @Test
    void 连不上服务商时其余探针标为未执行而不是未确认() {
        /* 1 号端口不会有人监听：连接直接拒绝，属于「地址级失败」 */
        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities("http://127.0.0.1:1/v1", "sk-test", "m");

        assertEquals(ModelCapabilityProbe.ProbeState.NOT_EXECUTED, r.perModality().get("embedding"));
        assertEquals(ModelCapabilityProbe.ProbeState.NOT_EXECUTED, r.perModality().get("images-gen"));
        assertEquals("NOT_EXECUTED", r.diagnostics().get("embedding").category());
        assertNull(r.diagnostics().get("embedding").statusCode(), "一条请求都没发，没有状态码");
        assertEquals(0, r.diagnostics().get("embedding").attempts());
        assertTrue(r.note().contains("未执行"), "清单里要说清这些能力压根没试过：" + r.note());
        assertTrue(r.unsupportedFamilies().isEmpty(), "没执行不能当成「不支持」");
    }

    @Test
    void 鉴权失败不短路_保留端点级权限差异() {
        chatStatusOverride = 401;
        chatBodyOverride = "{\"error\":{\"message\":\"invalid api key\"}}";

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertEquals(5, chatRequests.size(), "401 只说明 chat 这条过不去，媒体探针仍要试");
        assertTrue(r.endpoints().contains(ModelProfile.EP_EMBEDDING),
                "网关常按端点授权：chat 401 不代表 embedding 不可用");
    }

    // ==================== 超时分级（S2） ====================

    @Test
    void 慢生图不被chat的超时截断() {
        imagesDelayMs = 400;
        imagesStatus = 200;
        ModelCapabilityProbe probe = new ModelCapabilityProbe(new ModelCapabilityProbe.Timeouts(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3)));

        ModelCapabilityProbe.ProbeResult r = probe.probeCapabilities(baseUrl(), "sk-test", "m");

        assertTrue(r.endpoints().contains(ModelProfile.EP_IMAGES),
                "images 超时 3s > 延迟 0.4s，应确认；用统一的 20s 或更短就会误判");
    }

    @Test
    void 超过该端点超时记为未确认且不拖垮其他探针() {
        imagesDelayMs = 1500;
        imagesStatus = 200;
        ModelCapabilityProbe probe = new ModelCapabilityProbe(new ModelCapabilityProbe.Timeouts(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(300),
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5)));

        long start = System.nanoTime();
        ModelCapabilityProbe.ProbeResult r = probe.probeCapabilities(baseUrl(), "sk-test", "m");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, r.perModality().get("images-gen"),
                "超时只是「这次没确认到」");
        assertFalse(r.endpoints().contains(ModelProfile.EP_IMAGES));
        assertTrue(r.endpoints().contains(ModelProfile.EP_CHAT), "chat 不受 images 超时影响");
        assertTrue(r.endpoints().contains(ModelProfile.EP_EMBEDDING), "embedding 也不受影响");
        assertTrue(r.unsupportedFamilies().isEmpty(), "超时不能用来撤销旧声明");
        assertTrue(elapsedMs < 4000, "单项超时不该把整次探测拖到分钟级，实际 " + elapsedMs + "ms");
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

    // ==================== 回执形状验收 / 实时语音模型 ====================

    @Test
    void 对话回执没有choices时不算确认() {
        emptyChatStub = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertEquals(ModelCapabilityProbe.ProbeState.UNCONFIRMED, r.perModality().get("chat-text"),
                "200 空壳不是一次对话的产出，不能确认 chat");
        assertFalse(r.endpoints().contains(ModelProfile.EP_CHAT));
        assertFalse(r.output().contains(ModelProfile.MOD_TEXT));
        assertEquals("RESPONSE_SHAPE", r.diagnostics().get("chat-text").category(),
                "要落到「返回内容不是该端点的产出」，而不是含糊的未确认");
    }

    @Test
    void 空壳回执不会把媒体输入一起标上() {
        emptyChatStub = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertTrue(r.input().isEmpty(),
                "媒体探针拿到的是同一个空壳：不能凭 200 把 image/audio/video/file 全写上");
    }

    @Test
    void 实时语音模型说明需要WebSocket并归为协议缺口() {
        emptyChatStub = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "qwen-audio-3.1-realtime-flash");

        assertEquals("PROTOCOL", r.diagnostics().get("chat-text").category(),
                "这是协议层缺口：不是请求体格式错，也不是模型不会对话");
        assertTrue(r.note().contains("WebSocket"), "结论里要说清它只走 WebSocket：" + r.note());
        assertTrue(r.note().contains("未确认"), "三行结论清单照旧保留");
        assertFalse(r.endpoints().contains(ModelProfile.EP_CHAT));
    }

    @Test
    void 普通模型不会收到实时语音提示() {
        emptyChatStub = true;

        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "m");

        assertFalse(r.note().contains("WebSocket"), "只有实时模型才该出现这段说明：" + r.note());
    }

    @Test
    void 实时模型被网关代理成正常对话时照常确认() {
        ModelCapabilityProbe.ProbeResult r = new ModelCapabilityProbe()
                .probeCapabilities(baseUrl(), "sk-test", "qwen-audio-3.1-realtime-flash");

        assertTrue(r.endpoints().contains(ModelProfile.EP_CHAT),
                "有 choices 就是真能对话，模型名不该反过来影响判定");
        assertFalse(r.note().contains("WebSocket"), "能对话就不要给「测不出来」的解释");
    }

    /**
     * note 只给**结论清单**，不给理由：支持 / 不支持 / 未确认各一行。
     *
     * <p>早先这里把每个未确认探针的服务商原话（JSON 回执）都拼进 note，
     * 弹窗里一屏几百个字，管理员得自己从里面挑「到底支持什么」。
     * 原话回服务端日志（{@code log.info}），要追具体哪次被拒翻日志即可。
     */
    @Test
    void 说明只列结论清单不带服务商原话() {
        ModelCapabilityProbe probe = new ModelCapabilityProbe();

        String note = probe.probeCapabilities("http://127.0.0.1:" + port + "/v1", "sk-test", "m").note();

        assertTrue(note.contains("支持："), "确认到的要单独成行");
        assertTrue(note.contains("图片输入"), "图片输入在本桩里是通的（2xx），要列进支持");
        assertTrue(note.contains("不支持："), "端点确实不存在的要单独成行");
        assertTrue(note.contains("音频输入"), "音频输入 404 → 不支持，要列进清单");
        assertTrue(note.contains("未确认："), "没探到的要单独成行");
        assertTrue(note.contains("视频输入"), "视频输入 400 → 未确认，要列进清单");
        assertFalse(note.contains("服务商回"), "服务商原话回服务端日志，不进弹窗");
        assertFalse(note.contains("chat-video-in"), "探针键是内部名，弹窗里给人看的是中文名");
    }
}
