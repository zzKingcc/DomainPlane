package com.zzkingcc.domainplane.common.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内累计指标。
 *
 * <p>放在 {@code common} 而不是运行层：上报方横跨各层，运行层（工具路由）、
 * 服务层（鉴权拦截器）、基础设施层（会话检索的索引写入与兜底）都要计数。
 * 这类"谁都能报、只有一个出口"的计数器若挂在某一层，其它层的上报就得靠回调绕过去。</p>
 *
 * <p>指标只累计、不清零，进程重启即归零；{@link #snapshot()} 的键名是对外契约
 * （{@code GET /admin/metrics}），改动必须同步 {@code docs/接口文档.md}。</p>
 *
 * @author zzkingcc
 */
public final class MetricsRegistry {

    private static final LongAdder CHAT_REQUESTS = new LongAdder();
    private static final LongAdder RESUME_REQUESTS = new LongAdder();
    private static final LongAdder STOP_REQUESTS = new LongAdder();
    private static final LongAdder TOOL_CALLS = new LongAdder();
    private static final LongAdder TOOL_FAILURES = new LongAdder();

    /* ===== 会话历史检索（M4 治理：召回质量可观测） ===== */

    /** 倒排检索执行次数（装配层卡片段与 recall_history 工具的都算） */
    private static final LongAdder RECALL_SEARCHES = new LongAdder();
    /** 检索返回非空卡片的次数 */
    private static final LongAdder RECALL_HITS = new LongAdder();
    /** 检索空手的次数 */
    private static final LongAdder RECALL_MISSES = new LongAdder();
    /** 走了子串兜底的次数（索引没建 / 查询失败），与命中率一起看才说明"索引是否真的在用" */
    private static final LongAdder RECALL_FALLBACKS = new LongAdder();
    /** recall_history 工具实际检索的次数（不含被预算拦下的） */
    private static final LongAdder RECALL_TOOL_CALLS = new LongAdder();
    /** 被每轮检索预算拦下的次数（R7：模型把检索当玩具时这个数会飙） */
    private static final LongAdder RECALL_BUDGET_BLOCKED = new LongAdder();
    /** 成功写入倒排索引的轮数 */
    private static final LongAdder INDEXED_TURNS = new LongAdder();
    /** 累计写入的词根数（÷ 轮数 ≈ 每轮词根量，R2 的体积观测） */
    private static final LongAdder INDEXED_TERMS = new LongAdder();
    /** 索引写失败次数（派生数据，失败靠子串兜底） */
    private static final LongAdder INDEX_WRITE_FAILURES = new LongAdder();

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

    /**
     * 一次倒排检索。
     *
     * @param hit      是否返回了卡片
     * @param fallback 是否走了子串兜底（索引未命中或索引查询失败）
     */
    public static void recallSearch(boolean hit, boolean fallback) {
        RECALL_SEARCHES.increment();
        if (hit) {
            RECALL_HITS.increment();
        } else {
            RECALL_MISSES.increment();
        }
        if (fallback) {
            RECALL_FALLBACKS.increment();
        }
    }

    /** recall_history 工具的一次实际检索（预算已通过、参数合法） */
    public static void recallToolCall() {
        RECALL_TOOL_CALLS.increment();
    }

    /** 一次被每轮检索预算拦下的调用 */
    public static void recallBudgetBlocked() {
        RECALL_BUDGET_BLOCKED.increment();
    }

    /** 一轮成功写入倒排索引，并带上写入的词根数 */
    public static void indexed(int terms) {
        INDEXED_TURNS.increment();
        if (terms > 0) {
            INDEXED_TERMS.add(terms);
        }
    }

    /** 一次索引写入失败（派生数据，不影响这一轮对话） */
    public static void indexWriteFailure() {
        INDEX_WRITE_FAILURES.increment();
    }

    /* ===== MCP 连接（治理：外部工具接入的可观测） ===== */

    private static final LongAdder MCP_TOOL_CALLS = new LongAdder();
    private static final LongAdder MCP_TOOL_FAILURES = new LongAdder();
    private static final LongAdder MCP_RECONNECTS = new LongAdder();
    /** 当前就绪的 MCP 连接数（仪表，由运行时持有者更新） */
    private static final AtomicLong MCP_CONNECTIONS_READY = new AtomicLong();
    /** 当前注册进平台的 MCP 工具数（仪表，由桥接层更新） */
    private static final AtomicLong MCP_TOOLS = new AtomicLong();

    /** 一次 MCP 工具调用尝试 */
    public static void mcpToolCall(boolean success) {
        MCP_TOOL_CALLS.increment();
        if (!success) {
            MCP_TOOL_FAILURES.increment();
        }
    }

    /** 一次 MCP 连接重连尝试 */
    public static void mcpReconnect() {
        MCP_RECONNECTS.increment();
    }

    /** 更新"就绪连接数"仪表（连接建立/失效时调用） */
    public static void mcpConnectionsReady(long value) {
        MCP_CONNECTIONS_READY.set(value);
    }

    /** 更新"已注册 MCP 工具数"仪表（盖章时调用） */
    public static void mcpTools(long value) {
        MCP_TOOLS.set(value);
    }

    /** 快照；键名是对外契约，改动需同步 {@code docs/接口文档.md} */
    public static Map<String, Object> snapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uptimeSeconds", Duration.between(STARTED_AT, Instant.now()).toSeconds());
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("chatRequests", CHAT_REQUESTS.sum());
        counters.put("resumeRequests", RESUME_REQUESTS.sum());
        counters.put("stopRequests", STOP_REQUESTS.sum());
        counters.put("toolCalls", TOOL_CALLS.sum());
        counters.put("toolFailures", TOOL_FAILURES.sum());
        counters.put("recallSearches", RECALL_SEARCHES.sum());
        counters.put("recallHits", RECALL_HITS.sum());
        counters.put("recallMisses", RECALL_MISSES.sum());
        counters.put("recallFallbacks", RECALL_FALLBACKS.sum());
        counters.put("recallToolCalls", RECALL_TOOL_CALLS.sum());
        counters.put("recallBudgetBlocked", RECALL_BUDGET_BLOCKED.sum());
        counters.put("indexedTurns", INDEXED_TURNS.sum());
        counters.put("indexedTerms", INDEXED_TERMS.sum());
        counters.put("indexWriteFailures", INDEX_WRITE_FAILURES.sum());
        counters.put("mcpToolCalls", MCP_TOOL_CALLS.sum());
        counters.put("mcpToolFailures", MCP_TOOL_FAILURES.sum());
        counters.put("mcpReconnects", MCP_RECONNECTS.sum());
        Map<String, Object> gauges = new LinkedHashMap<>();
        gauges.put("mcpConnectionsReady", MCP_CONNECTIONS_READY.get());
        gauges.put("mcpTools", MCP_TOOLS.get());
        body.put("gauges", gauges);
        body.put("counters", counters);
        return body;
    }
}
