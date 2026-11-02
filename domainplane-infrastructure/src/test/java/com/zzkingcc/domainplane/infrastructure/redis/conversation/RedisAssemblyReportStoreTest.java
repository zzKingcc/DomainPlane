package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.Cursor.CursorId;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F04-a 装配清单账本：每会话一 List，只留最近若干条，写失败不抛。
 *
 * <p>这里锁三条：</p>
 * <ul>
 *   <li><b>新的在前</b>：排障看的是"最近几轮为什么这么装"；</li>
 *   <li><b>账本可裁</b>：它是派生数据，裁剪不构成"丢历史"（历史在归档里）；</li>
 *   <li><b>删域必须 fail-closed</b>：空域拒绝执行，否则会清空全部域。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("F04-a 装配清单账本：只留最近若干条 / 删域 fail-closed")
class RedisAssemblyReportStoreTest {

    private static final String SESSION = "default.sales|s-1";

    /** 内存 Redis 桩：只实现 List 语义（LPUSH 在头、LTRIM 留 [0,n)、LRANGE） */
    private static class StubTemplate extends StringRedisTemplate {
        final Deque<String> list = new LinkedList<>();
        final List<String> expired = new ArrayList<>();
        final List<String> deletedKeys = new ArrayList<>();
        /** SCAN 要返回的键 */
        final List<String> scanKeys = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public ListOperations<String, String> opsForList() {
            return (ListOperations<String, String>) Proxy.newProxyInstance(
                    StubTemplate.class.getClassLoader(),
                    new Class<?>[]{ListOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "leftPush" -> {
                            list.addFirst((String) args[1]);
                            yield (long) list.size();
                        }
                        case "trim" -> {
                            long end = (Long) args[2];
                            while (list.size() > end + 1) {
                                list.removeLast();
                            }
                            yield null;
                        }
                        case "range" -> {
                            long start = (Long) args[1];
                            long end = (Long) args[2];
                            List<String> out = new ArrayList<>(list);
                            int from = (int) Math.max(0, start);
                            int to = (int) Math.min(out.size() - 1, end);
                            yield from > to ? List.<String>of() : new ArrayList<>(out.subList(from, to + 1));
                        }
                        case "toString" -> "stub-list-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 ListOperations 方法：" + method.getName());
                    });
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            expired.add(key);
            return true;
        }

        @Override
        public Boolean delete(String key) {
            deletedKeys.add(key);
            return true;
        }

        @Override
        public Long delete(Collection<String> keys) {
            deletedKeys.addAll(keys);
            return (long) keys.size();
        }

        @Override
        public Cursor<String> scan(ScanOptions options) {
            return new ListCursor(scanKeys);
        }
    }

    private static final class ListCursor implements Cursor<String> {
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

    private static ContextAssemblyReport report(long turnId, int windowTokens) {
        return new ContextAssemblyReport(SESSION, turnId, Instant.now(), 24_000, 10,
                0, 0, 0, 0, 0, List.of(), 4, windowTokens, 0, 5, 100, false);
    }

    @Test
    @DisplayName("recent 新的在前：排障看的是最近几轮")
    void recentIsNewestFirst() {
        StubTemplate template = new StubTemplate();
        RedisAssemblyReportStore store = new RedisAssemblyReportStore(template);

        store.save(SESSION, report(1, 11));
        store.save(SESSION, report(2, 22));
        store.save(SESSION, report(3, 33));

        List<ContextAssemblyReport> recent = store.recent(SESSION, 10);
        assertEquals(3, recent.size());
        assertEquals(3L, recent.get(0).turnId(), "最后写的在最前");
        assertEquals(1L, recent.get(2).turnId());
    }

    @Test
    @DisplayName("每会话只留最近 KEEP_PER_SESSION 条：老账不值得占着键")
    void keepsOnlyTheMostRecent() {
        StubTemplate template = new StubTemplate();
        RedisAssemblyReportStore store = new RedisAssemblyReportStore(template);

        int total = AssemblyReportStore.KEEP_PER_SESSION + 20;
        for (int i = 1; i <= total; i++) {
            store.save(SESSION, report(i, i));
        }

        List<ContextAssemblyReport> recent = store.recent(SESSION, 100);
        assertEquals(AssemblyReportStore.KEEP_PER_SESSION, recent.size(), "超出上限即裁最老的");
        assertEquals((long) total, recent.get(0).turnId(), "最新的一条必须还在");
        assertTrue(template.expired.contains("domainplane:conv:asm:" + SESSION),
                "账本必须自带存活期：会话没了之后这些键没有别的清理入口");
    }

    @Test
    @DisplayName("空会话 / 空入参返回空列表，不抛")
    void emptyInputsAreSafe() {
        StubTemplate template = new StubTemplate();
        RedisAssemblyReportStore store = new RedisAssemblyReportStore(template);

        assertTrue(store.recent("", 10).isEmpty());
        assertTrue(store.recent(null, 10).isEmpty());
        assertTrue(store.recent(SESSION, 10).isEmpty());
        store.save(SESSION, null);   // 不该抛
        store.save("", report(1, 1));
        assertTrue(store.recent(SESSION, 10).isEmpty());
    }

    @Test
    @DisplayName("写失败只告警：账本不是对话的一环")
    void writeFailureDoesNotThrow() {
        StubTemplate template = new StubTemplate() {
            @Override
            public Boolean expire(String key, Duration timeout) {
                throw new IllegalStateException("Redis 抖动");
            }
        };
        RedisAssemblyReportStore store = new RedisAssemblyReportStore(template);

        store.save(SESSION, report(1, 1));   // 不抛
        assertEquals(1, store.recent(SESSION, 10).size(),
                "TTL 那一步失败不影响清单本身：账本落了，只是没设存活期");
    }

    @Test
    @DisplayName("删域 fail-closed：空域拒绝，正常域清键")
    void deleteByDomainIsFailClosed() {
        StubTemplate template = new StubTemplate();
        template.scanKeys.add("domainplane:conv:asm:default|s-1");
        template.scanKeys.add("domainplane:conv:asm:default|s-2");
        RedisAssemblyReportStore store = new RedisAssemblyReportStore(template);

        assertThrows(ChatMemoryException.class, () -> store.deleteByDomain(""),
                "空域会让 pattern 退化成匹配全部域");
        assertThrows(ChatMemoryException.class, () -> store.deleteByDomain("  "),
                "空白域同上");
        assertEquals(2, store.deleteByDomain("default"));
    }
}
