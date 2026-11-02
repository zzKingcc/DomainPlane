package com.zzkingcc.domainplane.common.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * （{@code GET /admin/metrics}），改动必须同步 {@code docs/设计文档.md} F02 §2.7 的指标表。</p>
 *
 * <p>趋势数据不落库：进程重启即失是已知代价（管控台不是审计系统，趋势只为
 * 「刚才这段有没有异常」提供眼睛）。固定窗口环形缓冲，容量 60 点，采样间隔
 * 由运行层 {@code MetricsSampler} 驱动（默认 10s，即 10 分钟窗口）。</p>
 *
 * @author zzkingcc
 */
public final class MetricsRegistry {

    /** 历史点数上限（默认 10s 一点 = 最近 10 分钟） */
    private static final int MAX_POINTS = 60;

    private static final LongAdder CHAT_REQUESTS = new LongAdder();
    private static final LongAdder RESUME_REQUESTS = new LongAdder();
    private static final LongAdder STOP_REQUESTS = new LongAdder();
    private static final LongAdder TOOL_CALLS = new LongAdder();
    private static final LongAdder TOOL_FAILURES = new LongAdder();

    /* ===== 工具实例心跳（治理：实例是否还活着，此前只进状态快照，看不出趋势） ===== */

    /** 受理的心跳次数（含被强制下线拒掉的那些） */
    private static final LongAdder INSTANCE_HEARTBEATS = new LongAdder();
    /** 被拒的心跳次数（实例已 FORCE_OFFLINE，回 410 Gone） */
    private static final LongAdder INSTANCE_HEARTBEAT_FAILURES = new LongAdder();

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

    /* ===== blob 存储（媒体暂存区：字节搬出 Redis 后的回收对账） ===== */

    /** 回收扫描轮数（含失败轮） */
    private static final LongAdder BLOB_SWEEPS = new LongAdder();
    /** 累计检查过的 blob 数（活着的 + 回收的 + 跳过的都在内） */
    private static final LongAdder BLOBS_SCANNED = new LongAdder();
    /** 累计回收的无主 blob 数 */
    private static final LongAdder BLOBS_RECLAIMED = new LongAdder();
    /** 回收失败次数（删不动的，下轮重试） */
    private static final LongAdder BLOB_SWEEP_FAILURES = new LongAdder();

    /* ===== 知识库存储（F05 去 ES 强依赖：写入/重灌/原文档案的健康度） ===== */

    /** store.write 累计批次数（ ingest 与重灌都走这里） */
    private static final LongAdder KB_STORE_WRITES = new LongAdder();
    /** store.write 失败批次数（fail-closed：任一批失败即整次导入/重灌失败） */
    private static final LongAdder KB_STORE_FAILURES = new LongAdder();
    /** 重灌写入的切片条数（换模型/换后端时这个数应≈换之前的切片总数） */
    private static final LongAdder KB_REINDEX_CHUNKS = new LongAdder();
    /** archive 累计写入字节数（原文权威源的体积观测：换 BlobStore 介质时看费用） */
    private static final LongAdder KB_ARCHIVE_BYTES = new LongAdder();
    /** 对账回收的孤儿 archive 键数（archive 有、索引无） */
    private static final LongAdder KB_ARCHIVE_ORPHANS = new LongAdder();
    /** 对账补写的缺档切片文件数（索引有、archive 无——写序失败的痕迹，应趋近 0） */
    private static final LongAdder KB_ARCHIVE_MISSING = new LongAdder();

    private static final Instant STARTED_AT = Instant.now();

    /** 历史环形缓冲：旧点在头、新点在尾；读写都走锁，容量小不值得上无锁结构 */
    private static final Deque<Map<String, Object>> HISTORY = new ArrayDeque<>();

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
     * 受理一次工具实例心跳。
     *
     * <p>与 {@link #instanceHeartbeatFailure()} 成对出现是有意的：失败数单独摆着没有
     * 分母，10 次失败在 100 次心跳里和在 10,000 次里是完全两回事。</p>
     */
    public static void instanceHeartbeat() {
        INSTANCE_HEARTBEATS.increment();
    }

    /** 一次被拒的心跳（实例已被强制下线，接入层回 410 Gone） */
    public static void instanceHeartbeatFailure() {
        INSTANCE_HEARTBEAT_FAILURES.increment();
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

    /**
     * 一轮孤儿 blob 回收。
     *
     * <p>四个数要一起看：scanned 长期为零说明字节没在往 blob 落（或登记表永不过期），
     * reclaimed 持续上涨是正常的（暂存区本来就在周转），failures 不为零说明有删不动的
     * 对象——那种情况下 reclaimed 会停在原地，磁盘/费用却在静默地烧。</p>
     */
    public static void blobSweep(int scanned, int reclaimed, int failed) {
        BLOB_SWEEPS.increment();
        BLOBS_SCANNED.add(Math.max(0, scanned));
        BLOBS_RECLAIMED.add(Math.max(0, reclaimed));
        if (failed > 0) {
            BLOB_SWEEP_FAILURES.add(failed);
        }
    }

    /** 一批切片写入存储成功（{@code store.write} 一次调用算一批） */
    public static void kbStoreWrite() {
        KB_STORE_WRITES.increment();
    }

    /** 一批切片写入存储失败（调用方按 fail-closed 回滚） */
    public static void kbStoreWriteFailure() {
        KB_STORE_FAILURES.increment();
    }

    /** 重灌写入的切片条数（按批累计） */
    public static void kbReindexChunks(int chunks) {
        KB_REINDEX_CHUNKS.add(Math.max(0, chunks));
    }

    /** archive 写入的字节数（一个文档一个 JSON 文件，按次累计） */
    public static void kbArchiveBytes(long bytes) {
        KB_ARCHIVE_BYTES.add(Math.max(0, bytes));
    }

    /** 对账回收的孤儿 archive 键（archive 有、索引登记表无） */
    public static void kbArchiveOrphan() {
        KB_ARCHIVE_ORPHANS.increment();
    }

    /** 对账补写的缺档（索引有、archive 无）：持续上涨说明 ingest 后的 archive 写入在失败 */
    public static void kbArchiveMissing() {
        KB_ARCHIVE_MISSING.increment();
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

    /** 快照；键名是对外契约，改动需同步 {@code docs/设计文档.md} F02 §2.7 的指标表 */
    public static Map<String, Object> snapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uptimeSeconds", Duration.between(STARTED_AT, Instant.now()).toSeconds());
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("chatRequests", CHAT_REQUESTS.sum());
        counters.put("resumeRequests", RESUME_REQUESTS.sum());
        counters.put("stopRequests", STOP_REQUESTS.sum());
        counters.put("toolCalls", TOOL_CALLS.sum());
        counters.put("toolFailures", TOOL_FAILURES.sum());
        counters.put("instanceHeartbeats", INSTANCE_HEARTBEATS.sum());
        counters.put("instanceHeartbeatFailures", INSTANCE_HEARTBEAT_FAILURES.sum());
        counters.put("recallSearches", RECALL_SEARCHES.sum());
        counters.put("recallHits", RECALL_HITS.sum());
        counters.put("recallMisses", RECALL_MISSES.sum());
        counters.put("recallFallbacks", RECALL_FALLBACKS.sum());
        counters.put("recallToolCalls", RECALL_TOOL_CALLS.sum());
        counters.put("recallBudgetBlocked", RECALL_BUDGET_BLOCKED.sum());
        counters.put("indexedTurns", INDEXED_TURNS.sum());
        counters.put("indexedTerms", INDEXED_TERMS.sum());
        counters.put("indexWriteFailures", INDEX_WRITE_FAILURES.sum());
        counters.put("blobSweeps", BLOB_SWEEPS.sum());
        counters.put("blobsScanned", BLOBS_SCANNED.sum());
        counters.put("blobsReclaimed", BLOBS_RECLAIMED.sum());
        counters.put("blobSweepFailures", BLOB_SWEEP_FAILURES.sum());
        counters.put("kbStoreWrites", KB_STORE_WRITES.sum());
        counters.put("kbStoreFailures", KB_STORE_FAILURES.sum());
        counters.put("kbReindexChunks", KB_REINDEX_CHUNKS.sum());
        counters.put("kbArchiveBytes", KB_ARCHIVE_BYTES.sum());
        counters.put("kbArchiveOrphans", KB_ARCHIVE_ORPHANS.sum());
        counters.put("kbArchiveMissing", KB_ARCHIVE_MISSING.sum());
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

    /* ===== 趋势（固定窗口环形缓冲） ===== */

    /**
     * 记一个采样点。
     *
     * <p>存<b>累计值</b>而不是区间增量：这样查询侧可以按任意两点算区间速率，
     * 丢点、乱序、重复采都不影响读数；若存增量，丢一个点这段就成了看不见的洞。</p>
     */
    public static synchronized void sample() {
        HISTORY.addLast(samplePoint());
        while (HISTORY.size() > MAX_POINTS) {
            HISTORY.pollFirst();
        }
    }

    /**
     * 历史点（旧 → 新），末尾附一个实时点。
     *
     * <p>实时点与最后采样点同秒时<b>替换</b>而不是并排追加：两次读数落在同一秒
     * 会画出零间隔双点，前端算增量得到一段假的"零流量"。</p>
     *
     * @return 每点 {@code {t: epochSecond, counters: {...}}}；副本，调用方可改
     */
    public static synchronized List<Map<String, Object>> history() {
        List<Map<String, Object>> list = new ArrayList<>(HISTORY);
        Map<String, Object> now = samplePoint();
        if (!list.isEmpty()
                && Objects.equals(list.get(list.size() - 1).get("t"), now.get("t"))) {
            list.set(list.size() - 1, now);
            return list;
        }
        list.add(now);
        return list;
    }

    /** 当前时刻的采样点：t 取整到秒，粒度足以画分钟级趋势，又不至于把窗口切碎 */
    private static Map<String, Object> samplePoint() {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("t", Instant.now().getEpochSecond());
        point.put("counters", new LinkedHashMap<>(countersOf(snapshot())));
        return point;
    }

    /** 当前历史窗口容量（对外说明"最长能看多久"，避免每处硬编码 60） */
    public static int historyCapacity() {
        return MAX_POINTS;
    }

    /** snapshot() 的 counters 段（包内可见：测试与自身采样用） */
    @SuppressWarnings("unchecked")
    static Map<String, Object> countersOf(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("counters");
    }

    /** 包内只读视图：测试断言环形缓冲行为用 */
    static List<Map<String, Object>> historyForTest() {
        return Collections.unmodifiableList(new ArrayList<>(HISTORY));
    }
}
