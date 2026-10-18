package com.zzkingcc.domainplane.server.mcp;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpOAuthClient} 协议层：用内存级 {@link StubOAuthServer} 驱动真实 HTTP，
 * 覆盖五步流程（发现资源 → 发现服务器 → 动态注册 → 授权码+PKCE 换 token → 刷新），
 * 以及 PKCE 计算、well-known 插入、挑战解析等纯函数。
 */
class McpOAuthClientTest {

    private final McpOAuthClient client = new McpOAuthClient();

    // ==================== 发现 ====================

    @Test
    void discoverResource_parsesResourceAndServers() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            McpOAuthClient.ProtectedResourceMetadata md = client.discoverResource(stub.protectedResourceUrl());
            assertEquals(stub.issuer() + "/mcp", md.resource());
            assertEquals(List.of(stub.issuer()), md.authorizationServers());
            assertTrue(stub.requests().contains("GET /.well-known/oauth-protected-resource"));
        }
    }

    @Test
    void discoverServer_returnsUsableMetadata() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            McpOAuthClient.ServerMetadata md = client.discoverServer(stub.issuer());
            assertTrue(md.usable(), "authorization/token 端点齐备才算可用");
            assertEquals(stub.issuer() + "/authorize", md.authorizationEndpoint());
            assertEquals(stub.tokenUrl(), md.tokenEndpoint());
            assertEquals(stub.registerUrl(), md.registrationEndpoint());
        }
    }

    @Test
    void register_returnsClientId_andSendsConfiguredClientName() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            String clientId = client.register(stub.registerUrl(), "WorkBuddy", "http://127.0.0.1:9527/cb");
            assertEquals("stub-client-123", clientId);
            assertEquals("WorkBuddy", stub.lastClientName, "注册名必须可配（腾讯准入只放行受信任平台名）");
            assertTrue(stub.requests().contains("POST /register"));
        }
    }

    // ==================== 授权码 + PKCE 换 token ====================

    @Test
    void exchangeCode_withCorrectVerifier_returnsTokenSet() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            String verifier = McpOAuthClient.generateVerifier();
            stub.issueCode("code-abc", McpOAuthClient.s256Challenge(verifier));
            McpOAuthClient.TokenSet ts = client.exchangeCode(
                    stub.tokenUrl(), "code-abc", verifier, "http://127.0.0.1:9527/cb", "stub-client-123", null);
            assertEquals("access-1", ts.accessToken());
            assertEquals("refresh-1", ts.refreshToken());
            assertEquals("mail", ts.scope());
            assertTrue(ts.expiresAtEpochMs() > System.currentTimeMillis(), "expires_in 应换算为绝对过期时间");
            assertFalse(stub.lastTokenForm.containsKey("client_secret"), "无 secret 时不该传该字段");
        }
    }

    @Test
    void exchangeCode_withSecret_sendsClientSecretPost() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            String verifier = McpOAuthClient.generateVerifier();
            stub.issueCode("code-abc", McpOAuthClient.s256Challenge(verifier));
            client.exchangeCode(stub.tokenUrl(), "code-abc", verifier,
                    "http://127.0.0.1:9527/cb", "stub-client-123", "s3cret");
            assertEquals("s3cret", stub.lastTokenForm.get("client_secret"),
                    "手工登记的机密客户端要按 client_secret_post 带 secret");
        }
    }

    @Test
    void exchangeCode_withWrongVerifier_rejectedAsInvalidGrant() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            stub.issueCode("code-abc", McpOAuthClient.s256Challenge("verifier-right"));
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> client.exchangeCode(
                    stub.tokenUrl(), "code-abc", "verifier-wrong", "http://127.0.0.1:9527/cb", "cid", null));
            assertTrue(ex.getMessage().contains("invalid_grant"), "PKCE 不匹配必须按 invalid_grant 拒绝");
        }
    }

    @Test
    void exchangeCode_withUnknownCode_rejected() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> client.exchangeCode(
                    stub.tokenUrl(), "no-such-code", "v", "http://127.0.0.1:9527/cb", "cid", null));
            assertTrue(ex.getMessage().contains("invalid_grant"));
        }
    }

    // ==================== 刷新 ====================

    @Test
    void refresh_withoutRotation_returnsNewAccessOnly() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            McpOAuthClient.TokenSet ts = client.refresh(stub.tokenUrl(), "refresh-1", "stub-client-123", null);
            assertEquals("access-2", ts.accessToken());
            assertNull(ts.refreshToken(), "服务器不轮换时无新 refresh_token，由 Provider 保留旧的");
            assertFalse(stub.lastTokenForm.containsKey("client_secret"));
        }
    }

    @Test
    void refresh_withSecret_sendsClientSecretPost() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            client.refresh(stub.tokenUrl(), "refresh-1", "stub-client-123", "s3cret");
            assertEquals("s3cret", stub.lastTokenForm.get("client_secret"), "刷新同样要带 secret");
        }
    }

    @Test
    void refresh_withRotation_returnsNewRefreshToken() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            stub.rotateRefresh = true;
            McpOAuthClient.TokenSet ts = client.refresh(stub.tokenUrl(), "refresh-1", "stub-client-123", null);
            assertEquals("access-2", ts.accessToken());
            assertEquals("refresh-2", ts.refreshToken());
        }
    }

    @Test
    void refresh_withUnknownToken_rejected() throws Exception {
        try (StubOAuthServer stub = new StubOAuthServer()) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> client.refresh(stub.tokenUrl(), "refresh-bad", "cid", null));
            assertTrue(ex.getMessage().contains("invalid_grant"));
        }
    }

    // ==================== 纯函数：PKCE / 挑战解析 / well-known / 授权 URL ====================

    @Test
    void s256Challenge_matchesBase64UrlSha256() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("test-verifier".getBytes(StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        assertEquals(expected, McpOAuthClient.s256Challenge("test-verifier"));
    }

    @Test
    void generateVerifier_isUrlSafeAndLongEnough() {
        String v = McpOAuthClient.generateVerifier();
        assertTrue(v.length() >= 43, "RFC 7636 要求 43-128 位，当前 " + v.length());
        assertTrue(v.matches("[A-Za-z0-9\\-_]+"), "必须 URL-safe");
        assertFalse(v.equals(McpOAuthClient.generateVerifier()), "每次生成必须不同");
    }

    @Test
    void parseResourceMetadataUrl_extractsQuotedUrl() {
        assertEquals("https://host/.well-known/oauth-protected-resource",
                McpOAuthClient.parseResourceMetadataUrl(
                        "Bearer resource_metadata=\"https://host/.well-known/oauth-protected-resource\""));
    }

    @Test
    void parseResourceMetadataUrl_returnsNullWhenAbsent() {
        assertNull(McpOAuthClient.parseResourceMetadataUrl("Bearer"));
        assertNull(McpOAuthClient.parseResourceMetadataUrl(null));
        assertNull(McpOAuthClient.parseResourceMetadataUrl(""));
    }

    @Test
    void wellKnown_insertsPathBetweenHostAndPath() {
        assertEquals("https://host/.well-known/oauth-authorization-server",
                McpOAuthClient.wellKnown("https://host", "oauth-authorization-server"));
        assertEquals("https://host/.well-known/oauth-authorization-server",
                McpOAuthClient.wellKnown("https://host/", "oauth-authorization-server"));
        assertEquals("https://host/.well-known/oauth-authorization-server/tenant1",
                McpOAuthClient.wellKnown("https://host/tenant1", "oauth-authorization-server"));
    }

    @Test
    void authorizeUrl_carriesPkceAndState() {
        McpOAuthClient.ServerMetadata md = new McpOAuthClient.ServerMetadata(
                "http://host", "http://host/authorize", "http://host/token", null);
        String url = client.authorizeUrl(md, "cid", "http://127.0.0.1:9527/cb",
                "chal", "st-123", "mail.read");
        assertTrue(url.startsWith("http://host/authorize?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=cid"));
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A9527%2Fcb"));
        assertTrue(url.contains("state=st-123"));
        assertTrue(url.contains("code_challenge=chal"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("scope=mail.read"));
    }

    @Test
    void authorizeUrl_omitsBlankScope() {
        McpOAuthClient.ServerMetadata md = new McpOAuthClient.ServerMetadata(
                "http://host", "http://host/authorize", "http://host/token", null);
        String url = client.authorizeUrl(md, "cid", "http://cb", "chal", "st", "  ");
        assertFalse(url.contains("scope="), "空 scope 不应拼进 URL");
    }
}
