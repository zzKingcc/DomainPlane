package com.zzkingcc.domainplane.toolprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个工具的声明（实例侧）—— 会被原样发给服务端，成为服务端注册表里的工具元数据
 *
 * @author zzkingcc
 * @param name             工具名（全局唯一；同名 = 同一逻辑工具的多个副本）
 * @param description      给 LLM 的用途说明（写清"何时调用 / 何时不要调用"比参数描述更重要）
 * @param category         管理页分类（不参与任何过滤）
 * @param version          语义化版本
 * @param domains          可用域（授权边界）。每项都是从根域出发的完整路径，
 *                         判定按累加（命中该域或其任一祖先即见）；留空 = 挂根域 = 全树可见；无通配
 * @param sideEffect       {@code READ} / {@code WRITE} / {@code DESTRUCTIVE}
 * @param idempotent       是否幂等（决定失败后能否自动重试）
 * @param toModel          结果是否回填 LLM
 * @param requiresApproval 是否需要人工二次确认
 * @param approvalMode     {@code ALWAYS}（新注解只保留 {@code NONE} / {@code ALWAYS}）
 * @param approvalReason   展示给审批人的原因
 * @param parameters       参数 JSON Schema（{@code {"type":"object","properties":{...},"required":[...]}}）
 */
public record ToolSpec(String name,
                       String description,
                       String category,
                       String version,
                       List<String> domains,
                       String sideEffect,
                       boolean idempotent,
                       boolean toModel,
                       boolean requiresApproval,
                       String approvalMode,
                       String approvalReason,
                       JsonNode parameters) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        domains = domains == null ? List.of() : List.copyOf(domains);
        category = blankTo(category, "default");
        version = blankTo(version, "1.0.0");
        sideEffect = blankTo(sideEffect, "READ");
        approvalMode = approvalMode == null ? "" : approvalMode;
        approvalReason = approvalReason == null ? "" : approvalReason;
    }

    /** 最简声明：只有名字与用途（参数为空对象） */
    public static ToolSpec of(String name, String description) {
        return of(name, description, MAPPER.createObjectNode());
    }

    public static ToolSpec of(String name, String description, JsonNode parameters) {
        return new ToolSpec(name, description, "default", "1.0.0", List.of(), "READ",
                true, true, false, "", "", parameters);
    }

    /**
     * 便捷构造参数 schema（省掉手写 {@code {"type":"object",...}} 的样板）
     *
     * @param properties 属性名 → 属性 schema（如 {@code Map.of("orderNo", Map.of("type","string"))}）
     * @param required   必填属性名
     */
    public static JsonNode schema(Map<String, Object> properties, String... required) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.set("properties", MAPPER.valueToTree(properties == null ? Map.of() : properties));
        if (required != null && required.length > 0) {
            root.set("required", MAPPER.valueToTree(List.of(required)));
        }
        return root;
    }

    /**
     * 声明可用域（授权边界，覆盖式设置）。每项为完整路径，判定按累加；
     * 留空 = 挂根域 = 全树可见；无通配。
     */
    public ToolSpec withDomains(String... domains) {
        return new ToolSpec(name, description, category, version, List.of(domains), sideEffect,
                idempotent, toModel, requiresApproval, approvalMode, approvalReason, parameters);
    }

    public ToolSpec withCategory(String category) {
        return new ToolSpec(name, description, category, version, domains, sideEffect,
                idempotent, toModel, requiresApproval, approvalMode, approvalReason, parameters);
    }

    public ToolSpec withSideEffect(String sideEffect) {
        return new ToolSpec(name, description, category, version, domains, sideEffect,
                idempotent, toModel, requiresApproval, approvalMode, approvalReason, parameters);
    }

    public ToolSpec withIdempotent(boolean idempotent) {
        return new ToolSpec(name, description, category, version, domains, sideEffect,
                idempotent, toModel, requiresApproval, approvalMode, approvalReason, parameters);
    }

    public ToolSpec withToModel(boolean toModel) {
        return new ToolSpec(name, description, category, version, domains, sideEffect,
                idempotent, toModel, requiresApproval, approvalMode, approvalReason, parameters);
    }

    /** 需要人工确认（有副作用的工具应配它；{@code mode} 留空按 ALWAYS 处理） */
    public ToolSpec withApproval(String mode, String reason) {
        return new ToolSpec(name, description, category, version, domains, sideEffect,
                idempotent, toModel, true, mode, reason, parameters);
    }

    /** 序列化成一条 manifest 条目 */
    public Map<String, Object> toManifest() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("description", description == null ? "" : description);
        entry.put("category", category);
        entry.put("version", version);
        entry.put("domains", domains);
        entry.put("sideEffect", sideEffect);
        entry.put("idempotent", idempotent);
        entry.put("toModel", toModel);
        entry.put("requiresApproval", requiresApproval);
        entry.put("approvalMode", approvalMode);
        entry.put("approvalReason", approvalReason);
        entry.put("parameters", parameters == null ? MAPPER.createObjectNode() : parameters);
        return entry;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
