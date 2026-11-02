package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F04-a 装配清单：每段给了多少、砍了多少，逐段落账。
 *
 * <p>这里锁三条：</p>
 * <ul>
 *   <li><b>清单是纯增量</b>：{@code assemble} 与 {@code assembleWithReport} 的消息序列必须一致
 *       —— 观测不能反过来改装配结果；</li>
 *   <li><b>砍了就要记账</b>：摘要截行 / 卡片被砍 / 窗口整轮丢，三处各有各的字段，
 *       且任一发生即 {@code degraded}；</li>
 *   <li><b>"召回了但被砍"要能分辨</b>：这是 {@link ContextAssemblyReport#lostRecall()} 存在的全部理由。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("F04-a 装配清单：每段给了多少 / 砍了多少")
class ContextAssemblyReportTest {

    private static final SystemMessage SYSTEM = SystemMessage.from("你是域助手");

    private static UserMessage question(String text) {
        return UserMessage.from(text);
    }

    private static List<ChatMessage> turns(int count, int charsEach) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(UserMessage.from("问".repeat(Math.max(1, charsEach / 2)) + i));
            out.add(AiMessage.from("答".repeat(Math.max(1, charsEach / 2)) + i));
        }
        return out;
    }

    private static HistoryCard card(long turnId) {
        return new HistoryCard(turnId, Instant.now(), "这一轮讨论了退款时效与流程" + turnId, List.of());
    }

    @Test
    @DisplayName("清单是纯增量：带清单装配与不带清单装配的消息序列必须逐条一致")
    void reportIsPurelyAdditive() {
        ContextAssembler assembler = new ContextAssembler();
        List<ChatMessage> window = turns(2, 40);
        List<HistoryCard> cards = List.of(card(3));

        List<ChatMessage> plain = assembler.assemble(SYSTEM, window, "第 1 轮：问订单", cards,
                question("本轮提问"));
        ContextAssembler.Assembled assembled = assembler.assembleWithReport("d|s", 7L, SYSTEM,
                window, "第 1 轮：问订单", cards, question("本轮提问"));

        assertEquals(plain.size(), assembled.messages().size());
        for (int i = 0; i < plain.size(); i++) {
            assertEquals(plain.get(i), assembled.messages().get(i),
                    "第 " + i + " 条消息不一致：加观测不能改装配结果");
        }
        assertEquals("d|s", assembled.report().sessionKey());
        assertEquals(7L, assembled.report().turnId());
    }

    @Test
    @DisplayName("预算充足：不降级、不丢任何一段")
    void nothingDroppedWhenBudgetIsEnough() {
        ContextAssembler assembler = new ContextAssembler();

        ContextAssemblyReport report = assembler.assembleWithReport("d|s", 1L, SYSTEM, turns(2, 40),
                "第 1 轮：问订单", List.of(card(3)), question("本轮")).report();

        assertFalse(report.degraded(), "预算充足时不该有降级");
        assertFalse(report.lostRecall());
        assertTrue(report.droppedCardTurnIds().isEmpty());
        assertEquals(0, report.windowDroppedMessages());
        assertEquals(0, report.summaryDroppedLines());
        assertEquals(4, report.windowMessages(), "两轮 = 4 条消息");
        assertTrue(report.windowTokens() > 0);
        assertTrue(report.totalTokens() >= report.windowTokens() + report.currentTokens());
    }

    @Test
    @DisplayName("摘要被按行截：截掉的行数要记进 summaryDroppedLines")
    void summaryClippingIsAccounted() {
        // 摘要预算压到 1 token：多行摘要必然被截
        ContextAssembler assembler = new ContextAssembler(24_000, 14_400, 1, 8, 5);
        String summary = "第 1 轮：问订单\n第 2 轮：问退款\n第 3 轮：问发票";

        ContextAssemblyReport report = assembler.assembleWithReport("d|s", 1L, SYSTEM, List.of(),
                summary, List.of(), question("本轮")).report();

        assertTrue(report.summaryDroppedLines() > 0, "摘要被截就要记账");
        assertTrue(report.degraded());
    }

    @Test
    @DisplayName("卡片被预算砍掉：被砍的轮号进 droppedCardTurnIds，lostRecall 为真")
    void droppedCardsAreRecorded() {
        ContextAssembler assembler = new ContextAssembler(100, 60, 10, 8, 5);
        List<HistoryCard> cards = List.of(card(2), card(3), card(4));

        ContextAssemblyReport report = assembler.assembleWithReport("d|s", 1L, SYSTEM, List.of(),
                null, cards, question("本轮")).report();

        assertEquals(1, report.cardCount(), "预算只够留一张");
        assertEquals(List.of(3L, 4L), report.droppedCardTurnIds(),
                "被砍的是相关度最低的那两张（调用方已按相关度排序）");
        assertTrue(report.lostRecall(), "召回了但被砍 —— 这正是【它为什么忘了】的答案之一");
    }

    @Test
    @DisplayName("窗口整轮丢：丢的消息条数要记进 windowDroppedMessages")
    void windowDroppingIsAccounted() {
        ContextAssembler assembler = new ContextAssembler(24_000, 100, 2_000, 8, 5);

        ContextAssemblyReport report = assembler.assembleWithReport("d|s", 1L, SYSTEM,
                turns(4, 400), null, List.of(), question("本轮")).report();

        assertEquals(2, report.windowMessages(), "兜底：再怎么紧也留最近一轮");
        assertEquals(6, report.windowDroppedMessages(), "8 条只剩 2 条，丢了 6 条（整轮丢，2 条一批）");
        assertTrue(report.degraded());
        assertTrue(report.lostRecall());
    }

    @Test
    @DisplayName("单条提问顶满预算：只留系统提示与提问，其余全部记为丢弃")
    void questionFillingBudgetIsDegraded() {
        ContextAssembler assembler = new ContextAssembler(1, 1, 1, 8, 5);

        ContextAssembler.Assembled assembled = assembler.assembleWithReport("d|s", 1L, SYSTEM,
                turns(2, 40), "第 1 轮：问订单", List.of(card(3)), question("一个很长很长的问题"));

        assertEquals(2, assembled.messages().size(), "只剩系统提示 + 本轮提问");
        assertTrue(assembled.report().degraded());
        assertEquals(4, assembled.report().windowDroppedMessages(), "窗口整段没进请求");
    }
}
