package com.zzkingcc.domainplane.runtime.tool;

/**
 * 工具调用的线程上下文
 *
 * <p>承载这一轮的身份与预算：{@code traceId}（排障）、{@code sessionId}（会话隔离）、
 * {@code searchBudget}（历史检索次数）。工具被挪到隔离线程执行后 {@code ThreadLocal}
 * 天然不跟随，而这三样每一个都不能丢：
 * {@code traceId} 丢了排障线索断；{@code sessionId} 丢了，历史检索工具就不知道
 * 该读哪个会话的档（跨会话读是越权口子，方案 §6.3）；
 * {@code searchBudget} 丢了，"每轮限次"会退化成每次调用各自为政、限不住。</p>
 *
 * @author zzkingcc
 */
public final class ToolInvocationContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    private static final ThreadLocal<RoundSearchBudget> BUDGET = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    /**
     * 绑定本轮 traceId（在调用工具前调用）。
     *
     * @param traceId 本轮 traceId；为 {@code null} 时执行器自行取当前 MDC 值
     */
    public static void bind(String traceId) {
        bind(traceId, null, null);
    }

    /**
     * 绑定本轮 traceId 与会话标识。
     *
     * @param sessionId 本轮 sessionId（不含域）；历史检索工具用它定位归档
     */
    public static void bind(String traceId, String sessionId) {
        bind(traceId, sessionId, null);
    }

    /**
     * 绑定本轮 traceId、会话标识与检索预算。
     *
     * @param searchBudget 本轮的检索预算对象；多个工具调用共享同一个对象才能"限住一轮"
     */
    public static void bind(String traceId, String sessionId, RoundSearchBudget searchBudget) {
        HOLDER.set(traceId);
        if (sessionId != null && !sessionId.isBlank()) {
            SESSION.set(sessionId);
        }
        if (searchBudget != null) {
            BUDGET.set(searchBudget);
        }
    }

    /** 解除绑定（调用工具后务必调用，避免污染同线程的下一轮） */
    public static void clear() {
        HOLDER.remove();
        SESSION.remove();
        BUDGET.remove();
    }

    /** 本轮 traceId，未绑定时为 {@code null} */
    public static String traceId() {
        return HOLDER.get();
    }

    /**
     * 本轮 sessionId（不含域），未绑定时为 {@code null}。
     *
     * <p>归档键需要"域 + sessionId"：域读 {@code RetrievalScope.current()}（同一绑定点），
     * 两者合起来才是会话键。工具签名里不带这两个参数，是为了不给模型"指定别的会话"的口子。</p>
     */
    public static String sessionId() {
        return SESSION.get();
    }

    /** 本轮历史检索预算；未绑定时为 {@code null}（此时工具按"不限制"处理并告警） */
    public static RoundSearchBudget searchBudget() {
        return BUDGET.get();
    }
}
