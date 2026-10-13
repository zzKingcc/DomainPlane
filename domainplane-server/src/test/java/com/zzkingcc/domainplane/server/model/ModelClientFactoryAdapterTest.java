package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 适配参数的请求体注入：{@link ModelClientFactory} 把档案 params 合并进 OpenAI 兼容请求体。
 *
 * 用一个本地 JDK HttpServer 当桩，验证（设计文档 §6 / §10「请求体注入」）：
 *   • 私有参数（std=false）经 {@code customParameters} 以**顶层字段**进入请求体；
 *   • 适配 schema 登记了 default 而用户没填的键在此补缺，没登记 default 的不出现；
 *   • 标准参数（temperature / maxTokens）走 builder setter，留空就不出现；
 *   • 指纹变（调优值变）才重建客户端 —— 否则缓存旧实例继续用旧参数。
 *
 * @author zzkingcc
 */
class ModelClientFactoryAdapterTest {

    /** langchain4j 能解析的最小合法 chat completion 响应 */
    private static final String REPLY = """
            {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private HttpServer server;
    private int port;
    private final Map<String, String> captured = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            try (InputStream in = ex.getRequestBody()) {
                captured.put("body", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            byte[] out = REPLY.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ModelAdapterRegistry productionRegistry() {
        return new ModelAdapterRegistry(new DefaultResourceLoader());
    }

    private ModelProfile chatProfile(String modelName, Map<String, Object> params,
                                     Double temperature, Integer maxTokens) {
        return new ModelProfile("sol", List.of(ModelProfile.EP_CHAT), null, null,
                "http://127.0.0.1:" + port + "/v1", "sk-test", modelName,
                temperature, maxTokens, null, null, null, null, params);
    }

    private JsonNode body() {
        String raw = captured.get("body");
        assertNotNull(raw, "桩没收到请求体");
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 私有参数注入 ====================

    @Test
    void 私有调优参数以顶层字段进入请求体() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        factory.chat(chatProfile("gpt-5.6-sol",
                Map.of("reasoningEffort", "high", "verbosity", "low"), null, null)).chat("hi");

        JsonNode body = body();
        assertEquals("high", body.get("reasoning_effort").asText(),
                "私有参数经 customParameters 合并进请求体（@JsonAnyGetter → 顶层字段）");
        assertEquals("low", body.get("verbosity").asText());
        assertEquals("gpt-5.6-sol", body.get("model").asText());
        assertNull(body.get("temperature"), "没声明温度就不发送，由服务商默认决定");
        assertNull(body.get("max_tokens"), "没声明最大输出就不发送");
        assertNull(body.get("max_completion_tokens"), "没声明最大输出就不发送");
    }

    @Test
    void GPT6Sol的none档经wire翻译后以顶层字段下发() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        factory.chat(chatProfile("gpt-6-sol",
                Map.of("reasoningEffort", "none"), null, null)).chat("hi");

        JsonNode body = body();
        assertEquals("none", body.get("reasoning_effort").asText(),
                "gpt-6-sol 有 none 档；key→wire 翻译后进请求体");
        assertEquals("gpt-6-sol", body.get("model").asText());
        assertNull(body.get("verbosity"), "GPT-6 Sol 未登记 verbosity（官方未确认支持），不得下发");
    }

    @Test
    void DeepSeek的none档经wire翻译后以顶层字段下发() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        factory.chat(chatProfile("deepseek-flash",
                Map.of("reasoningEffort", "none"), null, null)).chat("hi");

        JsonNode body = body();
        assertEquals("none", body.get("reasoning_effort").asText(),
                "DeepSeek 的思考开关走顶层 reasoning_effort：none = 关闭思考（非思考模式）");
        assertEquals("deepseek-flash", body.get("model").asText());
    }

    @Test
    void 未填时登记的default补缺而未登记的保持不发() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        factory.chat(chatProfile("gpt-5.6-sol", Map.of(), null, null)).chat("hi");

        JsonNode body = body();
        assertEquals("medium", body.get("reasoning_effort").asText(),
                "登记了 default 的键：用户没填 → 按适配登记的默认下发");
        assertEquals("medium", body.get("verbosity").asText(),
                "verbosity 官方默认 medium，同样补缺");
        assertNull(body.get("max_tokens"), "没登记 default 的键：用户没填 → 不发送");
    }

    @Test
    void 适配登记的default补缺且用户填了不被覆盖() {
        ModelAdapterRegistry.ParamSpec effort = new ModelAdapterRegistry.ParamSpec(
                "reasoningEffort", "思考强度", "enum", "reasoning_effort", null, false,
                null, null, null, "high",
                List.of(new ModelAdapterRegistry.Option("high", "high"),
                        new ModelAdapterRegistry.Option("low", "low")), null, null);
        ModelAdapterRegistry reg = ModelAdapterRegistry.of(List.of(
                new ModelAdapterRegistry.Adapter("t-default", "test", "测试", null,
                        new ModelAdapterRegistry.MatchSpec(List.of(), List.of("gpt-5.6-sol"), List.of()),
                        Map.of("chat", List.of(effort)))));
        ModelClientFactory factory = new ModelClientFactory(reg);

        factory.chat(chatProfile("gpt-5.6-sol", null, null, null)).chat("hi");
        assertEquals("high", body().get("reasoning_effort").asText(),
                "适配登记了 default 而用户没填：补缺（「没填」对适配就是按登记的默认走）");

        factory.chat(chatProfile("gpt-5.6-sol", Map.of("reasoningEffort", "low"), null, null)).chat("hi");
        assertEquals("low", body().get("reasoning_effort").asText(), "用户显式填的值压过 default");
    }

    @Test
    void 未命中适配时params原样下发() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        /* 保存路径上这种档案会被控制器拒绝（未适配不能调优）；工厂层按原样转发，不私自吞掉 */
        factory.chat(chatProfile("not-a-registered-model", Map.of("top_p", 0.9), null, null)).chat("hi");

        assertEquals(0.9, body().get("top_p").asDouble(), 1e-9);
    }

    // ==================== 值形态（嵌套对象 / 数组）与条件过滤 ====================

    @Test
    void 嵌套对象参数按wireWrap包装后下发() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        factory.chat(chatProfile("deepseek-flash",
                Map.of("responseFormat", "json_object"), null, null)).chat("hi");

        JsonNode rf = body().get("response_format");
        assertNotNull(rf, "response_format 必须下发");
        assertTrue(rf.isObject(), "线上形状是嵌套对象，不是裸字符串");
        assertEquals("json_object", rf.get("type").asText());
    }

    @Test
    void 数组参数原样下发_停止序列() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        factory.chat(chatProfile("deepseek-flash",
                Map.of("stop", List.of("###", "STOP")), null, null)).chat("hi");

        JsonNode stop = body().get("stop");
        assertNotNull(stop, "stop 必须下发");
        assertTrue(stop.isArray(), "stop 线上是字符串数组");
        assertEquals(2, stop.size());
        assertEquals("###", stop.get(0).asText());
    }

    @Test
    void 条件不成立的私有参数不发射() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());

        factory.chat(chatProfile("deepseek-flash",
                Map.of("reasoningEffort", "high", "topP", 0.98), null, null)).chat("hi");
        assertEquals(0.98, body().get("top_p").asDouble(), 1e-9, "思考模式下 top_p 可调");

        factory.chat(chatProfile("deepseek-flash",
                Map.of("reasoningEffort", "none", "logprobs", true, "topLogprobs", 5), null, null)).chat("hi");
        JsonNode body = body();
        assertNull(body.get("top_p"), "非思考模式下 top_p 固定 1.0，不发射");
        assertEquals(5, body.get("top_logprobs").asInt(), "logprobs 开着时 top_logprobs 照发");
    }

    @Test
    void 绕开控制器造出的越界条件值也不发射() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        /* 正常路径上这种档案在保存时就被 validateParams 拒了；这里锁的是兜底：
           绕过控制器写入的档案（改配置文件、旧版本残留）也不该把无效字段发给服务商 */
        factory.chat(chatProfile("deepseek-flash",
                Map.of("reasoningEffort", "none", "topP", 0.98), null, null)).chat("hi");

        assertNull(body().get("top_p"), "非思考模式下的 top_p 不发射");
    }

    @Test
    void 条件不成立的标准参数不发射_条件成立才发射() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());

        factory.chat(chatProfile("deepseek-flash", Map.of("reasoningEffort", "high"), 0.3, 2048)).chat("hi");
        JsonNode body = body();
        assertNull(body.get("temperature"), "思考模式下温度不生效，不下发");
        Integer sentMax = body.has("max_tokens") ? body.get("max_tokens").asInt()
                : body.has("max_completion_tokens") ? body.get("max_completion_tokens").asInt() : null;
        assertEquals(2048, sentMax, "最大输出没有条件，照发");

        factory.chat(chatProfile("deepseek-flash", Map.of("reasoningEffort", "none"), 0.3, null)).chat("hi");
        assertEquals(0.3, body().get("temperature").asDouble(), 1e-9, "非思考模式下温度才下发");
    }

    @Test
    void 未登记条件的标准参数不受条件过滤影响() {
        /* 造一个「温度无条件」的适配：条件过滤只可能收窄登记了条件的参数，
           没登记条件的标准参数分毫不动 */
        ModelAdapterRegistry.ParamSpec temp = new ModelAdapterRegistry.ParamSpec(
                "temperature", "温度", "number", "temperature", null, true,
                0.0, 2.0, 0.1, null, List.of(), null, null);
        ModelAdapterRegistry reg = ModelAdapterRegistry.of(List.of(
                new ModelAdapterRegistry.Adapter("t-nocond", "test", "测试", null,
                        new ModelAdapterRegistry.MatchSpec(List.of(), List.of("gpt-5.6-sol"), List.of()),
                        Map.of("chat", List.of(temp)))));
        ModelClientFactory factory = new ModelClientFactory(reg);

        factory.chat(chatProfile("gpt-5.6-sol", Map.of("reasoningEffort", "high"), 0.7, null)).chat("hi");
        assertEquals(0.7, body().get("temperature").asDouble(), 1e-9,
                "没给温度登记条件 → 填了就发的既有行为分毫不动");
    }

    // ==================== 标准参数与缓存 ====================

    @Test
    void 标准参数走老字段且留空不出现() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());
        /* DeepSeek 的温度只在非思考模式生效，先切到 none —— 否则拿不到「标准参数照发」的样本 */
        factory.chat(chatProfile("deepseek-flash",
                Map.of("reasoningEffort", "none"), 0.7, 2048)).chat("hi");

        JsonNode body = body();
        assertEquals(0.7, body.get("temperature").asDouble(), 1e-9);
        Integer sentMax = body.has("max_tokens") ? body.get("max_tokens").asInt()
                : body.has("max_completion_tokens") ? body.get("max_completion_tokens").asInt() : null;
        assertEquals(2048, sentMax, "声明的最大输出 token 必须下发（max_tokens / max_completion_tokens 其一）");

        /* 留空 → 两个标准字段都不出现。温度登记的 default 只用于界面空态提示，不注入 */
        factory.chat(chatProfile("deepseek-flash", Map.of("reasoningEffort", "none"), null, null)).chat("hi");
        JsonNode empty = body();
        assertNull(empty.get("temperature"), "温度留空不发送");
        assertNull(empty.get("max_tokens"), "最大输出留空不发送");
        assertNull(empty.get("max_completion_tokens"), "最大输出留空不发送");
    }

    @Test
    void 调优参数变了才重建客户端() {
        ModelClientFactory factory = new ModelClientFactory(productionRegistry());

        ChatModel a = factory.chat(chatProfile("gpt-5.6-sol", Map.of("verbosity", "low"), null, null));
        ChatModel b = factory.chat(chatProfile("gpt-5.6-sol", Map.of("verbosity", "low"), null, null));
        assertSame(a, b, "参数没变应复用缓存实例");

        ChatModel c = factory.chat(chatProfile("gpt-5.6-sol", Map.of("verbosity", "high"), null, null));
        assertNotSame(a, c, "调优值变 → 指纹变 → 必须换客户端，否则保存成功行为不变");
    }
}
