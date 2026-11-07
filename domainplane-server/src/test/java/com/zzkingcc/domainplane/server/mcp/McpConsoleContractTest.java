package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.server.controller.AdminMcpController;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 管控台的数据契约（S2）：list / tools 两个 GET 的新增字段。
 *
 * <p>三条底线：① 协商版本与能力集真的来自 initialize 响应，不是硬编码常量；
 * ② headers 明文（含 Bearer 凭据）与授权挑战里的 URL query 绝不进响应；
 * ③ 工具目录的审批结论取本地策略，不信外部 server 的 readOnlyHint。</p>
 *
 * 不引 Mockito：真实 store + 真实 StubMcpServer(HTTP) + 覆写 runtime() 的 provider，
 * 与 {@link McpToolBridgePublishTest} 同一套测试手法。
 */
class McpConsoleContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 可控 provider：只覆写 runtime()，让 controller 拿到我们造好的运行时 */
    static class FakeProvider extends McpConnectionRuntimeProvider {
        private McpConnectionRuntime current;

        FakeProvider(McpConnectionStore store) {
            super(store, new McpOAuthClient());
        }

        void use(McpConnectionRuntime runtime) {
            this.current = runtime;
        }

        @Override
        public McpConnectionRuntime runtime(String name) {
            return current;
        }
    }

    private StorageLocations storage(Path dir) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "domainplane.settings.path", dir.resolve("settings").toString(),
                "domainplane.logging.path", dir.resolve("logs").toString(),
                "domainplane.export.path", dir.resolve("export").toString())));
        return new StorageLocations(env);
    }

    /** headers 里故意放一枚明文凭据；审批策略取 ALL（最严），用于验证本地兜底 */
    private McpConnection conn(String url) {
        return new McpConnection("probe", url, McpTransport.STREAMABLE_HTTP,
                Map.of("Authorization", "Bearer SECRET-VALUE"), 5000, true,
                McpApprovalPolicy.ALL, Set.of(), "契约测试");
    }

    private McpConnectionStore storeWith(McpConnection c) {
        McpConnectionStore store = new McpConnectionStore(storage(tempDir));
        assertEquals(null, store.upsert(c), "测试连接应能落盘");
        return store;
    }

    private AdminMcpController controller(McpConnectionStore store, FakeProvider provider) {
        // list() / tools() 都不碰 bridge、oauthSessions、oauthClient
        return new AdminMcpController(store, provider, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItem(Map<String, Object> body) {
        return ((List<Map<String, Object>>) body.get("connections")).get(0);
    }

    @Test
    void list_exposesNewFields_andNeverLeaksHeaderValues() {
        McpConnection c = conn("http://127.0.0.1:1/mcp");
        McpConnectionStore store = storeWith(c);
        FakeProvider provider = new FakeProvider(store);
        provider.use(McpConnectionRuntime.readyForTest(c.name(), c, List.of()));

        Map<String, Object> body = controller(store, provider).list();
        Map<String, Object> item = firstItem(body);

        assertTrue(item.containsKey("lastAttemptMs"), "上次探测时间必须回显");
        assertTrue(item.containsKey("protocolVersion"));
        assertTrue(item.containsKey("protocolEra"), "协议时代要回显（无状态 / 会话）");
        assertTrue(item.containsKey("capabilities"));
        assertTrue(item.containsKey("authChallenge"));
        assertEquals(List.of("Authorization"), item.get("headerKeys"), "只回显请求头键名");
        assertEquals(Map.of(), item.get("capabilities"), "未协商过时给空能力集而不是 null");

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertFalse(json.contains("SECRET-VALUE"), "headers 里的凭据值绝不能出现在响应里（含落盘路径之外的任何位置）");
    }

    @Test
    void list_masksAuthChallengeQuery() {
        McpConnection c = conn("http://127.0.0.1:1/mcp");
        McpConnectionStore store = storeWith(c);
        FakeProvider provider = new FakeProvider(store);
        McpConnectionRuntime runtime = McpConnectionRuntime.readyForTest(c.name(), c, List.of());
        runtime.markNeedsAuth("Bearer realm=\"mcp\", "
                + "resource_metadata=\"https://idp.example.com/.well-known/oauth?token=SUPER-SECRET\", "
                + "scope=\"mail.read\"");
        provider.use(runtime);

        String challenge = (String) firstItem(controller(store, provider).list()).get("authChallenge");
        assertNotNull(challenge);
        assertFalse(challenge.contains("SUPER-SECRET"), "挑战值里的 URL query 必须摘掉");
        assertTrue(challenge.contains("scope=\"mail.read\""), "scope 等非敏感参数要留给排障");
    }

    @Test
    void list_reportsNegotiatedProtocolAndCapabilities() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                String method = node.path("method").asText("");
                long id = node.path("id").asLong(0);
                if ("initialize".equals(method)) {
                    return StubMcpServer.Resp.jsonSession(200, StubMcpServer.rpcResult(id,
                            "{\"protocolVersion\":\"2025-06-18\","
                                    + "\"capabilities\":{\"tools\":{\"listChanged\":true},\"resources\":{}},"
                                    + "\"serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}}"), "sess-abc");
                }
                if ("tools/list".equals(method)) {
                    return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{\"tools\":[]}"));
                }
                if ("notifications/initialized".equals(method)) {
                    return StubMcpServer.Resp.empty(202);
                }
                return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{}"));
            };
            McpConnection c = conn(stub.url());
            McpConnectionStore store = storeWith(c);
            FakeProvider provider = new FakeProvider(store);
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());
            provider.use(runtime);

            Map<String, Object> item = firstItem(controller(store, provider).list());
            assertEquals("2025-06-18", item.get("protocolVersion"), "协商版本来自 initialize 响应，不是客户端硬编码");
            assertEquals("LEGACY", item.get("protocolEra"), "旧 server 应标成会话式协议");
            assertTrue(((Map<?, ?>) item.get("capabilities")).containsKey("tools"));
            assertTrue(((Number) item.get("lastAttemptMs")).longValue() > 0);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void tools_exposesSchemaAndGovernanceMetadata() throws Exception {
        McpConnection c = conn("http://127.0.0.1:1/mcp");
        McpConnectionStore store = storeWith(c);
        FakeProvider provider = new FakeProvider(store);
        JsonNode schema = mapper.readTree(
                "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"}},\"required\":[\"to\"]}");
        JsonNode annotations = mapper.readTree("{\"readOnlyHint\":true,\"idempotentHint\":true}");
        McpRpcClient.McpToolInfo info =
                new McpRpcClient.McpToolInfo("send_mail", "发送邮件", schema, annotations);
        provider.use(McpConnectionRuntime.readyForTest(c.name(), c, List.of(info)));

        Map<String, Object> body = controller(store, provider).tools("probe");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertEquals(1, tools.size());
        Map<String, Object> t = tools.get(0);

        assertEquals("send_mail", t.get("originalName"));
        assertEquals("object", ((JsonNode) t.get("inputSchema")).path("type").asText());
        assertEquals(Boolean.TRUE, t.get("idempotent"));
        assertEquals(Boolean.TRUE, t.get("approvalRequired"),
                "审批结论取本地 ALL 策略：即便 server 自报 readOnlyHint 也不免审批");
        assertEquals(List.of(), t.get("boundDomains"));
        assertEquals("mcp://probe@" + c.url(), t.get("source"));
    }
}
