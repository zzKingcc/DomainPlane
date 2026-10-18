package com.zzkingcc.domainplane.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.runtime.tool.InstanceRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.tool.RemoteToolExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：外部工具实例不得占用 {@code "mcp:"} 保留前缀（P0 守卫的新落点）。
 *
 * <p>守卫从 {@code ToolRegistry.replaceInstanceTools}（MCP 与外部共用的 chokepoint，误伤内部 MCP）
 * 移到了本控制器：外部工具实例的唯一注册入口。这里锁死"外部 mcp: 注册被 400 拒、普通 instanceId 不受影响"。</p>
 *
 * <p>本项目测试不引 Mockito：{@link HttpServletRequest} 用 JDK 动态代理造一个只答 {@code getRemoteAddr} 的实例。</p>
 */
class ToolRegistrationControllerMcpPrefixTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry tools = new ToolRegistry();
    private final ToolRegistrationController controller =
            new ToolRegistrationController(new InstanceRegistry(), tools,
                    new RemoteToolExecutor(tools, 30000, 3));

    private HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) ->
                        "getRemoteAddr".equals(method.getName()) ? "10.0.0.9" : null);
    }

    private JsonNode body(String instanceId) throws Exception {
        return mapper.readTree("{\"instanceId\":\"" + instanceId + "\","
                + "\"endpoint\":\"http://10.0.0.5:8081/invoke\","
                + "\"manifest\":[{\"name\":\"some_tool\",\"description\":\"d\","
                + "\"domains\":[\"default.sales\"]}]}");
    }

    /** 外部占用 mcp: 前缀 → 400，且不进注册表 */
    @Test
    void externalMcpPrefixedInstance_rejected() throws Exception {
        ResponseEntity<Map<String, Object>> resp = controller.register(body("mcp:evil"), request());

        assertEquals(400, resp.getStatusCode().value());
        assertEquals(Boolean.FALSE, resp.getBody().get("accepted"));
        assertTrue(String.valueOf(resp.getBody().get("reason")).contains("mcp:"),
                "拒绝原因应点明是 mcp: 保留前缀");
        assertTrue(tools.find("some_tool").isEmpty(), "被拒的注册不能进注册表");
    }

    /** 普通 instanceId 不受影响：照常 200 受理并注册（守卫只针对 mcp:） */
    @Test
    void normalInstance_stillAccepted() throws Exception {
        ResponseEntity<Map<String, Object>> resp = controller.register(body("normal-instance"), request());

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("accepted"));
        assertTrue(tools.find("some_tool").isPresent(), "普通实例的工具应照常注册");
        assertFalse(tools.find("some_tool").get().descriptor().visibleIn("default.hr"),
                "只对声明域可见");
    }
}
