package com.zzkingcc.domainplane.runtime.tool;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖参数说明后重建 specification 的前提：渲染器是纯函数，只认 Param 树，
 * 换掉 description 不会顺手改掉 schema 的形状。整棵 schema 序列化成字符串比对，
 * 比逐个 getter 断言更严 —— 多一个 required、少一个子字段都会被抓到。
 */
class ParamSchemaRendererTest {

    private static ToolDescriptor.Param p(String name, String type, String desc,
                                          List<ToolDescriptor.Param> props,
                                          List<ToolDescriptor.Param> items) {
        return new ToolDescriptor.Param(name, type, desc, true, List.of(), "", false, props, items);
    }

    private static List<ToolDescriptor.Param> tree() {
        ToolDescriptor.Param status = p("status", "string", "状态原说明", List.of(), List.of());
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

    private static String json(Object value) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("schema 序列化失败", e);
        }
    }

    @Test
    @DisplayName("只换说明时重建后的 schema 形状完全一致")
    void shapeUnchanged() {
        List<ToolDescriptor.Param> base = tree();
        String before = json(ParamSchemaRenderer.toJsonSchema(base));

        List<ToolDescriptor.Param> changed = ParamTextTree.withDescriptions(base,
                Map.of("prompt", "新提示词说明"));
        String after = json(ParamSchemaRenderer.toJsonSchema(changed));

        assertTrue(after.contains("新提示词说明"));
        assertEquals(before, after.replace("新提示词说明", "提示词原说明"),
                "除说明文字外，属性、required、嵌套结构一个都不该变");
    }

    @Test
    @DisplayName("嵌套子字段与数组元素的说明都进 schema")
    void nestedDescriptionsReachSchema() {
        List<ToolDescriptor.Param> changed = ParamTextTree.withDescriptions(tree(),
                Map.of("filter.status", "新状态说明",
                        "tags[]", "新元素说明",
                        "tags[].sku", "新编号说明"));
        String schema = json(ParamSchemaRenderer.toJsonSchema(changed));

        assertTrue(schema.contains("新状态说明"));
        assertTrue(schema.contains("新元素说明"));
        assertTrue(schema.contains("新编号说明"));
        assertTrue(schema.contains("过滤条件原说明"), "父级说明不该被子级覆盖");
    }

    @Test
    @DisplayName("空参数树产出空对象 schema 而不是 null")
    void emptyTree() {
        JsonObjectSchema schema = ParamSchemaRenderer.toJsonSchema(List.of());
        assertNotNull(schema);
        assertTrue(json(schema).contains("properties"));
    }

    @Test
    @DisplayName("required 由 Param 树决定，覆盖说明不影响必填")
    void requiredStable() {
        String before = json(ParamSchemaRenderer.toJsonSchema(tree()));
        String after = json(ParamSchemaRenderer.toJsonSchema(
                ParamTextTree.withDescriptions(tree(), Map.of("prompt", "改了说明"))));
        assertEquals(before.replace("提示词原说明", "改了说明"), after);
    }
}
