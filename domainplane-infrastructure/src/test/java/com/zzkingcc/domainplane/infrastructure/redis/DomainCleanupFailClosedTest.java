package com.zzkingcc.domainplane.infrastructure.redis;

import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.util.CloseableIterator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-04：按域清理会话记忆与检查点必须 fail-closed。
 *
 * <p>这两处清理在删域流程里各自承担一条<b>安全保证</b>，而原实现把失败降级成
 * {@code log.warn + return 0}：</p>
 * <ul>
 *   <li><b>记忆</b>保留期默认永久（{@code ttl = null}）。不清的话，同路径域重建
 *       （索引名与记忆键都含域路径）后这段对话历史原样复活，新域的会话一开始就带着旧上下文；</li>
 *   <li><b>断点</b>里存着"即将执行、尚未执行"的工具调用。不清的话，24 小时内同路径域重建
 *       就能 {@code resume(approved=true)} 把当初被审批拦下的破坏性动作补执行掉 ——
 *       域的删除没有撤销授权。</li>
 * </ul>
 *
 * <p>而用户看到的是"删除成功"。原注释写的理由是"删域流程不应因清理失败而中止
 * （域本身的删除已经完成）"，这个前提不成立：清理在 {@code domainRegistry.delete}
 * <b>之前</b>，抛出去正是中止在那之前，域还在。</p>
 *
 * <p>另有一处空域守卫：{@code domain} 为空时 pattern 会退化成 {@code 前缀|*}，
 * 匹配<b>全部域</b>的记忆 —— 一次误传就是全量清空，且不可恢复。守卫必须落在拼 pattern 之前。</p>
 *
 * @author zzkingcc
 */
@DisplayName("I-04 按域清理记忆/检查点：失败必须抛出，空域必须拒绝")
class DomainCleanupFailClosedTest {

    // ==================== 桩 ====================

    /** 内存版游标：{@link Cursor} 是接口，直接手写一个够用 */
    private static class ListCursor implements Cursor<String> {

        private final Iterator<String> delegate;
        private boolean closed;

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
        @Override public void close() { closed = true; }
        @Override public CursorId getId() { return CursorId.of(0L); }
        @Override public long getCursorId() { return 0L; }
        @Override public boolean isClosed() { return closed; }
        @Override public long getPosition() { return 0L; }
    }

    /**
     * 可编程的 Redis 模板桩：只覆盖 {@code scan} 与 {@code delete}，
     * 这两个正是两处 {@code deleteByDomain} 唯一用到的命令。
     */
    private static class StubRedisTemplate extends StringRedisTemplate {

        List<String> scanAnswer = List.of();
        RuntimeException scanFailure;
        RuntimeException deleteFailure;

        final AtomicInteger scanCalls = new AtomicInteger();
        final AtomicInteger deleteCalls = new AtomicInteger();
        final List<Collection<String>> deletedBatches = new ArrayList<>();
        ScanOptions lastScanOptions;

        @Override
        public Cursor<String> scan(ScanOptions options) {
            scanCalls.incrementAndGet();
            lastScanOptions = options;
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

    private static RedisChatMemoryStore memoryStore(StubRedisTemplate stub) {
        return new RedisChatMemoryStore(stub, null);
    }

    private static RedisCheckpointSaver checkpointSaver(StubRedisTemplate stub) {
        // 序列化器在 deleteByDomain 路径上完全用不到；CheckpointListSerializer 只是包一层
        return new RedisCheckpointSaver(stub, null, Duration.ofHours(24));
    }

    // ==================== 记忆：空域守卫 ====================

    @Test
    @DisplayName("记忆 deleteByDomain：null / 空串 / 纯空格 都必须拒绝，且不发任何 Redis 命令")
    void memoryRejectsBlankDomain() {
        for (String blank : new String[]{null, "", "   "}) {
            StubRedisTemplate stub = new StubRedisTemplate();
            ChatMemoryException e = assertThrows(ChatMemoryException.class,
                    () -> memoryStore(stub).deleteByDomain(blank),
                    "空域会让 pattern 匹配全部域，必须拒绝：[" + blank + "]");
            assertEquals("INVALID_PARAMETER", e.getCodeName());
            assertEquals(0, stub.scanCalls.get(), "守卫必须落在拼 pattern 与发命令之前");
            assertEquals(0, stub.deleteCalls.get());
        }
    }

    @Test
    @DisplayName("检查点 deleteByDomain：空域同样拒绝")
    void checkpointRejectsBlankDomain() {
        for (String blank : new String[]{null, "", "\t"}) {
            StubRedisTemplate stub = new StubRedisTemplate();
            ChatMemoryException e = assertThrows(ChatMemoryException.class,
                    () -> checkpointSaver(stub).deleteByDomain(blank));
            assertEquals("INVALID_PARAMETER", e.getCodeName());
            assertEquals(0, stub.scanCalls.get());
            assertEquals(0, stub.deleteCalls.get());
        }
    }

    // ==================== 记忆：清理失败必须抛 ====================

    @Test
    @DisplayName("记忆 deleteByDomain：SCAN 失败时抛 CHAT_MEMORY_DELETE_ERROR")
    void memoryThrowsWhenScanFails() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanFailure = new IllegalStateException("Redis 连接不可用");

        ChatMemoryException e = assertThrows(ChatMemoryException.class,
                () -> memoryStore(stub).deleteByDomain("default.sales"));

        assertEquals("CHAT_MEMORY_DELETE_ERROR", e.getCodeName());
        assertTrue(e.getMessage().contains("default.sales"), "文案要带上模式便于定位：" + e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    @DisplayName("记忆 deleteByDomain：DEL 失败时同样抛（不能只包住 SCAN）")
    void memoryThrowsWhenDeleteFails() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanAnswer = List.of("domainplane:chat:memory:default.sales|s1");
        stub.deleteFailure = new IllegalStateException("READONLY You can't write against a read only replica");

        ChatMemoryException e = assertThrows(ChatMemoryException.class,
                () -> memoryStore(stub).deleteByDomain("default.sales"));

        assertEquals("CHAT_MEMORY_DELETE_ERROR", e.getCodeName());
        // 删不掉的后果是"同路径域重建后历史复活"，文案必须说明键可能残留
        assertTrue(e.getMessage().contains("残留"), "文案要说明键可能残留：" + e.getMessage());
    }

    @Test
    @DisplayName("检查点 deleteByDomain：失败必须抛，且文案点明断点里可能有待执行动作")
    void checkpointThrowsAndExplainsRisk() {
        StubRedisTemplate scanFail = new StubRedisTemplate();
        scanFail.scanFailure = new IllegalStateException("Redis 超时");
        ChatMemoryException scanEx = assertThrows(ChatMemoryException.class,
                () -> checkpointSaver(scanFail).deleteByDomain("default.sales"));
        assertEquals("CHECKPOINT_ERROR", scanEx.getCodeName());

        StubRedisTemplate delFail = new StubRedisTemplate();
        delFail.scanAnswer = List.of("domainplane:graph:checkpoint:default.sales|s1");
        delFail.deleteFailure = new IllegalStateException("BUSY Redis is busy");
        ChatMemoryException delEx = assertThrows(ChatMemoryException.class,
                () -> checkpointSaver(delFail).deleteByDomain("default.sales"));
        assertEquals("CHECKPOINT_ERROR", delEx.getCodeName());
        assertTrue(delEx.getMessage().contains("待执行"), "文案要点明残留断点里存着待执行的工具调用：" + delEx.getMessage());
    }

    // ==================== 正常路径未被破坏 ====================

    @Test
    @DisplayName("记忆 deleteByDomain：无匹配键时返回 0，且不发出 DEL")
    void memoryReturnsZeroWhenNoKeys() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanAnswer = List.of();

        assertEquals(0, memoryStore(stub).deleteByDomain("default.sales"));
        assertEquals(1, stub.scanCalls.get());
        assertEquals(0, stub.deleteCalls.get(), "没键可删时不该发 DEL");
    }

    @Test
    @DisplayName("记忆 deleteByDomain：正常删除并返回删除数，pattern 精确到该域")
    void memoryDeletesKeysOfThatDomainOnly() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanAnswer = List.of(
                "domainplane:chat:memory:default.sales|s1",
                "domainplane:chat:memory:default.sales|s2");

        assertEquals(2, memoryStore(stub).deleteByDomain("default.sales"));
        assertEquals(1, stub.deleteCalls.get());
        assertEquals(1, stub.deletedBatches.size());
        assertEquals(2, stub.deletedBatches.get(0).size());
        assertEquals("domainplane:chat:memory:default.sales|*", stub.lastScanOptions.getPattern(),
                "SCAN 的 match 必须锁死到该域，绝不能退化成通配全部");
    }

    @Test
    @DisplayName("检查点 deleteByDomain：正常路径与 pattern 同样成立")
    void checkpointDeletesKeysOfThatDomainOnly() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanAnswer = List.of("domainplane:graph:checkpoint:default.sales|s1");

        assertEquals(1, checkpointSaver(stub).deleteByDomain("default.sales"));
        assertEquals("domainplane:graph:checkpoint:default.sales|*", stub.lastScanOptions.getPattern());
    }

    @Test
    @DisplayName("两处清理的键前缀互不重叠（记忆与断点是两套键，不会互相误删）")
    void memoryAndCheckpointKeysDoNotCollide() {
        StubRedisTemplate memory = new StubRedisTemplate();
        memory.scanAnswer = List.of("domainplane:chat:memory:default|s1");
        assertEquals(1, memoryStore(memory).deleteByDomain("default"));
        assertEquals("domainplane:chat:memory:default|*", memory.lastScanOptions.getPattern());

        StubRedisTemplate checkpoint = new StubRedisTemplate();
        checkpoint.scanAnswer = List.of("domainplane:graph:checkpoint:default|s1");
        assertEquals(1, checkpointSaver(checkpoint).deleteByDomain("default"));
        assertEquals("domainplane:graph:checkpoint:default|*", checkpoint.lastScanOptions.getPattern());
    }

    @Test
    @DisplayName("域路径会先 trim 再拼 pattern（避免 ' default ' 拼出匹配不到任何键的模式）")
    void domainIsTrimmedBeforeBuildingPattern() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanAnswer = List.of("domainplane:chat:memory:default.sales|s1");

        assertEquals(1, memoryStore(stub).deleteByDomain("  default.sales  "));
        assertEquals("domainplane:chat:memory:default.sales|*", stub.lastScanOptions.getPattern());
    }

    /** 编译期提醒：本测试只用到 Cursor 的迭代与关闭语义，CloseableIterator 的默认 close 无需覆盖 */
    @Test
    @DisplayName("桩自检：ListCursor 迭代与关闭语义正确")
    void stubCursorBehaves() {
        ListCursor cursor = new ListCursor(List.of("a", "b"));
        assertEquals("a", cursor.next());
        assertEquals("b", cursor.next());
        assertTrue(!cursor.hasNext());
        assertThrows(NoSuchElementException.class, cursor::next);
        assertTrue(!cursor.isClosed());
        cursor.close();
        assertTrue(cursor.isClosed());
        // 保证 CloseableIterator 的契约在桩上成立
        assertTrue(cursor instanceof CloseableIterator);
    }
}
