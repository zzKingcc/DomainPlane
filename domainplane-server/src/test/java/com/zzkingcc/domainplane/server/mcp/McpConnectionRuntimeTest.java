package com.zzkingcc.domainplane.server.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpConnectionRuntime#connect} 的状态机出口（用 {@link StubMcpServer} 驱动真实 HTTP）：
 * 成功 → READY 带工具目录；401+挑战 → NEEDS_AUTH 并透出挑战值；其余异常 → FAILED。
 * NEEDS_AUTH / FAILED 都不残留 client 与目录（执行器与盖章都按 isReady 门禁）。
 */
class McpConnectionRuntimeTest {

    private McpConnection conn(String url) {
        return new McpConnection("conn", url, McpTransport.STREAMABLE_HTTP,
                Map.of(), 5000, true, McpApprovalPolicy.ALL, Set.of(), "");
    }

    @Test
    void connect_success_becomesReadyWithCatalog() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            McpConnectionRuntime runtime = new McpConnectionRuntime("conn", conn(stub.url()));
            try {
                runtime.connect(() -> Map.of());
                assertEquals(McpConnectionRuntime.Status.READY, runtime.status());
                assertTrue(runtime.isReady());
                assertEquals(2, runtime.catalog().size());
                assertNull(runtime.authChallenge());
                assertNull(runtime.lastError());
            } finally {
                runtime.closeQuietly();
            }
        }
    }

    @Test
    void connect_authChallenge_becomesNeedsAuthWithChallenge() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            String challenge = "Bearer resource_metadata=\"http://127.0.0.1:" + stub.port()
                    + "/.well-known/oauth-protected-resource\"";
            stub.responder = node -> StubMcpServer.Resp.withHeaders(401, "{}",
                    Map.of("WWW-Authenticate", challenge));
            McpConnectionRuntime runtime = new McpConnectionRuntime("conn", conn(stub.url()));
            runtime.connect(() -> Map.of());
            assertEquals(McpConnectionRuntime.Status.NEEDS_AUTH, runtime.status());
            assertFalse(runtime.isReady(), "未授权前一律不就绪");
            assertEquals(challenge, runtime.authChallenge(), "挑战值要留给 oauth/start 发现授权端点");
            assertTrue(runtime.catalog().isEmpty(), "不能残留旧目录");
            assertNull(runtime.client(), "不能残留旧 client");
        }
    }

    @Test
    void connect_httpError_becomesFailed() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(500, "boom");
            McpConnectionRuntime runtime = new McpConnectionRuntime("conn", conn(stub.url()));
            runtime.connect(() -> Map.of());
            assertEquals(McpConnectionRuntime.Status.FAILED, runtime.status());
            assertFalse(runtime.isReady());
            assertTrue(runtime.lastError().contains("500"));
        }
    }

    @Test
    void markNeedsAuth_clearsClientCatalogAndRecordsChallenge() {
        McpConnectionRuntime runtime = new McpConnectionRuntime("conn", conn("http://127.0.0.1:1/mcp"));
        runtime.markNeedsAuth("Bearer realm=\"x\"");
        assertEquals(McpConnectionRuntime.Status.NEEDS_AUTH, runtime.status());
        assertNull(runtime.client());
        assertTrue(runtime.catalog().isEmpty());
        assertTrue(runtime.lastError().contains("Bearer realm=\"x\""));
    }
}
