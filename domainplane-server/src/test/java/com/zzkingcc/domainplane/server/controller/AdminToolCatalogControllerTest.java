package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.tool.InstanceEndpoint;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.tool.ToolTextOverlay;
import com.zzkingcc.domainplane.server.tool.ToolTextStore;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminToolCatalogControllerTest {

    private static final ToolExecutor NOOP = (request, context) -> "ok";
    @TempDir
    Path temp;
    private ToolRegistry registry;
    private AdminToolCatalogController controller;

    private static ToolRegistry.Registered tool(String name, List<InstanceEndpoint> endpoints) {
        ToolDescriptor.Param p = new ToolDescriptor.Param("q", "string", "原参数说明",
                true, List.of(), "", false);
        ToolDescriptor d = new ToolDescriptor(name, "原说明", "cat", "1", Tool.Effect.READ,
                true, true, List.of(p), List.of("default"),
                ToolDescriptor.Approval.none(), "Bean#method");
        ToolSpecification spec = ToolSpecification.builder()
                .name(name).description("原说明").build();
        return new ToolRegistry.Registered(d, spec, NOOP, endpoints);
    }

    @BeforeEach
    void setup() {
        registry = new ToolRegistry();
        registry.register(tool("inner.search", List.of()));
        registry.register(tool("remote.echo",
                List.of(new InstanceEndpoint("inst-1", "http://127.0.0.1:9000/invoke"))));
        ToolTextOverlay overlay = new ToolTextOverlay(registry,
                new ToolTextStore(temp.resolve("tool-text.json")));
        overlay.captureBaselines();
        controller = new AdminToolCatalogController(overlay);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tools() {
        return (List<Map<String, Object>>) controller.list().get("tools");
    }

    private Map<String, Object> item(String name) {
        return tools().stream().filter(i -> name.equals(i.get("name")))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("清单包含内置与远程，编辑权限由后端标注")
    void listMarksEditability() {
        assertEquals(2, tools().size());
        assertEquals("LOCAL", item("inner.search").get("kind"));
        assertEquals(true, item("inner.search").get("editable"));
        assertEquals("REMOTE", item("remote.echo").get("kind"));
        assertEquals(false, item("remote.echo").get("editable"));
    }

    @Test
    @DisplayName("保存后清单与注册表都反映新文案")
    void saveApplies() {
        Map<String, Object> result = controller.save("inner.search",
                new AdminToolCatalogController.TextBody("新说明", Map.of("q", "新参数说明")));

        assertEquals(true, result.get("success"));
        assertEquals("新说明", item("inner.search").get("description"));
        assertEquals("新说明", registry.find("inner.search").orElseThrow()
                .specification().description());
        assertEquals("新参数说明", registry.find("inner.search").orElseThrow()
                .descriptor().params().get(0).description());
    }

    @Test
    @DisplayName("远程工具与不存在的工具返回参数错误")
    void saveRefused() {
        BaseException remote = assertThrows(BaseException.class, () -> controller.save("remote.echo",
                new AdminToolCatalogController.TextBody("x", Map.of())));
        assertTrue(remote.getMessage().contains("内置工具"));
        assertThrows(BaseException.class, () -> controller.save("nope",
                new AdminToolCatalogController.TextBody("x", Map.of())));
    }

    @Test
    @DisplayName("参数路径失效时报错，不静默丢弃")
    void unknownPathRefused() {
        assertThrows(BaseException.class, () -> controller.save("inner.search",
                new AdminToolCatalogController.TextBody("x", Map.of("not.exist", "y"))));
    }

    @Test
    @DisplayName("恢复默认回到注解原文")
    void reset() {
        controller.save("inner.search",
                new AdminToolCatalogController.TextBody("新说明", Map.of("q", "新参数说明")));

        controller.reset("inner.search");

        assertEquals("原说明", item("inner.search").get("description"));
        assertEquals("原参数说明", registry.find("inner.search").orElseThrow()
                .descriptor().params().get(0).description());
    }

    @Test
    @DisplayName("空请求体等价于清除全部覆盖")
    void nullBodyClears() {
        controller.save("inner.search",
                new AdminToolCatalogController.TextBody("新说明", Map.of("q", "新参数说明")));

        controller.save("inner.search", null);

        assertEquals("原说明", item("inner.search").get("description"));
        assertEquals(false, item("inner.search").get("overridden"));
    }

    @Test
    @DisplayName("远程工具不能恢复默认")
    void resetRefused() {
        assertThrows(BaseException.class, () -> controller.reset("remote.echo"));
    }
}
