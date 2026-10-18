package com.zzkingcc.domainplane.runtime.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按本轮预算装配（{@link ContextAssembler#withTotalBudget}）。
 *
 * <p>它服务的是"模型上下文比配置预算更小"这一种情形：装配实例是启动期按配置造好的单例，
 * 而模型按域、按请求解析，所以"给模型看多少"必须每轮重算一次。这里锁三件事：</p>
 *
 * <ul>
 *   <li><b>按同一比例同步收窄</b>——只换总预算的话，收缩会全部落到最后放入的窗口段上，
 *       总预算 2000 时摘要段能吃掉 1900、窗口只剩兜底的 512，与"摘要补缺口、窗口才是
 *       近况原文"的优先级正好相反；</li>
 *   <li><b>轮数 / 条数上限不动</b>——它们不由 token 预算定义；</li>
 *   <li><b>收窄真的生效</b>——同一份历史，收窄后装配出的窗口确实比不收紧时短，
 *       且本轮提问永远在最后。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("按本轮预算装配（模型窗口小于配置预算时）")
class ContextAssemblerBudgetTest {

    private static final SystemMessage SYSTEM = SystemMessage.from("你是域助手");
    private static final int TOTAL = 24_000;
    private static final int WINDOW = (int) (TOTAL * 0.6);
    private static final int SUMMARY = 2_000;
    private static final int TURNS = 8;
    private static final int CARDS = 5;

    private static ContextAssembler base() {
        return new ContextAssembler(TOTAL, WINDOW, SUMMARY, TURNS, CARDS);
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

    @Test
    void 预算没变时返回自身() {
        ContextAssembler a = base();
        assertSame(a, a.withTotalBudget(TOTAL), "没变就不该造新对象");
    }

    @Test
    void 收窄时窗口段与摘要段按同一比例同步收窄() {
        ContextAssembler narrowed = base().withTotalBudget(12_000);

        assertEquals(12_000, narrowed.totalBudget());
        assertEquals((int) Math.round(WINDOW * 0.5), narrowed.windowBudget(),
                "窗口段应等比收窄，实际 " + narrowed.windowBudget());
        assertEquals((int) Math.round(SUMMARY * 0.5), narrowed.summaryBudget(),
                "摘要段应等比收窄 —— 只收紧总预算会让它吃掉小预算下的大半份额");
    }

    @Test
    void 轮数上限与卡片条数上限不被改动() {
        ContextAssembler narrowed = base().withTotalBudget(2_000);

        assertEquals(TURNS, narrowed.windowTurns(),
                "窗口轮数是轮数上限，不由 token 预算定义");
        assertEquals(CARDS, narrowed.maxCards(),
                "卡片条数是条数上限，实际装配时会被预算进一步收紧");
    }

    @Test
    void 收窄后窗口确实变短且本轮提问仍在最后() {
        List<ChatMessage> window = turns(TURNS, 400);
        UserMessage current = UserMessage.from("本轮提问");

        List<ChatMessage> full = base().assemble(SYSTEM, window, null, List.of(), current);
        List<ChatMessage> tight = base().withTotalBudget(2_000)
                .assemble(SYSTEM, window, null, List.of(), current);

        assertSame(current, tight.get(tight.size() - 1), "本轮提问永不丢");
        assertEquals(SYSTEM, tight.get(0), "系统提示不进预算，照旧在最前");
        assertTrue(tight.size() < full.size(),
                "收窄应真的生效：宽预算 " + full.size() + " 条，紧预算 " + tight.size() + " 条");
    }

    @Test
    void 小预算下窗口仍保底最近一轮() {
        /* 摘要段很长 + 窗口段很长、预算很小：最坏那一档 */
        List<ChatMessage> window = turns(TURNS, 400);
        String longSummary = String.join("\n", java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> "第 " + i + " 轮：" + "老".repeat(80))
                .toList());
        UserMessage current = UserMessage.from("本轮提问");

        List<ChatMessage> out = base().withTotalBudget(2_000)
                .assemble(SYSTEM, window, longSummary, List.of(), current);

        /* 段序固定为 系统 / 摘要 / 卡片 / 窗口 / 本轮提问：这里没卡片，故窗口在倒数第二位之前 */
        int windowMessages = out.size() - 3;
        assertTrue(windowMessages >= 2,
                "窗口段至少要保住最近一轮（2 条消息），实际 " + windowMessages + " 条 —— "
                        + "退到 0 条等于让模型看不见上一轮说了什么");
        assertSame(current, out.get(out.size() - 1));
    }
}
