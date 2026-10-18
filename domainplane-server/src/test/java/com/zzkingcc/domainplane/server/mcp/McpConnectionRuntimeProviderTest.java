package com.zzkingcc.domainplane.server.mcp;

import com.zzkingcc.domainplane.server.env.StorageLocations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpConnectionRuntimeProvider} 的 OAuth 三条路径：
 * ① {@code effectiveHeaders} 每次现取（未过期 token 注入 Bearer，过期不注入）；
 * ② {@code ensureFreshToken} 门禁（无 token 放行 / 未过期放行 / 过期必须刷新成功，否则 connect 直接留 NEEDS_AUTH，不发请求）；
 * ③ {@code refreshToken}（服务器不轮换 refresh_token 时保留旧的；轮换时跟进）。
 */
class McpConnectionRuntimeProviderTest {

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

    private McpConnection conn(String url) {
        return new McpConnection("conn", url, McpTransport.STREAMABLE_HTTP,
                Map.of("X-Static", "1"), 5000, true, McpApprovalPolicy.ALL, Set.of(), "");
    }

    private McpConnection.OAuthToken token(String access, String refresh, long expiresAtEpochMs,
                                           String tokenEndpoint) {
        return new McpConnection.OAuthToken(access, refresh, expiresAtEpochMs, "mail",
                "stub-client-123", tokenEndpoint);
    }

    // ==================== effectiveHeaders ====================

    @Test
    void effectiveHeaders_injectsBearerForUnexpiredToken() {
        McpConnectionStore store = new McpConnectionStore(storage());
        store.upsert(conn("http://127.0.0.1:9/mcp"));
        store.saveToken("conn", token("tok-1", "ref-1",
                System.currentTimeMillis() + 3600_000, "http://127.0.0.1:9/token"));
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());

        Map<String, String> headers = provider.effectiveHeaders("conn");
        assertEquals("Bearer tok-1", headers.get("Authorization"));
        assertEquals("1", headers.get("X-Static"), "静态头必须保留");
    }

    @Test
    void effectiveHeaders_omitsExpiredToken() {
        McpConnectionStore store = new McpConnectionStore(storage());
        store.upsert(conn("http://127.0.0.1:9/mcp"));
        store.saveToken("conn", token("tok-1", "ref-1",
                System.currentTimeMillis() - 1000, "http://127.0.0.1:9/token"));
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());

        assertFalse(provider.effectiveHeaders("conn").containsKey("Authorization"),
                "过期 token 不注入（放了也是 401）");
    }

    @Test
    void effectiveHeaders_noToken_keepsStaticHeaders() {
        McpConnectionStore store = new McpConnectionStore(storage());
        store.upsert(conn("http://127.0.0.1:9/mcp"));
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());

        Map<String, String> headers = provider.effectiveHeaders("conn");
        assertEquals("1", headers.get("X-Static"));
        assertFalse(headers.containsKey("Authorization"));
    }

    // ==================== ensureFreshToken 门禁 ====================

    @Test
    void ensureFreshToken_noToken_returnsTrue() {
        McpConnectionStore store = new McpConnectionStore(storage());
        store.upsert(conn("http://127.0.0.1:9/mcp"));
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());
        assertTrue(provider.ensureFreshToken("conn"), "静态头路径（无 token）直接放行");
    }

    @Test
    void ensureFreshToken_unexpired_returnsTrue() {
        McpConnectionStore store = new McpConnectionStore(storage());
        store.upsert(conn("http://127.0.0.1:9/mcp"));
        store.saveToken("conn", token("tok-1", "ref-1",
                System.currentTimeMillis() + 3600_000, "http://127.0.0.1:1/token"));
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());
        assertTrue(provider.ensureFreshToken("conn"));
    }

    @Test
    void ensureFreshToken_expiredRefreshFails_returnsFalse() {
        McpConnectionStore store = new McpConnectionStore(storage());
        store.upsert(conn("http://127.0.0.1:9/mcp"));
        store.saveToken("conn", token("tok-1", "ref-1",
                System.currentTimeMillis() - 1000, "http://127.0.0.1:1/token")); // 死端口，刷新必失败
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());
        assertFalse(provider.ensureFreshToken("conn"), "过期且刷不动必须返回 false 阻断建连");
    }

    @Test
    void connect_withExpiredUnrefreshableToken_blocksWithoutRequest() throws Exception {
        try (StubMcpServer stub = new StubMcpServer()) {
            stub.responder = McpRpcClientTest::happy;
            McpConnectionStore store = new McpConnectionStore(storage());
            store.upsert(conn(stub.url()));
            store.saveToken("conn", token("tok-1", "ref-1",
                    System.currentTimeMillis() - 1000, "http://127.0.0.1:1/token"));
            McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());

            provider.connect("conn");

            assertTrue(stub.requests.isEmpty(), "刷新失败就不该发建连请求（发了也是 401）");
            McpConnectionRuntime runtime = provider.runtime("conn");
            assertNotNull(runtime);
            assertEquals(McpConnectionRuntime.Status.NEEDS_AUTH, runtime.status());
            assertFalse(runtime.isReady());
        }
    }

    // ==================== refreshToken ====================

    @Test
    void refreshToken_preservesOldRefreshWhenServerOmitsNew() throws Exception {
        try (StubOAuthServer oauth = new StubOAuthServer()) {
            McpConnectionStore store = new McpConnectionStore(storage());
            store.upsert(conn("http://127.0.0.1:9/mcp"));
            store.saveToken("conn", token("tok-1", "refresh-1",
                    System.currentTimeMillis() - 1000, oauth.tokenUrl()));
            McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());

            assertTrue(provider.refreshToken("conn"));
            McpConnection.OAuthToken t = store.tokenOf("conn");
            assertEquals("access-2", t.accessToken());
            assertEquals("refresh-1", t.refreshToken(), "服务器不轮换时必须保留旧 refresh_token，否则续一次就断链");
            assertEquals("stub-client-123", t.clientId(), "client_id 必须随 token 保留（刷新请求要用）");
            assertEquals(oauth.tokenUrl(), t.tokenEndpoint());
        }
    }

    @Test
    void refreshToken_usesRotatedRefreshWhenServerReturnsNew() throws Exception {
        try (StubOAuthServer oauth = new StubOAuthServer()) {
            oauth.rotateRefresh = true;
            McpConnectionStore store = new McpConnectionStore(storage());
            store.upsert(conn("http://127.0.0.1:9/mcp"));
            store.saveToken("conn", token("tok-1", "refresh-1",
                    System.currentTimeMillis() - 1000, oauth.tokenUrl()));
            McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());

            assertTrue(provider.refreshToken("conn"));
            assertEquals("refresh-2", store.tokenOf("conn").refreshToken());
        }
    }

    @Test
    void refreshToken_withoutRefreshToken_returnsFalse() {
        McpConnectionStore store = new McpConnectionStore(storage());
        store.upsert(conn("http://127.0.0.1:9/mcp"));
        store.saveToken("conn", token("tok-1", null,
                System.currentTimeMillis() - 1000, "http://127.0.0.1:1/token"));
        McpConnectionRuntimeProvider provider = new McpConnectionRuntimeProvider(store, new McpOAuthClient());
        assertFalse(provider.refreshToken("conn"), "没有 refresh_token 无从刷新");
        assertNull(store.tokenOf("conn").refreshToken());
    }
}
