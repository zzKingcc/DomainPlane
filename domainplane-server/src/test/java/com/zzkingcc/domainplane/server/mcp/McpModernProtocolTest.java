package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无状态协议（2026-07-28）：时代探测、逐请求 {@code _meta} 与头、{@code resultType}。
 *
 * <p>三条底线：① 判定现代 server 之后绝不能再去发 {@code initialize}；
 * ② 探测失败要能退回会话式协议（存量 server 全是那一代）；
 * ③ 服务端只支持本平台没实现的版本时必须明确报错，而不是硬猜一个版本继续发。</p>
 *
 * 全部走真实 HTTP（{@link StubMcpServer}），不 mock 传输层。
 */
class McpModernProtocolTest {

    private static final String MODERN = McpRpcClient.MODERN_PROTOCOL_VERSION;

    private McpConnection conn(String url) {
        return new McpConnection("modern", url, McpTransport.STREAMABLE_HTTP, Map.of(), 5000, true,
                McpApprovalPolicy.ALL, Set.of(), "协议测试");
    }

    private static String discoverBody(long id) {
        return StubMcpServer.rpcResult(id, "{\"resultType\":\"complete\","
                + "\"supportedVersions\":[\"" + MODERN + "\"],"
                + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                + "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}},"
                + "\"ttlMs\":3600000,\"cacheScope\":\"public\"}");
    }

    private static String toolsBody(long id) {
        return StubMcpServer.rpcResult(id, "{\"resultType\":\"complete\",\"tools\":["
                + "{\"name\":\"Echo\",\"description\":\"回显\","
                + "\"inputSchema\":{\"type\":\"object\",\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}}"
                + "],\"ttlMs\":60000,\"cacheScope\":\"private\"}");
    }

    /** 400 + UnsupportedProtocolVersionError，带 data.supported（规范里的样子） */
    private static StubMcpServer.Resp unsupported(long id, String supportedJson) {
        return StubMcpServer.Resp.json(400, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{"
                + "\"code\":-32022,\"message\":\"Unsupported protocol version\","
                + "\"data\":{\"supported\":" + supportedJson + ",\"requested\":\"" + MODERN + "\"}}}");
    }

    private StubMcpServer.Recorded only(StubMcpServer stub, String rpcMethod) {
        return stub.requests.stream().filter(r -> rpcMethod.equals(r.rpcMethod)).findFirst().orElseThrow();
    }

    /** 一条请求体里是否带了某方法名 */
    private static boolean sent(StubMcpServer stub, String method) {
        return stub.requests.stream().anyMatch(r -> r.body != null && r.body.contains("\"" + method + "\""));
    }

    @Test
    void modernServer_negotiatesViaDiscover_andNeverHandshakes() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                String method = node.path("method").asText("");
                long id = node.path("id").asLong();
                if ("server/discover".equals(method)) {
                    return StubMcpServer.Resp.json(200, discoverBody(id));
                }
                if ("tools/list".equals(method)) {
                    return StubMcpServer.Resp.json(200, toolsBody(id));
                }
                return StubMcpServer.Resp.json(404, StubMcpServer.rpcError(id, -32601, "Method not found"));
            };
            McpConnection c = conn(stub.url());
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());

            assertEquals(McpConnectionRuntime.Status.READY, runtime.status(), runtime.lastError());
            assertEquals(MODERN, runtime.protocolVersion(), "版本来自 server/discover 的 supportedVersions");
            assertEquals(McpRpcClient.Era.MODERN, runtime.protocolEra());
            assertEquals(List.of("Echo"), runtime.catalog().stream().map(McpRpcClient.McpToolInfo::name).toList());
            assertTrue(((Map<?, ?>) runtime.capabilities()).containsKey("tools"));

            assertFalse(sent(stub, "initialize"), "无状态协议没有 initialize 握手");
            assertFalse(sent(stub, "notifications/initialized"), "无状态协议没有初始化通知");

            StubMcpServer.Recorded discover = only(stub, "server/discover");
            assertEquals(MODERN, discover.header("mcp-protocol-version"), "头部版本必须与 _meta 一致");
            assertEquals("server/discover", discover.header("mcp-method"));
            assertTrue(discover.body.contains("\"io.modelcontextprotocol/protocolVersion\":\"" + MODERN + "\""));
            assertTrue(discover.body.contains("\"io.modelcontextprotocol/clientCapabilities\":{}"),
                    "能力集如实上报为空（本平台不支持采样/根目录/交互提问）");

            StubMcpServer.Recorded list = only(stub, "tools/list");
            assertEquals("tools/list", list.header("mcp-method"));
            assertNull(list.session, "无状态协议不再有 Mcp-Session-Id");
            assertTrue(list.body.contains("\"_meta\""), "每个请求都要带 _meta");
        }
    }

    @Test
    void legacyServer_answeringMethodNotFound_fallsBackToInitialize() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                String method = node.path("method").asText("");
                long id = node.path("id").asLong();
                if ("server/discover".equals(method)) {
                    return StubMcpServer.Resp.json(404, StubMcpServer.rpcError(id, -32601, "Method not found"));
                }
                if ("initialize".equals(method)) {
                    return StubMcpServer.Resp.jsonSession(200, StubMcpServer.rpcResult(id,
                            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}}"), "sess-1");
                }
                if ("tools/list".equals(method)) {
                    return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{\"tools\":[]}"));
                }
                return StubMcpServer.Resp.empty(202);
            };
            McpConnection c = conn(stub.url());
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());

            assertEquals(McpConnectionRuntime.Status.READY, runtime.status(), runtime.lastError());
            assertEquals("2025-06-18", runtime.protocolVersion());
            assertEquals(McpRpcClient.Era.LEGACY, runtime.protocolEra());
            assertEquals("sess-1", only(stub, "tools/list").session, "退回会话式后必须继续带会话头");
        }
    }

    @Test
    void unsupportedVersion_withOnlyUnimplementedVersions_failsWithActionableMessage() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> unsupported(node.path("id").asLong(), "[\"2027-01-01\"]");
            McpConnection c = conn(stub.url());
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());

            assertEquals(McpConnectionRuntime.Status.FAILED, runtime.status(),
                    "确认是现代 server 但无共同版本：不许退化成 initialize 硬试");
            assertTrue(runtime.lastError().contains("2027-01-01"), runtime.lastError());
            assertFalse(sent(stub, "initialize"), "这条路上 initialize 也必然失败，不该再发一遍");
        }
    }

    @Test
    void unsupportedVersion_withLegacyVersionsAvailable_stillFallsBack() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                String method = node.path("method").asText("");
                long id = node.path("id").asLong();
                if ("server/discover".equals(method)) {
                    return unsupported(id, "[\"2025-06-18\"]");
                }
                if ("initialize".equals(method)) {
                    return StubMcpServer.Resp.jsonSession(200, StubMcpServer.rpcResult(id,
                            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}"), "sess-2");
                }
                if ("tools/list".equals(method)) {
                    return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{\"tools\":[]}"));
                }
                return StubMcpServer.Resp.empty(202);
            };
            McpConnection c = conn(stub.url());
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());

            assertEquals(McpConnectionRuntime.Status.READY, runtime.status(), runtime.lastError());
            assertEquals(McpRpcClient.Era.LEGACY, runtime.protocolEra());
            assertEquals("2025-06-18", runtime.protocolVersion());
        }
    }

    @Test
    void unsupportedVersion_claimingOurVersion_isRetriedOnce() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                String method = node.path("method").asText("");
                long id = node.path("id").asLong();
                if ("server/discover".equals(method)) {
                    // 首包假装不认版本，但 data.supported 里明明有本平台实现的那版
                    if (probes.getAndIncrement() == 0) {
                        return unsupported(id, "[\"" + MODERN + "\"]");
                    }
                    return StubMcpServer.Resp.json(200, discoverBody(id));
                }
                return StubMcpServer.Resp.json(200, toolsBody(id));
            };
            McpConnection c = conn(stub.url());
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());

            assertEquals(McpConnectionRuntime.Status.READY, runtime.status(), runtime.lastError());
            assertEquals(2, probes.get(), "只许重试一次");
            assertEquals(McpRpcClient.Era.MODERN, runtime.protocolEra());
        }
    }

    @Test
    void toolsCall_sendsNameHeader_andReportsInputRequiredHonestly() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                String method = node.path("method").asText("");
                long id = node.path("id").asLong();
                if ("server/discover".equals(method)) {
                    return StubMcpServer.Resp.json(200, discoverBody(id));
                }
                if ("tools/list".equals(method)) {
                    return StubMcpServer.Resp.json(200, toolsBody(id));
                }
                if ("tools/call".equals(method)) {
                    return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id,
                            "{\"resultType\":\"input_required\",\"inputRequests\":[{\"kind\":\"elicitation\"}]}"));
                }
                return StubMcpServer.Resp.json(404, StubMcpServer.rpcError(id, -32601, "Method not found"));
            };
            McpConnection c = conn(stub.url());
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());

            McpRpcClient.McpCallResult r = runtime.client().toolsCall("Echo", "{\"q\":\"hi\"}");
            assertFalse(r.success(), "input_required 不能当成功结果吞掉");
            assertTrue(r.isError());
            assertTrue(r.text().contains("input_required"), r.text());
            assertTrue(r.text().contains("1 项 inputRequests"), r.text());

            StubMcpServer.Recorded call = only(stub, "tools/call");
            assertEquals("Echo", call.header("mcp-name"), "工具名要进 Mcp-Name 头");
            assertTrue(call.body.contains("\"arguments\":{\"q\":\"hi\"}"));
            assertTrue(call.body.contains("\"_meta\""));
            assertNull(call.session);
        }
    }

    @Test
    void jsonSchema2020_12_keywords_arePassedThroughUntouched() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                String method = node.path("method").asText("");
                long id = node.path("id").asLong();
                if ("server/discover".equals(method)) {
                    return StubMcpServer.Resp.json(200, discoverBody(id));
                }
                return StubMcpServer.Resp.json(200, toolsBody(id));
            };
            McpConnection c = conn(stub.url());
            McpConnectionRuntime runtime = new McpConnectionRuntime(c.name(), c);
            runtime.connect(() -> Map.of());

            JsonNode schema = runtime.catalog().get(0).inputSchema();
            assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText(),
                    "2020-12 的 schema 原样透传（本平台不做校验，也不改写）");
        }
    }
}
