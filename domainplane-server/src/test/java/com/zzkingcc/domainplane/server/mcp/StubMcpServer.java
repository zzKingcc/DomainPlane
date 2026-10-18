package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 内存级 MCP stub server —— 用 JDK 内置 {@link HttpServer}（零外部依赖）驱动
 * {@link McpRpcClient} / {@link McpToolExecutor} 走真实 HTTP，而非 mock 掉客户端。
 *
 * <ul>
 *   <li>{@link #responder} 由测试按请求体（解析成 JSON-RPC）编排响应；</li>
 *   <li>{@link #requests} 记录每个收到的请求（方法 / 认证头 / 会话头 / 原始体），供断言；</li>
 *   <li>支持 JSON 响应、SSE（{@code text/event-stream}）响应、空体 202、任意状态码。</li>
 * </ul>
 */
final class StubMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer http;
    final List<Recorded> requests = new CopyOnWriteArrayList<>();
    volatile Function<JsonNode, Resp> responder = node -> Resp.json(200, "{}");

    StubMcpServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", this::handle);
        http.start();
    }

    String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
    }

    int port() {
        return http.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        Recorded rec = new Recorded();
        rec.method = ex.getRequestMethod();
        rec.path = ex.getRequestURI().getPath();
        rec.auth = ex.getRequestHeaders().getFirst("Authorization");
        rec.session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        rec.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(rec);

        JsonNode node = null;
        try {
            node = MAPPER.readTree(rec.body);
        } catch (Exception ignore) {
            // 非 JSON 体也记录下来，responder 自行决定
        }
        Resp resp = responder.apply(node);
        if (resp.sessionId != null) {
            ex.getResponseHeaders().set("Mcp-Session-Id", resp.sessionId);
        }
        resp.headers.forEach(ex.getResponseHeaders()::set);
        byte[] out = resp.body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", resp.contentType);
        if (out.length == 0) {
            ex.sendResponseHeaders(resp.status, -1);
        } else {
            ex.sendResponseHeaders(resp.status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        http.stop(0);
    }

    /** 一条被记录下来的请求 */
    static final class Recorded {
        String method;
        String path;
        String auth;
        String session;
        String body;
    }

    /** stub 响应 */
    static final class Resp {
        final int status;
        final String body;
        final String contentType;
        final String sessionId;
        final Map<String, String> headers;

        private Resp(int status, String body, String contentType, String sessionId,
                     Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
            this.sessionId = sessionId;
            this.headers = headers == null ? Map.of() : headers;
        }

        static Resp json(int status, String body) {
            return new Resp(status, body, "application/json", null, null);
        }

        static Resp jsonSession(int status, String body, String sessionId) {
            return new Resp(status, body, "application/json", sessionId, null);
        }

        static Resp empty(int status) {
            return new Resp(status, "", "application/json", null, null);
        }

        static Resp sse(int status, String dataPayload) {
            return new Resp(status, "event: message\ndata: " + dataPayload + "\n\n",
                    "text/event-stream", null, null);
        }

        /** 带任意响应头（典型：401 的 WWW-Authenticate 认证挑战） */
        static Resp withHeaders(int status, String body, Map<String, String> headers) {
            return new Resp(status, body, "application/json", null, headers);
        }
    }

    // ==================== JSON-RPC 响应拼装（两个测试共用） ====================

    static String rpcResult(long id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }

    static String rpcError(long id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}";
    }
}
