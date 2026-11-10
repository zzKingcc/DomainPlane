package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数说明的路径规则：三处（展示 / 保存 / 校验）只有这一份定义，这里把它钉死。
 */
class ParamTextTreeTest {

    private static ToolDescriptor.Param p(String name, String type, String desc,
                                          List<ToolDescriptor.Param> props,
                                          List<ToolDescriptor.Param> items) {
        return new ToolDescriptor.Param(name, type, desc, true, List.of("A", "B"), "示例", false,
                props, items);
    }

    /** 顶层标量 + 嵌套对象 + 对象数组（元素带子字段） */
    private static List<ToolDescriptor.Param> tree() {
        ToolDescriptor.Param status = p("status", "enum", "状态原说明", List.of(), List.of());
        ToolDescriptor.Param filter = p("filter", "object", "过滤条件原说明",
                List.of(status), List.of());
        ToolDescriptor.Param sku = p("sku", "string", "编号原说明", List.of(), List.of());
        ToolDescriptor.Param tagItem = p(null, "object", "标签元素原说明",
                List.of(sku), List.of());
        ToolDescriptor.Param tags = p("tags", "array", "标签原说明",
                List.of(), List.of(tagItem));
        return List.of(p("prompt", "string", "提示词原说明", List.of(), List.of()),
                filter, tags);
    }

    @Test
    @DisplayName("路径覆盖顶层、对象子字段、数组元素与元素子字段")
    void flattenPaths() {
        List<ParamTextTree.Node> nodes = ParamTextTree.flatten(tree());
        assertEquals(List.of("prompt", "filter", "filter.status", "tags", "tags[]", "tags[].sku"),
                nodes.stream().map(ParamTextTree.Node::path).toList());
        assertEquals(List.of(0, 0, 1, 0, 1, 2),
                nodes.stream().map(ParamTextTree.Node::depth).toList());
        assertEquals("[]", nodes.get(4).name());
    }

    @Test
    @DisplayName("基线说明按路径收齐")
    void baseline() {
        Map<String, String> baseline = ParamTextTree.baselineDescriptions(tree());
        assertEquals("状态原说明", baseline.get("filter.status"));
        assertEquals("标签元素原说明", baseline.get("tags[]"));
        assertEquals("编号原说明", baseline.get("tags[].sku"));
    }

    @Test
    @DisplayName("替换说明时结构字段一个都不动")
    void rewriteKeepsShape() {
        List<ToolDescriptor.Param> changed = ParamTextTree.withDescriptions(tree(),
                Map.of("filter.status", "新状态说明", "tags[]", "新元素说明"));

        List<ParamTextTree.Node> nodes = ParamTextTree.flatten(changed);
        assertEquals("新状态说明", nodes.get(2).description());
        assertEquals("新元素说明", nodes.get(4).description());
        assertEquals("提示词原说明", nodes.get(0).description());

        ToolDescriptor.Param status = changed.get(1).properties().get(0);
        assertEquals("enum", status.type());
        assertTrue(status.required());
        assertEquals(List.of("A", "B"), status.allowValues());
        assertEquals("示例", status.example());
        assertFalse(status.sensitive());
        assertEquals("编号原说明", changed.get(2).items().get(0).properties().get(0).description());
    }

    @Test
    @DisplayName("空白覆盖值回落基线，不会把空说明发给模型")
    void blankOverrideFallsBack() {
        List<ToolDescriptor.Param> changed = ParamTextTree.withDescriptions(tree(),
                Map.of("prompt", "   ", "filter.status", ""));
        assertEquals("提示词原说明", ParamTextTree.flatten(changed).get(0).description());
        assertEquals("状态原说明", ParamTextTree.flatten(changed).get(2).description());
    }

    @Test
    @DisplayName("注解改了参数结构后旧路径能被检出")
    void unknownPaths() {
        assertEquals(List.of("filter.gone", "tags[].missing"),
                ParamTextTree.unknownPaths(tree(),
                        List.of("prompt", "filter.gone", "tags[]", "tags[].missing")));
        assertTrue(ParamTextTree.unknownPaths(tree(), List.of("prompt", "filter.status")).isEmpty());
    }

    @Test
    @DisplayName("原树不被修改")
    void originalTreeUntouched() {
        List<ToolDescriptor.Param> original = tree();
        ParamTextTree.withDescriptions(original, Map.of("prompt", "改过的说明"));
        assertEquals("提示词原说明", ParamTextTree.flatten(original).get(0).description());
    }
}
