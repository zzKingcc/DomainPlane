package com.zzkingcc.domainplane.runtime.usage;

import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M4 真实用量回填：两条口径的合流规则。
 *
 * <p>这里锁的核心是<b>不虚高、也不冒充</b>：服务商报了就用真值，没报的那一侧是
 * {@code null}（而不是拿估算值填上装作真实）；两条口径下 {@code totalTokens} 的含义不同，
 * 不能混着加（真实分支再叠加工具估算 = 重复计数，因为工具用量已含在下一轮 prompt 里）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("M4 用量回填：实报优先、缺失回落、合计不重复计数")
class TokenUsageRecorderTest {

    @AfterEach
    void clearStats() {
        TokenUsageRecorder.finishAndLog();
    }

    @Test
    @DisplayName("整轮无上报：prompt 为空，completion 与合计都走估算（合计含工具）")
    void estimateOnlyWhenNothingReported() {
        TokenUsageRecorder.begin();
        TokenUsageRecorder.addLlmOutputTokens(100);
        TokenUsageRecorder.recordToolCall("query_order", "aaaa", "bbbbbbbb");
        int toolTokens = TokenUsageRecorder.estimateTokens("aaaa")
                + TokenUsageRecorder.estimateTokens("bbbbbbbb");

        TokenUsage usage = TokenUsageRecorder.toUsage();
        assertNull(usage.promptTokens(), "没有上报就不编 prompt");
        assertEquals(100, usage.completionTokens());
        assertEquals(100 + toolTokens, usage.totalTokens(),
                "估算口径 = LLM 输出 + 工具输入 + 工具输出");
    }

    @Test
    @DisplayName("两侧都上报：走真实值，合计 = prompt + completion，不叠加工具估算")
    void realUsageWinsAndDoesNotDoubleCountTools() {
        TokenUsageRecorder.begin();
        TokenUsageRecorder.addLlmOutputTokens(999);          // 估算值应被真实值盖掉
        TokenUsageRecorder.recordToolCall("query_order", "aaaa", "bbbbbbbb"); // 真实口径下不该进合计
        TokenUsageRecorder.recordRealUsage(1500, 220);

        TokenUsage usage = TokenUsageRecorder.toUsage();
        assertEquals(1500, usage.promptTokens());
        assertEquals(220, usage.completionTokens());
        assertEquals(1720, usage.totalTokens(), "工具用量已含在下一轮 prompt 里，不能重复相加");
    }

    @Test
    @DisplayName("只上报一侧：报的那侧用真值，另一侧回落估算")
    void partialReportFallsBackPerSide() {
        TokenUsageRecorder.begin();
        TokenUsageRecorder.addLlmOutputTokens(80);
        TokenUsageRecorder.recordRealUsage(1000, null);

        TokenUsage usage = TokenUsageRecorder.toUsage();
        assertEquals(1000, usage.promptTokens());
        assertEquals(80, usage.completionTokens(), "生成侧没报，回落估算");
        assertEquals(1080, usage.totalTokens());
    }

    @Test
    @DisplayName("一轮多次模型调用：真实用量累加（agent → tools → agent）")
    void realUsageAccumulatesAcrossCalls() {
        TokenUsageRecorder.begin();
        TokenUsageRecorder.recordRealUsage(1000, 100);
        TokenUsageRecorder.recordRealUsage(1200, 150);

        TokenUsage usage = TokenUsageRecorder.toUsage();
        assertEquals(2200, usage.promptTokens());
        assertEquals(250, usage.completionTokens());
        assertEquals(2450, usage.totalTokens());
        assertEquals(2, TokenUsageRecorder.snapshot().realUsageCalls());
    }

    @Test
    @DisplayName("两侧都是 null：不算一次上报（绝不拿 0 冒充真实）")
    void nullsAreNotReported() {
        TokenUsageRecorder.begin();
        TokenUsageRecorder.addLlmOutputTokens(50);
        TokenUsageRecorder.recordRealUsage(null, null);

        assertEquals(0, TokenUsageRecorder.snapshot().realUsageCalls());
        TokenUsage usage = TokenUsageRecorder.toUsage();
        assertNull(usage.promptTokens());
        assertEquals(50, usage.completionTokens(), "仍然回落估算");
    }

    @Test
    @DisplayName("上报 0 视为「这一侧没数据」：不写进合计，另一侧照旧")
    void zeroIsTreatedAsMissing() {
        TokenUsageRecorder.begin();
        TokenUsageRecorder.recordRealUsage(0, 300);

        TokenUsage usage = TokenUsageRecorder.toUsage();
        assertNull(usage.promptTokens(), "0 不等于「真实为 0」，是没有数据");
        assertEquals(300, usage.completionTokens());
        assertEquals(300, usage.totalTokens());
    }

    @Test
    @DisplayName("begin 清空上一轮残留：不允许上轮用量串到本轮")
    void beginResetsStats() {
        TokenUsageRecorder.begin();
        TokenUsageRecorder.recordRealUsage(1000, 100);

        TokenUsageRecorder.begin();
        TokenUsage usage = TokenUsageRecorder.toUsage();
        assertNull(usage.promptTokens());
        assertEquals(0, usage.completionTokens());
        assertEquals(0, usage.totalTokens());
    }
}
