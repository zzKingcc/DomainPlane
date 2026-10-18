package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 召回装配：预算、顺序、降级。
 *
 * <p>装配层是"这轮给模型看多少"的唯一决策点，也是封顶拒绝语义的替代品。
 * 这里锁三件事：<b>顺序</b>（摘要/卡片在窗口之前，模型先看到压缩再看到原文）、
 * <b>降级</b>（装不下先砍卡片、再缩窗口、最后截摘要，且永远不丢本轮提问）、
 * <b>窗口整轮丢</b>（不留"有问无答"的孤提问）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("M2 召回装配：顺序 / 预算 / 降级链")
class ContextAssemblerTest {

    private static final SystemMessage SYSTEM = SystemMessage.from("你是域助手");

    private static UserMessage question(String text) {
        return UserMessage.from(text);
    }

    /** 造一轮：提问 + 回答，文本长度可控 */
    private static List<ChatMessage> turns(int count, int charsEach) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(UserMessage.from("问".repeat(Math.max(1, charsEach / 2)) + i));
            out.add(AiMessage.from("答".repeat(Math.max(1, charsEach / 2)) + i));
        }
        return out;
    }

    /**
     * 从装配结果里切出窗口段。
     *
     * <p>不能按类型筛：摘要段与卡片段也是 {@link UserMessage}，混进来就数不准。
     * 按位置切：系统/摘要/卡片各占一段，窗口在最后一段（本轮提问）之前。</p>
     */
    private static List<ChatMessage> windowOf(List<ChatMessage> assembled, boolean hasSummary,
                                              boolean hasCards) {
        int start = 1 /* system */ + (hasSummary ? 1 : 0) + (hasCards ? 1 : 0);
        return new ArrayList<>(assembled.subList(start, assembled.size() - 1));
    }

    // ==================== 顺序 ====================

    @Test
    @DisplayName("顺序：系统提示 → 摘要 → 卡片 → 窗口 → 本轮提问")
    void orderIsSystemSummaryCardsWindowCurrent() {
        ContextAssembler assembler = new ContextAssembler();
        List<ChatMessage> out = assembler.assemble(SYSTEM,
                turns(2, 20), "第 1 轮：问订单", List.of(card(3)), question("本轮提问"));

        // 系统 + 摘要 + 卡片 + 2 轮窗口（4 条）+ 本轮提问
        assertEquals(8, out.size());
        assertTrue(out.get(0) instanceof SystemMessage);
        assertTrue(textOf(out.get(1)).startsWith("【早期对话摘要】"));
        assertTrue(textOf(out.get(2)).startsWith("【可能相关的历史轮次】"));
        assertTrue(textOf(out.get(3)).startsWith("问"), "窗口原文紧随卡片：" + textOf(out.get(3)));
        assertEquals("本轮提问", textOf(out.get(out.size() - 1)));
    }

    @Test
    @DisplayName("三段都为空时退化成「系统提示 + 本轮提问」")
    void degradesToSystemAndCurrentWhenNothingElse() {
        ContextAssembler assembler = new ContextAssembler();
        List<ChatMessage> out = assembler.assemble(SYSTEM, List.of(), null, List.of(), question("问"));

        assertEquals(2, out.size());
        assertEquals("问", textOf(out.get(1)));
    }

    @Test
    @DisplayName("系统提示为空时不加这一段（与改造前一致）")
    void skipsBlankSystem() {
        ContextAssembler assembler = new ContextAssembler();
        List<ChatMessage> out = assembler.assemble(null, List.of(), null, null, question("问"));
        assertEquals(1, out.size());
    }

    // ==================== 预算与降级 ====================

    @Test
    @DisplayName("窗口超预算：从最老的一端整轮丢，且不留下孤提问")
    void windowDropsWholeTurnsFromOldest() {
        // 窗口预算压到 300 token：4 轮长文本必然装不下
        ContextAssembler assembler = new ContextAssembler(24_000, 300, 2_000, 8, 5);
        List<ChatMessage> out = assembler.assemble(SYSTEM, turns(4, 400), null, null, question("本轮"));

        List<ChatMessage> window = windowOf(out, false, false);
        assertTrue(window.size() >= 2, "至少要留下最近一轮");
        assertEquals(0, window.size() % 2, "窗口必须是整轮，不留半轮：" + window.size());
        assertTrue(textOf(window.get(window.size() - 1)).startsWith("答"),
                "丢掉的是最老的，最新回答必须还在");
    }

    @Test
    @DisplayName("卡片超预算：从末位开始砍（相关度由高到低）")
    void cardsDropFromTail() {
        ContextAssembler assembler = new ContextAssembler(24_000, 24_000, 2_000, 8, 5);
        List<HistoryCard> cards = List.of(card(1), card(2), card(3), card(4), card(5));
        List<ChatMessage> out = assembler.assemble(SYSTEM, List.of(), null, cards, question("问"));

        String cardsText = textOf(out.get(1));
        assertTrue(cardsText.contains("第 1 轮"), "相关度最高的必须留下");
        assertFalse(cardsText.contains("第 5 轮"), "末位最相关的必须先被砍：" + cardsText);
    }

    @Test
    @DisplayName("摘要超预算：保留最近的、丢最老的，且按行切不切出半句话")
    void summaryKeepsNewestLines() {
        ContextAssembler assembler = new ContextAssembler(24_000, 24_000, 200, 8, 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("第 ").append(i).append(" 轮：").append("摘要内容".repeat(20));
            if (i < 20) {
                sb.append('\n');
            }
        }
        List<ChatMessage> out = assembler.assemble(SYSTEM, List.of(), sb.toString(), null, question("问"));

        // 第一行是渲染头，摘要行从第二行起
        String[] lines = textOf(out.get(1)).split("\n");
        assertFalse(join(lines).contains("第 1 轮："), "最老的要被丢");
        assertTrue(join(lines).contains("第 20 轮："), "最近的必须留着");
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].startsWith("第 ") && lines[i].contains(" 轮："),
                    "不能切出半句话：" + lines[i]);
        }
    }

    private static String join(String[] lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("永不丢本轮提问：预算小到连一段都装不下也一样")
    void currentQuestionSurvivesTinyBudget() {
        ContextAssembler assembler = new ContextAssembler(1, 1, 1, 8, 0);
        List<ChatMessage> out = assembler.assemble(SYSTEM, turns(3, 50), "第 1 轮：摘要", List.of(card(9)),
                question("本轮提问"));

        ChatMessage last = out.get(out.size() - 1);
        assertEquals("本轮提问", textOf(last));
        assertSame(question("本轮提问").getClass(), last.getClass());
    }

    @Test
    @DisplayName("窗口本身超预算到底：仍保留最近一轮，不把窗口清空")
    void windowNeverEmptied() {
        // 最近一轮自身就远超窗口预算
        ContextAssembler assembler = new ContextAssembler(24_000, 10, 2_000, 8, 0);
        List<ChatMessage> out = assembler.assemble(SYSTEM, turns(3, 2000), null, null, question("问"));

        List<ChatMessage> window = windowOf(out, false, false);
        assertEquals(2, window.size(), "最坏也只退到最近一轮");
    }

    // ==================== 摘要素材 ====================

    @Test
    @DisplayName("summaryOf：只摘要滑出窗口的老轮次，排除开放轮与中止轮")
    void summaryOfSkipsWindowOpenAndAborted() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecord(1, "老轮一", false, false),
                turnRecord(2, "开放轮", true, false),
                turnRecord(3, "中止轮", false, true),
                turnRecord(4, "窗口内一", false, false),
                turnRecord(5, "窗口内二", false, false));

        String summary = ContextAssembler.summaryOf(records, 2);

        assertNotNull(summary);
        assertTrue(summary.contains("第 1 轮"), "滑出窗口的老轮次要进摘要");
        assertFalse(summary.contains("第 2 轮"), "开放轮不是可引用内容");
        assertFalse(summary.contains("第 3 轮"), "中止轮不是可引用内容");
        assertFalse(summary.contains("第 4 轮") && summary.contains("第 5 轮"),
                "窗口内的轮次不重复进摘要");
    }

    @Test
    @DisplayName("summaryOf：没有老轮次时返回 null（不产生空的摘要段）")
    void summaryOfReturnsNullWhenNothingToSummarize() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecord(1, "只有一轮", false, false));
        assertNull(ContextAssembler.summaryOf(records, 8));
        assertNull(ContextAssembler.summaryOf(List.of(), 8));
        assertNull(ContextAssembler.summaryOf(null, 8));
    }

    @Test
    @DisplayName("cardsOf：同样排除开放轮与中止轮，工具名去重")
    void cardsOfSkipsNonCitableTurns() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecordWithTools(1, false, false, "query_order", "query_order", "refund"),
                turnRecord(2, "开放", true, false),
                turnRecord(3, "中止", false, true));

        List<HistoryCard> cards = ContextAssembler.cardsOf(records);

        assertEquals(1, cards.size());
        assertEquals(1L, cards.get(0).turnId());
        assertEquals(List.of("query_order", "refund"), cards.get(0).tools(), "工具名要去重");
    }

    // ==================== 滚动摘要 + 缺口补齐（M4-R1）====================

    @Test
    @DisplayName("summaryWithRolling：没有滚动摘要时等于纯 digest 拼接（M2 行为）")
    void rollingAbsentFallsBackToDigest() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecord(1, "老轮一", false, false),
                turnRecord(2, "老轮二", false, false),
                turnRecord(3, "窗口内", false, false));

        assertEquals(ContextAssembler.summaryOf(records, 1),
                ContextAssembler.summaryWithRolling(null, records, 1, 0L));
        assertEquals(ContextAssembler.summaryOf(records, 1),
                ContextAssembler.summaryWithRolling("   ", records, 1, 0L));
    }

    @Test
    @DisplayName("summaryWithRolling：摘要已覆盖全部滑出窗口轮次时，只剩摘要本身（trim）")
    void rollingCoversGapLeavesSummaryOnly() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecord(1, "老轮一", false, false),
                turnRecord(2, "老轮二", false, false),
                turnRecord(3, "窗口内", false, false));

        // 摘要已覆盖到第 2 轮，滑出窗口的是 1、2，没有缺口
        assertEquals("滚动摘要正文",
                ContextAssembler.summaryWithRolling("  滚动摘要正文  ", records, 1, 2L));
    }

    @Test
    @DisplayName("summaryWithRolling：摘要滞后的那几轮用 digest 补齐（否则历史凭空消失）")
    void rollingLeavesGapFilledByDigest() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecord(1, "老轮一", false, false),
                turnRecord(2, "老轮二", false, false),
                turnRecord(3, "老轮三", false, false),
                turnRecord(4, "窗口内", false, false));

        // 窗口 1 轮 → 滑出 1、2、3；摘要只覆盖到第 1 轮 → 缺口是 2、3
        String out = ContextAssembler.summaryWithRolling("滚动摘要", records, 1, 1L);

        assertNotNull(out);
        assertTrue(out.startsWith("滚动摘要"), "摘要在前：" + out);
        assertTrue(out.contains("第 2 轮：老轮二"), "缺口第一轮要补上：" + out);
        assertTrue(out.contains("第 3 轮：老轮三"), "缺口最末轮要补上：" + out);
        assertFalse(out.contains("老轮一"), "已被摘要覆盖的轮次不重复：" + out);
    }

    @Test
    @DisplayName("summaryWithRolling：滚动摘要与缺口都没有时返回 null（不产生空摘要段）")
    void rollingAndGapBothEmptyReturnsNull() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecord(1, "唯一一轮", false, false));
        // 没有滚动摘要、也没有滑出窗口的轮次（全在窗口内）
        assertNull(ContextAssembler.summaryWithRolling(null, records, 8, 0L));
        // 有滚动摘要但没有缺口：返回摘要本身（不是 null）
        assertEquals("滚动摘要", ContextAssembler.summaryWithRolling("滚动摘要", records, 8, 1L));
        // 两边都没有
        assertNull(ContextAssembler.summaryWithRolling(null, null, 8, 5L));
    }

    @Test
    @DisplayName("summaryWithRolling：缺口同样排除开放轮与中止轮")
    void gapExcludesNonCitableTurns() {
        List<com.zzkingcc.domainplane.api.conversation.TurnRecord> records = List.of(
                turnRecord(1, "开放", true, false),
                turnRecord(2, "中止", false, true),
                turnRecord(3, "正常", false, false),
                turnRecord(4, "窗口内", false, false));

        String out = ContextAssembler.summaryWithRolling(null, records, 1, 0L);

        assertNotNull(out);
        assertTrue(out.contains("第 3 轮：正常"));
        assertFalse(out.contains("开放"));
        assertFalse(out.contains("中止"));
    }

    // ==================== 渲染 ====================

    @Test
    @DisplayName("卡片渲染带轮号 / 时间 / 摘要 / 工具，并提示用 read_history 取原文")
    void cardRendering() {
        HistoryCard card = new HistoryCard(12, Instant.parse("2026-10-01T09:30:00Z"),
                "关于订单导出", List.of("query_order"));
        String text = TurnView.renderCards(List.of(card));

        assertTrue(text.startsWith("【可能相关的历史轮次】"));
        assertTrue(text.contains("· 第 12 轮"));
        assertTrue(text.contains("关于订单导出"));
        assertTrue(text.contains("用过 query_order"));
        assertTrue(text.contains("read_history"));
    }

    @Test
    @DisplayName("摘要渲染是单段文本，带取原文的指引")
    void summaryRendering() {
        String text = TurnView.renderSummary("第 1 轮：问订单");
        assertTrue(text.startsWith("【早期对话摘要】"));
        assertTrue(text.contains("第 1 轮：问订单"));
        assertTrue(text.contains("read_history"));
        assertEquals("", TurnView.renderSummary(null));
    }

    // ==================== helpers ====================

    private static HistoryCard card(long turnId) {
        return new HistoryCard(turnId, Instant.now(), "第 " + turnId + " 轮摘要", List.of());
    }

    private static com.zzkingcc.domainplane.api.conversation.TurnRecord turnRecord(
            long id, String digest, boolean open, boolean aborted) {
        return new com.zzkingcc.domainplane.api.conversation.TurnRecord(
                id, "default|s", Instant.now(), open ? null : Instant.now(),
                com.zzkingcc.domainplane.api.conversation.UserInput.of("问", List.of()),
                List.of(), "答", null,
                new com.zzkingcc.domainplane.api.conversation.TurnFlags(false, aborted, false, null),
                digest);
    }

    private static com.zzkingcc.domainplane.api.conversation.TurnRecord turnRecordWithTools(
            long id, boolean open, boolean aborted, String... tools) {
        List<com.zzkingcc.domainplane.api.conversation.FlowEvent> flow = new ArrayList<>();
        for (String t : tools) {
            flow.add(com.zzkingcc.domainplane.api.conversation.FlowEvent.ofTool(
                    com.zzkingcc.domainplane.api.conversation.FlowEventType.TOOL_CALL, t, "{}"));
        }
        return new com.zzkingcc.domainplane.api.conversation.TurnRecord(
                id, "default|s", Instant.now(), open ? null : Instant.now(),
                com.zzkingcc.domainplane.api.conversation.UserInput.of("问", List.of()),
                flow, "答", null,
                new com.zzkingcc.domainplane.api.conversation.TurnFlags(false, aborted, false, null),
                "摘要");
    }

    private static String textOf(ChatMessage message) {
        if (message instanceof SystemMessage sm) {
            return sm.text();
        }
        if (message instanceof UserMessage um && um.hasSingleText()) {
            return um.singleText();
        }
        if (message instanceof AiMessage am) {
            return am.text();
        }
        return "";
    }
}
