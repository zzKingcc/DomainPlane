package com.zzkingcc.domainplane.server.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 工具执行器：把一次工具调用翻译成对 MCP server 的 {@code tools/call}。
 *
 * 必须每次调用经 {@link McpConnectionRuntimeProvider} 解析"活的"运行时，
 * 因为 {@code ToolRegistry.refreshReplica} 对已存在条目会保留旧 executor，
 * 闭包了旧客户端的执行器在重连后只会用死连接。这里只持有连接名 + 原始工具名，运行时现取。
 *
 * 错误语义照 {@code RemoteToolExecutor}：业务失败 / 传输失败都回喂文本给模型（不抛），
 * 让模型据此改写参数或改述；只有意外异常才抛出（由 ToolRouter 兜底脱敏）。
 *
 * 运行中 401（token 刚过期）：先经 {@link McpConnectionRuntimeProvider#refreshToken} 刷新并重试一次
 * （client 的请求头是每次现取的，刷新后不重建 client 即生效）；刷新不了则标记 NEEDS_AUTH
 * 并回喂"需要重新授权"，等管理员在管控台完成 OAuth。
 *
 * 不走隔离层：保持非 local，ToolRouter 直接执行；连接级 callTimeoutMs 是占住编排线程的上限。
 *
 * @author zzkingcc
 */
public class McpToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);

    private final McpConnectionRuntimeProvider runtimes;
    private final String connectionName;
    private final String remoteToolName;

    public McpToolExecutor(McpConnectionRuntimeProvider runtimes, String connectionName, String remoteToolName) {
        this.runtimes = runtimes;
        this.connectionName = connectionName;
        this.remoteToolName = remoteToolName;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        long start = System.currentTimeMillis();
        boolean success = false;
        McpConnectionRuntime runtime = runtimes.runtime(connectionName);
        if (runtime == null || !runtime.isReady()) {
            return fail("MCP 连接 " + connectionName + " 当前未就绪（连接失败或未建立），工具 "
                    + remoteToolName + " 暂不可用", start);
        }
        try {
            McpRpcClient.McpCallResult result;
            try {
                result = runtime.client().toolsCall(remoteToolName, request.arguments());
            } catch (McpAuthChallengeException auth) {
                // 运行中 401：token 可能刚过期，刷新一次重试；刷新不了留授权门禁
                log.warn("[MCP 执行器] 连接 {} 调用中收到认证挑战，尝试刷新 token 后重试", connectionName);
                if (!runtimes.refreshToken(connectionName)) {
                    runtimes.markNeedsAuth(connectionName, auth.challenge());
                    return fail("工具 " + remoteToolName + " 调用失败（连接 " + connectionName
                            + "）：MCP 授权已失效，需要管理员重新授权", start);
                }
                // 重试前必须重新解析运行时：刷新 token 这段时间里，reconcile 可能已经把旧 client
                // 关掉并换上新实例。继续用进入时那个引用，轻则对着已关闭的 HttpClient 调用，
                // 重则 NPE，而失败信息会指向"授权失效"，把人往错误方向带。
                McpConnectionRuntime retried = runtimes.runtime(connectionName);
                if (retried == null || !retried.isReady()) {
                    return fail("工具 " + remoteToolName + " 调用失败（连接 " + connectionName
                            + "）：连接在重试期间被重置，请稍后再试", start);
                }
                result = retried.client().toolsCall(remoteToolName, request.arguments());
            }
            if (result.isError()) {
                return fail("工具 " + remoteToolName + " 执行失败: "
                        + (result.text().isBlank() ? "MCP server 未给出原因" : result.text()), start);
            }
            success = true;
            return result.text();
        } catch (Exception e) {
            return fail("工具 " + remoteToolName + " 调用失败（连接 " + connectionName + "）："
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), start);
        } finally {
            if (success) {
                MetricsRegistry.mcpToolCall(true);
                // 成功的事件只记一行，不带结果正文：正文可能很长，也可能含业务数据
                runtimes.eventLog().record(connectionName, McpEventLog.CALL, true,
                        System.currentTimeMillis() - start, "模型调用 " + remoteToolName);
            }
        }
    }

    /**
     * 失败收口：日志 + 指标 + 事件 + 回喂文本，一处写。
     *
     * <p>失败分支有 5 处，分散写必有遗漏（历史上漏记指标就是这样发生的）；
     * 收敛成一个方法后，新增分支不可能漏掉事件记录。</p>
     */
    private String fail(String message, long start) {
        log.warn("[MCP 执行器] {}", message);
        MetricsRegistry.mcpToolCall(false);
        runtimes.eventLog().record(connectionName, McpEventLog.CALL, false,
                System.currentTimeMillis() - start, "模型调用 " + remoteToolName + "：" + message);
        return message;
    }
}
