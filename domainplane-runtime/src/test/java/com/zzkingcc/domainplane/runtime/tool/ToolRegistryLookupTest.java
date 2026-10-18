package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #34：{@code find(null)} 在 {@code ConcurrentHashMap} 上直接抛 NPE。
 *
 * <p>模型偶尔会吐出空工具名（或只有空白的名字）。原实现把 NPE 抛给编排层，
 * 整轮对话以 {@code ORCHESTRATION_FAILED} 收场；而"工具不存在"本来就有
 * {@code TOOL_NOT_FOUND} 的回喂路径：空名应该走同一条路，让模型收到
 * "没这个工具"的反馈并自我纠正。</p>
 *
 * @author zzkingcc
 */
@DisplayName("ToolRegistry.find：空名字返回不存在，而不是 NPE")
class ToolRegistryLookupTest {

    private final ToolRegistry registry = new ToolRegistry();

    private static ToolRegistry.Registered tool(String name) {
        ToolDescriptor d = new ToolDescriptor(name, "desc", "cat", "1",
                null, true, true, List.of(), List.of("default.ops"), null, "domainplane");
        ToolSpecification spec = ToolSpecification.builder().name(name).description("desc").build();
        ToolExecutor noop = (request, context) -> "ok";
        return new ToolRegistry.Registered(d, spec, noop, List.of());
    }

    @Test
    @DisplayName("null / 空白 / 全空白：一律 Optional.empty()")
    void blankNamesReturnEmptyInsteadOfThrowing() {
        registry.register(tool("kb.search"));

        assertEquals(Optional.empty(), registry.find(null), "null 不能抛 NPE");
        assertEquals(Optional.empty(), registry.find(""), "空串不能抛 NPE");
        assertEquals(Optional.empty(), registry.find("   "), "全空白不能抛 NPE");
        assertEquals(Optional.empty(), registry.find("\t\n"), "控制字符也算空白");
    }

    @Test
    @DisplayName("正常名字仍查得到：守卫没有把查询功能一起废掉")
    void existingNameStillResolves() {
        registry.register(tool("kb.search"));

        assertTrue(registry.find("kb.search").isPresent(), "已注册的工具必须查得到");
        assertEquals(Optional.empty(), registry.find("kb.search "), "首尾空白按查不到处理，不做隐式 trim");
    }
}
