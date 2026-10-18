package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolBridge#publish} → {@link ToolRegistry} 的端到端（P0 的核心回归）。
 *
 * <p>曾经 {@code replaceInstanceTools} 拒 {@code "mcp:"} 前缀，{@code publish} 一盖章就抛，
 * 工具永远注册不进。这里用"就绪运行时 + 真实注册表"驱动 publish，锁死四件事：
 * ① {@code mcp:} 前缀能落地；② 只对绑定域可见；③ 无绑定落 {@code REVOKED} 哨兵、对任何域不可见；
 * ④ 未就绪则摘除（降级不残留幽灵工具）；④b NEEDS_AUTH（等管理员 OAuth 授权）同样摘除。</p>
 */
class McpToolBridgePublishTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 可控 provider：只覆写 runtime()，绕开真实建连（测试 seam 提供就绪/未就绪运行时） */
    static class FakeProvider extends McpConnectionRuntimeProvider {
        private McpConnectionRuntime current;

        FakeProvider(McpConnectionStore store) {
            super(store, new McpOAuthClient());
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

    private McpRpcClient.McpToolInfo info(String name, String desc, String annotationsJson) throws Exception {
        JsonNode anno = annotationsJson == null ? null : mapper.readTree(annotationsJson);
        return new McpRpcClient.McpToolInfo(name, desc, mapper.createObjectNode(), anno);
    }

    private McpConnection conn(String name) {
        return new McpConnection(name, "http://localhost:8787/mcp", McpTransport.STREAMABLE_HTTP,
                Map.of("Authorization", "Bearer test-token-qqmail"), 30000, true,
                McpApprovalPolicy.ALL, Set.of(), "");
    }

    /** ① mcp: 前缀能落地 + ② 只对绑定域可见；副作用与审批按注解/策略推导 */
    @Test
    void publish_registersTools_visibleOnlyToBoundDomain() throws Exception {
        McpConnectionStore store = new McpConnectionStore(storage(tempDir));
        store.upsert(conn("qqmail"));
        store.bind("default.sales", List.of("qqmail"));

        McpConnection connection = store.get("qqmail");
        McpConnectionRuntime runtime = McpConnectionRuntime.readyForTest("qqmail", connection,
                List.of(info("GetMe", "账号", "{\"readOnlyHint\":true}"),
                        info("DeleteMessage", "删除", "{\"readOnlyHint\":false,\"destructiveHint\":true}")));

        FakeProvider provider = new FakeProvider(store);
        provider.current = runtime;

        ToolRegistry registry = new ToolRegistry();
        new McpToolBridge(registry, store, provider).publish("qqmail");

        assertTrue(registry.find("mcp__qqmail__GetMe").isPresent(), "mcp: 前缀必须能注册（P0）");
        ToolDescriptor getMe = registry.find("mcp__qqmail__GetMe").orElseThrow().descriptor();
        assertTrue(getMe.visibleIn("default.sales"), "绑定域可见");
        assertFalse(getMe.visibleIn("default.hr"), "未绑定域不可见");
        assertEquals(Tool.Effect.READ, getMe.sideEffect());
        assertTrue(getMe.requiresApproval(), "默认 ALL 策略：READ 也需审批");

        ToolDescriptor del = registry.find("mcp__qqmail__DeleteMessage").orElseThrow().descriptor();
        assertEquals(Tool.Effect.DESTRUCTIVE, del.sideEffect());
        assertTrue(del.visibleIn("default.sales"));
        assertFalse(del.visibleIn("default.hr"));
    }

    /** ③ 无绑定 → 落 REVOKED 哨兵，对任何域都不可见（绝不是挂根域全树可见） */
    @Test
    void publish_withNoBinding_stampsRevoked_invisibleEverywhere() throws Exception {
        McpConnectionStore store = new McpConnectionStore(storage(tempDir));
        store.upsert(conn("qqmail"));   // 有意不 bind

        McpConnection connection = store.get("qqmail");
        McpConnectionRuntime runtime = McpConnectionRuntime.readyForTest("qqmail", connection,
                List.of(info("GetMe", "账号", "{\"readOnlyHint\":true}")));

        FakeProvider provider = new FakeProvider(store);
        provider.current = runtime;

        ToolRegistry registry = new ToolRegistry();
        new McpToolBridge(registry, store, provider).publish("qqmail");

        ToolDescriptor getMe = registry.find("mcp__qqmail__GetMe").orElseThrow().descriptor();
        assertFalse(getMe.visibleIn("default.sales"), "无绑定必须对任何域不可见");
        assertFalse(getMe.visibleIn("default"), "更不能挂根域全树可见");
        assertTrue(getMe.domains().contains(ToolDescriptor.REVOKED_DOMAIN), "应落 REVOKED 哨兵");
    }

    /** ④ 未就绪 → 摘除（降级不残留幽灵工具） */
    @Test
    void publish_whenNotReady_removesTools() throws Exception {
        McpConnectionStore store = new McpConnectionStore(storage(tempDir));
        store.upsert(conn("qqmail"));
        store.bind("default.sales", List.of("qqmail"));

        McpConnection connection = store.get("qqmail");
        McpConnectionRuntime ready = McpConnectionRuntime.readyForTest("qqmail", connection,
                List.of(info("GetMe", "账号", "{\"readOnlyHint\":true}")));
        McpConnectionRuntime notReady = new McpConnectionRuntime("qqmail", connection); // 从未建连

        FakeProvider provider = new FakeProvider(store);
        ToolRegistry registry = new ToolRegistry();
        McpToolBridge bridge = new McpToolBridge(registry, store, provider);

        provider.current = ready;
        bridge.publish("qqmail");
        assertTrue(registry.find("mcp__qqmail__GetMe").isPresent(), "就绪时应注册");

        provider.current = notReady;
        bridge.publish("qqmail");
        assertTrue(registry.find("mcp__qqmail__GetMe").isEmpty(), "未就绪应摘除");
    }

    /** ④b NEEDS_AUTH（等管理员授权）→ 同样摘除：未授权前工具不可见 */
    @Test
    void publish_whenNeedsAuth_removesTools() throws Exception {
        McpConnectionStore store = new McpConnectionStore(storage(tempDir));
        store.upsert(conn("qqmail"));
        store.bind("default.sales", List.of("qqmail"));

        McpConnection connection = store.get("qqmail");
        McpConnectionRuntime ready = McpConnectionRuntime.readyForTest("qqmail", connection,
                List.of(info("GetMe", "账号", "{\"readOnlyHint\":true}")));
        McpConnectionRuntime needsAuth = new McpConnectionRuntime("qqmail", connection);
        needsAuth.markNeedsAuth("Bearer resource_metadata=\"http://127.0.0.1/x/.well-known/oauth-protected-resource\"");

        FakeProvider provider = new FakeProvider(store);
        ToolRegistry registry = new ToolRegistry();
        McpToolBridge bridge = new McpToolBridge(registry, store, provider);

        provider.current = ready;
        bridge.publish("qqmail");
        assertTrue(registry.find("mcp__qqmail__GetMe").isPresent(), "授权前就绪时应注册");

        provider.current = needsAuth;
        bridge.publish("qqmail");
        assertTrue(registry.find("mcp__qqmail__GetMe").isEmpty(), "NEEDS_AUTH 未授权前工具必须摘除");
        assertEquals(McpConnectionRuntime.Status.NEEDS_AUTH, needsAuth.status());
    }
}
