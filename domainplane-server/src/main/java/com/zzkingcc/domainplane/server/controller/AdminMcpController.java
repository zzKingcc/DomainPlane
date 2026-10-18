package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.mcp.McpApprovalPolicy;
import com.zzkingcc.domainplane.server.mcp.McpConnection;
import com.zzkingcc.domainplane.server.mcp.McpConnectionRuntime;
import com.zzkingcc.domainplane.server.mcp.McpConnectionRuntimeProvider;
import com.zzkingcc.domainplane.server.mcp.McpConnectionStore;
import com.zzkingcc.domainplane.server.mcp.McpOAuthClient;
import com.zzkingcc.domainplane.server.mcp.McpOAuthSessionStore;
import com.zzkingcc.domainplane.server.mcp.McpToolBridge;
import com.zzkingcc.domainplane.server.mcp.McpToolCatalog;
import com.zzkingcc.domainplane.server.mcp.McpTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理面：MCP 连接（资源）的增删改、连通性测试、工具目录查看与域绑定。
 *
 * 设计要点：
 *   • 连接是一等资源，与"向哪些域授权"解耦：删域只解绑、不删连接（与模型绑定同口径）。
 *   • 任何写操作（增改 / 启停 / 删 / 绑定）后都即时触发"连接就绪 → 盖章注册"，不依赖周期 reconcile。
 *   • 凭据（headers）只回 {@code hasHeaders} 布尔，明文只在测试连通性时本进程内短暂使用，
 *     不回显、不落库明文到响应里（落盘由 {@link McpConnectionStore} 负责）。
 *
 * 路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin")
public class AdminMcpController {

    private final McpConnectionStore store;
    private final McpConnectionRuntimeProvider runtimes;
    private final McpToolBridge bridge;
    private final McpOAuthSessionStore oauthSessions;
    private final McpOAuthClient oauthClient;

    public AdminMcpController(McpConnectionStore store,
                              McpConnectionRuntimeProvider runtimes,
                              McpToolBridge bridge,
                              McpOAuthSessionStore oauthSessions,
                              McpOAuthClient oauthClient) {
        this.store = store;
        this.runtimes = runtimes;
        this.bridge = bridge;
        this.oauthSessions = oauthSessions;
        this.oauthClient = oauthClient;
    }

    /** 连接列表（含运行期就绪状态、工具数、绑定域、最近错误） */
    @GetMapping("/mcp-connections")
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (McpConnection conn : store.list()) {
            McpConnectionRuntime runtime = runtimes.runtime(conn.name());
            boolean ready = runtime != null && runtime.isReady();
            int toolCount = ready ? runtime.catalog().size() : 0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", conn.name());
            item.put("url", conn.url());
            item.put("transport", conn.transport().name());
            item.put("enabled", conn.enabled());
            item.put("approvalPolicy", conn.approvalPolicy().name());
            item.put("callTimeoutMs", conn.callTimeoutMs());
            item.put("hasHeaders", !conn.headers().isEmpty());
            item.put("toolAllowlist", new ArrayList<>(conn.toolAllowlist()));
            item.put("remark", conn.remark());
            item.put("ready", ready);
            item.put("needsAuth", runtime != null
                    && runtime.status() == McpConnectionRuntime.Status.NEEDS_AUTH);
            item.put("status", runtime == null ? "DISCONNECTED" : runtime.status().name());
            item.put("toolCount", toolCount);
            item.put("lastError", runtime == null ? null : runtime.lastError());
            item.put("boundDomains", store.boundDomainsOf(conn.name()));
            // OAuth 客户端配置：非密字段回显（表单编辑用），secret 只回是否存在
            item.put("oauthClientId", conn.oauth().clientId());
            item.put("oauthRedirectUri", conn.oauth().redirectUri());
            item.put("oauthClientName", conn.oauth().clientName());
            item.put("oauthScope", conn.oauth().scope());
            item.put("hasOauthClientSecret", conn.oauth().hasClientSecret());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("connections", items);
        body.put("count", items.size());
        body.put("settingsFile", store.filePath());
        return body;
    }

    /**
     * 新增 / 更新一条连接：落盘后立即尝试建连 + 盖章（走连接就绪回调）。
     * 建连失败不阻断保存：配置先落盘，失败状态回显，可稍后重试或改地址。
     */
    @PostMapping("/mcp-connections")
    public Map<String, Object> save(@RequestBody(required = false) ConnectionBody body) {
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        String name = body.getName() == null ? "" : body.getName().trim();
        if (name.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "连接名不能为空");
        }
        McpTransport transport = parseTransport(body.getTransport());
        McpApprovalPolicy policy = parseApproval(body.getApprovalPolicy());
        Map<String, String> headers = (body.getHeaders() == null) ? Map.of() : body.getHeaders();
        Set<String> allowlist = (body.getToolAllowlist() == null) ? Set.of()
                : body.getToolAllowlist().stream().filter(h -> h != null && !h.isBlank())
                    .collect(Collectors.toSet());
        // 编辑既有连接时，headers 与 clientSecret 是"不回显"字段：留空 = 保持原值（防误清）
        McpConnection existing = store.get(name);
        if (existing != null) {
            if (headers.isEmpty() && !existing.headers().isEmpty()) {
                headers = existing.headers();
            }
            if ((body.getOauthClientSecret() == null || body.getOauthClientSecret().isBlank())
                    && existing.oauth().hasClientSecret()) {
                body.setOauthClientSecret(existing.oauth().clientSecret());
            }
        }
        McpConnection.OAuthConfig oauth = new McpConnection.OAuthConfig(
                body.getOauthClientId(), body.getOauthClientSecret(), body.getOauthRedirectUri(),
                body.getOauthClientName(), body.getOauthScope());
        McpConnection conn = new McpConnection(name,
                body.getUrl() == null ? "" : body.getUrl().trim(),
                transport, headers,
                body.getCallTimeoutMs() == null ? 0 : body.getCallTimeoutMs(),
                body.getEnabled() == null || body.getEnabled(),
                policy, allowlist,
                body.getRemark() == null ? "" : body.getRemark(),
                oauth);
        String failure = store.upsert(conn);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        // 落盘后即时建连 + 盖章（连接就绪回调会触发 bridge.publish）
        runtimes.connect(name);
        McpConnectionRuntime runtime = runtimes.runtime(name);
        boolean ready = runtime != null && runtime.isReady();
        boolean needsAuth = runtime != null
                && runtime.status() == McpConnectionRuntime.Status.NEEDS_AUTH;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", name);
        out.put("ready", ready);
        out.put("needsAuth", needsAuth);
        out.put("toolCount", ready ? runtime.catalog().size() : 0);
        out.put("lastError", runtime == null ? null : runtime.lastError());
        out.put("settingsFile", store.filePath());
        out.put("message", ready
                ? "已保存并接通 MCP 连接「" + name + "」，工具 " + runtime.catalog().size() + " 个"
                : needsAuth
                    ? "已保存 MCP 连接「" + name + "」，但该服务器要求管理员授权（OAuth）——"
                      + "请在连接卡片上点「授权」完成登录，授权前工具不可见"
                    : "已保存 MCP 连接「" + name + "」但建连失败（稍后可在列表点「测试」重试）："
                        + (runtime == null ? "未知错误" : runtime.lastError()));
        return out;
    }

    /** 删除连接：先摘工具、再断开、最后删配置与绑定（连接级联解绑，不删域） */
    @DeleteMapping("/mcp-connections")
    public Map<String, Object> delete(@RequestParam("name") String name) {
        // 先判存在再动手：名称拼错或重复点击时，原来的顺序会先把工具摘掉、再去报"不存在"，
        // 副作用已经发生且不回滚：连接还留在配置里，工具却全没了。
        if (store.get(name) == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "MCP 连接「" + name + "」不存在");
        }
        bridge.unpublish(name);
        runtimes.disconnect(name);
        if (!store.delete(name)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "MCP 连接「" + name + "」不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", name.trim());
        out.put("settingsFile", store.filePath());
        out.put("message", "已删除 MCP 连接「" + name.trim() + "」（其工具已摘除、绑定已解绑）");
        return out;
    }

    /** 启停：关 = 摘工具并断开；开 = 建连 + 盖章 */
    @PostMapping("/mcp-connections/{name}/toggle")
    public Map<String, Object> toggle(@PathVariable("name") String name,
                                      @RequestParam("enabled") boolean enabled) {
        String failure = store.setEnabled(name, enabled);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        if (enabled) {
            runtimes.connect(name);
        } else {
            bridge.unpublish(name);
            runtimes.disconnect(name);
        }
        McpConnectionRuntime runtime = runtimes.runtime(name);
        boolean ready = enabled && runtime != null && runtime.isReady();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", name.trim());
        out.put("enabled", enabled);
        out.put("ready", ready);
        out.put("lastError", runtime == null ? null : runtime.lastError());
        return out;
    }

    /**
     * 连通性测试：强制重连（initialize + tools/list），返回就绪状态与工具清单预览。
     * 成功即按当前绑定盖章（让模型立即可见）。
     */
    @PostMapping("/mcp-connections/{name}/test")
    public Map<String, Object> test(@PathVariable("name") String name) {
        if (!store.exists(name)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "MCP 连接「" + name + "」不存在");
        }
        runtimes.connect(name);
        McpConnectionRuntime runtime = runtimes.runtime(name);
        boolean ready = runtime != null && runtime.isReady();
        boolean needsAuth = runtime != null
                && runtime.status() == McpConnectionRuntime.Status.NEEDS_AUTH;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("ready", ready);
        out.put("needsAuth", needsAuth);
        out.put("status", runtime == null ? "DISCONNECTED" : runtime.status().name());
        out.put("lastError", runtime == null ? null : runtime.lastError());
        if (ready) {
            out.put("toolCount", runtime.catalog().size());
            out.put("tools", runtime.catalog().stream()
                    .map(t -> t.name()).collect(Collectors.toList()));
        }
        out.put("message", ready
                ? "连通成功，工具 " + (runtime != null ? runtime.catalog().size() : 0) + " 个"
                : needsAuth
                    ? "该 MCP 服务器要求管理员授权（OAuth）——请点「授权」完成登录"
                    : "连通失败：" + (runtime == null ? "未知错误" : runtime.lastError()));
        return out;
    }

    /** 某连接工具目录详情（原始名 / 映射名 / 副作用 / 是否被白名单放行） */
    @GetMapping("/mcp-connections/{name}/tools")
    public Map<String, Object> tools(@PathVariable("name") String name) {
        McpConnection conn = store.get(name);
        if (conn == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "MCP 连接「" + name + "」不存在");
        }
        McpConnectionRuntime runtime = runtimes.runtime(name);
        List<Map<String, Object>> items = new ArrayList<>();
        if (runtime != null && runtime.isReady()) {
            for (McpToolCatalog.CatalogItem it : McpToolCatalog.catalogView(runtime, conn)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("originalName", it.originalName());
                m.put("mappedName", it.mappedName());
                m.put("sideEffect", it.sideEffect());
                m.put("description", it.description());
                m.put("allowed", it.allowed());
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("name", name);
        out.put("ready", runtime != null && runtime.isReady());
        out.put("tools", items);
        out.put("count", items.size());
        return out;
    }

    /** 全量域绑定映射：域 → 连接列表，以及连接 → 域列表（管控台双向展示） */
    @GetMapping("/mcp-bindings")
    public Map<String, Object> bindings() {
        Map<String, List<String>> domainBindings = store.allDomainBindings();
        Map<String, List<String>> connectionBindings = new LinkedHashMap<>();
        for (McpConnection conn : store.list()) {
            connectionBindings.put(conn.name(), store.boundDomainsOf(conn.name()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("domainBindings", domainBindings);
        out.put("connectionBindings", connectionBindings);
        out.put("settingsFile", store.filePath());
        return out;
    }

    /**
     * 设置某域直接绑定的连接集合（全量覆盖；空列表 = 解绑该域）。
     * 写盘后即时重盖章（按当前全部绑定关系重新注册各连接的工具）。
     */
    @PutMapping("/mcp-bindings/{domain}")
    public Map<String, Object> bindDomain(@PathVariable("domain") String domain,
                                         @RequestBody(required = false) BindBody body) {
        List<String> connections = (body == null || body.getConnections() == null)
                ? List.of() : body.getConnections();
        String failure = store.bind(domain, connections);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        bridge.publishAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("domain", domain.trim());
        out.put("connections", connections);
        out.put("message", connections.isEmpty()
                ? "已解除域 " + domain.trim() + " 的全部 MCP 连接绑定"
                : "已将域 " + domain.trim() + " 绑定到 MCP 连接：" + connections);
        return out;
    }

    // ==================== OAuth 授权（连接级） ====================

    /**
     * 发起 OAuth 授权：探测/取认证挑战 → 资源元数据 → 授权服务器元数据 → 注册/复用 client_id
     * → PKCE + state 暂存 → 返回授权 URL（前端 window.location 跳转，管理员在浏览器完成登录与同意）。
     */
    @PostMapping("/mcp-connections/{name}/oauth/start")
    public Map<String, Object> oauthStart(@PathVariable("name") String name, HttpServletRequest request) {
        if (!store.exists(name)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "MCP 连接「" + name + "」不存在");
        }
        McpConnection conn = store.get(name);
        // ① 认证挑战：运行时记过就直接用；否则当场连一次探出来（保存时探测失败也会走到这）
        String challenge = null;
        McpConnectionRuntime rt = runtimes.runtime(name);
        if (rt != null && rt.authChallenge() != null) {
            challenge = rt.authChallenge();
        } else {
            runtimes.connect(name);
            rt = runtimes.runtime(name);
            if (rt != null) {
                challenge = rt.authChallenge();
            }
        }
        if (challenge == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "该连接没有返回认证挑战（WWW-Authenticate）。若只是令牌填错，请直接在编辑里更新请求头；"
                            + "只有服务器要求 OAuth 时才需要授权");
        }
        try {
            // ② RFC 9728 资源元数据 → RFC 8414 授权服务器元数据
            String resourceUrl = McpOAuthClient.parseResourceMetadataUrl(challenge);
            if (resourceUrl == null || resourceUrl.isBlank()) {
                resourceUrl = originOf(conn.url()) + "/.well-known/oauth-protected-resource";
            }
            McpOAuthClient.ProtectedResourceMetadata prm = oauthClient.discoverResource(resourceUrl);
            String authServer = (prm.authorizationServers() == null || prm.authorizationServers().isEmpty())
                    ? originOf(conn.url())
                    : prm.authorizationServers().get(0);
            McpOAuthClient.ServerMetadata sm = oauthClient.discoverServer(authServer);
            if (!sm.usable()) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "授权服务器元数据缺少 authorization / token 端点：" + authServer);
            }
            // 回调地址：连接可覆盖（服务商只认固定回调时用），否则取当前管控台地址
            McpConnection.OAuthConfig oc = conn.oauth();
            String redirect = oc.redirectUriOrNull() != null ? oc.redirectUriOrNull()
                    : redirectUri(name, request);
            // ③ client_id 解析：授权过 → 复用；配了手工 client_id → 跳过动态注册；否则 DCR
            String clientId = null;
            McpConnection.OAuthToken existing = store.tokenOf(name);
            if (existing != null && existing.clientId() != null && !existing.clientId().isBlank()) {
                clientId = existing.clientId();
            } else if (oc.hasClientId()) {
                clientId = oc.clientId();
            } else if (sm.registrationEndpoint() != null && !sm.registrationEndpoint().isBlank()) {
                try {
                    clientId = oauthClient.register(sm.registrationEndpoint(), oc.clientNameOrDefault(), redirect);
                } catch (Exception re) {
                    // 典型：腾讯官方 MCP 的准入只放行受信任平台，DCR 一律 403
                    throw new BaseException(ErrorCode.INVALID_PARAMETER,
                            "动态客户端注册被授权服务器拒绝：" + brief(re)
                                    + "。该服务器可能只接受受信任平台的注册——请改为在连接编辑里填写"
                                    + "预登记的 client_id（需先在服务商处申请应用并配好回调地址），"
                                    + "或修改 client 名称后重试");
                }
            } else {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "授权服务器不支持动态客户端注册，且本连接尚未登记 client_id，无法授权");
            }
            // ④ PKCE(S256) + state（state 一次性消费，防重放；verifier 跨请求暂存）
            String verifier = McpOAuthClient.generateVerifier();
            String codeChallenge = McpOAuthClient.s256Challenge(verifier);
            McpOAuthSessionStore.Session session = oauthSessions.create(name, verifier, clientId,
                    redirect, oc.hasScope() ? oc.scope() : null, sm.tokenEndpoint());
            // ⑤ 授权 URL：管理员浏览器跳转
            String url = oauthClient.authorizeUrl(sm, clientId, session.redirectUri(),
                    codeChallenge, session.state(), session.scope());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 0);
            out.put("authorizeUrl", url);
            out.put("message", "已生成授权链接，跳转后请完成登录与授权");
            return out;
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "发起 OAuth 授权失败："
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * OAuth 回调：校验 state → 授权码换 token → 加密同口径落盘 → 带 token 建连并盖章 →
     * 302 回管控台 MCP 页（auth=ok / auth=fail&reason=...，由前端提示）。
     */
    @GetMapping("/mcp-connections/{name}/oauth/callback")
    public void oauthCallback(@PathVariable("name") String name,
                              @RequestParam(value = "code", required = false) String code,
                              @RequestParam(value = "state", required = false) String state,
                              @RequestParam(value = "error", required = false) String error,
                              HttpServletResponse response) throws IOException {
        String back = "/console/mcp.html";
        if (error != null && !error.isBlank()) {
            response.sendRedirect(back + "?auth=fail&name=" + encUri(name) + "&reason=" + encUri(error));
            return;
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            response.sendRedirect(back + "?auth=fail&name=" + encUri(name)
                    + "&reason=" + encUri("回调缺少 code / state 参数"));
            return;
        }
        McpOAuthSessionStore.Session session = oauthSessions.consume(state);
        if (session == null || !session.connectionName().equals(name)) {
            response.sendRedirect(back + "?auth=fail&name=" + encUri(name)
                    + "&reason=" + encUri("state 无效或已过期，请重新发起授权"));
            return;
        }
        McpConnection conn = store.get(name);
        if (conn == null) {
            response.sendRedirect(back + "?auth=fail&name=" + encUri(name)
                    + "&reason=" + encUri("连接已被删除，请重新配置后再授权"));
            return;
        }
        try {
            McpOAuthClient.TokenSet ts = oauthClient.exchangeCode(session.tokenEndpoint(), code,
                    session.codeVerifier(), session.redirectUri(), session.clientId(),
                    conn.oauth().clientSecret());
            store.saveToken(name, new McpConnection.OAuthToken(ts.accessToken(), ts.refreshToken(),
                    ts.expiresAtEpochMs(), ts.scope(), session.clientId(), session.tokenEndpoint()));
            // 带 token 重新建连：就绪回调会触发盖章，绑定的域立即可见
            runtimes.connect(name);
            McpConnectionRuntime rt = runtimes.runtime(name);
            if (rt != null && rt.isReady()) {
                response.sendRedirect(back + "?auth=ok&name=" + encUri(name));
            } else {
                String reason = rt == null ? "建连失败" : String.valueOf(rt.lastError());
                response.sendRedirect(back + "?auth=fail&name=" + encUri(name)
                        + "&reason=" + encUri(reason));
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            response.sendRedirect(back + "?auth=fail&name=" + encUri(name) + "&reason=" + encUri(reason));
        }
    }

    // ==================== 内部 ====================

    /** 授权回调地址（与授权页同源，浏览器带着凭证 Cookie 回来） */
    private static String redirectUri(String name, HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        String portPart = (port == 80 && "http".equals(scheme)) || (port == 443 && "https".equals(scheme))
                ? "" : ":" + port;
        return scheme + "://" + request.getServerName() + portPart
                + "/admin/mcp-connections/" + name + "/oauth/callback";
    }

    private static String originOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return url;
        }
    }

    private static String encUri(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** 异常信息截断（授权流程要把服务商的错误描述带回给管理员，但别刷屏） */
    private static String brief(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String flat = m.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private static McpTransport parseTransport(String v) {
        if (v == null || v.isBlank()) {
            return McpTransport.STREAMABLE_HTTP;
        }
        try {
            return McpTransport.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return McpTransport.STREAMABLE_HTTP;
        }
    }

    private static McpApprovalPolicy parseApproval(String v) {
        if (v == null || v.isBlank()) {
            return McpApprovalPolicy.ALL;
        }
        try {
            return McpApprovalPolicy.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return McpApprovalPolicy.ALL;
        }
    }

    /** 新增 / 更新连接的请求体（headers 为可选认证头，如 Authorization） */
    @Data
    public static class ConnectionBody {
        private String name;
        private String url;
        private String transport;
        private Map<String, String> headers;
        private Integer callTimeoutMs;
        private Boolean enabled;
        private String approvalPolicy;
        private Set<String> toolAllowlist;
        private String remark;
        // ---- OAuth 客户端配置（连接级授权；secret 不回显，留空=保持原值）----
        private String oauthClientId;
        private String oauthClientSecret;
        private String oauthRedirectUri;
        private String oauthClientName;
        private String oauthScope;
    }

    /** 域绑定请求体 */
    @Data
    public static class BindBody {
        private List<String> connections;
    }
}
