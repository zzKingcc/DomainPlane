package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F04-d 会话档案分页：按轮号区间读归档，不建索引、不新增键、不写入。
 *
 * <p>锁四条：不给 from 落最后一页；from 越界回落而不是给空页；size 有上下限；
 * 装配清单只挂本页轮号（且丢掉 turnId=0 的开轮失败账）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("F04-d 会话档案分页：轮号区间 / 最新页 / 装配清单过滤")
class RedisSessionStorageTest {

    private static final String KEY = "default.sales|s-1";

    private static TurnRecord turn(long id) {
        return new TurnRecord(id, KEY, null, null, null, List.of(),
                "answer-" + id, null, null, "digest-" + id);
    }

    private static ContextAssemblyReport report(long turnId) {
        return new ContextAssemblyReport(KEY, turnId, null, 24_000,
                0, 0, 0, 0, 0, 0, List.of(),
                0, 0, 0, 0, 0, false);
    }

    /** 归档键的长度由 LLEN 提供，分页代码只认这个 O(1) 数字 */
    private static class StubTemplate extends StringRedisTemplate {
        private final long size;

        StubTemplate(long size) {
            this.size = size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ListOperations<String, String> opsForList() {
            return (ListOperations<String, String>) Proxy.newProxyInstance(
                    StubTemplate.class.getClassLoader(),
                    new Class<?>[]{ListOperations.class},
                    (proxy, method, args) -> "size".equals(method.getName()) ? size : null);
        }
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
}
