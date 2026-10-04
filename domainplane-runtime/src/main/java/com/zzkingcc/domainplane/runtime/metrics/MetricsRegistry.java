package com.zzkingcc.domainplane.runtime.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内累计指标。
 *
 * @author zzkingcc
 */
public final class MetricsRegistry {

    private static final LongAdder CHAT_REQUESTS = new LongAdder();
    private static final LongAdder RESUME_REQUESTS = new LongAdder();
    private static final LongAdder STOP_REQUESTS = new LongAdder();
    private static final LongAdder TOOL_CALLS = new LongAdder();
    private static final LongAdder TOOL_FAILURES = new LongAdder();
    private static final Instant STARTED_AT = Instant.now();

    private MetricsRegistry() {}

    /** 按入口路径计数；管理端与探针请求不计入，避免稀释会话类指标 */
    public static void request(String uri) {
        if (uri.endsWith("/chat")) {
            CHAT_REQUESTS.increment();
        } else if (uri.endsWith("/resume")) {
            RESUME_REQUESTS.increment();
        } else if (uri.contains("/stop/")) {
            STOP_REQUESTS.increment();
        }
    }

    /**
     * 一次工具调用尝试。
     *
     * @param success {@code false} 表示"工具不存在"或执行抛异常（两者对模型都是失败信号）
     */
    public static void toolCall(boolean success) {
        TOOL_CALLS.increment();
        if (!success) {
            TOOL_FAILURES.increment();
        }
    }

    /** 快照；键名是对外契约，改动需同步 {@code docs/API.md} */
    public static Map<String, Object> snapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uptimeSeconds", Duration.between(STARTED_AT, Instant.now()).toSeconds());
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("chatRequests", CHAT_REQUESTS.sum());
        counters.put("resumeRequests", RESUME_REQUESTS.sum());
        counters.put("stopRequests", STOP_REQUESTS.sum());
        counters.put("toolCalls", TOOL_CALLS.sum());
        counters.put("toolFailures", TOOL_FAILURES.sum());
        body.put("counters", counters);
        return body;
    }
}
