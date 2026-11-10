package com.zzkingcc.domainplane.runtime.tool;

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
import java.util.List;

/**
 * 由 {@link ToolDescriptor.Param} 树生成喂给模型的 JSON Schema。
 *
 * <p>原来这段是 {@link AnnotatedToolScanner} 的私有方法。管控台要改参数说明时必须重建
 * specification，若另写一份渲染逻辑，两份必然漂移 —— 漂移的样子是「页面上改了参数说明，
 * 模型看到的 schema 换了形状」。所以抽成唯一出口：启动扫描与运行期覆盖都走这里。</p>
 *
 * <p>重建之所以安全：本地 Bean 工具的 specification 本来就是这棵树生成的，
 * 用同一个渲染器再跑一遍，除 description 外形状必然一致（远程工具的 schema 来自上游
 * JSON，不走这条路，也不允许覆盖）。</p>
 */
public final class ParamSchemaRenderer {

    private ParamSchemaRenderer() {
    }

    public static JsonObjectSchema toJsonSchema(List<ToolDescriptor.Param> params) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();
        if (params == null) {
            return builder.build();
        }
        for (ToolDescriptor.Param param : params) {
            if (param.name() == null || param.name().isBlank()) {
                continue;
            }
            builder.addProperty(param.name(), toSchemaElement(param));
            if (param.required()) {
                required.add(param.name());
            }
        }
        if (!required.isEmpty()) {
            builder.required(required.toArray(new String[0]));
        }
        return builder.build();
    }

    private static JsonSchemaElement toSchemaElement(ToolDescriptor.Param param) {
        String description = param.description();
        return switch (param.type()) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            // DTO / record：把子字段递归展开成嵌套 object，模型才知道该构造什么
            case "object" -> objectSchema(param);
            case "enum" -> JsonEnumSchema.builder()
                    .description(description)
                    .enumValues(param.allowValues())
                    .build();
            case "array" -> {
                // 数组元素结构必须展开，否则 List<DTO> 会被模型看成字符串数组
                JsonSchemaElement item = param.items().isEmpty()
                        ? JsonStringSchema.builder().build()
                        : toSchemaElement(param.items().get(0));
                yield JsonArraySchema.builder().description(description).items(item).build();
            }
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /**
     * 嵌套对象：子字段递归展开；没有子字段时给一个「空对象」而不是退化成字符串，
     * 退化成字符串会让模型产出字符串，反序列化到 DTO 必然失败。
     */
    private static JsonSchemaElement objectSchema(ToolDescriptor.Param param) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
                .description(param.description());
        List<String> required = new ArrayList<>();
        for (ToolDescriptor.Param child : param.properties()) {
            builder.addProperty(child.name(), toSchemaElement(child));
            if (child.required()) {
                required.add(child.name());
            }
        }
        if (!required.isEmpty()) {
            builder.required(required.toArray(new String[0]));
        }
        return builder.build();
    }
}
