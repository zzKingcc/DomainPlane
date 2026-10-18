package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存级 OAuth 授权服务器 stub —— 用 JDK 内置 {@link HttpServer} 驱动
 * {@link McpOAuthClient} 走真实 HTTP，覆盖 RFC 9728 / 8414 / 7591 与 token 端点：
 *
 * <ul>
 *   <li>{@code GET /.well-known/oauth-protected-resource} —— 保护资源元数据；</li>
 *   <li>{@code GET /.well-known/oauth-authorization-server} —— 授权服务器元数据；</li>
 *   <li>{@code POST /register} —— 动态客户端注册，返回固定 client_id；</li>
 *   <li>{@code POST /token} —— authorization_code（校验 PKCE S256）+ refresh_token
 *       （{@link #rotateRefresh} 控制是否轮换 refresh_token）。</li>
 * </ul>
 *
 * {@link #issueCode} 先登记"授权码 → 期望的 code_challenge"，模拟管理员同意后回调带回 code；
 * token 端点验 {@code code_verifier} 的 S256 是否匹配，不匹配按 {@code invalid_grant} 拒绝。
 */
final class StubOAuthServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer http;
    /** 授权码 → 期望的 code_challenge（PKCE 绑定） */
    private final Map<String, String> challenges = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    /** 刷新时是否轮换 refresh_token（默认不轮换，考察调用方保留旧 token 的逻辑） */
    volatile boolean rotateRefresh = false;
    /** 最后一次 /register 收到的 client_name（断言 DCR 用了可配名称） */
    volatile String lastClientName;
    /** 最后一次 /token 收到的表单（断言 client_secret 是否透传） */
    volatile Map<String, String> lastTokenForm = Map.of();

    StubOAuthServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::handle);
        http.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    String protectedResourceUrl() {
        return issuer() + "/.well-known/oauth-protected-resource";
    }

    String tokenUrl() {
        return issuer() + "/token";
    }

    String registerUrl() {
        return issuer() + "/register";
    }

    /** 登记一个已授权的 code（绑定期望的 code_challenge） */
    void issueCode(String code, String codeChallenge) {
        challenges.put(code, codeChallenge);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        requests.add(method + " " + path);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            if ("GET".equals(method) && "/.well-known/oauth-protected-resource".equals(path)) {
                respondJson(ex, 200, "{\"resource\":\"" + issuer() + "/mcp\","
                        + "\"authorization_servers\":[\"" + issuer() + "\"]}");
                return;
            }
            if ("GET".equals(method) && "/.well-known/oauth-authorization-server".equals(path)) {
                respondJson(ex, 200, "{\"issuer\":\"" + issuer() + "\","
                        + "\"authorization_endpoint\":\"" + issuer() + "/authorize\","
                        + "\"token_endpoint\":\"" + tokenUrl() + "\","
                        + "\"registration_endpoint\":\"" + registerUrl() + "\"}");
                return;
            }
            if ("POST".equals(method) && "/register".equals(path)) {
                try {
                    JsonNode meta = MAPPER.readTree(body);
                    lastClientName = meta.path("client_name").asText(null);
                } catch (Exception ignore) {
                    lastClientName = null;
                }
                respondJson(ex, 201, "{\"client_id\":\"stub-client-123\","
                        + "\"client_name\":\"DomainPlane\"}");
                return;
            }
            if ("POST".equals(method) && "/token".equals(path)) {
                handleToken(ex, body);
                return;
            }
            respondJson(ex, 404, "{\"error\":\"not_found\"}");
        } catch (Exception e) {
            respondJson(ex, 500, "{\"error\":\"stub_failure\"}");
        }
    }

    private void handleToken(HttpExchange ex, String body) throws IOException {
        Map<String, String> form = parseForm(body);
        lastTokenForm = form;
        String grant = form.get("grant_type");
        if ("authorization_code".equals(grant)) {
            String code = form.get("code");
            String expected = challenges.remove(code);
            if (expected == null) {
                respondJson(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"code 无效或已消费\"}");
                return;
            }
            String verifier = form.get("code_verifier");
            String actual = McpOAuthClient.s256Challenge(verifier == null ? "" : verifier);
            if (!expected.equals(actual)) {
                respondJson(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE 校验失败\"}");
                return;
            }
            respondJson(ex, 200, "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\","
                    + "\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\"mail\"}");
            return;
        }
        if ("refresh_token".equals(grant)) {
            if (!"refresh-1".equals(form.get("refresh_token"))) {
                respondJson(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"refresh_token 无效\"}");
                return;
            }
            if (rotateRefresh) {
                respondJson(ex, 200, "{\"access_token\":\"access-2\",\"refresh_token\":\"refresh-2\","
                        + "\"expires_in\":3600,\"token_type\":\"Bearer\"}");
            } else {
                // 常见行为：只返新 access_token，不轮换 refresh_token
                respondJson(ex, 200, "{\"access_token\":\"access-2\","
                        + "\"expires_in\":3600,\"token_type\":\"Bearer\"}");
            }
            return;
        }
        respondJson(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void respondJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
        ex.close();
    }

    @Override
    public void close() {
        http.stop(0);
    }

    /** 已收到的请求（"METHOD path"），供断言发现/注册确实发生过 */
    List<String> requests() {
        return List.copyOf(requests);
    }
}
