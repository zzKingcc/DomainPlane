package com.zzkingcc.domainplane.server.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;

/**
 * 所有 MCP 连接运行时的持有者（按连接名索引）。
 *
 * 启动后异步建连所有启用的连接（不阻塞平台启动，连接失败只记状态）。连接是运行期联上的、
 * 配置是持久化的，两者谁先发生都可能，建连就绪或绑定变更都会通知 {@link McpToolBridge} 重新盖章。
 *
 * OAuth 连接级认证的三条路径都汇聚到这里：
 * <ul>
 *   <li>建连前 {@link #ensureFreshToken}：token 已过期必须刷新成功才放行（失败留 NEEDS_AUTH）；
 *       未过期但进刷新窗口只 best-effort 刷新，失败不阻断（旧 token 还能用，真失效时执行器兜）；</li>
 *   <li>建连中收到认证挑战（401 + WWW-Authenticate）→ 运行时置 {@code NEEDS_AUTH}，等管理员授权；</li>
 *   <li>运行中 401 由 {@link McpToolExecutor} 调 {@link #refreshToken} 重试，刷新不了调
 *       {@link #markNeedsAuth}。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@Component
public class McpConnectionRuntimeProvider {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionRuntimeProvider.class);

    private final McpConnectionStore store;
    private final McpOAuthClient oauth;
    private final Map<String, McpConnectionRuntime> runtimes = new ConcurrentHashMap<>();

    /** 连接就绪后的回调（由 McpToolBridge 注入为"盖章注册"），避免与 Bridge 形成构造环 */
    private volatile Consumer<String> afterConnect;

    public McpConnectionRuntimeProvider(McpConnectionStore store, McpOAuthClient oauth) {
        this.store = store;
        this.oauth = oauth;
    }

    /** 注入连接就绪回调（Bridge 在构造后设置，避免构造期循环依赖） */
    public void setAfterConnect(Consumer<String> afterConnect) {
        this.afterConnect = afterConnect;
    }

    /** 启动后异步建连所有启用的连接（后台线程，不阻塞启动） */
    @PostConstruct
    public void start() {
        Thread starter = new Thread(this::connectAll, "mcp-starter");
        starter.setDaemon(true);
        starter.start();
    }

    /** 建连所有启用的连接 */
    public void connectAll() {
        for (McpConnection conn : store.list()) {
            if (conn.enabled()) {
                connect(conn.name());
            }
        }
    }

    /** 建连 / 重建单个连接；就绪后触发盖章回调 */
    public void connect(String connectionName) {
        MetricsRegistry.mcpReconnect();
        McpConnection conn = store.get(connectionName);
        if (conn == null) {
            runtimes.remove(connectionName);
            return;
        }
        if (!ensureFreshToken(connectionName)) {
            // token 已过期且刷新失败：不进建连（必然 401），直接留授权门禁
            McpConnectionRuntime blocked = runtimes.computeIfAbsent(connectionName,
                    n -> new McpConnectionRuntime(n, conn));
            blocked.markNeedsAuth("OAuth token 已过期且刷新失败，需要管理员重新授权");
            return;
        }
        McpConnectionRuntime runtime = runtimes.computeIfAbsent(connectionName,
                n -> new McpConnectionRuntime(n, conn));
        runtime.connect(() -> effectiveHeaders(connectionName));
        if (runtime.isReady() && afterConnect != null) {
            afterConnect.accept(connectionName);
        }
    }

    /**
     * 有效请求头：静态 headers + 未过期的 OAuth Bearer。
     * 每次请求现取，token 刷新后不重建 client 即生效（与执行器 resolve 活连接同一思路）。
     */
    public Map<String, String> effectiveHeaders(String connectionName) {
        McpConnection conn = store.get(connectionName);
        if (conn == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>(conn.headers());
        McpConnection.OAuthToken token = store.tokenOf(connectionName);
        if (token != null && !token.expired()) {
            headers.put("Authorization", "Bearer " + token.accessToken());
        }
        return headers;
    }

    /**
     * 确保 token 可再战：无 token（静态头路径）→ true；未过期 → true（进刷新窗口则 best-effort
     * 刷新，失败不阻断）；已过期 → 必须刷新成功。
     */
    public boolean ensureFreshToken(String connectionName) {
        McpConnection.OAuthToken token = store.tokenOf(connectionName);
        if (token == null) {
            return true;
        }
        if (!token.expired()) {
            if (token.expiringSoon()) {
                refreshToken(connectionName); // best-effort：旧 token 还能用，不阻断
            }
            return true;
        }
        return refreshToken(connectionName);
    }

    /**
     * 刷新 token；无 refresh_token 或刷新失败返回 false（不抛）。
     *
     * <p>"读 token → 网络往返 → 写回"必须整体互斥：reconcile 线程与工具执行的 401 重试
     * 会并发调用它，两条都在往返后写回，后写的那条覆盖先写的。对启用 refresh_token 轮换的
     * 授权服务器，被覆盖掉的那条已经作废，该连接从此只能人工重新授权。
     * 刷新是低频动作（通常小时级一次），串行化的代价可以忽略。</p>
     */
    public synchronized boolean refreshToken(String connectionName) {
        McpConnection.OAuthToken token = store.tokenOf(connectionName);
        if (token == null || token.refreshToken() == null || token.refreshToken().isBlank()) {
            return false;
        }
        // 等锁期间别人可能已经刷好了：再核一次，别把刚生效的 token 又换一遍
        if (!token.expired() && !token.expiringSoon()) {
            return true;
        }
        McpConnection conn = store.get(connectionName);
        String secret = conn == null ? "" : conn.oauth().clientSecret();
        try {
            McpOAuthClient.TokenSet ts = oauth.refresh(token.tokenEndpoint(),
                    token.refreshToken(), token.clientId(), secret);
            // 服务器不返还新 refresh_token 是常见行为：保留旧的，否则续一次就断链
            String refresh = ts.refreshToken() == null || ts.refreshToken().isBlank()
                    ? token.refreshToken() : ts.refreshToken();
            store.saveToken(connectionName, new McpConnection.OAuthToken(
                    ts.accessToken(), refresh, ts.expiresAtEpochMs(), ts.scope(),
                    token.clientId(), token.tokenEndpoint()));
            log.info("[MCP 运行时] 连接 {} 的 OAuth token 已刷新", connectionName);
            return true;
        } catch (Exception e) {
            log.warn("[MCP 运行时] 连接 {} 的 OAuth token 刷新失败: {}", connectionName, e.getMessage());
            return false;
        }
    }

    /** 标记需要授权（运行中 401 且刷新失败时由执行器调用）；工具在下次 reconcile 时摘除 */
    public void markNeedsAuth(String connectionName, String challenge) {
        McpConnectionRuntime runtime = runtimes.get(connectionName);
        if (runtime != null) {
            runtime.markNeedsAuth(challenge);
        }
    }

    /** 断开并移除一个连接的运行时 */
    public void disconnect(String connectionName) {
        McpConnectionRuntime runtime = runtimes.remove(connectionName);
        if (runtime != null) {
            runtime.closeQuietly();
        }
    }

    /** 取活的运行时（执行器每次调用都经此解析，避免闭包死连接） */
    public McpConnectionRuntime runtime(String connectionName) {
        return runtimes.get(connectionName);
    }

    /** 所有连接名 */
    public Set<String> names() {
        return runtimes.keySet();
    }
}
