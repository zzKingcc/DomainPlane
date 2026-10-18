package com.zzkingcc.domainplane.server.mcp;

import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具"盖章注册"的唯一入口（幂等）。
 *
 * 必须被三处驱动：① 连接就绪 / 工具目录刷新；② 绑定变更；③ 启动恢复。
 * 谁来都按"当前连接状态 × 当前持久化绑定"盖一次章，一次消掉"绑定先于连接"和
 * "连接先于绑定"两种时序问题（两边都不做注册，只有这里做）。
 *
 * 因为 {@code ToolRegistry.replaceInstanceTools} 本身是按 diff 同步的，天然幂等。
 *
 * @author zzkingcc
 */
@Component
public class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final ToolRegistry toolRegistry;
    private final McpConnectionStore store;
    private final McpConnectionRuntimeProvider runtimes;

    public McpToolBridge(ToolRegistry toolRegistry, McpConnectionStore store,
                        McpConnectionRuntimeProvider runtimes) {
        this.toolRegistry = toolRegistry;
        this.store = store;
        this.runtimes = runtimes;
    }

    /**
     * 盖章单个连接：连接未就绪 / 禁用 / 已删除 → 摘除该实例全部工具（模型立即不可见）；
     * 否则按"当前绑定的域"把工具目录注册/刷新进注册表。
     */
    public void publish(String connectionName) {
        String instanceId = "mcp:" + connectionName;
        McpConnection conn = store.get(connectionName);
        if (conn == null) {
            toolRegistry.removeInstance(instanceId);
            log.info("[MCP 桥接] 连接 {} 已不存在，摘除其工具", connectionName);
            return;
        }
        McpConnectionRuntime runtime = runtimes.runtime(connectionName);
        if (!conn.enabled() || runtime == null || !runtime.isReady()) {
            toolRegistry.removeInstance(instanceId);
            log.info("[MCP 桥接] 连接 {} 未就绪/已禁用，摘除其工具（enabled={}, ready={}）",
                    connectionName, conn.enabled(), runtime != null && runtime.isReady());
            return;
        }
        List<String> binding = store.boundDomainsOf(connectionName);
        List<ToolRegistry.Registered> catalog =
                McpToolCatalog.toRegistered(runtime, new ArrayList<>(binding), conn, runtimes);
        toolRegistry.replaceInstanceTools(instanceId, conn.url(), catalog);
        log.info("[MCP 桥接] 连接 {} 已盖章：工具 {} 个，绑定域 {}", connectionName, catalog.size(), binding);
    }

    /** 盖章全部连接：启动恢复与周期性 reconcile 用 */
    public void publishAll() {
        for (McpConnection conn : store.list()) {
            if (conn.enabled()) {
                publish(conn.name());
            } else {
                toolRegistry.removeInstance("mcp:" + conn.name());
            }
        }
    }

    /** 摘掉一个连接的全部工具（删除连接 / 禁用时调用） */
    public void unpublish(String connectionName) {
        toolRegistry.removeInstance("mcp:" + connectionName);
    }
}
