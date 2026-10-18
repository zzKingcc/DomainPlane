package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：MCP 连接以 {@code "mcp:<连接名>"} 作 instanceId 走 {@link ToolRegistry#replaceInstanceTools} 注册工具。
 *
 * <p>曾经 {@code replaceInstanceTools} 无条件拒 {@code "mcp:"} 前缀（本意是挡外部 tool-provider 占用），
 * 结果把 MCP 自己的盖章也挡死：每次注册抛 {@link IllegalArgumentException}，工具永远注册不进、
 * 正常配置重启即启动失败。守卫已移到 {@code ToolRegistrationController}（外部唯一注册入口）；
 * 这里锁死"内部 MCP 路径不再被拒"。</p>
 */
class ToolRegistryMcpPrefixTest {

    private final ToolRegistry registry = new ToolRegistry();

    private static ToolRegistry.Registered tool(String name, String source, List<String> domains) {
        ToolDescriptor d = new ToolDescriptor(name, "desc", "cat", "1",
                null, true, true, List.of(), domains, null, source);
        ToolSpecification spec = ToolSpecification.builder().name(name).description("desc").build();
        ToolExecutor noop = (request, context) -> "ok";
        return new ToolRegistry.Registered(d, spec, noop, List.of());
    }

    /** MCP 盖章用的 "mcp:<连接名>" instanceId 必须能被 replaceInstanceTools 接纳（P0 回归） */
    @Test
    void mcpPrefixedInstance_registersInsteadOfThrowing() {
        registry.replaceInstanceTools("mcp:qqmail", "http://localhost:8787/mcp",
                List.of(tool("mcp__qqmail__GetMe", "mcp://qqmail@http://localhost:8787/mcp",
                        List.of("default.sales"))));

        assertTrue(registry.find("mcp__qqmail__GetMe").isPresent(), "mcp: 前缀实例的工具必须能注册");
        assertTrue(registry.find("mcp__qqmail__GetMe").get().descriptor().visibleIn("default.sales"));
    }

    /** 二次盖章（重连 / 绑定变更）幂等：同一 mcp: 实例重复上报不报错 */
    @Test
    void mcpPrefixedInstance_republishIsIdempotent() {
        List<ToolRegistry.Registered> manifest = List.of(
                tool("mcp__qqmail__GetMe", "mcp://qqmail@http://localhost:8787/mcp",
                        List.of("default.sales")));
        registry.replaceInstanceTools("mcp:qqmail", "http://localhost:8787/mcp", manifest);
        registry.replaceInstanceTools("mcp:qqmail", "http://localhost:8787/mcp", manifest);

        assertTrue(registry.find("mcp__qqmail__GetMe").isPresent(), "重复盖章后工具仍在");
    }

    /** 下线照常摘除：removeInstance 对 mcp: 实例同样生效 */
    @Test
    void mcpPrefixedInstance_removeInstanceDetaches() {
        registry.replaceInstanceTools("mcp:qqmail", "http://localhost:8787/mcp",
                List.of(tool("mcp__qqmail__GetMe", "mcp://qqmail@http://localhost:8787/mcp",
                        List.of("default.sales"))));
        registry.removeInstance("mcp:qqmail");
        assertTrue(registry.find("mcp__qqmail__GetMe").isEmpty());
    }

    /** 两个不同 MCP 连接声明同名原始工具，命名空间隔离后都能注册、互不覆盖 */
    @Test
    void twoMcpConnections_sameOriginalName_bothRegister() {
        registry.replaceInstanceTools("mcp:a", "http://a/mcp",
                List.of(tool("mcp__a__GetMe", "mcp://a@http://a/mcp", List.of("default.sales"))));
        registry.replaceInstanceTools("mcp:b", "http://b/mcp",
                List.of(tool("mcp__b__GetMe", "mcp://b@http://b/mcp", List.of("default.sales"))));

        assertTrue(registry.find("mcp__a__GetMe").isPresent());
        assertTrue(registry.find("mcp__b__GetMe").isPresent());
        assertFalse(registry.find("mcp__a__GetMe").get().endpoints()
                .equals(registry.find("mcp__b__GetMe").get().endpoints()));
    }
}
