package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.tool.InstanceEndpoint;
import com.zzkingcc.domainplane.runtime.tool.ParamSchemaRenderer;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTextOverlayTest {

    private static final ToolExecutor NOOP = (request, context) -> "ok";
    @TempDir
    Path temp;
    private ToolRegistry registry;
    private ToolTextStore store;
    private ToolTextOverlay overlay;

    private static ToolDescriptor.Param param(String name, String desc) {
        return new ToolDescriptor.Param(name, "string", desc, true, List.of(), "", false);
    }

    private static ToolRegistry.Registered registered(String name, String desc, String paramDesc,
                                                       List<InstanceEndpoint> endpoints) {
        ToolDescriptor d = new ToolDescriptor(name, desc, "cat", "1", Tool.Effect.READ,
                true, true, List.of(param("q", paramDesc)), List.of("default"),
                ToolDescriptor.Approval.none(), "Bean#method");
        ToolSpecification spec = ToolSpecification.builder()
                .name(name).description(desc)
                .parameters(ParamSchemaRenderer.toJsonSchema(d.params()))
                .build();
        return new ToolRegistry.Registered(d, spec, NOOP, endpoints);
    }

    private static ToolRegistry.Registered local(String name, String desc) {
        return registered(name, desc, "原参数说明", List.of());
    }

    private static String json(Object value) {
        try {
            return new ObjectMapper()
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                    .writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setup() {
        registry = new ToolRegistry();
        registry.register(local("inner.search", "原说明"));
        registry.register(registered("remote.echo", "远程说明", "远程参数说明",
                List.of(new InstanceEndpoint("inst-1", "http://127.0.0.1:9000/invoke"))));
        registry.register(registered("mcp.tool", "MCP说明", "MCP参数说明",
                List.of(new InstanceEndpoint("mcp:demo", "stdio"))));
        store = new ToolTextStore(temp.resolve("tool-text.json"));
        overlay = new ToolTextOverlay(registry, store);
        overlay.captureBaselines();
    }

    private Map<String, Object> item(String name) {
        return overlay.catalog().stream()
                .filter(i -> name.equals(i.get("name")))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("保存后 descriptor 与 specification 两处一起生效")
    void saveAppliesBothFaces() {
        overlay.save("inner.search", "新说明", Map.of("q", "新参数说明"));

        ToolRegistry.Registered after = registry.find("inner.search").orElseThrow();
        assertEquals("新说明", after.descriptor().description());
        assertEquals("新说明", after.specification().description());
        assertEquals("新参数说明", after.descriptor().params().get(0).description());
        assertTrue(json(after.specification().parameters()).contains("新参数说明"),
                "发给模型的 schema 里必须带新参数说明");
    }

    @Test
    @DisplayName("目录按来源分类，远程与 MCP 只读")
    void catalogKinds() {
        assertEquals("LOCAL", item("inner.search").get("kind"));
        assertEquals(true, item("inner.search").get("editable"));
        assertEquals("REMOTE", item("remote.echo").get("kind"));
        assertEquals(false, item("remote.echo").get("editable"));
        assertEquals("MCP", item("mcp.tool").get("kind"));
        assertEquals(false, item("mcp.tool").get("editable"));
        assertEquals("远程说明", item("remote.echo").get("description"));
    }

    @Test
    @DisplayName("目录给出参数树、基线与覆盖标记")
    void catalogParamTree() {
        overlay.save("inner.search", "新说明", Map.of("q", "新参数说明"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) item("inner.search").get("params");
        assertEquals("q", params.get(0).get("path"));
        assertEquals("新参数说明", params.get(0).get("description"));
        assertEquals("原参数说明", params.get(0).get("baselineDescription"));
        assertEquals(true, params.get(0).get("overridden"));
        assertEquals(true, item("inner.search").get("overridden"));
        assertEquals(false, item("inner.search").get("drift"));
    }

    @Test
    @DisplayName("PUT 是全量替换：只提交工具说明会清掉旧的参数覆盖")
    void fullReplacement() {
        overlay.save("inner.search", "新说明", Map.of("q", "新参数说明"));
        overlay.save("inner.search", "更新的说明", Map.of());

        assertEquals("更新的说明", registry.find("inner.search").orElseThrow()
                .descriptor().description());
        assertEquals("原参数说明", registry.find("inner.search").orElseThrow()
                .descriptor().params().get(0).description());
    }

    @Test
    @DisplayName("恢复默认回到注解原文")
    void reset() {
        overlay.save("inner.search", "新说明", Map.of("q", "新参数说明"));
        overlay.reset("inner.search");

        ToolRegistry.Registered after = registry.find("inner.search").orElseThrow();
        assertEquals("原说明", after.descriptor().description());
        assertEquals("原说明", after.specification().description());
        assertEquals("原参数说明", after.descriptor().params().get(0).description());
        assertTrue(store.load().tools().isEmpty());
    }

    @Test
    @DisplayName("非本地工具 / 不存在的工具 / 失效路径一律拒绝")
    void rejections() {
        assertThrows(IllegalArgumentException.class,
                () -> overlay.save("remote.echo", "x", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> overlay.save("mcp.tool", "x", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> overlay.save("nope", "x", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> overlay.save("inner.search", "x", Map.of("not.exist", "y")));
        assertEquals("远程说明", registry.find("remote.echo").orElseThrow()
                .descriptor().description());
    }

    @Test
    @DisplayName("declareDomains 改过的可用域不会被覆盖重建回滚")
    void domainsNotRolledBack() {
        assertTrue(registry.declareDomains("inner.search", List.of("default.sales")));
        overlay.save("inner.search", "新说明", Map.of());

        assertEquals(List.of("default.sales"), registry.find("inner.search").orElseThrow()
                .descriptor().domains());
    }

    @Test
    @DisplayName("重启后重放覆盖")
    void replayAfterRestart() {
        overlay.save("inner.search", "新说明", Map.of("q", "新参数说明"));

        ToolRegistry restarted = new ToolRegistry();
        restarted.register(local("inner.search", "原说明"));
        ToolTextOverlay replayed = new ToolTextOverlay(restarted, store);
        replayed.captureBaselines();
        replayed.replay();

        assertEquals("新说明", restarted.find("inner.search").orElseThrow()
                .descriptor().description());
        assertEquals("新参数说明", restarted.find("inner.search").orElseThrow()
                .descriptor().params().get(0).description());
    }

    @Test
    @DisplayName("注解说明升级后目录标出漂移，覆盖本身不丢")
    void driftDetected() {
        overlay.save("inner.search", "新说明", Map.of("q", "新参数说明"));

        ToolRegistry upgraded = new ToolRegistry();
        upgraded.register(local("inner.search", "注解改过的新说明"));
        ToolTextOverlay afterUpgrade = new ToolTextOverlay(upgraded, store);
        afterUpgrade.captureBaselines();

        assertEquals(true, afterUpgrade.catalog().get(0).get("drift"));
        assertFalse(store.load().tools().isEmpty(), "不能因为漂移就自动清掉用户写的文案");
    }
}
