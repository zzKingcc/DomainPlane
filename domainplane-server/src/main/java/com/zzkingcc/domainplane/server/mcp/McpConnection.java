package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条 MCP 连接配置 —— 平台侧把"一个 MCP server"当作一种资源来管理。
 *
 * 一个连接 = 一个 MCP 实例，provider id 为 {@code mcp:<name>}；其 {@code tools/list}
 * 拉到的工具经命名规范化后注册进 ToolRegistry，域绑定 = 把"已绑定的域"盖章进这些工具的
 * {@code domains} 字段（见 {@link McpToolBridge}）。
 *
 * @author zzkingcc
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpConnection(

        /** 连接名（唯一，同时是工具命名空间前缀）；[A-Za-z0-9_-]{1,64}，禁止包含 "__" */
        String name,

        /** MCP server 端点（Streamable HTTP / legacy SSE 的基础地址） */
        String url,

        /** 传输类型（v1 仅 STREAMABLE_HTTP 被实装，LEGACY_SSE 预留） */
        McpTransport transport,

        /** 自定义请求头（含认证，如 Authorization: Bearer xxx）；落盘为明文，出口脱敏 */
        Map<String, String> headers,

        /** 单次 tools/call 超时（毫秒）；非 local 工具不经隔离层，此值是占住编排线程的上限 */
        int callTimeoutMs,

        /** 启用开关：关掉 = 不连、不注册、现有工具摘除 */
        boolean enabled,

        /** 连接级审批策略（工具级注解与策略共同决定是否需要人工批准） */
        McpApprovalPolicy approvalPolicy,

        /** 启用的工具原始名集合（空 = 全部启用）；用于投毒防护与上下文瘦身 */
        Set<String> toolAllowlist,

        /** 备注 */
        String remark,

        /** OAuth 客户端配置（连接级授权）；旧文件无此字段时归一化为 NONE，不参与判空 */
        OAuthConfig oauth) {

    public McpConnection {
        name = name == null ? "" : name.trim();
        url = url == null ? "" : url.trim();
        transport = transport == null ? McpTransport.STREAMABLE_HTTP : transport;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        callTimeoutMs = callTimeoutMs <= 0 ? 30000 : callTimeoutMs;
        // 与 oauth 同口径：旧文件 / 手工编辑的 JSON 漏了 approvalPolicy 时不能是 null，
        // McpToolCatalog 注册时直接调 requiresApproval()，null 会 NPE 炸掉整条连接的注册。
        // 回落 ALL 而不是 NONE：外部 server 自报的 readOnlyHint 不可信，缺配置 = 最严档。
        approvalPolicy = approvalPolicy == null ? McpApprovalPolicy.ALL : approvalPolicy;
        toolAllowlist = toolAllowlist == null ? Set.of() : Set.copyOf(toolAllowlist);
        remark = remark == null ? "" : remark;
        oauth = oauth == null ? OAuthConfig.NONE : oauth;
    }

    /** 无 OAuth 配置的旧构造（测试与内部便捷构造用） */
    public McpConnection(String name, String url, McpTransport transport, Map<String, String> headers,
                         int callTimeoutMs, boolean enabled, McpApprovalPolicy approvalPolicy,
                         Set<String> toolAllowlist, String remark) {
        this(name, url, transport, headers, callTimeoutMs, enabled, approvalPolicy, toolAllowlist, remark,
                OAuthConfig.NONE);
    }

    /** 是否允许该原始工具名（白名单为空 = 全部允许） */
    public boolean allows(String originalToolName) {
        return toolAllowlist.isEmpty() || toolAllowlist.contains(originalToolName);
    }

    /** 名称合法性（返回原因；null = 合法） */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "连接名不能为空";
        }
        String n = name.trim();
        if (n.length() > 64) {
            return "连接名最长 64 个字符，当前 " + n.length() + " 个";
        }
        if (!n.matches("[A-Za-z0-9_-]+")) {
            return "连接名只允许字母、数字、下划线、连字符：" + n;
        }
        if (n.contains("__")) {
            return "连接名禁止包含连续双下划线（与工具名前缀 mcp__<连接>__<工具> 的约定冲突）：" + n;
        }
        return null;
    }

    /** 校验完整字段；返回原因（null = 合法） */
    public String validate() {
        String nameFailure = validateName(name);
        if (nameFailure != null) {
            return nameFailure;
        }
        String urlFailure = McpConnectionStore.checkBaseUrl(url);
        if (urlFailure != null) {
            return urlFailure;
        }
        return null;
    }

    /**
     * OAuth 客户端配置（连接级）：授权流程里"我们是谁、从哪回来"。
     *
     * <p>为什么需要手工 client_id：RFC 7591 动态注册是<b>可选项</b>，腾讯官方 MCP 的 OAuth
     * 准入只放行少量受信任平台（社区实测接受 client 名含 Codex / Claude / WorkBuddy / CodeBuddy，
     * 清单不公开且可能变），其它一律 403 {@code redirect_uri does not match any trusted platform}。
     * 被拒时的正路是去服务商处预登记应用拿 client_id（+secret），填在这里跳过动态注册。</p>
     *
     * <p>clientSecret 与 headers 同口径明文落盘（加密已登记为后续专项）。</p>
     */
    public record OAuthConfig(String clientId, String clientSecret, String redirectUri,
                              String clientName, String scope) {

        /** 空配置（默认）：全部走动态注册 */
        public static final OAuthConfig NONE = new OAuthConfig("", "", "", "", "");

        /** DCR 用的 client 名：留空时用平台名 WorkBuddy（腾讯准入实测接受，随时可能变，故可配） */
        public static final String DEFAULT_CLIENT_NAME = "WorkBuddy";

        public OAuthConfig {
            clientId = clientId == null ? "" : clientId.trim();
            clientSecret = clientSecret == null ? "" : clientSecret.trim();
            redirectUri = redirectUri == null ? "" : redirectUri.trim();
            clientName = clientName == null ? "" : clientName.trim();
            scope = scope == null ? "" : scope.trim();
        }

        /** 是否配了手工 client_id（有则跳过动态注册） */
        public boolean hasClientId() {
            return !clientId.isBlank();
        }

        public boolean hasClientSecret() {
            return !clientSecret.isBlank();
        }

        /** 授权范围（空 = 不传 scope，用服务器默认） */
        public boolean hasScope() {
            return !scope.isBlank();
        }

        /** DCR client 名：空则回落平台名 */
        public String clientNameOrDefault() {
            return clientName.isBlank() ? DEFAULT_CLIENT_NAME : clientName;
        }

        /** 覆盖回调地址：空则用请求推导的当前管控台地址 */
        public String redirectUriOrNull() {
            return redirectUri.isBlank() ? null : redirectUri;
        }
    }

    /**
     * OAuth token（连接级）：管理员授权一次、平台代持、该连接的所有域共用。
     * 与 headers 同口径明文落盘（加密已登记为后续专项），refresh_token 是长有效期凭据，
     * 落地加密前不应接入不信任的 MCP server。
     */
    public record OAuthToken(String accessToken, String refreshToken, long expiresAtEpochMs,
                             String scope, String clientId, String tokenEndpoint) {

        private static final long REFRESH_MARGIN_MS = 60 * 1000;

        /** 已过期（expiresAt 缺省 0 时视为已过期，交由刷新流程处理） */
        public boolean expired() {
            return System.currentTimeMillis() >= expiresAtEpochMs;
        }

        /** 进入过期前 60s 刷新窗口即视为"将过期" */
        public boolean expiringSoon() {
            return System.currentTimeMillis() + REFRESH_MARGIN_MS >= expiresAtEpochMs;
        }
    }

    /** 落盘结构（顶层 connections + domainBindings + oauthTokens） */
    public record FileShape(List<McpConnection> connections, Map<String, List<String>> domainBindings,
                            Map<String, OAuthToken> oauthTokens) {
        public FileShape {
            connections = connections == null ? List.of() : List.copyOf(connections);
            domainBindings = domainBindings == null ? Map.of() : Map.copyOf(domainBindings);
            oauthTokens = oauthTokens == null ? Map.of() : Map.copyOf(oauthTokens);
        }

        /** 无 token 的旧文件 / 调用方兼容构造 */
        public FileShape(List<McpConnection> connections, Map<String, List<String>> domainBindings) {
            this(connections, domainBindings, Map.of());
        }
    }
}
