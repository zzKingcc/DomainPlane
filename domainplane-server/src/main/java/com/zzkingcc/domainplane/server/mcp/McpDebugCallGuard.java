package com.zzkingcc.domainplane.server.mcp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管控台「试跑」的限流闸门：每连接一个滑动窗口，窗口内超过上限即拒绝。
 *
 * <p>为什么需要它：{@code POST /{name}/tools/call} 是真实副作用通道，不是沙箱。
 * 没有闸门时，一次误点（或页面按钮的连点/重放）就能让外部系统收到成串写请求。
 * 限流按连接计，避免一条连接的高频把其它连接挤掉。</p>
 *
 * <p>为什么窗口内计数而不是令牌桶：这里的目标是"别让手滑变成压测"，
 * 不需要平滑速率，也不需要突发额度——固定窗口足够，且行为对运维可解释
 * （"每 60 秒最多 10 次"）。</p>
 *
 * <p>内存态：单实例口径，与"平台当前是单实例部署"一致；多实例化时这张表要跟着会话运行态
 * 一起挪到共享存储（已登记在案）。</p>
 *
 * @author zzkingcc
 */
public class McpDebugCallGuard {

    private final int maxCalls;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public McpDebugCallGuard(int maxCalls, long windowMs) {
        this.maxCalls = Math.max(1, maxCalls);
        this.windowMs = Math.max(1000L, windowMs);
    }

    /**
     * 申请一次额度。
     *
     * @return true = 放行（已计入本次）；false = 窗口内额度已用尽
     */
    public synchronized boolean tryAcquire(String connectionName, long nowMs) {
        if (connectionName == null || connectionName.isBlank()) {
            return false;
        }
        Deque<Long> deque = hits.computeIfAbsent(connectionName, k -> new ArrayDeque<>());
        long from = nowMs - windowMs;
        while (!deque.isEmpty() && deque.peekFirst() <= from) {
            deque.pollFirst();
        }
        if (deque.size() >= maxCalls) {
            return false;
        }
        deque.addLast(nowMs);
        return true;
    }

    /** 还要等多久才有额度（毫秒）；有额度返回 0。用于把"稍后再试"说得具体 */
    public synchronized long retryAfterMs(String connectionName, long nowMs) {
        Deque<Long> deque = hits.get(connectionName);
        if (deque == null || deque.size() < maxCalls) {
            return 0;
        }
        Long oldest = deque.peekFirst();
        if (oldest == null) {
            return 0;
        }
        return Math.max(0L, oldest + windowMs - nowMs);
    }

    /** 删连接时清掉它的计数 */
    public void forget(String connectionName) {
        hits.remove(connectionName);
    }

    /** 窗口上限（条） */
    public int maxCalls() {
        return maxCalls;
    }

    /** 窗口长度（毫秒） */
    public long windowMs() {
        return windowMs;
    }
}
