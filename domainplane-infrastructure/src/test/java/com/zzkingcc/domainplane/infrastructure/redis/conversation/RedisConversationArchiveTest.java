package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.FlowEvent;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.MediaRef;
import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1a 会话归档：一轮 = 一个 List 元素，开轮即落档、闭轮原位写回。
 *
 * <p>这里锁的是几条<b>不变式</b>，它们一旦破，上层两条保证就没了：</p>
 * <ul>
 *   <li><b>turnId = 下标 + 1</b>：<code>closeTurn / abortTurn / deleteTurn</code> 全按下标写，
 *       错位一次就是覆盖别人的轮；</li>
 *   <li><b>至多一条开放轮且必在尾部</b>：崩溃恢复与"审批挂起又开新对话"全靠
 *       {@code findOpenTurn} 一次读尾部判定；</li>
 *   <li><b>重复收尾必须无痕</b>：同一轮可能先被 stop 标记中止、再踩到异常路径，
 *       第二次不能叠加事件、不能改闭合时间；</li>
 *   <li><b>按域清理失败必须抛</b>：归档是<b>全量对话</b>，漏清等于"删域成功"之后
 *       一句旧话还能把整段历史勾回来（方案 §10-R5）。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M1a 会话归档：开轮/闭轮/中止/清理的不变式")
class RedisConversationArchiveTest {

    private static final String SESSION = "default.sales|s-1";

    // ==================== 桩 ====================

    /** 内存版游标 */
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
     * List 命令桩：{@code ListOperations} 有几十个方法，只按需实现 6 个，
     * 其余走动态代理抛 {@code UnsupportedOperationException}（踩到即说明实现改了路径）。
     */
    private static ListOperations<String, String> listOps(List<String> items) {
        @SuppressWarnings("unchecked")
        ListOperations<String, String> ops = (ListOperations<String, String>) Proxy.newProxyInstance(
                RedisConversationArchiveTest.class.getClassLoader(),
                new Class<?>[]{ListOperations.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "size" -> (long) items.size();
                    case "rightPush" -> {
                        items.add((String) args[1]);
                        yield (long) items.size();
                    }
                    case "set" -> {
                        items.set(((Number) args[1]).intValue(), (String) args[2]);
                        yield null;
                    }
                    case "index" -> {
                        int i = ((Number) args[1]).intValue();
                        yield (i >= 0 && i < items.size()) ? items.get(i) : null;
                    }
                    case "rightPop" -> items.isEmpty() ? null : items.remove(items.size() - 1);
                    case "range" -> {
                        long from = ((Number) args[1]).longValue();
                        long to = ((Number) args[2]).longValue();
                        yield slice(items, from, to);
                    }
                    default -> throw new UnsupportedOperationException("未桩的方法：" + method.getName());
                });
        return ops;
    }

    /** 负下标按 Redis 语义从尾部算（{@code -1} = 最后一个） */
    private static List<String> slice(List<String> items, long from, long to) {
        int size = items.size();
        int f = from < 0 ? Math.max(0, size + (int) from) : Math.min((int) from, size);
        int t = to < 0 ? size + (int) to : Math.min((int) to, size - 1);
        if (size == 0 || f > t || t < 0) {
            return List.of();
        }
        return new ArrayList<>(items.subList(f, t + 1));
    }

    private static class StubRedisTemplate extends StringRedisTemplate {

        final List<String> items = new ArrayList<>();
        List<String> scanAnswer = List.of();
        RuntimeException scanFailure;
        RuntimeException deleteFailure;

        final AtomicInteger deleteCalls = new AtomicInteger();
        final List<Collection<String>> deletedBatches = new ArrayList<>();
        String lastScanPattern;

        @Override public ListOperations<String, String> opsForList() { return listOps(items); }

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

        @Override
        public Boolean delete(String key) {
            deleteCalls.incrementAndGet();
            deletedBatches.add(List.of(key));
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            return Boolean.TRUE;
        }
    }

    private static RedisConversationArchive archive(StubRedisTemplate stub) {
        return new RedisConversationArchive(stub);
    }

    private static TurnRecord closed(long turnId, String question, String answer) {
        return new TurnRecord(turnId, SESSION, Instant.parse("2026-10-14T10:00:00Z"),
                Instant.parse("2026-10-14T10:00:08Z"), UserInput.of(question, List.of()),
                List.of(FlowEvent.of(FlowEventType.TOOL_CALL, "查订单")),
                answer, new TokenUsage(10, 20, 30), new TurnFlags(false, false, false, null),
                "摘要" + turnId);
    }

    // ==================== 开轮 / 闭轮 ====================

    @Test
    @DisplayName("开轮：turnId 从 1 递增，落档元素 finishedAt 为空（开放轮）")
    void openTurnAssignsIncreasingIds() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        long first = archive.openTurn(SESSION, UserInput.of("第一句", List.of()));
        assertEquals(1L, first);

        // 闭掉第一轮，再开第二轮：turnId 必须跟着长度走，否则下标会错位
        archive.closeTurn(SESSION, first, closed(first, "第一句", "第一个回答"));
        long second = archive.openTurn(SESSION, UserInput.of("第二句", List.of()));
        assertEquals(2L, second);

        TurnRecord open = archive.findOpenTurn(SESSION).orElseThrow();
        assertEquals(2L, open.turnId());
        assertTrue(open.isOpen(), "新开的一轮必须是开放轮");
        assertEquals("第二句", open.user().text());
        assertTrue(open.flow().isEmpty(), "刚开轮还没有流程事件");
    }

    @Test
    @DisplayName("闭轮：原位写回，不新增元素；再读 turnId 与内容都对得上")
    void closeTurnRewritesInPlace() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        long id = archive.openTurn(SESSION, UserInput.of("问", List.of()));
        archive.closeTurn(SESSION, id, closed(id, "问", "答"));

        assertEquals(1, stub.items.size(), "闭轮是覆盖，不是追加");
        TurnRecord read = archive.turnAt(SESSION, id).orElseThrow();
        assertFalse(read.isOpen());
        assertEquals("答", read.answer());
        assertEquals(30, read.usage().totalTokens());
        assertEquals("摘要1", read.digest());
        assertTrue(archive.findOpenTurn(SESSION).isEmpty(), "闭完之后不该还有开放轮");
    }

    // ==================== 中止 / 审批被拒 ====================

    @Test
    @DisplayName("中止：标记 aborted 并留下现场（原因 + 半截回答），但仍算闭轮")
    void abortKeepsSceneAndMarksAborted() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        long id = archive.openTurn(SESSION, UserInput.of("问", List.of()));
        archive.abortTurn(SESSION, id, "用户停止", "答到一半");

        TurnRecord read = archive.turnAt(SESSION, id).orElseThrow();
        assertFalse(read.isOpen(), "中止轮也是闭轮 —— 否则下次开轮会读到两条开放轮");
        assertTrue(read.flags().aborted());
        assertEquals(2, read.flow().size(), "原因与半截回答都要留在档里");
        assertEquals(FlowEventType.ERROR, read.flow().get(0).type());
        assertEquals(FlowEventType.ASSISTANT_DELTA, read.flow().get(1).type());
        assertEquals("答到一半", read.flow().get(1).text());
        assertTrue(archive.findOpenTurn(SESSION).isEmpty());
    }

    @Test
    @DisplayName("重复收尾必须无痕：第二次 abort 不叠加事件、不改闭合时间")
    void abortIsIdempotent() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        long id = archive.openTurn(SESSION, UserInput.of("问", List.of()));
        archive.abortTurn(SESSION, id, "用户停止", null);
        Instant firstClose = archive.turnAt(SESSION, id).orElseThrow().finishedAt();

        archive.abortTurn(SESSION, id, "异常兜底", null);

        TurnRecord read = archive.turnAt(SESSION, id).orElseThrow();
        assertEquals(1, read.flow().size(), "第二条 ERROR 不该再叠上来");
        assertEquals(firstClose, read.finishedAt(), "闭合时间不该被第二次收尾改写");
    }

    @Test
    @DisplayName("已正常闭合的轮：abort 不改写它（异常兜底不能把好轮改成中止轮）")
    void abortDoesNotTouchClosedTurn() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        long id = archive.openTurn(SESSION, UserInput.of("问", List.of()));
        archive.closeTurn(SESSION, id, closed(id, "问", "完整回答"));
        archive.abortTurn(SESSION, id, "异常兜底", null);

        TurnRecord read = archive.turnAt(SESSION, id).orElseThrow();
        assertFalse(read.flags().aborted());
        assertEquals("完整回答", read.answer(), "完整回答不能被清成中止轮");
    }

    @Test
    @DisplayName("审批被拒：approvalOutcome 记为 REJECTED")
    void rejectMarksApprovalOutcome() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        long id = archive.openTurn(SESSION, UserInput.of("删库", List.of()));
        archive.rejectTurn(SESSION, id);

        TurnRecord read = archive.turnAt(SESSION, id).orElseThrow();
        assertEquals("REJECTED", read.flags().approvalOutcome());
        assertTrue(read.flags().aborted());
    }

    // ==================== 删开放轮 ====================

    @Test
    @DisplayName("deleteTurn 只删尾部那条开放轮；尾部不是它时一个元素都不动")
    void deleteTurnOnlyRemovesTailOpenTurn() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        long kept = archive.openTurn(SESSION, UserInput.of("第一轮", List.of()));
        archive.closeTurn(SESSION, kept, closed(kept, "第一轮", "答"));
        long open = archive.openTurn(SESSION, UserInput.of("第二轮", List.of()));

        // 指向已闭合的第一轮：不许删（删了就是 pop 掉别人的轮）
        archive.deleteTurn(SESSION, kept);
        assertEquals(2, stub.items.size(), "非尾部开放轮不许删");

        archive.deleteTurn(SESSION, open);
        assertEquals(1, stub.items.size());
        assertEquals(kept, archive.turnAt(SESSION, kept).orElseThrow().turnId());
    }

    // ==================== 崩溃恢复 ====================

    @Test
    @DisplayName("崩溃恢复：有开放轮收 1 条并标记中止，没有则收 0 条且不改数据")
    void recoverStaleOpenTurn() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        assertEquals(0, archive.recoverStaleOpenTurn(SESSION), "空归档不该有可恢复的轮");

        long id = archive.openTurn(SESSION, UserInput.of("断在半路", List.of()));
        assertEquals(1, archive.recoverStaleOpenTurn(SESSION));

        TurnRecord recovered = archive.turnAt(SESSION, id).orElseThrow();
        assertTrue(recovered.flags().aborted());
        assertEquals("断在半路", recovered.user().text(), "提问现场必须留着");
        assertEquals(0, archive.recoverStaleOpenTurn(SESSION), "恢复完之后不该再收出第二条");
    }

    // ==================== 读 ====================

    @Test
    @DisplayName("turnsBetween：越界裁剪到实际范围，非法区间返回空而不抛")
    void turnsBetweenClampsToRealRange() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);
        for (long i = 1; i <= 3; i++) {
            archive.closeTurn(SESSION, archive.openTurn(SESSION, UserInput.of("问" + i, List.of())),
                    closed(i, "问" + i, "答" + i));
        }

        assertEquals(List.of(), archive.turnsBetween(SESSION, 0, 2), "from < 1 非法");
        assertEquals(List.of(), archive.turnsBetween(SESSION, 3, 2), "from > to 非法");
        assertEquals(3, archive.turnsBetween(SESSION, 1, 99).size(), "上界越界应裁剪到实际长度");
        assertEquals(List.of("问2", "问3"), archive.turnsBetween(SESSION, 2, 3).stream()
                .map(t -> t.user().text()).toList());
        assertEquals(2, archive.recentTurns(SESSION, 2).size());
        assertEquals(List.of(), archive.recentTurns(SESSION, 0), "limit <= 0 返回空");
    }

    @Test
    @DisplayName("坏元素只跳过自己，同会话其余轮照读得出来")
    void corruptElementIsSkipped() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);
        archive.closeTurn(SESSION, archive.openTurn(SESSION, UserInput.of("好轮", List.of())),
                closed(1, "好轮", "答"));
        stub.items.add("{这不是 JSON");
        archive.closeTurn(SESSION, archive.openTurn(SESSION, UserInput.of("后来", List.of())),
                closed(3, "后来", "答"));

        List<TurnRecord> all = archive.turnsBetween(SESSION, 1, 99);
        assertEquals(2, all.size(), "一条坏数据不能毁掉整段历史");
        assertEquals("好轮", all.get(0).user().text());
        assertEquals("后来", all.get(1).user().text());
    }

    @Test
    @DisplayName("JSON 往返：时间按毫秒数值存，读回来与写入值一致")
    void jsonRoundTripKeepsInstants() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        Instant started = Instant.parse("2026-10-14T10:00:00.123Z");
        long id = archive.openTurn(SESSION, UserInput.of("问", List.of()));
        archive.closeTurn(SESSION, id, new TurnRecord(id, SESSION, started,
                Instant.parse("2026-10-14T10:00:09.456Z"), UserInput.of("问", List.of()),
                List.of(), "答", new TokenUsage(1, 2, 3), TurnFlags.open(), "摘要"));

        TurnRecord read = archive.turnAt(SESSION, id).orElseThrow();
        assertEquals(started, read.startedAt(), "毫秒存的是 toEpochMilli，读回必须相等（不是截断到秒）");
        assertNotEquals(0L, read.finishedAt().toEpochMilli() % 1000, "毫秒位要保住");
    }

    @Test
    @DisplayName("带媒体的轮记录必须能读回来：派生 getter（isImage）不许被写进 JSON")
    void mediaRefRoundTripDoesNotLeakDerivedGetters() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        List<MediaRef> media = List.of(new MediaRef("image", "image/png",
                "base64(image/png, 约 3000 字节)", "字节存附件区，可凭引用名取回（不落归档）",
                "img_1f7fd011d6d15b97"));
        long id = archive.openTurn(SESSION, UserInput.of("看图", media));
        archive.closeTurn(SESSION, id, new TurnRecord(id, SESSION, Instant.now(), Instant.now(),
                UserInput.of("看图", media), List.of(), "答", new TokenUsage(1, 2, 3),
                TurnFlags.open(), "摘要"));

        /* 多写一个 "image":true 出来，读侧就判未知字段、跳过整轮，那是"这几轮历史消失"，
           不是少一个字段。这里直接盯原始 JSON：kind 的值本就是 image，所以只有带引号的
           "image": 才是那个多出来的派生字段。 */
        assertFalse(stub.items.get(0).contains("\"image\":"),
                "派生 getter 混进了 JSON：" + stub.items.get(0));

        TurnRecord read = archive.turnAt(SESSION, id).orElseThrow();
        assertEquals("img_1f7fd011d6d15b97", read.user().media().get(0).attachmentId());
        assertTrue(read.user().media().get(0).isImage());
    }

    @Test
    @DisplayName("已落档的坏记录也要能读：多出来的派生字段 image 不该让整轮报废")
    void legacyDerivedFieldStillParses() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);
        /* 真实形态（2026-10-16 从 Redis 原样取出）：`"image":true` 是 isImage() 被当成
           getter 序列化的产物，其余字段都是当前的。 */
        stub.items.add("{\"turnId\":1,\"sessionKey\":\"" + SESSION + "\","
                + "\"startedAt\":1792155444773,\"finishedAt\":null,"
                + "\"user\":{\"text\":\"判断是什么字体？\",\"media\":[{"
                + "\"kind\":\"image\",\"mime\":\"image/png\","
                + "\"ref\":\"base64(image/png, 约 4962 字节)\","
                + "\"note\":\"字节存附件区，可凭引用名取回（不落归档）\","
                + "\"attachmentId\":\"img_1f7fd011d6d15b97\",\"image\":true}]},"
                + "\"flow\":[],\"answer\":null,\"usage\":null,"
                + "\"flags\":{\"truncated\":false,\"aborted\":false,\"indexed\":false,"
                + "\"approvalOutcome\":null},\"digest\":null}");

        TurnRecord read = archive.turnAt(SESSION, 1).orElseThrow();
        assertEquals("判断是什么字体？", read.user().text());
        assertEquals("img_1f7fd011d6d15b97", read.user().media().get(0).attachmentId());
        assertTrue(read.user().media().get(0).isImage());
    }

    // ==================== 清理 ====================

    @Test
    @DisplayName("按域清理：空域拒绝，且一个 Redis 命令都不发")
    void deleteByDomainRejectsBlank() {
        for (String blank : new String[]{null, "", "   "}) {
            StubRedisTemplate stub = new StubRedisTemplate();
            ChatMemoryException e = assertThrows(ChatMemoryException.class,
                    () -> archive(stub).deleteByDomain(blank),
                    "空域会让 pattern 退化成匹配全部域：[" + blank + "]");
            assertEquals("INVALID_PARAMETER", e.getCodeName());
            assertEquals(0, stub.deleteCalls.get(), "守卫必须落在拼 pattern 与发命令之前");
        }
    }

    @Test
    @DisplayName("按域清理：SCAN 与 DEL 失败都必须抛（只 warn 等于静默丢掉全量对话）")
    void deleteByDomainFailsClosed() {
        StubRedisTemplate scanBroken = new StubRedisTemplate();
        scanBroken.scanFailure = new IllegalStateException("Redis 连接不可用");
        ChatMemoryException e1 = assertThrows(ChatMemoryException.class,
                () -> archive(scanBroken).deleteByDomain("default.sales"));
        assertEquals("CHAT_MEMORY_DELETE_ERROR", e1.getCodeName());

        StubRedisTemplate delBroken = new StubRedisTemplate();
        delBroken.scanAnswer = List.of("domainplane:chat:archive:default.sales|s-1");
        delBroken.deleteFailure = new IllegalStateException("READONLY");
        ChatMemoryException e2 = assertThrows(ChatMemoryException.class,
                () -> archive(delBroken).deleteByDomain("default.sales"));
        assertEquals("CHAT_MEMORY_DELETE_ERROR", e2.getCodeName());
        assertTrue(e2.getMessage().contains("残留"), "文案要说明键可能残留：" + e2.getMessage());
    }

    @Test
    @DisplayName("按域清理：命中键才发 DEL，pattern 只覆盖本域")
    void deleteByDomainUsesDomainScopedPattern() {
        StubRedisTemplate stub = new StubRedisTemplate();
        stub.scanAnswer = List.of(
                "domainplane:chat:archive:default.sales|s-1",
                "domainplane:chat:archive:default.sales.order|s-2");

        assertEquals(2, archive(stub).deleteByDomain("default.sales"));
        assertEquals("domainplane:chat:archive:default.sales|*", stub.lastScanPattern);

        StubRedisTemplate empty = new StubRedisTemplate();
        assertEquals(0, archive(empty).deleteByDomain("default.sales"));
        assertEquals(0, empty.deleteCalls.get(), "没命中就不该发 DEL");
    }

    @Test
    @DisplayName("clearSession 只删这一个会话的键")
    void clearSessionDeletesSingleKey() {
        StubRedisTemplate stub = new StubRedisTemplate();
        archive(stub).clearSession(SESSION);

        assertEquals(1, stub.deleteCalls.get());
        assertEquals(List.of("domainplane:chat:archive:" + SESSION), stub.deletedBatches.get(0));
    }

    @Test
    @DisplayName("读不存在的会话 / 不存在的 turnId：返回空，不抛")
    void readsMissReturnEmpty() {
        StubRedisTemplate stub = new StubRedisTemplate();
        RedisConversationArchive archive = archive(stub);

        assertEquals(Optional.empty(), archive.findOpenTurn(SESSION));
        assertEquals(Optional.empty(), archive.turnAt(SESSION, 1));
        assertEquals(List.of(), archive.recentTurns(SESSION, 5));
        assertEquals(0, archive.recoverStaleOpenTurn(SESSION));
    }
}
