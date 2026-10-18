package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpConnection.OAuthConfig} 的归一化与默认值，以及旧版落盘文件（无 oauth 字段）
 * 的反序列化兼容：动态注册被服务商拒绝时，手工 client_id 是正路，配置模型不能炸在旧数据上。
 *
 * 另一条同类兼容：approvalPolicy 缺字段 / null 归一化为 ALL：McpToolCatalog 注册时
 * 直接调 requiresApproval()，null 会 NPE 炸掉整条连接的工具注册（管控台表单有兜底，
 * 手工编辑 JSON 没有，这里补的是那条路）。
 */
class McpConnectionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oauthConfig_nullComponents_normalizeToEmpty() {
        McpConnection.OAuthConfig oc = new McpConnection.OAuthConfig(null, null, null, null, null);
        assertEquals("", oc.clientId());
        assertEquals("", oc.clientSecret());
        assertEquals("", oc.redirectUri());
        assertEquals("", oc.clientName());
        assertEquals("", oc.scope());
        assertFalse(oc.hasClientId());
        assertFalse(oc.hasClientSecret());
        assertFalse(oc.hasScope());
        assertNull(oc.redirectUriOrNull());
    }

    @Test
    void oauthConfig_clientNameDefaultsToWorkBuddy() {
        // 腾讯官方 MCP 的 OAuth 准入实测只放行含 Codex/Claude/WorkBuddy/CodeBuddy 的 client 名；
        // 默认用本平台名，且可配（清单不公开、可能变）
        assertEquals("WorkBuddy", McpConnection.OAuthConfig.DEFAULT_CLIENT_NAME);
        assertEquals("WorkBuddy", new McpConnection.OAuthConfig("", "", "", "", "").clientNameOrDefault());
        assertEquals("Codex",
                new McpConnection.OAuthConfig("", "", "", "Codex", "").clientNameOrDefault());
    }

    @Test
    void oauthConfig_trimsAndResolvesOverrides() {
        McpConnection.OAuthConfig oc = new McpConnection.OAuthConfig(
                " cid ", " sec ", " https://mcp.example.com/cb ", " WorkBuddy ", " mail.read ");
        assertEquals("cid", oc.clientId());
        assertTrue(oc.hasClientId());
        assertTrue(oc.hasClientSecret());
        assertTrue(oc.hasScope());
        assertEquals("https://mcp.example.com/cb", oc.redirectUriOrNull());
    }

    @Test
    void legacyJson_withoutOauthField_deserializesToNoneNotNull() throws Exception {
        String json = "{\"connections\":[{\"name\":\"qqmail\",\"url\":\"http://127.0.0.1:9/mcp\","
                + "\"transport\":\"STREAMABLE_HTTP\",\"headers\":{},\"callTimeoutMs\":30000,"
                + "\"enabled\":true,\"approvalPolicy\":\"ALL\",\"toolAllowlist\":[],\"remark\":\"\"}],"
                + "\"domainBindings\":{},\"oauthTokens\":{}}";
        McpConnection.FileShape shape = mapper.readValue(json, McpConnection.FileShape.class);
        McpConnection conn = shape.connections().get(0);
        assertNotNull(conn.oauth(), "旧文件无 oauth 字段必须归一化为 NONE，不能是 null");
        assertFalse(conn.oauth().hasClientId());
        assertEquals("WorkBuddy", conn.oauth().clientNameOrDefault());
    }

    @Test
    void nineArgConstructor_defaultsOauthToNone() {
        McpConnection conn = new McpConnection("c", "http://127.0.0.1:9/mcp",
                McpTransport.STREAMABLE_HTTP, Map.of(), 30000, true,
                McpApprovalPolicy.ALL, Set.of(), "");
        assertNotNull(conn.oauth());
        assertFalse(conn.oauth().hasClientId());
        assertEquals(conn, new McpConnection("c", "http://127.0.0.1:9/mcp",
                McpTransport.STREAMABLE_HTTP, Map.of(), 30000, true,
                McpApprovalPolicy.ALL, Set.of(), "", conn.oauth()));
    }

    @Test
    void legacyJson_withoutApprovalPolicyField_deserializesToAllNotNull() throws Exception {
        // 与 oauth 同型的旧文件兼容：approvalPolicy 字段是后加的，旧 JSON 没有它。
        // 归一化必须发生在 compact constructor（反序列化也走它），不能只在前端表单兜底
        String json = "{\"connections\":[{\"name\":\"qqmail\",\"url\":\"http://127.0.0.1:9/mcp\","
                + "\"transport\":\"STREAMABLE_HTTP\",\"headers\":{},\"callTimeoutMs\":30000,"
                + "\"enabled\":true,\"toolAllowlist\":[],\"remark\":\"\"}],"
                + "\"domainBindings\":{},\"oauthTokens\":{}}";
        McpConnection.FileShape shape = mapper.readValue(json, McpConnection.FileShape.class);
        McpConnection conn = shape.connections().get(0);
        assertNotNull(conn.approvalPolicy(),
                "旧文件缺 approvalPolicy 必须归一化为 ALL，不能是 null（McpToolCatalog 会 NPE）");
        assertEquals(McpApprovalPolicy.ALL, conn.approvalPolicy());
    }

    @Test
    void approvalPolicy_null_normalizesToAll() {
        // 回落 ALL 而不是 NONE：外部 server 自报的 readOnlyHint 不可信，缺配置取最严档
        McpConnection conn = new McpConnection("c", "http://127.0.0.1:9/mcp",
                McpTransport.STREAMABLE_HTTP, Map.of(), 30000, true,
                null, Set.of(), "");
        assertEquals(McpApprovalPolicy.ALL, conn.approvalPolicy());
        assertTrue(conn.approvalPolicy().requiresApproval(com.zzkingcc.domainplane.api.annotation.Tool.Effect.READ));
    }
}
