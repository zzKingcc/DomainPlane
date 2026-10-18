package com.zzkingcc.domainplane.server.mcp;

import com.zzkingcc.domainplane.server.mcp.McpRpcClient.McpToolInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 一个 MCP 连接的运行时状态：客户端、会话、工具目录、健康状态。
 *
 * 重连/会话失效时整体重建（新 McpRpcClient），因此执行器不能闭包旧客户端，
 * 它只持有连接名，每次调用经 {@link McpConnectionRuntimeProvider} 取"活的"运行时。
 *
 * @author zzkingcc
 */
public class McpConnectionRuntime {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionRuntime.class);

    public enum Status {
        CONNECTING, READY, FAILED,

        /** 服务器返回认证挑战（401 + WWW-Authenticate）：等管理员走 OAuth 授权；未授权前工具不可见 */
        NEEDS_AUTH
    }

    private final String connectionName;
    private final McpConnection connection;
    private volatile McpRpcClient client;
    private volatile List<McpToolInfo> catalog = List.of();
    private volatile Status status = Status.CONNECTING;
    private volatile String lastError;
    /** NEEDS_AUTH 时的 WWW-Authenticate 挑战值（oauth/start 据此发现授权端点） */
    private volatile String authChallenge;
    private volatile long lastAttemptMs;

    public McpConnectionRuntime(String connectionName, McpConnection connection) {
        this.connectionName = connectionName;
        this.connection = connection;
    }

    /**
     * 测试专用：以"就绪 + 给定工具目录"直接构造，跳过真实建连（不发任何网络请求）。
     * 仅供同包测试驱动 {@code McpToolBridge} / {@code McpToolCatalog} 的盖章翻译路径。
     */
    static McpConnectionRuntime readyForTest(String connectionName, McpConnection connection,
                                             List<McpToolInfo> catalog) {
        McpConnectionRuntime r = new McpConnectionRuntime(connectionName, connection);
        r.catalog = catalog == null ? List.of() : catalog;
        r.status = Status.READY;
        r.client = new McpRpcClient(connection.url(), connection.transport(),
                connection.headers(), connection.callTimeoutMs());
        return r;
    }

    public String connectionName() {
        return connectionName;
    }

    public McpConnection connection() {
        return connection;
    }

    public Status status() {
        return status;
    }

    public boolean isReady() {
        return status == Status.READY && client != null;
    }

    public String lastError() {
        return lastError;
    }

    public String authChallenge() {
        return authChallenge;
    }

    public List<McpToolInfo> catalog() {
        return catalog;
    }

    public long lastAttemptMs() {
        return lastAttemptMs;
    }

    /** 建立/重建连接：initialize + tools/list。失败只记状态，不抛（不影响其它连接与启动）。
     *  synchronized：reconcile / 管理员"测试" / 启动线程可能并发触发同一连接的建连，串行化避免重复建 client、旧的泄漏。
     *  @param headerSupplier 请求头提供者，每次请求现取，OAuth token 刷新后不重建 client 即生效 */
    public synchronized void connect(Supplier<Map<String, String>> headerSupplier) {
        lastAttemptMs = System.currentTimeMillis();
        status = Status.CONNECTING;
        authChallenge = null;
        closeQuietly();
        try {
            McpRpcClient c = new McpRpcClient(connection.url(), connection.transport(),
                    headerSupplier, connection.callTimeoutMs());
            c.initialize();
            List<McpToolInfo> tools = c.toolsList();
            this.client = c;
            this.catalog = tools;
            this.status = Status.READY;
            this.lastError = null;
            log.info("[MCP 运行时] 连接 {} 就绪，工具数 {}", connectionName, tools.size());
        } catch (McpAuthChallengeException e) {
            this.client = null;
            this.catalog = List.of();
            this.status = Status.NEEDS_AUTH;
            this.authChallenge = e.challenge();
            this.lastError = e.getMessage();
            log.warn("[MCP 运行时] 连接 {} 需要授权（{}），管理员完成 OAuth 前工具不可见",
                    connectionName, e.challenge());
        } catch (Exception e) {
            this.client = null;
            this.catalog = List.of();
            this.status = Status.FAILED;
            this.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[MCP 运行时] 连接 {} 建立失败: {}", connectionName, this.lastError);
        }
    }

    /** 标记需要授权（运行中 401 且刷新失败时由 Provider 调用）；工具应在下次 reconcile 时摘除 */
    public synchronized void markNeedsAuth(String challenge) {
        this.status = Status.NEEDS_AUTH;
        this.authChallenge = challenge;
        this.client = null;
        this.catalog = List.of();
        this.lastError = challenge == null
                ? "MCP 授权已失效，需要管理员重新授权"
                : "MCP 授权已失效，需要管理员重新授权（" + challenge + "）";
        closeQuietly();
        log.warn("[MCP 运行时] 连接 {} 标记为需要重新授权", connectionName);
    }

    /** 取活的客户端（调用前由执行器检查 isReady） */
    public McpRpcClient client() {
        return client;
    }

    void closeQuietly() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }
}
