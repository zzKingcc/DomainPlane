package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.Cursor.CursorId;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话体量盘点：F04-d 按轮号区间分页，F04-e 按域列会话。
 *
 * <p>分页锁四条：不给 from 落最后一页；from 越界回落而不是给空页；size 有上下限；
 * 装配清单只挂本页轮号（且丢掉 turnId=0 的开轮失败账）。
 * 清单锁三条：域筛选精确匹配、域清单不受筛选影响、按轮数降序取 top N。</p>
 *
 * @author zzkingcc
 */
@DisplayName("会话体量盘点：分页区间 / 按域筛选 / 轮数降序")
class RedisSessionStorageTest {

    private static final String KEY = "default.sales|s-1";
    private static final String ARCHIVE = "domainplane:chat:archive:";
    private static final String MEMORY = "domainplane:chat:memory:";
    private static final String ASSEMBLY = "domainplane:conv:asm:";

    private static TurnRecord turn(long id) {
        return new TurnRecord(id, KEY, null, null, null, List.of(),
                "answer-" + id, null, null, "digest-" + id);
    }

    private static ContextAssemblyReport report(long turnId) {
        return new ContextAssemblyReport(KEY, turnId, null, 24_000,
                0, 0, 0, 0, 0, 0, List.of(),
                0, 0, 0, 0, 0, false);
    }

    /**
     * 归档键的长度由 LLEN 提供，分页代码只认这个 O(1) 数字。
     * 没配 lengths 时所有键都答同一个 size（分页用例）；配了就按键分别答（清单用例）。
     */
    private static class StubTemplate extends StringRedisTemplate {
        private final long size;
        final Map<String, Long> lengths = new LinkedHashMap<>();
        final List<String> archiveKeys = new ArrayList<>();

        StubTemplate(long size) {
            this.size = size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ListOperations<String, String> opsForList() {
            return (ListOperations<String, String>) Proxy.newProxyInstance(
                    StubTemplate.class.getClassLoader(),
                    new Class<?>[]{ListOperations.class},
                    (proxy, method, args) -> {
                        if (!"size".equals(method.getName())) {
                            return null;
                        }
                        return lengths.isEmpty()
                                ? size : lengths.getOrDefault(String.valueOf(args[0]), 0L);
                    });
        }

        @Override
        public Cursor<String> scan(ScanOptions options) {
            return new ListCursor(archiveKeys);
        }
    }

    private static final class ListCursor implements Cursor<String> {
        private final Iterator<String> delegate;

        ListCursor(List<String> keys) {
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

    /** 内存归档桩：记下本次请求的区间，按 turnId 截出本页 */
    private static ConversationArchive archiveOf(List<TurnRecord> all, long[] range) {
        return (ConversationArchive) Proxy.newProxyInstance(
                RedisSessionStorageTest.class.getClassLoader(),
                new Class<?>[]{ConversationArchive.class},
                (proxy, method, args) -> {
                    if (!"turnsBetween".equals(method.getName())) {
                        return null;
                    }
                    long lo = (Long) args[1], hi = (Long) args[2];
                    range[0] = lo;
                    range[1] = hi;
                    List<TurnRecord> out = new ArrayList<>();
                    for (TurnRecord t : all) {
                        if (t.turnId() >= lo && t.turnId() <= hi) {
                            out.add(t);
                        }
                    }
                    return out;
                });
    }

    private static AssemblyReportStore reportsOf(List<ContextAssemblyReport> reports) {
        return (AssemblyReportStore) Proxy.newProxyInstance(
                RedisSessionStorageTest.class.getClassLoader(),
                new Class<?>[]{AssemblyReportStore.class},
                (proxy, method, args) -> "recent".equals(method.getName()) ? reports : null);
    }

    private static List<TurnRecord> turns(long total) {
        List<TurnRecord> out = new ArrayList<>();
        for (long i = 1; i <= total; i++) {
            out.add(turn(i));
        }
        return out;
    }

    private static RedisSessionStorage storage(long total, long[] range,
                                               List<ContextAssemblyReport> reports) {
        return new RedisSessionStorage(new StubTemplate(total),
                archiveOf(turns(total), range), reportsOf(reports), null);
    }

    /** 清单用例：造一批归档键，并给每个会话的三类键分别配好 LLEN */
    private static StubTemplate listing(String... sessionKeys) {
        StubTemplate t = new StubTemplate(0);
        for (String k : sessionKeys) {
            t.archiveKeys.add(ARCHIVE + k);
        }
        return t;
    }

    private static void counts(StubTemplate t, String sessionKey, long turns, long messages, long reports) {
        t.lengths.put(ARCHIVE + sessionKey, turns);
        t.lengths.put(MEMORY + sessionKey, messages);
        t.lengths.put(ASSEMBLY + sessionKey, reports);
    }

    private static RedisSessionStorage listingStorage(StubTemplate t) {
        return new RedisSessionStorage(t, null, null, null);
    }

    private static StubTemplate fourSessions() {
        StubTemplate t = listing("default.sales|s-1", "default.sales|s-2",
                "default.support|t-1", "default|r-1");
        counts(t, "default.sales|s-1", 30, 60, 10);
        counts(t, "default.sales|s-2", 10, 20, 5);
        counts(t, "default.support|t-1", 50, 100, 20);
        counts(t, "default|r-1", 5, 10, 2);
        return t;
    }

    // ==================== F04-d 分页 ====================

    @Test
    @DisplayName("不给 from 落到最后一页，区间是 [total-size+1, total]")
    void defaultsToLastPage() {
        long[] range = new long[2];
        RedisSessionStorage.Page p = storage(128, range, List.of()).page(KEY, null, 20);

        assertEquals(128, p.total());
        assertEquals(109, p.from());
        assertEquals(20, p.size());
        assertEquals(109, range[0]);
        assertEquals(128, range[1]);
        assertEquals(20, p.turns().size());
        assertEquals(109, p.turns().get(0).turnId());
        assertEquals(128, p.turns().get(19).turnId());
    }

    @Test
    @DisplayName("指定 from 就按该轮号起算，旧页不因新轮追加而重排")
    void explicitFrom() {
        long[] range = new long[2];
        RedisSessionStorage.Page p = storage(128, range, List.of()).page(KEY, 89L, 20);

        assertEquals(89, p.from());
        assertEquals(89, range[0]);
        assertEquals(108, range[1]);
        assertEquals(89, p.turns().get(0).turnId());
    }

    @Test
    @DisplayName("总轮数不足一页时从 1 开始")
    void shortSession() {
        long[] range = new long[2];
        RedisSessionStorage.Page p = storage(7, range, List.of()).page(KEY, null, 20);

        assertEquals(1, p.from());
        assertEquals(1, range[0]);
        assertEquals(7, p.turns().size());
    }

    @Test
    @DisplayName("from 越过尾部回落最新页，不返回界外空页")
    void fromBeyondTotal() {
        long[] range = new long[2];
        RedisSessionStorage.Page p = storage(128, range, List.of()).page(KEY, 999L, 20);

        assertEquals(109, p.from());
        assertEquals(20, p.turns().size());
    }

    @Test
    @DisplayName("size 有上下限；空会话返回空页且不读归档")
    void sizeClampAndEmptySession() {
        long[] range = new long[2];
        RedisSessionStorage s = storage(128, range, List.of());
        assertEquals(50, s.page(KEY, 1L, 1000).size());
        assertEquals(1, s.page(KEY, 1L, 0).size());
        assertEquals(20, s.page(KEY, 1L, null).size());

        long[] emptyRange = new long[2];
        RedisSessionStorage.Page empty = storage(0, emptyRange, List.of()).page(KEY, null, 20);
        assertEquals(0, empty.total());
        assertEquals(1, empty.from());
        assertTrue(empty.turns().isEmpty());
        assertEquals(0, emptyRange[0]);
        assertEquals(0, emptyRange[1]);
    }

    @Test
    @DisplayName("装配清单只挂本页轮号，turnId=0 的账丢掉，结果按轮号升序")
    void reportsFilteredByPageRange() {
        long[] range = new long[2];
        List<ContextAssemblyReport> reports = List.of(
                report(120), report(130), report(109), report(0), report(108));
        RedisSessionStorage.Page p = storage(128, range, reports).page(KEY, null, 20);

        assertEquals(2, p.assemblyReports().size());
        assertEquals(109, p.assemblyReports().get(0).turnId());
        assertEquals(120, p.assemblyReports().get(1).turnId());
    }

    // ==================== F04-e 按域筛选 ====================

    @Test
    @DisplayName("按域精确匹配，域清单仍是全部域（下拉要能切回去）")
    void listFiltersByDomainButKeepsAllDomains() {
        RedisSessionStorage.Listing l = listingStorage(fourSessions()).list("default.sales", 50);

        assertEquals(List.of("default", "default.sales", "default.support"), l.domains());
        assertEquals(List.of("s-1", "s-2"),
                l.sessions().stream().map(s -> s.sessionId()).toList(),
                "按轮数降序；精确匹配，不含别的域");
        assertEquals(30, l.sessions().get(0).archiveTurns());
        assertEquals(60, l.sessions().get(0).memoryMessages());
        assertEquals(10, l.sessions().get(0).assemblyReports());
    }

    @Test
    @DisplayName("不给域时按轮数降序取 top N，旧入口 topBySize 走同一套")
    void listWithoutDomainRanksByTurnsAndClampsLimit() {
        RedisSessionStorage s = listingStorage(fourSessions());

        assertEquals(List.of("t-1", "s-1"),
                s.list(null, 2).sessions().stream().map(x -> x.sessionId()).toList());
        assertEquals(s.list(null, 2).sessions(), s.topBySize(2));
        assertEquals(4, s.list(null, 50).sessions().size());
    }

    @Test
    @DisplayName("空白域等同全部域；筛到不存在的域返回空会话但域清单还在")
    void blankAndUnknownDomain() {
        RedisSessionStorage s = listingStorage(fourSessions());

        assertEquals(4, s.list("   ", 50).sessions().size());
        RedisSessionStorage.Listing missing = s.list("default.ghost", 50);
        assertTrue(missing.sessions().isEmpty());
        assertEquals(3, missing.domains().size());
    }

    @Test
    @DisplayName("没有归档键时返回空清单，不报错")
    void emptyArchiveYieldsEmptyListing() {
        RedisSessionStorage.Listing l = listingStorage(listing()).list(null, 50);

        assertTrue(l.domains().isEmpty());
        assertTrue(l.sessions().isEmpty());
    }
}
