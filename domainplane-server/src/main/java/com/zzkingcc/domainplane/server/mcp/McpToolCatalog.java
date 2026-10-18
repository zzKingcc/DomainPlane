package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.tool.ToolParamSchema;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 工具目录 → 平台注册项的翻译器。
 *
 * 职责：tools/list 结果 → List<Registered>。复用 {@link ToolParamSchema} 做 JSON Schema 翻译，
 * 含命名空间前缀 + 字符规范化 + 去重 + 截断哈希、副作用等级推导、审批策略推导、白名单过滤。
 *
 * @author zzkingcc
 */
public final class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private static final int MAX_NAME = 64;

    private McpToolCatalog() {
    }

    /**
     * 把一个连接的工具目录翻译成注册项。
     *
     * @param runtime         已就绪的连接运行时（提供工具目录）
     * @param bindingDomains  该连接当前绑定的域（空 = 落 REVOKED 哨兵，对任何域都不可见）
     * @param connection      连接配置（提供前缀、审批策略、白名单、url）
     */
    public static List<ToolRegistry.Registered> toRegistered(McpConnectionRuntime runtime,
                                                            List<String> bindingDomains,
                                                            McpConnection connection,
                                                            McpConnectionRuntimeProvider runtimes) {
        List<String> bound = (bindingDomains == null || bindingDomains.isEmpty())
                ? List.of(ToolDescriptor.REVOKED_DOMAIN)
                : List.copyOf(bindingDomains);
        String instanceId = "mcp:" + connection.name();
        String source = "mcp://" + connection.name() + "@" + connection.url();

        Map<String, Integer> usedNames = new LinkedHashMap<>();
        List<ToolRegistry.Registered> out = new ArrayList<>();
        for (McpRpcClient.McpToolInfo tool : runtime.catalog()) {
            String originalName = tool.name();
            if (originalName == null || originalName.isBlank()) {
                continue;
            }
            if (!connection.allows(originalName)) {
                // 白名单裁剪：投毒防护 + 上下文瘦身
                continue;
            }
            String mappedName = normalizeName(connection.name(), originalName, usedNames);
            Tool.Effect sideEffect = deriveSideEffect(tool.annotations());
            boolean idempotent = tool.annotations() != null
                    && tool.annotations().path("idempotentHint").asBoolean(false);

            ToolDescriptor.Approval approval = connection.approvalPolicy()
                    .requiresApproval(sideEffect)
                    ? new ToolDescriptor.Approval("ALWAYS", "", "", List.of(), 0)
                    : ToolDescriptor.Approval.none();

            // JSON Schema 翻译（处理 $ref/$defs/allOf 等高级特性：发现即告警，best-effort 翻译）
            warnIfAdvancedSchema(originalName, tool.inputSchema());
            List<ToolDescriptor.Param> params = ToolParamSchema.toParams(tool.inputSchema());
            ToolSpecification specification = ToolSpecification.builder()
                    .name(mappedName)
                    .description(tool.description())
                    .parameters(ToolParamSchema.toSpecificationSchema(tool.inputSchema()))
                    .build();

            ToolDescriptor descriptor = new ToolDescriptor(
                    mappedName,
                    tool.description(),
                    "mcp",
                    "1.0.0",
                    sideEffect,
                    idempotent,
                    true,
                    List.copyOf(params),
                    bound,
                    approval,
                    source);

            // 执行器只持有连接名 + 原始工具名，每次调用经 runtimes 解析活的客户端
            McpToolExecutor executor = new McpToolExecutor(runtimes, connection.name(), originalName);
            // endpoints 留空：replaceInstanceTools 会按 instanceId + url 生成非本地副本
            out.add(new ToolRegistry.Registered(descriptor, specification, executor, List.of()));
        }
        log.info("[MCP 目录] 连接 {} 翻译出 {} 个注册项（绑定域 {}）", connection.name(), out.size(), bound);
        return out;
    }

    /** 副作用等级：readOnlyHint→READ，destructiveHint→DESTRUCTIVE，缺省→WRITE（保守默认，外部不可信） */
    private static Tool.Effect deriveSideEffect(JsonNode annotations) {
        if (annotations == null || annotations.isNull()) {
            return Tool.Effect.WRITE;
        }
        if (annotations.path("destructiveHint").asBoolean(false)) {
            return Tool.Effect.DESTRUCTIVE;
        }
        if (annotations.path("readOnlyHint").asBoolean(false)) {
            return Tool.Effect.READ;
        }
        return Tool.Effect.WRITE;
    }

    /** 命名规范化：mcp__<连接>__<原始名>，非法字符替换为 _，超长截断加哈希，连接内去重 */
    private static String normalizeName(String connectionName, String originalName, Map<String, Integer> used) {
        String prefix = "mcp__" + connectionName + "__";
        StringBuilder norm = new StringBuilder();
        for (char c : originalName.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.') {
                norm.append(c);
            } else {
                norm.append('_');
            }
        }
        String base = norm.toString();
        String candidate = prefix + base;
        if (candidate.length() > MAX_NAME) {
            // 截断原始名再追加短哈希
            int room = MAX_NAME - prefix.length() - 9;
            if (room < 1) {
                room = 1;
            }
            String hash = shortHash(originalName);
            base = base.length() > room ? base.substring(0, room) : base;
            candidate = prefix + base + "_" + hash;
        }
        // 连接内去重：映射结果重复则追加 _2 / _3
        if (used.containsKey(candidate)) {
            int n = used.get(candidate) + 1;
            used.put(candidate, n);
            candidate = candidate + "_" + n;
        } else {
            used.put(candidate, 1);
        }
        return candidate;
    }

    /** 检测高级 JSON Schema 特性：发现即告警（ToolParamSchema 会忽略它们，可能产出空参数） */
    private static void warnIfAdvancedSchema(String toolName, JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return;
        }
        String text = schema.toString();
        for (String key : new String[]{"\"$ref\"", "\"$defs\"", "\"allOf\"", "\"anyOf\"", "\"oneOf\""}) {
            if (text.contains(key)) {
                log.warn("[MCP 目录] 工具 {} 的 inputSchema 含高级特性 {}，当前翻译会忽略它（参数可能不完整），"
                        + "建议后续支持 $ref 内联解析", toolName, key);
                return;
            }
        }
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0000";
        }
    }

    /** 列出某连接工具目录里"原始名 → 映射名"的对照（供管控台展示排障） */
    public static Map<String, String> nameMapping(McpConnectionRuntime runtime, McpConnection connection) {
        Map<String, String> map = new LinkedHashMap<>();
        Map<String, Integer> used = new LinkedHashMap<>();
        for (McpRpcClient.McpToolInfo tool : runtime.catalog()) {
            if (tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            map.put(tool.name(), normalizeName(connection.name(), tool.name(), used));
        }
        return map;
    }

    /** 管控台用的工具目录视图项 */
    public record CatalogItem(String originalName, String mappedName, String sideEffect,
                              String description, boolean allowed) {
    }

    /** 工具目录视图：原始名 / 映射名 / 副作用等级 / 描述 / 是否被白名单放行（供管控台展示排障） */
    public static List<CatalogItem> catalogView(McpConnectionRuntime runtime, McpConnection connection) {
        Map<String, Integer> used = new LinkedHashMap<>();
        List<CatalogItem> out = new ArrayList<>();
        for (McpRpcClient.McpToolInfo tool : runtime.catalog()) {
            if (tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            boolean allowed = connection.allows(tool.name());
            String mapped = allowed ? normalizeName(connection.name(), tool.name(), used) : null;
            out.add(new CatalogItem(tool.name(), mapped,
                    deriveSideEffect(tool.annotations()).name(), tool.description(), allowed));
        }
        return out;
    }
}
