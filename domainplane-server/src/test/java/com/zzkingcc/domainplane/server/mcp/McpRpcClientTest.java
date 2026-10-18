package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpRpcClient} 协议层：用内存级 {@link StubMcpServer} 驱动真实 HTTP，
 * 覆盖 initialize / tools/list / tools/call、会话头、认证头、参数编组、SSE 解析、错误与降级。
 */
class McpRpcClientTest {

    private McpRpcClient client(String url) {
        return new McpRpcClient(url, McpTransport.STREAMABLE_HTTP, Map.of(), 5000);
    }

    private McpRpcClient client(String url, Map<String, String> headers) {
        return new McpRpcClient(url, McpTransport.STREAMABLE_HTTP, headers, 5000);
    }

    /** 通用 happy responder：initialize / notifications / tools/list / tools/call 全接管 */
    static StubMcpServer.Resp happy(JsonNode node) {
        String method = node.path("method").asText("");
        long id = node.path("id").asLong(0);
        switch (method) {
            case "initialize":
                return StubMcpServer.Resp.jsonSession(200,
                        StubMcpServer.rpcResult(id,
                                "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                                        + "\"serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}}"),
                        "sess-abc");
            case "notifications/initialized":
                return StubMcpServer.Resp.empty(202);
            case "tools/list":
                return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{\"tools\":["
                        + "{\"name\":\"GetMe\",\"description\":\"账号\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},\"annotations\":{\"readOnlyHint\":true}},"
                        + "{\"name\":\"SendMessage\",\"description\":\"发送\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"}},\"required\":[\"to\"]},"
                        + "\"annotations\":{\"readOnlyHint\":false}}"
                        + "]}"));
            case "tools/call":
                return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],\"isError\":false}"));
            default:
                return StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(id, "{}"));
        }
    }

    private StubMcpServer.Recorded firstWithBody(StubMcpServer stub, String needle) {
        return stub.requests.stream().filter(r -> r.body.contains(needle)).findFirst().orElseThrow();
    }

    @Test
    void initialize_returnsNegotiatedVersion_andSendsInitializedNotification() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            try (McpRpcClient client = client(stub.url())) {
                assertEquals("2025-03-26", client.initialize());
            }
            assertTrue(stub.requests.stream().anyMatch(r -> r.body.contains("\"initialize\"")));
            assertTrue(stub.requests.stream().anyMatch(r -> r.body.contains("notifications/initialized")),
                    "initialize 后必须发 notifications/initialized");
        }
    }

    @Test
    void requests_afterInitialize_carrySessionHeader() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            try (McpRpcClient client = client(stub.url())) {
                client.initialize();
                client.toolsList();
            }
            assertNull(firstWithBody(stub, "\"initialize\"").session, "initialize 首包不带会话头");
            assertEquals("sess-abc", firstWithBody(stub, "tools/list").session,
                    "拿到 Mcp-Session-Id 后，后续请求必须回带");
        }
    }

    @Test
    void requests_carryConfiguredHeaders() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            try (McpRpcClient client = client(stub.url(), Map.of("Authorization", "Bearer test-token-qqmail"))) {
                client.initialize();
            }
            assertTrue(stub.requests.stream().allMatch(r -> "Bearer test-token-qqmail".equals(r.auth)),
                    "连接级 headers（认证）必须随每个请求发出");
        }
    }

    @Test
    void toolsList_parsesNameDescriptionAndAnnotations() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            try (McpRpcClient client = client(stub.url())) {
                List<McpRpcClient.McpToolInfo> tools = client.toolsList();
                assertEquals(2, tools.size());
                McpRpcClient.McpToolInfo getMe = tools.get(0);
                assertEquals("GetMe", getMe.name());
                assertEquals("账号", getMe.description());
                assertTrue(getMe.annotations().path("readOnlyHint").asBoolean(false));
                assertEquals("SendMessage", tools.get(1).name());
                assertFalse(tools.get(1).annotations().path("readOnlyHint").asBoolean(true));
            }
        }
    }

    @Test
    void toolsCall_success_extractsTextResult() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            try (McpRpcClient client = client(stub.url())) {
                McpRpcClient.McpCallResult r = client.toolsCall("GetMe", "{}");
                assertTrue(r.success());
                assertFalse(r.isError());
                assertEquals("OK", r.text());
            }
        }
    }

    @Test
    void toolsCall_businessError_setsIsErrorAndText() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(
                    node.path("id").asLong(),
                    "{\"content\":[{\"type\":\"text\",\"text\":\"邮件不存在\"}],\"isError\":true}"));
            try (McpRpcClient client = client(stub.url())) {
                McpRpcClient.McpCallResult r = client.toolsCall("GetMessage", "{\"messageId\":\"x\"}");
                assertFalse(r.success());
                assertTrue(r.isError(), "业务失败 isError=true，与传输失败是两回事");
                assertEquals("邮件不存在", r.text());
            }
        }
    }

    @Test
    void toolsCall_multipleTextBlocks_joinedByNewline() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(
                    node.path("id").asLong(),
                    "{\"content\":[{\"type\":\"text\",\"text\":\"第一段\"},"
                            + "{\"type\":\"text\",\"text\":\"第二段\"}],\"isError\":false}"));
            try (McpRpcClient client = client(stub.url())) {
                assertEquals("第一段\n第二段", client.toolsCall("x", "{}").text());
            }
        }
    }

    @Test
    void toolsCall_nonTextBlock_renderedAsPlaceholder() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(200, StubMcpServer.rpcResult(
                    node.path("id").asLong(),
                    "{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"..\"}],"
                            + "\"isError\":false}"));
            try (McpRpcClient client = client(stub.url())) {
                assertTrue(client.toolsCall("x", "{}").text().contains("[非文本块: image/png"),
                        "非文本块应渲染占位说明，而非静默丢弃");
            }
        }
    }

    @Test
    void toolsCall_validArgumentsJson_forwardedToServer() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            try (McpRpcClient client = client(stub.url())) {
                client.toolsCall("SendMessage", "{\"to\":\"a@b.com\",\"subject\":\"hi\"}");
            }
            String body = firstWithBody(stub, "tools/call").body;
            assertTrue(body.contains("\"to\":\"a@b.com\""));
            assertTrue(body.contains("\"subject\":\"hi\""));
        }
    }

    @Test
    void toolsCall_invalidArgumentsJson_sentAsEmptyObject() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            try (McpRpcClient client = client(stub.url())) {
                client.toolsCall("SendMessage", "not-a-json");
            }
            assertTrue(firstWithBody(stub, "tools/call").body.contains("\"arguments\":{}"),
                    "非法 JSON 参数应按空对象发送，而不是把脏字符串透传");
        }
    }

    @Test
    void rpcErrorResult_throwsIllegalStateWithMessage() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(200,
                    StubMcpServer.rpcError(node.path("id").asLong(), -32601, "方法未实现"));
            try (McpRpcClient client = client(stub.url())) {
                IllegalStateException ex = assertThrows(IllegalStateException.class, client::toolsList);
                assertTrue(ex.getMessage().contains("方法未实现"));
                assertTrue(ex.getMessage().contains("-32601"));
            }
        }
    }

    @Test
    void httpNon2xx_throwsIllegalStateWithStatus() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> StubMcpServer.Resp.json(500, "server exploded");
            try (McpRpcClient client = client(stub.url())) {
                IllegalStateException ex = assertThrows(IllegalStateException.class, client::toolsList);
                assertTrue(ex.getMessage().contains("500"));
            }
        }
    }

    @Test
    void http401WithChallenge_throwsAuthChallengeException() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            String challenge = "Bearer resource_metadata=\"http://127.0.0.1:" + stub.port()
                    + "/.well-known/oauth-protected-resource\"";
            stub.responder = node -> StubMcpServer.Resp.withHeaders(401,
                    "{\"error\":\"token expired\"}", Map.of("WWW-Authenticate", challenge));
            try (McpRpcClient client = client(stub.url())) {
                McpAuthChallengeException ex = assertThrows(McpAuthChallengeException.class, client::toolsList);
                assertEquals(challenge, ex.challenge(), "挑战值必须原样透出，oauth/start 靠它发现授权端点");
                assertTrue(ex.getMessage().contains("401"));
            }
        }
    }

    @Test
    void http401WithoutChallenge_throwsIllegalState() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            // 不带 WWW-Authenticate 的 401 = 静态 token 配错，与 OAuth 挑战是两条路
            stub.responder = node -> StubMcpServer.Resp.json(401,
                    "{\"error\":\"Bearer token is missing\"}");
            try (McpRpcClient client = client(stub.url())) {
                IllegalStateException ex = assertThrows(IllegalStateException.class, client::toolsList);
                assertTrue(ex.getMessage().contains("401"));
                assertTrue(ex.getMessage().contains("Bearer token is missing"));
            }
        }
    }

    @Test
    void sseResponse_parsedFromDataEvent() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = node -> {
                if ("tools/list".equals(node.path("method").asText())) {
                    return StubMcpServer.Resp.sse(200, StubMcpServer.rpcResult(node.path("id").asLong(),
                            "{\"tools\":[{\"name\":\"SSETool\",\"description\":\"d\","
                                    + "\"inputSchema\":{\"type\":\"object\"}}]}"));
                }
                return happy(node);
            };
            try (McpRpcClient client = client(stub.url())) {
                List<McpRpcClient.McpToolInfo> tools = client.toolsList();
                assertEquals(1, tools.size());
                assertEquals("SSETool", tools.get(0).name());
            }
        }
    }
}
