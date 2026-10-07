package com.zzkingcc.domainplane.runtime.tool;

/**
 * 工具调用的线程上下文
 *
 * 只承载这一轮的 traceId：工具被挪到隔离线程执行后 {@code ThreadLocal} 天然不跟随，
 * 而 {@code traceId} 就是"工具侧日志 ↔ 服务端这次调用"的唯一线索，丢了排障线索就断。
 *
 * @author zzkingcc
 */
public final class ToolInvocationContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    /**
     * 绑定本轮 traceId（在调用工具前调用）。
     *
     * @param traceId 本轮 traceId；为 {@code null} 时执行器自行取当前 MDC 值
     */
    public static void bind(String traceId) {
        HOLDER.set(traceId);
    }

    /** 解除绑定（调用工具后务必调用，避免污染同线程的下一轮） */
    public static void clear() {
        HOLDER.remove();
    }

    /** 本轮 traceId，未绑定时为 {@code null} */
    public static String traceId() {
        return HOLDER.get();
    }
}
