package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.controller.AdminModelEndpointController;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelEndpointClient 的四族出口 + 协议分派。
 *
 * 用一个本地 JDK HttpServer 当桩，验证：
 *   • 请求体按各端点的真实协议拼对（model / prompt / size / n / top_n / voice …）；
 *   • 响应能正确解析（url / b64_json / 重排分数 / 音频字节 / 识别文本）。
 *
 * 控制器则用真实 ModelProfileRegistry（落临时目录）+ 同一个桩服务器，验证"端点族校验 + 委托客户端"。
 *
 * @author zzkingcc
 */
class ModelEndpointClientTest {

    private HttpServer server;
    private int port;
    private final Map<String, String> captured = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/generations", ex -> respond(ex, "application/json",
                "{\"created\":1,\"data\":[{\"url\":\"http://x/y.png\"},{\"b64_json\":\"Qk9CSA==\"}]}"));
        server.createContext("/rerank", ex -> respond(ex, "application/json",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9,\"document\":\"d0\"},"
                        + "{\"index\":1,\"relevance_score\":0.3}]}"));
        server.createContext("/audio/speech", ex -> respond(ex, "audio/mpeg", new byte[]{1, 2, 3}));
        server.createContext("/audio/transcriptions", ex -> respond(ex, "application/json",
                "{\"text\":\"hello world\",\"language\":\"zh\"}"));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== 客户端：四族出口 ====================

    @Test
    void 生图请求体含model与prompt且解析出url与b64() {
        ModelEndpointClient client = new ModelEndpointClient();
        ModelProfile profile = openAi("img", List.of(ModelProfile.EP_IMAGES));

        ModelEndpointClient.ImageResult result = client.generateImage(profile,
                new ModelEndpointClient.ImageRequest("a cat", "1024x1024", 1, null));

        JsonNode body = readCaptured("/images/generations");
        assertEquals("test-model", body.get("model").asText());
        assertEquals("a cat", body.get("prompt").asText());
        assertEquals("1024x1024", body.get("size").asText());
        assertEquals(1, body.get("n").asInt());
        assertEquals(2, result.items().size());
        assertEquals("http://x/y.png", result.items().get(0).url());
        assertEquals("Qk9CSA==", result.items().get(1).b64Json());
    }

    @Test
    void 重排请求体含query与documents且解析出分数() {
        ModelEndpointClient client = new ModelEndpointClient();
        ModelProfile profile = openAi("rk", List.of(ModelProfile.EP_RERANK));

        ModelEndpointClient.RerankResult result = client.rerank(profile,
                new ModelEndpointClient.RerankRequest("q", List.of("d1", "d2"), 2, null));

        JsonNode body = readCaptured("/rerank");
        assertEquals("test-model", body.get("model").asText());
        assertEquals("q", body.get("query").asText());
        assertEquals(2, body.get("documents").size());
        assertEquals(2, body.get("top_n").asInt());
        assertEquals(2, result.results().size());
        assertEquals(0, result.results().get(0).index());
        assertEquals(0.9, result.results().get(0).score(), 1e-9);
        assertEquals("d0", result.results().get(0).document());
    }

    @Test
    void 语音合成请求体含input且返回音频字节() {
        ModelEndpointClient client = new ModelEndpointClient();
        ModelProfile profile = openAi("tts", List.of(ModelProfile.EP_TTS));

        ModelEndpointClient.SpeechResult result = client.textToSpeech(profile,
                new ModelEndpointClient.SpeechRequest("hi", "alloy", null, null));

        JsonNode body = readCaptured("/audio/speech");
        assertEquals("test-model", body.get("model").asText());
        assertEquals("hi", body.get("input").asText());
        assertEquals("alloy", body.get("voice").asText());
        assertNotNull(result.audio());
        assertEquals(3, result.audio().length);
    }

    @Test
    void 语音识别发送multipart并解析出文本() {
        ModelEndpointClient client = new ModelEndpointClient();
        ModelProfile profile = openAi("asr", List.of(ModelProfile.EP_ASR));

        ModelEndpointClient.TranscriptionResult result = client.transcribe(profile,
                new ModelEndpointClient.TranscriptionRequest("audio-bytes".getBytes(StandardCharsets.UTF_8),
                        "audio.wav", null, null, null));

        String body = captured.get("/audio/transcriptions");
        assertTrue(body.contains("file"), "multipart 必须带 file 字段，实际：" + body);
        assertTrue(body.contains("test-model"), "必须带 model 字段，实际：" + body);
        assertTrue(body.contains("audio.wav"), "必须带文件名，实际：" + body);
        assertEquals("hello world", result.text());
        assertEquals("zh", result.language());
    }

    // ==================== 客户端：入参校验 ====================

    @Test
    void 生图缺prompt抛参数错误() {
        ModelEndpointClient client = new ModelEndpointClient();
        ModelProfile profile = openAi("img", List.of(ModelProfile.EP_IMAGES));

        BaseException e = assertThrows(BaseException.class,
                () -> client.generateImage(profile,
                        new ModelEndpointClient.ImageRequest("   ", null, null, null)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
    }

    @Test
    void 服务商返回4xx时抛出并带错误信息() {
        HttpServer errServer = null;
        try {
            errServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int errPort = errServer.getAddress().getPort();
            errServer.createContext("/images/generations", ex -> respond(ex, "application/json",
                    "{\"error\":{\"message\":\"invalid prompt\"}}".getBytes(StandardCharsets.UTF_8), 400));
            errServer.start();

            ModelEndpointClient client = new ModelEndpointClient();
            ModelProfile profile = new ModelProfile("img", List.of(ModelProfile.EP_IMAGES), null, null,
                    "http://localhost:" + errPort, "sk-test", "m",
                    null, null, null, null, null, null, null);

            ModelEndpointException e = assertThrows(ModelEndpointException.class,
                    () -> client.generateImage(profile,
                            new ModelEndpointClient.ImageRequest("x", null, null, null)));
            assertEquals(ErrorCode.LLM_UNAVAILABLE, e.getErrorCode());
            assertTrue(e.getMessage().contains("invalid prompt"), "要透出服务商返回的错误，实际：" + e.getMessage());
        } catch (IOException io) {
            throw new RuntimeException(io);
        } finally {
            if (errServer != null) {
                errServer.stop(0);
            }
        }
    }

    // ==================== 控制器：端点族校验 + 委托 ====================

    @Test
    void 控制器成功路径走通四族() {
        AdminModelEndpointController controller = controllerWith(
                openAi("all", List.of(ModelProfile.EP_IMAGES, ModelProfile.EP_RERANK,
                        ModelProfile.EP_TTS, ModelProfile.EP_ASR)));

        @SuppressWarnings("unchecked")
        Map<String, Object> img = controller.generateImage("all",
                body(new AdminModelEndpointController.ImageBody(), b -> b.setPrompt("a cat")));
        assertEquals(0, img.get("code"));
        assertTrue(img.containsKey("images"));

        Map<String, Object> rk = controller.rerank("all",
                rerankBody("q", List.of("d1", "d2")));
        assertEquals(0, rk.get("code"));

        Map<String, Object> sp = controller.textToSpeech("all",
                speechBody("hi"));
        assertEquals(0, sp.get("code"));
        assertNotNull(sp.get("audioBase64"));

        Map<String, Object> tr = controller.transcribe("all",
                transcribeBody("YWJj", "audio.wav"));
        assertEquals(0, tr.get("code"));
        assertEquals("hello world", tr.get("text"));
    }

    @Test
    void 控制器在档案未声明端点族时拒绝() {
        // 只声明了 chat 的档案，拿去发生图请求必须被拦在客户端之前
        AdminModelEndpointController controller = controllerWith(
                openAi("chatonly", List.of(ModelProfile.EP_CHAT)));

        ModelEndpointException e = assertThrows(ModelEndpointException.class,
                () -> controller.generateImage("chatonly",
                        body(new AdminModelEndpointController.ImageBody(), b -> b.setPrompt("x"))));
        assertEquals(ErrorCode.MODEL_ENDPOINT_UNSUPPORTED, e.getErrorCode());
        assertTrue(e.getMessage().contains("images"), "要说是缺哪个端点族，实际：" + e.getMessage());
    }

    @Test
    void 控制器在档案不存在时拒绝() {
        AdminModelEndpointController controller = controllerWith(
                openAi("real", List.of(ModelProfile.EP_IMAGES)));

        ModelEndpointException e = assertThrows(ModelEndpointException.class,
                () -> controller.generateImage("ghost",
                        body(new AdminModelEndpointController.ImageBody(), b -> b.setPrompt("x"))));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
    }

    // ==================== 辅助 ====================

    private ModelProfile openAi(String alias, List<String> endpoints) {
        return new ModelProfile(alias, endpoints, null, null,
                "http://localhost:" + port, "sk-test", "test-model",
                null, null, null, null, null, null, null);
    }

    private AdminModelEndpointController controllerWith(ModelProfile profile) {
        StandardEnvironment env = new StandardEnvironment();
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ModelProfileStore store = new ModelProfileStore(new StorageLocations(env));
        ModelProfileRegistry registry = new ModelProfileRegistry(store);
        String failure = registry.save(profile);
        if (failure != null) {
            throw new IllegalStateException("档案保存失败：" + failure);
        }
        return new AdminModelEndpointController(registry, new ModelEndpointClient());
    }

    private JsonNode readCaptured(String path) {
        String raw = captured.get(path);
        assertNotNull(raw, "桩没收到 " + path + " 的请求");
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange ex, String contentType, String body) {
        respond(ex, contentType, body.getBytes(StandardCharsets.UTF_8), 200);
    }

    private void respond(com.sun.net.httpserver.HttpExchange ex, String contentType, byte[] body) {
        respond(ex, contentType, body, 200);
    }

    private void respond(com.sun.net.httpserver.HttpExchange ex, String contentType, byte[] body, int status) {
        try {
            // 记下原始请求体，供断言
            try (InputStream in = ex.getRequestBody()) {
                captured.put(ex.getRequestURI().getPath(),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static AdminModelEndpointController.ImageBody body(
            AdminModelEndpointController.ImageBody b, java.util.function.Consumer<AdminModelEndpointController.ImageBody> cfg) {
        cfg.accept(b);
        return b;
    }

    private static AdminModelEndpointController.RerankBody rerankBody(String query, List<String> docs) {
        AdminModelEndpointController.RerankBody b = new AdminModelEndpointController.RerankBody();
        b.setQuery(query);
        b.setDocuments(docs);
        return b;
    }

    private static AdminModelEndpointController.SpeechBody speechBody(String input) {
        AdminModelEndpointController.SpeechBody b = new AdminModelEndpointController.SpeechBody();
        b.setInput(input);
        return b;
    }

    private static AdminModelEndpointController.TranscribeBody transcribeBody(String audioBase64, String filename) {
        AdminModelEndpointController.TranscribeBody b = new AdminModelEndpointController.TranscribeBody();
        b.setAudioBase64(audioBase64);
        b.setFilename(filename);
        return b;
    }
}
