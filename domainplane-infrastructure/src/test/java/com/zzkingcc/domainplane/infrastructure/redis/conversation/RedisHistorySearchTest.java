package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.FlowEvent;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.common.util.CjkTokenizer;
import com.zzkingcc.domainplane.common.util.TokenizerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 倒排检索：一词根一 ZSET + 登记 Set，打分 = 词根权重和 + 新近衰减。
 *
 * <p>这里锁的不变式：</p>
 * <ul>
 *   <li><b>索引是派生数据</b>：写失败只降级不抛（归档还在，重放即可重建）；</li>
 *   <li><b>检索主路径塌了要走子串兜底</b>：索引不达不能等于"卡片段消失"；</li>
 *   <li><b>删域必须 fail-closed</b>：漏删等于删域成功之后旧话还能被勾回来；</li>
 *   <li><b>开放轮 / 读不到的轮不进卡片</b>：半截对话不是可引用的结论。</li>
 * </ul>
 *
 * <p>Redis 侧用内存桩：ZSET/SET 真的按命令语义执行（ZADD 覆盖同分、SADD 计数），
 * pipeline 的回调真的被调起来，桩的是传输层，不是行为层。</p>
 *
 * @author zzkingcc
 */
@DisplayName("M3 倒排检索：索引写入 / 打分排序 / 兜底 / 删域级联")
class RedisHistorySearchTest {

    private static final String SESSION = "default.sales|s-1";
    private static final String IDX = "domainplane:conv:idx:";
    private static final String TERMS = "domainplane:conv:terms:";

    // ==================== 桩 ====================

    /** 内存版游标 */
    private static class ListCursor implements Cursor<String> {
        private final Iterator<String> delegate;

        ListCursor(Collection<String> keys) {
            this.delegate = keys.iterator();
        }

        @Override public boolean hasNext() { return delegate.hasNext(); }
        @Override public String next() {
            if (!delegate.hasNext()) {
                throw new NoSuchElementException();
            }
            return delegate.next();
        }
        @Override public void close() { }
        @Override public CursorId getId() { return CursorId.of(0L); }
        @Override public long getCursorId() { return 0L; }
        @Override public boolean isClosed() { return false; }
        @Override public long getPosition() { return 0L; }
    }

    /** pipeline 内每条命令的返回值，按命令顺序收集（对齐 Lettuce 的结果列表语义） */
    private static final class CommandLog {
        final List<Object> results = new ArrayList<>();
    }

    /**
     * 内存 Redis 桩：ZSET（member→score）+ Set 两张表，
     * {@code executePipelined} 把回调真的跑一遍（命令效果落在表上，返回值进日志）。
     */
    private static class StubRedisTemplate extends StringRedisTemplate {

        final Map<String, Map<String, Double>> zsets = new LinkedHashMap<>();
        final Map<String, Set<String>> sets = new LinkedHashMap<>();

        int pipelineCalls;
        RuntimeException pipelineFailure;

        final Deque<List<String>> scanAnswers = new ArrayDeque<>();
        RuntimeException scanFailure;
        final List<String> scanPatterns = new ArrayList<>();

        RuntimeException deleteFailure;
        int deleteCalls;
        final List<List<String>> deletedBatches = new ArrayList<>();

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public List<Object> executePipelined(SessionCallback<?> session) {
            pipelineCalls++;
            if (pipelineFailure != null) {
                throw pipelineFailure;
            }
            CommandLog log = new CommandLog();
            RedisOperations<String, String> ops = opsProxy(log);
            // SessionCallback.execute 是泛型方法，经原生类型分发（与实现侧同一约束）
            ((SessionCallback) session).execute(ops);
            return log.results;
        }

        @Override
        public Cursor<String> scan(ScanOptions options) {
            scanPatterns.add(options.getPattern());
            if (scanFailure != null) {
                throw scanFailure;
            }
            List<String> answer = scanAnswers.poll();
            return new ListCursor(answer == null ? List.of() : answer);
        }

        @Override
        public Long delete(Collection<String> keys) {
            if (keys.isEmpty()) {
                return 0L;
            }
            deleteCalls++;
            deletedBatches.add(new ArrayList<>(keys));
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            return (long) keys.size();
        }

        @Override public SetOperations<String, String> opsForSet() { return setOps(null); }
        @Override public ZSetOperations<String, String> opsForZSet() { return zsetOps(null); }

        private RedisOperations<String, String> opsProxy(CommandLog log) {
            return (RedisOperations<String, String>) Proxy.newProxyInstance(
                    StubRedisTemplate.class.getClassLoader(),
                    new Class<?>[]{RedisOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "opsForZSet" -> zsetOps(log);
                        case "opsForSet" -> setOps(log);
                        /* 索引键现在带 TTL（派生数据要能自然回收）。这条命令不产生
                           pipeline 结果，所以不往 log.results 里塞东西。 */
                        case "expire" -> Boolean.TRUE;
                        case "toString" -> "stub-redis-operations";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 RedisOperations 方法：" + method.getName());
                    });
        }

        private ZSetOperations<String, String> zsetOps(CommandLog log) {
            return (ZSetOperations<String, String>) Proxy.newProxyInstance(
                    StubRedisTemplate.class.getClassLoader(),
                    new Class<?>[]{ZSetOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "add" -> {
                            zsets.computeIfAbsent((String) args[0], k -> new LinkedHashMap<>())
                                    .put((String) args[1], ((Number) args[2]).doubleValue());
                            if (log != null) {
                                log.results.add(Boolean.TRUE);
                            }
                            yield Boolean.TRUE;
                        }
                        case "reverseRange" -> {
                            Set<String> range = reverseRange((String) args[0],
                                    ((Number) args[1]).longValue(), ((Number) args[2]).longValue());
                            if (log != null) {
                                log.results.add(range);
                            }
                            yield range;
                        }
                        case "toString" -> "stub-zset-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 ZSetOperations 方法：" + method.getName());
                    });
        }

        private SetOperations<String, String> setOps(CommandLog log) {
            return (SetOperations<String, String>) Proxy.newProxyInstance(
                    StubRedisTemplate.class.getClassLoader(),
                    new Class<?>[]{SetOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "add" -> {
                            Set<String> set = sets.computeIfAbsent((String) args[0],
                                    k -> new LinkedHashSet<>());
                            long added = 0;
                            for (Object v : (Object[]) args[1]) {
                                if (set.add((String) v)) {
                                    added++;
                                }
                            }
                            if (log != null) {
                                log.results.add(added);
                            }
                            yield added;
                        }
                        case "members" -> sets.getOrDefault((String) args[0], Set.of());
                        case "toString" -> "stub-set-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 SetOperations 方法：" + method.getName());
                    });
        }

        /** score 降序；同分按成员序，保证可复现 */
        private Set<String> reverseRange(String key, long start, long end) {
            Map<String, Double> members = zsets.getOrDefault(key, Map.of());
            List<String> ordered = members.entrySet().stream()
                    .sorted((a, b) -> {
                        int c = Double.compare(b.getValue(), a.getValue());
                        return c != 0 ? c : a.getKey().compareTo(b.getKey());
                    })
                    .map(Map.Entry::getKey).toList();
            int size = ordered.size();
            long f = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
            long t = end < 0 ? size + end : Math.min(end, size - 1);
            if (size == 0 || f > t || t < 0) {
                return new LinkedHashSet<>();
            }
            return new LinkedHashSet<>(ordered.subList((int) f, (int) t + 1));
        }
    }

    /** 内存归档：只实现检索用得到的两个读方法 */
    private static class StubArchive implements ConversationArchive {

        final Map<String, List<TurnRecord>> data = new LinkedHashMap<>();
        final Set<Long> failTurnIds = new HashSet<>();

        @Override public long openTurn(String sessionKey, UserInput user) { return 0; }
        @Override public void closeTurn(String sessionKey, long turnId, TurnRecord record) {}
        @Override public void abortTurn(String sessionKey, long turnId, String reason, String partialAnswer) {}
        @Override public void rejectTurn(String sessionKey, long turnId) {}
        @Override public void deleteTurn(String sessionKey, long turnId) {}
        @Override public Optional<TurnRecord> findOpenTurn(String sessionKey) { return Optional.empty(); }
        @Override public void clearSession(String sessionKey) {}
        @Override public int deleteByDomain(String domain) { return 0; }
        @Override public int recoverStaleOpenTurn(String sessionKey) { return 0; }

        @Override public Optional<TurnRecord> turnAt(String sessionKey, long turnId) {
            if (failTurnIds.contains(turnId)) {
                throw new IllegalStateException("读 turnId=" + turnId + " 失败（桩）");
            }
            return data.getOrDefault(sessionKey, List.of()).stream()
                    .filter(t -> t.turnId() == turnId).findFirst();
        }

        @Override public List<TurnRecord> recentTurns(String sessionKey, int limit) {
            List<TurnRecord> all = data.getOrDefault(sessionKey, List.of());
            int k = Math.max(limit, 0);
            int from = Math.max(0, all.size() - k);
            return new ArrayList<>(all.subList(from, all.size()));
        }

        @Override public List<TurnRecord> turnsBetween(String sessionKey, long fromInclusive, long toInclusive) {
            List<TurnRecord> out = new ArrayList<>();
            for (TurnRecord t : data.getOrDefault(sessionKey, List.of())) {
                if (t.turnId() >= fromInclusive && t.turnId() <= toInclusive) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    private static RedisHistorySearch search(StubRedisTemplate stub, StubArchive archive) {
        return new RedisHistorySearch(stub, archive);
    }

    /** M4：按注入的分词调优参数构造（权重负责排序，追加停用词负责滤噪） */
    private static RedisHistorySearch search(StubRedisTemplate stub, StubArchive archive,
                                             TokenizerProfile profile) {
        return new RedisHistorySearch(stub, archive, profile);
    }

    private static TurnRecord closed(long turnId, String question, String answer,
                                     String digest, String... tools) {
        List<FlowEvent> flow = new ArrayList<>();
        for (String t : tools) {
            flow.add(FlowEvent.ofTool(FlowEventType.TOOL_CALL, t, "{}"));
        }
        return new TurnRecord(turnId, SESSION,
                Instant.parse("2026-10-14T10:00:00Z").plusSeconds(turnId),
                Instant.parse("2026-10-14T10:00:08Z").plusSeconds(turnId),
                UserInput.of(question, List.of()), flow, answer,
                new TokenUsage(1, 2, 3), new TurnFlags(false, false, false, null), digest);
    }

    private static TurnRecord open(long turnId, String question) {
        return new TurnRecord(turnId, SESSION, Instant.parse("2026-10-14T10:00:00Z"),
                null, UserInput.of(question, List.of()), List.of(), null, null,
                TurnFlags.open(), null);
    }

    private static List<Long> turnIdsOf(List<HistoryCard> cards) {
        return cards.stream().map(HistoryCard::turnId).toList();
    }

    // ==================== 写索引 ====================

    @Test
    @DisplayName("写索引：一词根一 ZADD + 登记 Set 一次 SADD，member/score 都是 turnId")
    void indexTurnWritesTermZSetsAndRegistry() {
        StubRedisTemplate stub = new StubRedisTemplate();
        TurnRecord turn = closed(1, "订单怎么还没到", "催单成功了", "催单", "query_order");

        search(stub, new StubArchive()).indexTurn(SESSION, turn);

        assertEquals(1, stub.pipelineCalls, "一轮一个 pipeline");

        // 期望词根 = 索引文本（摘要 + 提问 + 回答 + 工具名）切出来的全部词根
        String indexText = "催单\n订单怎么还没到\n催单成功了\nquery_order";
        List<String> expected = CjkTokenizer.tokenize(indexText).stream()
                .map(CjkTokenizer.Term::text).distinct().toList();
        assertEquals(expected.size(), stub.zsets.size(), "每个词根一个 ZSET，一个不多一个不少");
        for (String term : expected) {
            String key = IDX + SESSION + ":" + term;
            assertTrue(stub.zsets.containsKey(key), "缺词根 " + term + " 的 ZSET");
            assertEquals(Map.of("1", 1.0), stub.zsets.get(key),
                    "member 与 score 都应为 turnId（score 即轮号，天然支持取最新 N 轮）");
        }
        assertEquals(new LinkedHashSet<>(expected),
                stub.sets.get(TERMS + SESSION), "登记 Set 应收录本会话全部词根");
    }

    @Test
    @DisplayName("写索引：开放轮 / 空会话键 / 空索引文本，一个命令都不发")
    void indexTurnSkipsUnindexable() {
        StubRedisTemplate openStub = new StubRedisTemplate();
        search(openStub, new StubArchive()).indexTurn(SESSION, open(1, "问一半"));
        assertEquals(0, openStub.pipelineCalls, "开放轮不进索引");

        StubRedisTemplate nullKey = new StubRedisTemplate();
        search(nullKey, new StubArchive()).indexTurn(null, closed(1, "问", "答", "摘要"));
        search(nullKey, new StubArchive()).indexTurn("  ", closed(1, "问", "答", "摘要"));
        assertEquals(0, nullKey.pipelineCalls, "空会话键不进索引");

        StubRedisTemplate nullRecord = new StubRedisTemplate();
        search(nullRecord, new StubArchive()).indexTurn(SESSION, null);
        assertEquals(0, nullRecord.pipelineCalls);

        // 只有标点、无任何词根的轮：不该发空 pipeline
        StubRedisTemplate blankText = new StubRedisTemplate();
        search(blankText, new StubArchive()).indexTurn(SESSION,
                closed(1, "，，。", null, null));
        assertEquals(0, blankText.pipelineCalls, "没有词根就没有索引");
    }

    @Test
    @DisplayName("写索引失败只告警不抛：归档已落盘，重放即可重建")
    void indexTurnFailureOnlyWarns() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.pipelineFailure = new IllegalStateException("Redis 连接不可用");

        search(stub, new StubArchive()).indexTurn(SESSION, closed(1, "订单", "答", "摘要"));
        assertEquals(1, stub.pipelineCalls, "命令确实发过，失败被吞掉");
    }

    // ==================== 检索与打分 ====================

    /** 三轮归档：t1 只命中「订单」，t2 只命中「物流」，t3 两个都命中 */
    private static void threeTurnFixture(StubRedisTemplate stub, StubArchive archive) {
        RedisHistorySearch search = search(stub, archive);
        TurnRecord t1 = closed(1, "订单怎么还没到", "催单成功了", "催单", "query_order");
        TurnRecord t2 = closed(2, "物流信息查一下", "物流到哪了", "查物流", "query_logistics");
        TurnRecord t3 = closed(3, "订单物流都要查", "已查订单和物流", "订单与物流查询",
                "query_order", "query_logistics");
        for (TurnRecord t : List.of(t1, t2, t3)) {
            archive.data.computeIfAbsent(SESSION, k -> new ArrayList<>()).add(t);
            search.indexTurn(SESSION, t);
        }
    }

    @Test
    @DisplayName("打分：多词根命中压过单词根；权重相同则新近优先")
    void searchRanksByWeightThenRecency() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        threeTurnFixture(stub, archive);

        // 「订单 物流」两个 bigram：t3 双命中(4+2)，t2/t1 单命中(2+衰减)
        assertEquals(List.of(3L, 2L, 1L),
                turnIdsOf(search(stub, archive).search(SESSION, "订单 物流", 10)));

        // limit 生效：只取前两名
        assertEquals(List.of(3L, 2L),
                turnIdsOf(search(stub, archive).search(SESSION, "订单 物流", 2)));

        // 单词根查询：t3 与 t2 权重同为 2，新的 t3 在前（新近衰减兜底）
        assertEquals(List.of(3L, 2L),
                turnIdsOf(search(stub, archive).search(SESSION, "物流", 10)));
    }

    @Test
    @DisplayName("卡片带摘要与工具名，不带全文")
    void cardsCarryDigestAndToolsNotFullText() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        threeTurnFixture(stub, archive);

        List<HistoryCard> cards = search(stub, archive).search(SESSION, "订单 物流", 10);
        HistoryCard top = cards.get(0);
        assertEquals(3L, top.turnId());
        assertEquals("订单与物流查询", top.digest());
        assertEquals(List.of("query_order", "query_logistics"), top.tools());
        assertFalse(cards.isEmpty());
    }

    @Test
    @DisplayName("索引查无（从没写过索引）→ 子串扫描兜底，老数据照样能被找到")
    void searchFallsBackToSubstringScanWhenIndexEmpty() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        archive.data.put(SESSION, List.of(closed(1, "这个订单怎么退款", "已退款", "退款")));

        // 不写索引，直接查
        List<HistoryCard> cards = search(stub, archive).search(SESSION, "退款", 5);
        assertEquals(List.of(1L), turnIdsOf(cards));
        assertEquals(1, stub.pipelineCalls, "兜底前仍先试了倒排");
    }

    @Test
    @DisplayName("倒排查询失败（Redis 抖动）→ 降级子串扫描，不抛、不空手")
    void searchDegradesWhenPipelineFails() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        threeTurnFixture(stub, archive);
        stub.pipelineFailure = new IllegalStateException("连接被重置");

        List<HistoryCard> cards = search(stub, archive).search(SESSION, "订单", 5);
        // 子串兜底按"最新优先"匹配：t3、t1 的索引文本都含「订单」
        assertEquals(List.of(3L, 1L), turnIdsOf(cards));
    }

    @Test
    @DisplayName("查无结果：返回空列表，不抛")
    void searchReturnsEmptyWhenNothingMatches() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        threeTurnFixture(stub, archive);

        assertTrue(search(stub, archive).search(SESSION, "退款退货", 5).isEmpty());

        // 归档整个是空的：兜底也空
        assertTrue(search(new StubRedisTemplate(), new StubArchive())
                .search(SESSION, "订单", 5).isEmpty());
    }

    @Test
    @DisplayName("空参数直接返回空：不发任何 Redis 命令")
    void searchRejectsBlankArguments() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        RedisHistorySearch search = search(stub, archive);

        assertTrue(search.search(null, "订单", 5).isEmpty());
        assertTrue(search.search("  ", "订单", 5).isEmpty());
        assertTrue(search.search(SESSION, null, 5).isEmpty());
        assertTrue(search.search(SESSION, "   ", 5).isEmpty());
        assertTrue(search.search(SESSION, "订单", 0).isEmpty());
        assertEquals(0, stub.pipelineCalls);
    }

    @Test
    @DisplayName("索引里的轮在归档中已变成开放轮（竞态）→ 不进卡片")
    void searchSkipsOpenTurns() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        // 手工往索引里塞一条开放轮的命中（indexTurn 本身拒绝开放轮，这是防御路径）
        stub.zsets.put(IDX + SESSION + ":订单", new LinkedHashMap<>(Map.of("1", 1.0)));
        archive.data.put(SESSION, List.of(open(1, "订单问一半")));

        assertTrue(search(stub, archive).search(SESSION, "订单", 5).isEmpty(),
                "半截对话不是可引用的结论");
    }

    @Test
    @DisplayName("读某一轮归档失败：只跳过该轮，其余命中照常返回")
    void searchSkipsUnreadableTurns() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        threeTurnFixture(stub, archive);
        archive.failTurnIds.add(2L);

        assertEquals(List.of(3L, 1L),
                turnIdsOf(search(stub, archive).search(SESSION, "订单 物流", 10)));
    }

    // ==================== 删域级联 ====================

    @Test
    @DisplayName("删域：空域拒绝执行，且一个 Redis 命令都不发")
    void deleteByDomainRejectsBlank() {
        for (String blank : new String[]{null, "", "   "}) {
            StubRedisTemplate stub = new StubRedisTemplate();
            ChatMemoryException e = assertThrows(ChatMemoryException.class,
                    () -> search(stub, new StubArchive()).deleteByDomain(blank),
                    "空域会让 pattern 退化成匹配全部域：[" + blank + "]");
            assertEquals("INVALID_PARAMETER", e.getCodeName());
            assertEquals(0, stub.deleteCalls);
            assertTrue(stub.scanPatterns.isEmpty(), "守卫必须落在拼 pattern 之前");
        }
    }

    @Test
    @DisplayName("删域：登记表展开删索引键 → 删登记键 → 前缀兜底，计数为三者之和")
    void deleteByDomainDeletesRegistryThenTermsThenLeftovers() {
        StubRedisTemplate stub = new StubRedisTemplate();
        String termsKey = TERMS + SESSION;
        stub.sets.put(termsKey, new LinkedHashSet<>(List.of("订单", "物流")));
        // 第一次 SCAN（登记表）命中一个会话；第二次 SCAN（索引前缀）捡到一条漏网之鱼
        stub.scanAnswers.add(List.of(termsKey));
        stub.scanAnswers.add(List.of(IDX + "default.sales|s-9:残留"));

        int deleted = search(stub, new StubArchive()).deleteByDomain("default.sales");

        assertEquals(4, deleted, "2 索引键 + 1 登记键 + 1 兜底键");
        assertEquals(List.of(TERMS + "default.sales|*", IDX + "default.sales|*"),
                stub.scanPatterns, "先按登记表精确删，再按前缀兜底");
        assertEquals(List.of(
                        List.of(IDX + SESSION + ":订单", IDX + SESSION + ":物流"),
                        List.of(termsKey),
                        List.of(IDX + "default.sales|s-9:残留")),
                stub.deletedBatches, "删除顺序：登记表展开的索引键 → 登记键 → 兜底键");
    }

    @Test
    @DisplayName("删域：SCAN 与 DEL 失败都必须抛（只 warn 等于静默漏删）")
    void deleteByDomainFailsClosed() {
        StubRedisTemplate scanBroken = new StubRedisTemplate();
        scanBroken.scanFailure = new IllegalStateException("Redis 连接不可用");
        ChatMemoryException e1 = assertThrows(ChatMemoryException.class,
                () -> search(scanBroken, new StubArchive()).deleteByDomain("default.sales"));
        assertEquals("CHAT_MEMORY_DELETE_ERROR", e1.getCodeName());

        StubRedisTemplate delBroken = new StubRedisTemplate();
        delBroken.sets.put(TERMS + SESSION, Set.of("订单"));
        delBroken.scanAnswers.add(List.of(TERMS + SESSION));
        delBroken.deleteFailure = new IllegalStateException("READONLY");
        ChatMemoryException e2 = assertThrows(ChatMemoryException.class,
                () -> search(delBroken, new StubArchive()).deleteByDomain("default.sales"));
        assertEquals("CHAT_MEMORY_DELETE_ERROR", e2.getCodeName());
        assertTrue(e2.getMessage().contains("残留"), "文案要说明键可能残留：" + e2.getMessage());
    }

    @Test
    @DisplayName("删域：什么都没命中时不发 DEL，两次 SCAN 的 pattern 只覆盖本域")
    void deleteByDomainWithNothingToDelete() {
        StubRedisTemplate stub = new StubRedisTemplate();
        assertEquals(0, search(stub, new StubArchive()).deleteByDomain("default.sales"));
        assertEquals(0, stub.deleteCalls, "没命中就不该发 DEL");
        assertEquals(List.of(TERMS + "default.sales|*", IDX + "default.sales|*"),
                stub.scanPatterns);
    }

    @Test
    @DisplayName("接口常量：候选与兜底口径没有漂移")
    void constantsHold() {
        assertEquals(200, HistorySearch.MAX_TERMS_PER_TURN);
        assertEquals(50, HistorySearch.CANDIDATES_PER_TERM);
        assertEquals(300, HistorySearch.MAX_CANDIDATES);
        assertEquals(200, HistorySearch.FALLBACK_TAIL);
    }

    // ==================== 可观测（M4 治理） ====================

    @SuppressWarnings("unchecked")
    private static long counter(String key) {
        Map<String, Object> counters =
                (Map<String, Object>) MetricsRegistry.snapshot().get("counters");
        return ((Number) counters.get(key)).longValue();
    }

    @Test
    @DisplayName("检索指标：命中计 hits、空手计 misses、兜底另计（且都进 searches）")
    void searchReportsHitMissAndFallback() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        threeTurnFixture(stub, archive);
        RedisHistorySearch search = search(stub, archive);

        long searches = counter("recallSearches");
        long hits = counter("recallHits");
        long misses = counter("recallMisses");
        long fallbacks = counter("recallFallbacks");

        // 倒排命中：不算兜底
        assertFalse(search.search(SESSION, "订单 物流", 5).isEmpty());
        // 倒排查无 → 兜底命中：算 hits，也算 fallback
        assertFalse(search.search(SESSION, "催单", 5).isEmpty());
        // 什么都没有：算 miss
        assertTrue(search.search(SESSION, "退货换货", 5).isEmpty());

        assertEquals(searches + 3, counter("recallSearches"));
        assertEquals(hits + 2, counter("recallHits"));
        assertEquals(misses + 1, counter("recallMisses"));
        assertEquals(fallbacks + 1, counter("recallFallbacks"), "只有中间那次走了子串兜底");
    }

    @Test
    @DisplayName("检索指标：纯标点查询算一次空手，但不是兜底")
    void blankTokenQueryIsMissWithoutFallback() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        RedisHistorySearch search = search(stub, archive);

        long searches = counter("recallSearches");
        long misses = counter("recallMisses");
        long fallbacks = counter("recallFallbacks");

        assertTrue(search.search(SESSION, "，，。", 5).isEmpty());

        assertEquals(searches + 1, counter("recallSearches"));
        assertEquals(misses + 1, counter("recallMisses"));
        assertEquals(fallbacks, counter("recallFallbacks"), "没词根就不该去扫子串");
    }

    @Test
    @DisplayName("索引指标：成功计轮数与词根数，失败计失败数")
    void indexReportsTurnsTermsAndFailures() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisHistorySearch search = search(stub, new StubArchive());

        long turns = counter("indexedTurns");
        long terms = counter("indexedTerms");
        long failures = counter("indexWriteFailures");

        search.indexTurn(SESSION, closed(1, "订单怎么还没到", "催单成功了", "催单", "query_order"));
        assertEquals(turns + 1, counter("indexedTurns"));
        assertTrue(counter("indexedTerms") > terms, "词根数应随写入增长");

        stub.pipelineFailure = new IllegalStateException("Redis 连接不可用");
        search.indexTurn(SESSION, closed(2, "物流到哪了", "在路上", "查物流"));
        assertEquals(failures + 1, counter("indexWriteFailures"));
        assertEquals(turns + 1, counter("indexedTurns"), "写失败不该计成一轮成功索引");
    }

    // ==================== 分词调优（M4 运行期注入） ====================

    @Test
    @DisplayName("调优：追加停用词在写索引与查询两侧都生效（索引侧不产键、查询侧切不出词根）")
    void extraStopWordsApplyOnBothSides() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        // "查" 是单字、"order" 是整词：都被追加停用词滤掉 → 这一轮没有任何词根
        TokenizerProfile tuned = new TokenizerProfile(3, 2, 1, Set.of("查", "order"));
        RedisHistorySearch search = search(stub, archive, tuned);

        search.indexTurn(SESSION, closed(1, "查", "order", null));
        assertEquals(0, stub.pipelineCalls, "索引侧全部词根被滤 → 不发空 pipeline");
        assertEquals(0, stub.zsets.size());

        // 查询侧同一口径：切不出词根 → 空手返回，且不去扫子串兜底
        assertTrue(search.search(SESSION, "查 order", 5).isEmpty());
        assertEquals(0, stub.pipelineCalls, "无词根查询不该再发倒排命令");
    }

    @Test
    @DisplayName("调优：权重只影响查询打分，改变 bigram/整词 的相对分量即可翻转排序")
    void customWeightsFlipRanking() {
        StubRedisTemplate stub = new StubRedisTemplate();
        StubArchive archive = new StubArchive();
        RedisHistorySearch search = search(stub, archive);
        // t1 只含拉丁整词 "order"；t2 只含 CJK bigram "订单"
        TurnRecord t1 = closed(1, "order status", "order ok", "order");
        TurnRecord t2 = closed(2, "订单状态", "订单已发", "订单");
        archive.data.put(SESSION, new ArrayList<>(List.of(t1, t2)));
        search.indexTurn(SESSION, t1);
        search.indexTurn(SESSION, t2);

        // 默认权重 整词3 > bigram2：t1 压过 t2（新近衰减不足以翻盘）
        assertEquals(List.of(1L, 2L),
                turnIdsOf(search(stub, archive).search(SESSION, "订单 order", 10)));

        // 调成 bigram 重于整词：查询侧打分翻转，t2 上位
        // （权重不参与切词，索引键与文本无关，无需重建索引即可改分）
        TokenizerProfile bigramHeavy = new TokenizerProfile(1, 5, 1, Set.of());
        assertEquals(List.of(2L, 1L),
                turnIdsOf(search(stub, archive, bigramHeavy).search(SESSION, "订单 order", 10)));
    }
}
