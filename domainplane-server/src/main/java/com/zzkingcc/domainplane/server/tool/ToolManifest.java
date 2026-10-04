package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 整包 manifest 的解析器 —— 把工具实例发来的 JSON 翻译成注册项
 * @author zzkingcc
 */
public final class ToolManifest {

    private static final Logger log = LoggerFactory.getLogger(ToolManifest.class);

    private ToolManifest() {
    }

    /**
     * 解析结果
     *
     * @param instanceId 实例标识
     * @param endpoint   工具调用回流地址
     * @param tools      本次声明的工具（已按"实例 + 地址"构造好注册项，执行器为远程执行器）
     * @param digest     manifest 内容摘要，供在线表做"未变快路径"判等
     */
    public record Parsed(String instanceId,
                         String endpoint,
                         List<ToolRegistry.Registered> tools,
                         String digest) {

        /** 本次声明的工具名集合 */
        public Set<String> toolNames() {
            return tools.stream()
                    .map(registered -> registered.descriptor().name())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    /**
     * 解析整包心跳
     *
     * @param body        请求体（已反序列化为 JSON 树）
     * @param remoteExecutor 远程工具的执行器（每个远程注册项都用它执行，见 {@code RemoteToolExecutor}）
     * @throws IllegalArgumentException 实例级错误（缺 instanceId / endpoint、manifest 不是数组）
     */
    public static Parsed parse(JsonNode body, ToolExecutor remoteExecutor) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }
        String instanceId = text(body, "instanceId");
        if (instanceId == null) {
            throw new IllegalArgumentException("缺少 instanceId");
        }
        String endpoint = text(body, "endpoint");
        if (endpoint == null) {
            throw new IllegalArgumentException("缺少 endpoint（工具调用回流地址）");
        }

        JsonNode manifest = body.path("manifest");
        if (!manifest.isArray()) {
            throw new IllegalArgumentException("manifest 必须是数组（本次声明的全量工具）");
        }

        Map<String, ToolRegistry.Registered> byName = new LinkedHashMap<>();
        Iterator<JsonNode> it = manifest.elements();
        while (it.hasNext()) {
            JsonNode entry = it.next();
            String name = text(entry, "name");
            if (name == null) {
                log.warn("[工具注册] 实例 {} 的 manifest 中有缺少 name 的条目，已跳过", instanceId);
                continue;
            }
            if (byName.containsKey(name)) {
                log.warn("[工具注册] 实例 {} 的 manifest 中工具名 {} 重复，按首条处理", instanceId, name);
                continue;
            }
            byName.put(name, toRegistered(instanceId, endpoint, entry, name, remoteExecutor));
        }

        List<ToolRegistry.Registered> tools = List.copyOf(byName.values());
        log.info("[工具注册] 实例 {}（{}）声明 {} 个工具: {}",
                instanceId, endpoint, tools.size(), byName.keySet());
        return new Parsed(instanceId, endpoint, tools, digest(manifest, instanceId));
    }

    private static ToolRegistry.Registered toRegistered(String instanceId,
                                                        String endpoint,
                                                        JsonNode entry,
                                                        String name,
                                                        ToolExecutor remoteExecutor) {
        JsonNode parameters = entry.path("parameters");
        List<ToolDescriptor.Param> params = ToolParamSchema.toParams(parameters);
        ToolSpecification specification = ToolSpecification.builder()
                .name(name)
                .description(entry.path("description").asText(""))
                .parameters(ToolParamSchema.toSpecificationSchema(parameters))
                .build();

        ToolDescriptor descriptor = new ToolDescriptor(
                name,
                entry.path("description").asText(""),
                entry.path("category").asText("default"),
                entry.path("version").asText("1.0.0"),
                sideEffect(entry.path("sideEffect")),
                entry.path("idempotent").asBoolean(true),
                entry.path("toModel").asBoolean(true),
                List.copyOf(params),
                domains(entry.path("domains"), name),
                approval(entry),
                // source 只作来源标识与排障，不参与路由（路由永远看地址列表）
                "remote://" + instanceId + "@" + endpoint);

        // 副本地址由注册表按 instanceId + endpoint 生成，这里传入的 endpoints 会被忽略
        return new ToolRegistry.Registered(descriptor, specification, remoteExecutor, List.of());
    }

    /**
     * 审批策略
     */
    private static ToolDescriptor.Approval approval(JsonNode entry) {
        boolean required = entry.path("requiresApproval").asBoolean(false);
        String mode = text(entry, "approvalMode");
        if (!required) {
            return ToolDescriptor.Approval.none();
        }
        String effectiveMode = mode == null || "NONE".equalsIgnoreCase(mode) ? "ALWAYS" : mode.toUpperCase();
        return new ToolDescriptor.Approval(
                effectiveMode,
                entry.path("approvalCondition").asText(""),
                entry.path("approvalReason").asText(""),
                textList(entry.path("approverRoles")),
                entry.path("approvalTimeoutSeconds").asInt(300));
    }

    /** 副作用等级：接受枚举名（READ/WRITE/DESTRUCTIVE），也接受布尔（false=READ、true=WRITE） */
    private static Tool.Effect sideEffect(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Tool.Effect.READ;
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? Tool.Effect.WRITE : Tool.Effect.READ;
        }
        String raw = node.asText("").trim().toUpperCase();
        for (Tool.Effect effect : Tool.Effect.values()) {
            if (effect.name().equals(raw)) {
                return effect;
            }
        }
        return Tool.Effect.READ;
    }

    /**
     * 域：去空白 + 去重 + 剔空，并<b>校验路径合法性</b>；非法域直接拒绝整包注册。
     *
     * <p>非法域不能只 warn 放过：这些字符串会进 {@code declaredProfiles} 并让入口放行，
     * 等于让外部报文凭空造出一个"可用的域"。空列表是合法的（= 挂根域）。</p>
     */
    private static List<String> domains(JsonNode node, String toolName) {
        List<String> domains = textList(node).stream().map(String::trim).distinct().toList();
        for (String domain : domains) {
            String reason = Domains.validatePath(domain);
            if (reason != null) {
                throw new IllegalArgumentException("工具 " + toolName + " 声明的域 '" + domain
                        + "' 不合法（" + reason + "）；须为从 " + Domains.DEFAULT
                        + " 出发的完整路径，如 default.sales");
            }
        }
        return domains;
    }

    /**
     * manifest 内容摘要 —— 供在线表判断"这次心跳的 manifest 和上次一样吗"。
     */
    private static String digest(JsonNode manifest, String instanceId) {
        StringBuilder canonical = new StringBuilder();
        Iterator<JsonNode> it = manifest.elements();
        while (it.hasNext()) {
            JsonNode entry = it.next();
            canonical.append(text(entry, "name")).append('\u0001')
                    .append(compact(entry.path("description"))).append('\u0001')
                    .append(entry.path("category").asText("")).append('\u0001')
                    .append(entry.path("version").asText("")).append('\u0001')
                    .append(textList(entry.path("domains"))).append('\u0001')
                    .append(compact(entry.path("parameters"))).append('\u0001')
                    .append(entry.path("sideEffect").asText("")).append('\u0001')
                    .append(entry.path("idempotent").asText("")).append('\u0001')
                    .append(entry.path("toModel").asText("")).append('\u0001')
                    .append(entry.path("requiresApproval").asText("")).append('\u0001')
                    .append(entry.path("approvalMode").asText("")).append('\u0001')
                    .append(compact(entry.path("approvalCondition"))).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // 理论上不会发生（SHA-256 是 JDK 必备算法）。真发生了就退化成"永不判等"，
            // 即每次心跳都走完整 diff——宁可多做功，也不可漏同步。
            log.error("[工具注册] 实例 {} 的 manifest 摘要计算失败，本次按'已变化'处理: {}",
                    instanceId, e.getMessage());
            return "digest-" + System.nanoTime();
        }
    }

    /** 压缩 JSON 文本（去掉空白），让摘要不受缩进 / 换行影响 */
    private static String compact(JsonNode node) {
        return node == null || node.isMissingNode() ? "" : node.toString().replaceAll("\\s+", "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        String text = value.isMissingNode() || value.isNull() ? null : value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                String text = element.asText("");
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }
}
