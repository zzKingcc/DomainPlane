package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationSummaryStore.Stored;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-R1 滚动摘要的 Redis 实现：一个会话一个 Hash（text + upTo）。
 *
 * <p>这里锁三件事：</p>
 * <ul>
 *   <li><b>往返一致</b>：写进去的正文与进度读回来分毫不差；</li>
 *   <li><b>读坏不炸</b>：字段缺失 / 进度被写坏都按"还没摘要过"处理，装配侧会按 digest 补齐，
 *       不会丢内容；</li>
 *   <li><b>按域清理 fail-closed</b>：空域拒绝（否则 pattern 退化成匹配全部域），
 *       SCAN / DEL 失败必须抛，摘要不清掉，同路径域重建会复活旧会话的摘要。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M4-R1 滚动摘要：Hash 存取与按域清理")
class RedisConversationSummaryTest {

    private static final String SESSION = "default.sales|s-1";
    private static final String KEY = "domainplane:conv:sum:" + SESSION;

    // ==================== 桩 ====================

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

    private static HashOperations<String, String, String> hashOps(Map<String, Map<String, String>> store) {
        @SuppressWarnings("unchecked")
        HashOperations<String, String, String> ops = (HashOperations<String, String, String>)
                Proxy.newProxyInstance(RedisConversationSummaryTest.class.getClassLoader(),
                        new Class<?>[]{HashOperations.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "entries" -> {
                                Map<String, String> e = store.get((String) args[0]);
                                yield e == null ? Map.of() : new HashMap<>(e);
                            }
                            case "putAll" -> {
                                @SuppressWarnings("unchecked")
                                Map<String, String> incoming = (Map<String, String>) args[1];
                                store.computeIfAbsent((String) args[0], k -> new HashMap<>())
                                        .putAll(incoming);
                                yield null;
                            }
                            default -> throw new UnsupportedOperationException("未桩的方法：" + method.getName());
                        });
        return ops;
    }

    private static class StubRedisTemplate extends StringRedisTemplate {

        final Map<String, Map<String, String>> hashes = new HashMap<>();
        List<String> scanAnswer = List.of();
        RuntimeException scanFailure;
        RuntimeException deleteFailure;

        final AtomicInteger deleteCalls = new AtomicInteger();
        final List<Collection<String>> deletedBatches = new ArrayList<>();
        String lastScanPattern;

        @Override public HashOperations<String, String, String> opsForHash() { return hashOps(hashes); }

        @Override
        public Cursor<String> scan(ScanOptions options) {
            lastScanPattern = options.getPattern();
            if (scanFailure != null) {
                throw scanFailure;
            }
            return new ListCursor(scanAnswer);
        }

        @Override
        public Long delete(Collection<String> keys) {
            deleteCalls.incrementAndGet();
            deletedBatches.add(new ArrayList<>(keys));
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            return (long) keys.size();
        }
    }

    private static RedisConversationSummary summary(StubRedisTemplate stub) {
        return new RedisConversationSummary(stub);
    }

    // ==================== 往返 ====================

    @Test
    @DisplayName("存进去的正文与进度原样读回来；只写一个字段时另一个按缺省处理")
    void roundTrip() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationSummary store = summary(stub);

        store.save(SESSION, new Stored("第 1-5 轮：用户咨询退款", 5L));

        Stored read = store.load(SESSION).orElseThrow();
        assertEquals("第 1-5 轮：用户咨询退款", read.text());
        assertEquals(5L, read.upToTurnId());
        assertEquals(KEY, stub.hashes.keySet().iterator().next(), "键形态必须是 domainplane:conv:sum:{session}");

        // 覆盖写：第二次 save 只改这两个字段
        store.save(SESSION, new Stored("第 1-9 轮：合并后", 9L));
        assertEquals(9L, store.load(SESSION).orElseThrow().upToTurnId());
    }

    @Test
    @DisplayName("会话不存在 / 会话键空白：返回空，不抛")
    void loadMissReturnsEmpty() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationSummary store = summary(stub);

        assertEquals(Optional.empty(), store.load(SESSION));
        assertEquals(Optional.empty(), store.load(null));
        assertEquals(Optional.empty(), store.load("   "));
    }

    @Test
    @DisplayName("进度字段被写坏：按还没摘要过处理（装配侧会按 digest 补齐，不丢内容）")
    void corruptUpToFallsBackToZero() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.hashes.put(KEY, new HashMap<>(Map.of("text", "旧摘要", "upTo", "not-a-number")));

        Stored read = summary(stub).load(SESSION).orElseThrow();
        assertEquals("旧摘要", read.text());
        assertEquals(0L, read.upToTurnId(), "坏进度按 0 —— 缺口会重新用 digest 补，最坏是重复一遍，不会丢");
    }

    @Test
    @DisplayName("seek 失败 / save 失败都只告警：读返回空，写不抛")
    void ioFailuresAreSwallowed() {
        StubRedisTemplate broken = new StubRedisTemplate() {
            @Override public HashOperations<String, String, String> opsForHash() {
                throw new IllegalStateException("Redis 连接不可用");
            }
        };
        RedisConversationSummary store = summary(broken);

        assertEquals(Optional.empty(), store.load(SESSION), "读失败退化为无摘要（digest 拼接）");
        store.save(SESSION, new Stored("正文", 1L));  // 不该抛
    }

    @Test
    @DisplayName("save 空会话 / 空摘要：直接跳过，不写坏数据")
    void saveRejectsBlank() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationSummary store = summary(stub);

        store.save(null, new Stored("x", 1L));
        store.save("  ", new Stored("x", 1L));
        store.save(SESSION, null);
        assertTrue(stub.hashes.isEmpty(), "都不该落盘：" + stub.hashes);
    }

    // ==================== 按域清理 ====================

    @Test
    @DisplayName("按域清理：空域拒绝，且一个 Redis 命令都不发")
    void deleteByDomainRejectsBlank() {
        for (String blank : new String[]{null, "", "   "}) {
            StubRedisTemplate stub = new StubRedisTemplate();
            ChatMemoryException e = assertThrows(ChatMemoryException.class,
                    () -> summary(stub).deleteByDomain(blank),
                    "空域会让 pattern 退化成匹配全部域：[" + blank + "]");
            assertEquals("INVALID_PARAMETER", e.getCodeName());
            assertEquals(0, stub.deleteCalls.get(), "守卫必须落在拼 pattern 与发命令之前");
        }
    }

    @Test
    @DisplayName("按域清理：SCAN 与 DEL 失败都必须抛（只 warn 等于旧摘要在域重建后复活）")
    void deleteByDomainFailsClosed() {
        StubRedisTemplate scanBroken = new StubRedisTemplate();
        scanBroken.scanFailure = new IllegalStateException("Redis 连接不可用");
        ChatMemoryException e1 = assertThrows(ChatMemoryException.class,
                () -> summary(scanBroken).deleteByDomain("default.sales"));
        assertEquals("CHAT_MEMORY_DELETE_ERROR", e1.getCodeName());

        StubRedisTemplate delBroken = new StubRedisTemplate();
        delBroken.scanAnswer = List.of("domainplane:conv:sum:default.sales|s-1");
        delBroken.deleteFailure = new IllegalStateException("READONLY");
        ChatMemoryException e2 = assertThrows(ChatMemoryException.class,
                () -> summary(delBroken).deleteByDomain("default.sales"));
        assertEquals("CHAT_MEMORY_DELETE_ERROR", e2.getCodeName());
        assertTrue(e2.getMessage().contains("残留"), "文案要说明键可能残留：" + e2.getMessage());
    }

    @Test
    @DisplayName("按域清理：命中键才发 DEL，pattern 只覆盖本域")
    void deleteByDomainUsesDomainScopedPattern() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanAnswer = List.of(
                "domainplane:conv:sum:default.sales|s-1",
                "domainplane:conv:sum:default.sales.order|s-2");

        assertEquals(2, summary(stub).deleteByDomain("default.sales"));
        assertEquals("domainplane:conv:sum:default.sales|*", stub.lastScanPattern);

        StubRedisTemplate empty = new StubRedisTemplate();
        assertEquals(0, summary(empty).deleteByDomain("default.sales"));
        assertEquals(0, empty.deleteCalls.get(), "没命中就不该发 DEL");
    }

    @Test
    @DisplayName("无 TTL：存摘要只写 Hash 两个字段，不设过期（摘要有损压缩，重放复原不出）")
    void noTtlIsApplied() {
        StubRedisTemplate stub = new StubRedisTemplate();
        summary(stub).save(SESSION, new Stored("正文", 3L));

        Map<String, String> fields = stub.hashes.get(KEY);
        assertEquals(2, fields.size(), "只有 text 与 upTo 两个字段：" + fields);
        assertEquals("正文", fields.get("text"));
        assertEquals("3", fields.get("upTo"));
        assertFalse(fields.containsKey("ttl"));
    }
}
