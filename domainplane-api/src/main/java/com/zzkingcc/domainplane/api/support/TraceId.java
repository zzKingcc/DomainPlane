package com.zzkingcc.domainplane.api.support;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 排障标识（traceId）
 *
 * @author zzkingcc
 */
public final class TraceId {

    /** MDC 中的 key，与 logback pattern 的 {@code %X{traceId}} 对应 */
    public static final String MDC_KEY = "traceId";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceId() {}

    /**
     * 开始一段链路。
     *
     * @param externalTraceId 外部传入的 traceId（可为 null）；为 null 时自动生成
     * @return 本次生效的 traceId
     */
    public static String begin(String externalTraceId) {
        String id = (externalTraceId == null || externalTraceId.isBlank())
                ? generate()
                : externalTraceId.trim();
        HOLDER.set(id);
        MDC.put(MDC_KEY, id);
        return id;
    }

    /** 自动生成并置入，返回新 traceId */
    public static String begin() {
        return begin(null);
    }

    /** 读取当前 traceId；未开启时返回 null（不自动生成，避免污染） */
    public static String current() {
        return HOLDER.get();
    }

    /** 读取当前 traceId；未开启时临时生成一个（用于"忘了 begin"的兜底场景） */
    public static String currentOrNew() {
        String id = HOLDER.get();
        return id == null ? generate() : id;
    }

    /** 结束并清理。*/
    public static void end() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }

    /** 生成一个新的 traceId（8 位） */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
