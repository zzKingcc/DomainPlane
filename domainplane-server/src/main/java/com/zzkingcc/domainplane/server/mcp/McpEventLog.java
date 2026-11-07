package com.zzkingcc.domainplane.server.mcp;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * MCP 连接的事件记录（内存，按连接分桶，新→旧）。
 *
 * <p>为什么是内存环而不是落盘：事件是"刚才发生了什么"的运维视图，不是审计凭据——
 * 审计落在 AUDIT 日志（{@code AuditLog}），那条链路才是要长期留存、不可篡改的。
 * 落盘事件表要新增存储、TTL 与清理策略，收益只是"重启后还能翻旧账"，而 AUDIT 日志
 * 已经答了那个问题。故这里只有进程内环，重启即清空，页面上按"本次运行"口径展示。</p>
 *
 * <p>为什么按连接分桶而不是一条全局流：页面永远只看当前选中的连接，
 * 全局流既要在查询时过滤（噪音大），又会因为某个高频连接把别人的记录挤掉。</p>
 *
 * <p>与 {@code AuditLog} 曾经下架的内存环的区别：那个环没有消费方（页面不展示、
 * 接口不返回），属于死代码；这个环有真实消费方（{@code GET /{name}/events} + 页面事件 Tab）。</p>
 *
 * @author zzkingcc
 */
public class McpEventLog {

    /** 建连/重连结果（含启动、reconcile、测试连接触发） */
    public static final String CONNECT = "CONNECT";
    /** 一次工具调用（模型执行器与管控台试跑都记） */
    public static final String CALL = "CALL";
    /** 授权流程（发起 / 回调成功） */
    public static final String AUTH = "AUTH";
    /** 撤销授权（删本地 token） */
    public static final String REVOKE = "REVOKE";
    /** 配置变更（保存 / 启停 / 删除 / 域绑定） */
    public static final String CONFIG = "CONFIG";

    /** 每连接保留条数：够看"最近一轮发生了什么"，又不至于把高频连接的堆撑起来 */
    static final int PER_CONNECTION = 100;

    private static final int MAX_SUMMARY = 200;

    /** 一条事件；{@code ok=false} 在页面上按失败渲染 */
    public record Event(long atMs, String type, boolean ok, long durationMs, String summary) {
    }

    private final Map<String, Deque<Event>> byConnection = new ConcurrentHashMap<>();

    /**
     * 记一条事件。
     *
     * @param connectionName 连接名
     * @param type           {@link #CONNECT} / {@link #CALL} / {@link #AUTH} / {@link #REVOKE} / {@link #CONFIG}
     * @param ok             是否成功
     * @param durationMs     耗时（毫秒）；不适用传 0
     * @param summary        一句话摘要（调用方保证不含凭据明文）
     */
    public void record(String connectionName, String type, boolean ok, long durationMs, String summary) {
        if (connectionName == null || connectionName.isBlank()) {
            return;
        }
        Deque<Event> deque = byConnection.computeIfAbsent(connectionName,
                k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(new Event(System.currentTimeMillis(), type, ok,
                Math.max(0, durationMs), clip(summary)));
        while (deque.size() > PER_CONNECTION) {
            deque.pollLast();
        }
    }

    /** 最近事件（新→旧）；limit ≤ 0 时取全部保留量 */
    public List<Event> recent(String connectionName, int limit) {
        Deque<Event> deque = byConnection.get(connectionName);
        if (deque == null) {
            return List.of();
        }
        int cap = limit <= 0 ? PER_CONNECTION : Math.min(limit, PER_CONNECTION);
        List<Event> out = new ArrayList<>(Math.min(cap, deque.size()));
        for (Event e : deque) {
            if (out.size() >= cap) {
                break;
            }
            out.add(e);
        }
        return out;
    }

    /** 当前保留条数（页面显示"本次运行 N 条"） */
    public int size(String connectionName) {
        Deque<Event> deque = byConnection.get(connectionName);
        return deque == null ? 0 : deque.size();
    }

    /** 删连接时丢掉它的桶，避免同名重建后看到上一条连接的历史 */
    public void forget(String connectionName) {
        byConnection.remove(connectionName);
    }

    private static String clip(String summary) {
        if (summary == null) {
            return "";
        }
        String flat = summary.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_SUMMARY ? flat : flat.substring(0, MAX_SUMMARY) + "…";
    }
}
