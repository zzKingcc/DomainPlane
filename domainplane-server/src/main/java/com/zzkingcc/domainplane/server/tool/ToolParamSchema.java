package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 把工具实例声明的 JSON Schema 翻译成内核能用的两样东西
 * @author zzkingcc
 */
final class ToolParamSchema {

    private ToolParamSchema() {
    }

    /** 由根级 JSON Schema 生成喂给 LLM 的参数规格（递归保留嵌套结构） */
    static JsonObjectSchema toSpecificationSchema(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            return JsonObjectSchema.builder().build();
        }
        return objectSchema(parameters);
    }

    /**
     * 由根级 JSON Schema 派生描述符参数列表 —— 递归保留嵌套结构，与本地 Bean 工具口径一致。
     *
     * 必须递归保留嵌套结构：把嵌套拍成 object/array 会让"同一段工具代码"在本地部署与
     * 远端实例两种形态下得到不同的描述符（远端看不到 DTO 子字段，进而看不到声明在子字段上的
     * 示例 / 白名单 / 敏感）。上报报文里本来就带着 {@code properties}，没有理由丢掉。
     */
    static List<ToolDescriptor.Param> toParams(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            return List.of();
        }
        return toParams(parameters.path("properties"), textList(parameters.path("required")));
    }

    private static List<ToolDescriptor.Param> toParams(JsonNode properties, List<String> required) {
        List<ToolDescriptor.Param> params = new ArrayList<>();
        if (properties == null || !properties.isObject()) {
            return params;
        }
        Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode property = entry.getValue();
            List<String> allowValues = textList(property.path("enum"));
            String type = allowValues.isEmpty() ? typeName(property) : "enum";
            // 数组：从 items 还原元素结构（与本地工具 Param 树的 items 保持一致，不能拍平成字符串）
            List<ToolDescriptor.Param> items = List.of();
            if ("array".equals(type)) {
                JsonNode itemNode = property.path("items");
                if (itemNode.isObject()) {
                    items = List.of(itemParam(itemNode));
                }
            }
            params.add(new ToolDescriptor.Param(
                    entry.getKey(),
                    type,
                    property.path("description").asText("（未描述）"),
                    required.contains(entry.getKey()),
                    List.copyOf(allowValues),
                    property.path("example").asText(""),
                    property.path("x-sensitive").asBoolean(false),
                    toParams(property.path("properties"), textList(property.path("required"))),
                    items));
        }
        return params;
    }

    /** 数组元素的反向解析（无名字；递归保留其 object 子字段与嵌套数组） */
    private static ToolDescriptor.Param itemParam(JsonNode node) {
        List<String> allowValues = textList(node.path("enum"));
        String type = allowValues.isEmpty() ? typeName(node) : "enum";
        List<ToolDescriptor.Param> items = List.of();
        if ("array".equals(type)) {
            JsonNode itemNode = node.path("items");
            if (itemNode.isObject()) {
                items = List.of(itemParam(itemNode));
            }
        }
        return new ToolDescriptor.Param(
                null,
                type,
                node.path("description").asText("（未描述）"),
                false,
                List.copyOf(allowValues),
                node.path("example").asText(""),
                node.path("x-sensitive").asBoolean(false),
                toParams(node.path("properties"), textList(node.path("required"))),
                items);
    }

    private static JsonObjectSchema objectSchema(JsonNode node) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                builder.addProperty(entry.getKey(), element(entry.getValue()));
            }
        }
        List<String> required = textList(node.path("required"));
        if (!required.isEmpty()) {
            builder.required(required.toArray(new String[0]));
        }
        return builder.build();
    }

    private static JsonSchemaElement element(JsonNode node) {
        if (node == null || !node.isObject()) {
            return JsonStringSchema.builder().build();
        }
        String description = node.path("description").asText("");

        // enum 优先于 type：JSON Schema 里枚举通常是 {"type":"string","enum":[...]}
        List<String> allowValues = textList(node.path("enum"));
        if (!allowValues.isEmpty()) {
            return JsonEnumSchema.builder().description(description).enumValues(allowValues).build();
        }

        return switch (typeName(node)) {
            case "object" -> objectSchema(node);
            case "array" -> JsonArraySchema.builder()
                    .description(description)
                    .items(element(node.path("items")))
                    .build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /** JSON Schema 的 type 名（缺省按 string 处理，与本地工具对未知类型的兜底一致） */
    private static String typeName(JsonNode node) {
        JsonNode type = node.path("type");
        String name = type.isArray() ? type.path(0).asText("") : type.asText("");
        return name == null || name.isBlank() ? "string" : name;
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String text = node.asText("");
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }
}
