package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolExecutor#execute} 的三类出口：未就绪 / 业务失败 / 传输异常都不抛，
 * 一律回喂文本给模型（与 {@code RemoteToolExecutor} 同口径）；成功则回传结果文本。
 * 成功/失败路径用 {@link StubMcpServer} 驱动真实 HTTP。
 */
class McpToolExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private StorageLocations storage() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "domainplane.settings.path", tempDir.resolve("settings").toString(),
                "domainplane.logging.path", tempDir.resolve("logs").toString(),
                "domainplane.export.path", tempDir.resolve("export").toString())));
        return new StorageLocations(env);
    }

    private static ToolExecutionRequest req(String name, String args) {
        return ToolExecutionRequest.builder().name(name).arguments(args).build();
    }

    /** 就绪执行器：其运行时客户端指向 stub.url()，execute 会真实打到 stub */
    private McpToolExecutor readyExecutor(String url, String remoteTool) {
        McpConnectionStore store = new McpConnectionStore(storage());
        McpConnection conn = new McpConnection("conn", url, McpTransport.STREAMABLE_HTTP,
                Map.of(), 5000, true, McpApprovalPolicy.ALL, Set.of(), "");
        McpConnectionRuntime runtime = McpConnectionRuntime.readyForTest("conn", conn,
                List.of(new McpRpcClient.McpToolInfo(remoteTool, "d", mapper.createObjectNode(), null)));
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient()) {
            @Override
            public McpConnectionRuntime runtime(String name) {
                return runtime;
            }
        };
        return new McpToolExecutor(provider, "conn", remoteTool);
    }

    @Test
    void execute_whenRuntimeMissing_returnsNotReadyMessage() {
        McpConnectionStore store = new McpConnectionStore(storage());
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient()) {
            @Override
            public McpConnectionRuntime runtime(String name) {
                return null;
            }
        };
        String out = new McpToolExecutor(provider, "conn", "GetMe").execute(req("GetMe", "{}"), null);
        assertTrue(out.contains("未就绪"), "运行时缺失应回'未就绪'而非抛");
        assertTrue(out.contains("conn"));
        assertTrue(out.contains("GetMe"));
    }

    @Test
    void execute_whenRuntimeNotReady_returnsNotReadyMessage() {
        McpConnectionStore store = new McpConnectionStore(storage());
        McpConnection conn = new McpConnection("conn", "http://127.0.0.1:1/mcp",
                McpTransport.STREAMABLE_HTTP, Map.of(), 5000, true, McpApprovalPolicy.ALL, Set.of(), "");
        McpConnectionRuntime notReady = new McpConnectionRuntime("conn", conn); // 从未建连
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient()) {
            @Override
            public McpConnectionRuntime runtime(String name) {
                return notReady;
            }
        };
        String out = new McpToolExecutor(provider, "conn", "GetMe").execute(req("GetMe", "{}"), null);
        assertTrue(out.contains("未就绪"));
    }

    @Test
    void execute_success_returnsText() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(
                    node.path("id").asLong(),
                    "{\"content\":[{\"type\":\"text\",\"text\":\"user@qq.com\"}],\"isError\":false}"));
            String out = readyExecutor(stub.url(), "GetMe").execute(req("GetMe", "{}"), null);
            assertEquals("user@qq.com", out);
        }
    }

    @Test
    void execute_businessError_returnsFailureText() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(
                    node.path("id").asLong(),
                    "{\"content\":[{\"type\":\"text\",\"text\":\"邮箱不存在\"}],\"isError\":true}"));
            String out = readyExecutor(stub.url(), "GetMessage").execute(req("GetMessage", "{\"messageId\":\"x\"}"), null);
            assertTrue(out.contains("执行失败"), "业务失败回喂'执行失败'文本");
            assertTrue(out.contains("邮箱不存在"), "应带上 server 给的原因");
        }
    }

    @Test
    void execute_transportError_returnsFailureTextNoThrow() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(500, "boom");
            String out = readyExecutor(stub.url(), "GetMe").execute(req("GetMe", "{}"), null);
            assertTrue(out.contains("调用失败"), "传输异常回喂'调用失败'文本，不向上抛");
            assertTrue(out.contains("500"));
        }
    }

    /** 运行中 401 + 挑战、刷新失败 → 标记 NEEDS_AUTH + 回喂"需要管理员重新授权"，不抛 */
    @Test
    void execute_authChallenge_refreshFails_returnsNeedsAuthMessage() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.withHeaders(401, "{\"error\":\"token expired\"}",
                    Map.of("WWW-Authenticate",
                            "Bearer resource_metadata=\"http://127.0.0.1/x/.well-known/oauth-protected-resource\""));
            McpConnection conn = new McpConnection("conn", stub.url(), McpTransport.STREAMABLE_HTTP,
                    Map.of(), 5000, true, McpApprovalPolicy.ALL, Set.of(), "");
            McpConnectionRuntime runtime = McpConnectionRuntime.readyForTest("conn", conn,
                    List.of(new McpRpcClient.McpToolInfo("GetMe", "d", mapper.createObjectNode(), null)));
            boolean[] refreshed = {false};
            McpConnectionStore store = new McpConnectionStore(storage());
            McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient()) {
                @Override
                public McpConnectionRuntime runtime(String name) {
                    return runtime;
                }

                @Override
                public boolean refreshToken(String name) {
                    refreshed[0] = true;
                    return false;
                }

                @Override
                public void markNeedsAuth(String name, String challenge) {
                    runtime.markNeedsAuth(challenge);
                }
            };
            String out = new McpToolExecutor(provider, "conn", "GetMe").execute(req("GetMe", "{}"), null);
            assertTrue(refreshed[0], "收到认证挑战必须先尝试刷新 token");
            assertTrue(out.contains("重新授权"), "刷新失败应回喂重新授权提示，实际：" + out);
            assertEquals(McpConnectionRuntime.Status.NEEDS_AUTH, runtime.status(),
                    "刷新失败必须留授权门禁（工具下次 reconcile 摘除）");
        }
    }

    /** 运行中 401 + 挑战、刷新成功 → 重试一次并成功 */
    @Test
    void execute_authChallenge_refreshOk_retriesAndSucceeds() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            stub.responder = node -> {
                if (calls.getAndIncrement() == 0) {
                    return StubMcpServer.Resp.withHeaders(401, "{\"error\":\"token expired\"}",
                            Map.of("WWW-Authenticate", "Bearer resource_metadata=\"http://127.0.0.1/x\""));
                }
                return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(
                        node.path("id").asLong(),
                        "{\"content\":[{\"type\":\"text\",\"text\":\"user@qq.com\"}],\"isError\":false}"));
            };
            McpConnection conn = new McpConnection("conn", stub.url(), McpTransport.STREAMABLE_HTTP,
                    Map.of(), 5000, true, McpApprovalPolicy.ALL, Set.of(), "");
            McpConnectionRuntime runtime = McpConnectionRuntime.readyForTest("conn", conn,
                    List.of(new McpRpcClient.McpToolInfo("GetMe", "d", mapper.createObjectNode(), null)));
            McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(
                    new McpConnectionStore(storage()), new McpOAuthClient()) {
                @Override
                public McpConnectionRuntime runtime(String name) {
                    return runtime;
                }

                @Override
                public boolean refreshToken(String name) {
                    return true;
                }
            };
            String out = new McpToolExecutor(provider, "conn", "GetMe").execute(req("GetMe", "{}"), null);
            assertEquals("user@qq.com", out, "刷新成功后重试应拿到结果");
            assertTrue(calls.get() >= 2, "必须真的重试过");
            assertEquals(McpConnectionRuntime.Status.READY, runtime.status(), "刷新成功不该动状态");
        }
    }
}
