package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.exception.BaseException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试跑端点（S3）：写操作确认门禁、限流、事件记录、撤销授权。
 *
 * <p>这一层的失败模式都带真实后果，所以断言盯三件事：</p>
 * <ol>
 *   <li><b>未确认不得到上游</b>：写工具不带 {@code confirm} 时，stub 一次 {@code tools/call} 都不该收到
 *       ——"先打过去再说"就等于把审批策略绕开了；</li>
 *   <li><b>限流真的拦得住</b>：第 N+1 次拒绝且不再发请求；</li>
 *   <li><b>撤销后不再带 token</b>：删 token 后重连必须落到 NEEDS_AUTH，且响应里没有任何 token 本体。</li>
 * </ol>
 */
class McpToolCallEndpointTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 只覆写 runtime()：让 controller 拿到我们造好的运行时 */
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

    private McpConnection conn(String url) {
        return new McpConnection("probe", url, McpTransport.STREAMABLE_HTTP,
                Map.of("Authorization", "Bearer SECRET-VALUE"), 5000, true,
                McpApprovalPolicy.ALL, Set.of("send_mail", "list_mail"), "试跑测试");
    }

    private McpConnectionStore storeWith(McpConnection c) {
        McpConnectionStore store = new McpConnectionStore(storage(tempDir));
        assertEquals(null, store.upsert(c), "测试连接应能落盘");
        return store;
    }

    /** tools/call 成功 + 带上原文；写工具标注 WRITE，只读工具标注 readOnlyHint */
    private static StubMcpServer.Resp echoCall(JsonNode node) {
        String method = node.path("method").asText("");
        long id = node.path("id").asLong(0);
        if ("initialize".equals(method)) {
            return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id,
                    "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}}"));
        }
        if ("tools/list".equals(method)) {
            return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{\"tools\":[]}"));
        }
        if ("notifications/initialized".equals(method)) {
            return StubMcpServer.Resp.empty(202);
        }
        if ("tools/call".equals(method)) {
            return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id,
                    "{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"SENT-OK\"}],"
                            + "\"structuredContent\":{\"accepted\":1}}"));
        }
        return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{}"));
    }

    private static long callCount(StubMcpServer stub) {
        return stub.requests.stream().filter(r -> r.body != null && r.body.contains("\"tools/call\"")).count();
    }

    private McpRpcClient.McpToolInfo tool(String name, boolean readOnly) {
        try {
            JsonNode annotations = readOnly
                    ? mapper.readTree("{\"readOnlyHint\":true}") : mapper.readTree("{\"destructiveHint\":true}");
            return new McpRpcClient.McpToolInfo(name, name + " 说明",
                    mapper.readTree("{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\"}}}"),
                    annotations);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private AdminMcpController controller(McpConnectionStore store, McpConnectionRuntimeProvider provider) {
        return new AdminMcpController(store, provider, null, null, null);
    }

    private AdminMcpController.CallBody body(String tool, boolean confirm, String json) {
        AdminMcpController.CallBody b = new AdminMcpController.CallBody();
        b.setTool(tool);
        b.setConfirm(confirm);
        try {
            b.setArguments(mapper.readTree(json));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return b;
    }

    @Test
    void writeToolWithoutConfirm_isRejectedBeforeReachingUpstream() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpToolCallEndpointTest::echoCall;
            McpConnection c = conn(stub.url());
            McpConnectionStore store = storeWith(c);
            FakeProvider provider = new FakeProvider(store);
            provider.use(McpConnectionRuntime.readyForTest(c.name(), c,
                    List.of(tool("send_mail", false))));

            BaseException e = assertThrows(BaseException.class, () -> controller(store, provider)
                    .callTool("probe", body("send_mail", false, "{\"body\":\"hi\"}")));
            assertTrue(e.getMessage().contains("确认"), "未确认的写操作必须被拒并说清原因：" + e.getMessage());
            assertEquals(0, callCount(stub), "被拦下的调用绝不能先打到 MCP server 再报错");
        }
    }

    @Test
    void confirmedWriteTool_returnsTextAndRaw_andRecordsCallEvent() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpToolCallEndpointTest::echoCall;
            McpConnection c = conn(stub.url());
            McpConnectionStore store = storeWith(c);
            FakeProvider provider = new FakeProvider(store);
            provider.use(McpConnectionRuntime.readyForTest(c.name(), c,
                    List.of(tool("send_mail", false))));
            AdminMcpController controller = controller(store, provider);

            Map<String, Object> out = controller.callTool("probe",
                    body("send_mail", true, "{\"body\":\"hi\"}"));

            assertEquals(Boolean.TRUE, out.get("success"));
            assertEquals("SENT-OK", out.get("text"));
            assertEquals("DESTRUCTIVE", out.get("effect"));
            assertEquals(Boolean.TRUE, out.get("whitelisted"), "白名单里的工具应标注已开放");
            String raw = String.valueOf(out.get("raw"));
            assertTrue(raw.contains("structuredContent"), "响应原文要带回来（排障看 content 之外的字段）");
            assertEquals(1, callCount(stub));

            Map<String, Object> events = controller.events("probe", 10);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) events.get("events");
            assertEquals(1, list.size());
            assertEquals(McpEventLog.CALL, list.get(0).get("type"));
            assertEquals(Boolean.TRUE, list.get(0).get("ok"));
            assertTrue(String.valueOf(list.get(0).get("summary")).contains("send_mail"));
        }
    }

    @Test
    void readOnlyTool_needsNoConfirm_andMaskedByAllowlistStillRuns() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpToolCallEndpointTest::echoCall;
            McpConnection c = conn(stub.url());
            McpConnectionStore store = storeWith(c);
            FakeProvider provider = new FakeProvider(store);
            // search_doc 不在白名单：模型看不见，但试跑要能跑（开放前验证）
            provider.use(McpConnectionRuntime.readyForTest(c.name(), c,
                    List.of(tool("search_doc", true))));
            AdminMcpController controller = controller(store, provider);

            Map<String, Object> out = controller.callTool("probe",
                    body("search_doc", false, "{}"));
            assertEquals(Boolean.TRUE, out.get("success"), "只读工具不需要确认");
            assertEquals(Boolean.FALSE, out.get("confirmRequired"));
            assertEquals(Boolean.FALSE, out.get("whitelisted"), "不在白名单 = 未对模型开放");
        }
    }

    @Test
    void unknownTool_isRejected() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            McpConnection c = conn(stub.url());
            McpConnectionStore store = storeWith(c);
            FakeProvider provider = new FakeProvider(store);
            provider.use(McpConnectionRuntime.readyForTest(c.name(), c,
                    List.of(tool("send_mail", true))));

            BaseException e = assertThrows(BaseException.class, () -> controller(store, provider)
                    .callTool("probe", body("no_such_tool", true, "{}")));
            assertTrue(e.getMessage().contains("没有工具"), e.getMessage());
            assertEquals(0, callCount(stub));
        }
    }

    @Test
    void rateLimit_blocksAfterQuota() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpToolCallEndpointTest::echoCall;
            McpConnection c = conn(stub.url());
            McpConnectionStore store = storeWith(c);
            FakeProvider provider = new FakeProvider(store);
            provider.use(McpConnectionRuntime.readyForTest(c.name(), c,
                    List.of(tool("search_doc", true))));
            AdminMcpController controller = controller(store, provider);

            for (int i = 0; i < 10; i++) {
                assertEquals(Boolean.TRUE, controller.callTool("probe",
                        body("search_doc", false, "{}")).get("success"), "第 " + (i + 1) + " 次应在额度内");
            }
            BaseException e = assertThrows(BaseException.class, () -> controller
                    .callTool("probe", body("search_doc", false, "{}")));
            assertTrue(e.getMessage().contains("频繁"), e.getMessage());
            assertEquals(10, callCount(stub), "被限流的那次不能再打到 MCP server");
        }
    }

    @Test
    void revokeToken_removesCredential_andFallsBackToNeedsAuth() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            // 一律 401 + 挑战头：撤销 token 后重连必然需要重新授权
            stub.responder = node -> StubMcpServer.Resp.withHeaders(401, "",
                    Map.of("WWW-Authenticate", "Bearer realm=\"mcp\""));
            McpConnection c = conn(stub.url());
            McpConnectionStore store = storeWith(c);
            store.saveToken("probe", new McpConnection.OAuthToken("ACCESS-TOKEN-VALUE",
                    "REFRESH-TOKEN-VALUE", System.currentTimeMillis() + 3_600_000,
                    "mail.read", "cid-1", stub.url() + "/token"));
            McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());
            provider.connect("probe");
            AdminMcpController controller = controller(store, provider);

            // 撤销前：list 里有元信息、没有 token 本体
            @SuppressWarnings("unchecked")
            Map<String, Object> listBody = controller.list();
            @SuppressWarnings("unchecked")
            Map<String, Object> item = ((List<Map<String, Object>>) listBody.get("connections")).get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> tokenInfo = (Map<String, Object>) item.get("tokenInfo");
            assertEquals(Boolean.TRUE, tokenInfo.get("present"));
            assertEquals(Boolean.TRUE, tokenInfo.get("hasRefreshToken"));
            assertEquals("mail.read", tokenInfo.get("scope"));
            String json = mapper.writeValueAsString(listBody);
            assertFalse(json.contains("ACCESS-TOKEN-VALUE"), "access_token 本体绝不能进响应");
            assertFalse(json.contains("REFRESH-TOKEN-VALUE"), "refresh_token 本体绝不能进响应");

            Map<String, Object> out = controller.revokeToken("probe");
            assertEquals(Boolean.TRUE, out.get("hadToken"));
            assertEquals(Boolean.TRUE, out.get("needsAuth"), "没有凭据后应落到需授权");
            assertNull(store.tokenOf("probe"), "本地 token 必须真的删掉");

            @SuppressWarnings("unchecked")
            Map<String, Object> revokeEvents = controller.events("probe", 10);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) revokeEvents.get("events");
            assertTrue(list.stream().anyMatch(m -> McpEventLog.REVOKE.equals(m.get("type"))),
                    "撤销授权必须留一条事件");
            assertTrue(list.stream().anyMatch(m -> McpEventLog.CONNECT.equals(m.get("type"))),
                    "重连也应有事件（provider 记的 CONNECT）");
            assertNotNull(list.get(0).get("atMs"));
        }
    }
}
