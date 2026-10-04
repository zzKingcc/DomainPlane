package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.agent.CallerContext;

/**
 * 工具调用的审计上下文（线程绑定）
 * @author zzkingcc
 */
public final class ToolInvocationContext {

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    /**
     * 绑定本轮调用方身份与排障标识（在调用工具前调用）。
     *
     * @param caller  本轮调用方身份；为 {@code null} 时视为匿名（审计字段留空）
     * @param traceId 本轮 traceId；为 {@code null} 时执行器自行取当前 MDC 值
     */
    public static void bind(CallerContext caller, String traceId) {
        HOLDER.set(new Holder(caller, traceId));
    }

    /** 解除绑定（调用工具后务必调用，避免污染同线程的下一轮） */
    public static void clear() {
        HOLDER.remove();
    }

    /** 租户标识（审计字段），未绑定时为 {@code null} */
    public static String tenantId() {
        Holder holder = HOLDER.get();
        return holder == null || holder.caller() == null ? null : holder.caller().tenantId();
    }

    /** 用户标识（审计字段），未绑定时为 {@code null} */
    public static String userId() {
        Holder holder = HOLDER.get();
        return holder == null || holder.caller() == null ? null : holder.caller().userId();
    }

    /** 本轮 traceId，未绑定时为 {@code null} */
    public static String traceId() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.traceId();
    }

    private record Holder(CallerContext caller, String traceId) {
    }
}
