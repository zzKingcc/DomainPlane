package com.zzkingcc.domainplane.server.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * MCP 协调器：打通"连接就绪 → 盖章"，并做周期性 reconcile（启动 + 周期兜底）。
 *
 * 一致性保证：store 的 bindings 是唯一真相，注册表是派生缓存。连接就绪、绑定变更都会即时
 * 盖章；但为防止"绑定落盘后盖章失败/漏盖章"导致 store 与注册表长期不一致，这里做周期性兜底：
 * 每 30s 重新对齐，拉起失败连接（带冷却）、重盖章、摘掉已删连接的运行时。
 *
 * @author zzkingcc
 */
@Component
public class McpCoordinator {

    private static final Logger log = LoggerFactory.getLogger(McpCoordinator.class);

    /** 失败重连冷却（ms）：避免对死连接每轮 reconcile 都重连 */
    private static final long RECONNECT_COOLDOWN_MS = 60_000L;

    private final McpConnectionStore store;
    private final McpConnectionRuntimeProvider runtimes;
    private final McpToolBridge bridge;

    public McpCoordinator(McpConnectionStore store, McpConnectionRuntimeProvider runtimes, McpToolBridge bridge) {
        this.store = store;
        this.runtimes = runtimes;
        this.bridge = bridge;
    }

    @PostConstruct
    public void init() {
        // 启动兜底：确保已启用的连接都建连并盖章（provider.start() 建连后也会经回调盖章，
        // 这里再兜底一次，确保绑定关系与注册表一致）。放后台线程，避免启动时对所有 enabled
        // 连接同步建连拖慢启动（每条最坏约一个 connect + call 超时）。
        Thread t = new Thread(this::reconcile, "mcp-init-reconcile");
        t.setDaemon(true);
        t.start();
    }

    /** 周期 reconcile：拉起失败连接 + 重盖章 + 清理已删连接的运行时 */
    @Scheduled(fixedDelayString = "${domainplane.mcp.reconcile-ms:30000}")
    public void reconcile() {
        for (McpConnection conn : store.list()) {
            McpConnectionRuntime runtime = runtimes.runtime(conn.name());
            if (conn.enabled()) {
                if (runtime == null) {
                    runtimes.connect(conn.name());
                } else if (!runtime.isReady()) {
                    long since = System.currentTimeMillis() - runtime.lastAttemptMs();
                    if (since >= RECONNECT_COOLDOWN_MS) {
                        runtimes.connect(conn.name());
                    }
                } else {
                    // 已就绪：重盖章（绑定可能已变）
                    bridge.publish(conn.name());
                }
            } else {
                bridge.unpublish(conn.name());
            }
        }
        // 摘掉已从 store 删除的连接的运行时
        for (String name : runtimes.names()) {
            if (!store.exists(name)) {
                runtimes.disconnect(name);
            }
        }
    }
}
