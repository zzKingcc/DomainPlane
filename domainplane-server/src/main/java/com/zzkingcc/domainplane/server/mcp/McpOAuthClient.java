package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 连接级 OAuth 客户端：按 MCP 规范的 OAuth 2.1 流程拿 token。
 *
 * 只用 JDK HttpClient + Jackson（不引 SDK，契合离线构建）。流程五步：
 * <ol>
 *   <li> discoverResource —— RFC 9728 保护资源元数据（从 401 的 WWW-Authenticate 头拿地址）；</li>
 *   <li> discoverServer —— RFC 8414 授权服务器元数据（authorization / token / registration 端点）；</li>
 *   <li> register —— RFC 7591 动态客户端注册（无 client_id 时）；</li>
 *   <li> authorizeUrl + PKCE(S256) —— 管理员浏览器授权（state 由调用方经 {@link McpOAuthSessionStore} 暂存）；</li>
 *   <li> exchangeCode / refresh —— 授权码换 token / refresh_token 续期。</li>
 * </ol>
 *
 * @author zzkingcc
 */
@Component
public class McpOAuthClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern RESOURCE_METADATA =
            Pattern.compile("resource_metadata=\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** RFC 9728 保护资源元数据 */
    public record ProtectedResourceMetadata(String resource, List<String> authorizationServers) {
    }

    /** RFC 8414 授权服务器元数据（只取用到的字段） */
    public record ServerMetadata(String issuer, String authorizationEndpoint, String tokenEndpoint,
                                 String registrationEndpoint) {

        public boolean usable() {
            return authorizationEndpoint != null && !authorizationEndpoint.isBlank()
                    && tokenEndpoint != null && !tokenEndpoint.isBlank();
        }
    }

    /** token 响应（expiresAtEpochMs 由 expires_in 换算，缺省按 1 小时） */
    public record TokenSet(String accessToken, String refreshToken, long expiresAtEpochMs, String scope) {
    }

    // ==================== 发现 ====================

    public ProtectedResourceMetadata discoverResource(String resourceMetadataUrl) throws Exception {
        JsonNode root = getJson(resourceMetadataUrl);
        List<String> servers = new ArrayList<>();
        JsonNode arr = root.path("authorization_servers");
        if (arr.isArray()) {
            arr.forEach(n -> {
                if (!n.isNull() && !n.asText().isBlank()) {
                    servers.add(n.asText());
                }
            });
        }
        return new ProtectedResourceMetadata(root.path("resource").asText(null), List.copyOf(servers));
    }

    public ServerMetadata discoverServer(String authorizationServer) throws Exception {
        JsonNode root = getJson(wellKnown(authorizationServer, "oauth-authorization-server"));
        return new ServerMetadata(
                root.path("issuer").asText(null),
                root.path("authorization_endpoint").asText(null),
                root.path("token_endpoint").asText(null),
                root.path("registration_endpoint").asText(null));
    }

    // ==================== 注册 / 授权 / 换 token ====================

    /** RFC 7591 动态注册；返回 client_id */
    public String register(String registrationEndpoint, String clientName, String redirectUri) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_name", clientName);
        body.putArray("redirect_uris").add(redirectUri);
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "none");
        JsonNode root = postJson(registrationEndpoint, body);
        String clientId = root.path("client_id").asText(null);
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("动态注册响应缺少 client_id：" + abbreviate(root.toString()));
        }
        return clientId;
    }

    /** 构建授权 URL（授权码 + PKCE S256）；管理员浏览器跳转到它完成登录与同意 */
    public String authorizeUrl(ServerMetadata md, String clientId, String redirectUri,
                               String codeChallenge, String state, String scope) {
        StringBuilder sb = new StringBuilder(md.authorizationEndpoint());
        sb.append(md.authorizationEndpoint().contains("?") ? '&' : '?')
          .append("response_type=code")
          .append("&client_id=").append(enc(clientId))
          .append("&redirect_uri=").append(enc(redirectUri))
          .append("&state=").append(enc(state))
          .append("&code_challenge=").append(enc(codeChallenge))
          .append("&code_challenge_method=S256");
        if (scope != null && !scope.isBlank()) {
            sb.append("&scope=").append(enc(scope));
        }
        return sb.toString();
    }

    /** 授权码 + PKCE(S256) 换 token；clientSecret 非空时按 client_secret_post 一并提交 */
    public TokenSet exchangeCode(String tokenEndpoint, String code, String codeVerifier,
                                 String redirectUri, String clientId, String clientSecret) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("client_id", clientId);
        body.put("code_verifier", codeVerifier);
        if (clientSecret != null && !clientSecret.isBlank()) {
            body.put("client_secret", clientSecret);
        }
        return tokenRequest(tokenEndpoint, body);
    }

    /** refresh_token 续期；服务器不返还新 refresh_token 时由调用方保留旧的 */
    public TokenSet refresh(String tokenEndpoint, String refreshToken, String clientId,
                            String clientSecret) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        if (clientId != null && !clientId.isBlank()) {
            body.put("client_id", clientId);
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            body.put("client_secret", clientSecret);
        }
        return tokenRequest(tokenEndpoint, body);
    }

    // ==================== PKCE ====================

    /** 生成 code_verifier（43-128 位 URL-safe 随机串，取 32 字节） */
    public static String generateVerifier() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return URL_ENCODER.encodeToString(b);
    }

    /** S256 code_challenge = base64url(sha256(verifier)) */
    public static String s256Challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("PKCE S256 计算失败", e);
        }
    }

    /** 从 WWW-Authenticate 挑战值里解析 resource_metadata URL；解析不到返回 null */
    public static String parseResourceMetadataUrl(String challenge) {
        if (challenge == null || challenge.isBlank()) {
            return null;
        }
        Matcher m = RESOURCE_METADATA.matcher(challenge);
        return m.find() ? m.group(1) : null;
    }

    // ==================== 内部 ====================

    /**
     * RFC 8414 well-known 插入：issuer 带路径时插在 host 与 path 之间
     * （{@code https://host/tenant1} → {@code https://host/.well-known/oauth-authorization-server/tenant1}）。
     */
    static String wellKnown(String issuer, String name) {
        URI u = URI.create(issuer);
        String base = u.getScheme() + "://" + u.getAuthority();
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.isEmpty() || "/".equals(path)) {
            return base + "/.well-known/" + name;
        }
        return base + "/.well-known/" + name + path;
    }

    private TokenSet tokenRequest(String tokenEndpoint, ObjectNode body) throws Exception {
        JsonNode root = postForm(tokenEndpoint, body);
        String access = root.path("access_token").asText(null);
        if (access == null || access.isBlank()) {
            String err = root.path("error").asText("");
            String desc = root.path("error_description").asText("");
            throw new IllegalStateException("换取 token 失败：" + err
                    + (desc.isBlank() ? "" : "（" + desc + "）"));
        }
        long expiresIn = root.path("expires_in").asLong(3600);
        return new TokenSet(access, root.path("refresh_token").asText(null),
                System.currentTimeMillis() + expiresIn * 1000, root.path("scope").asText(null));
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("OAuth 元数据端点 " + url + " 返回 HTTP " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }
        return MAPPER.readTree(response.body());
    }

    private JsonNode postJson(String url, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("OAuth 端点 " + url + " 返回 HTTP " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }
        return MAPPER.readTree(response.body());
    }

    /** token 端点是 application/x-www-form-urlencoded（RFC 6749），不是 JSON */
    private JsonNode postForm(String url, ObjectNode body) throws Exception {
        StringBuilder form = new StringBuilder();
        body.fields().forEachRemaining(e -> {
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(enc(e.getKey())).append('=').append(enc(e.getValue().asText()));
        });
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("OAuth token 端点 " + url + " 返回 HTTP " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }
        return MAPPER.readTree(response.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
