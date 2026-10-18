package com.zzkingcc.domainplane.runtime.conversation;

import org.junit.jupiter.api.Test;

/**
 * 装配预算的封顶计算（{@link ContextBudget}）。
 *
 * <p>这一层只做一件事：把"平台想给多少"与"模型能吃多少"取小，且只封顶、不放大。
 * 断言用 {@code assert} 语句（与项目其余测试一致，surefire 默认开启 -ea）。</p>
 *
 * @author zzkingcc
 */
class ContextBudgetTest {

    private static final int CONFIGURED = 24_000;

    @Test
    void 未声明窗口时维持配置值() {
        assert ContextBudget.effective(CONFIGURED, null, null) == CONFIGURED
                : "未声明 = 平台不知道 → 保持原行为，而不是按 0 去把预算压到下限";
        assert ContextBudget.effective(CONFIGURED, 0, null) == CONFIGURED
                : "0 / 负数已在档案层归一为未声明，这里再兜一道";
        assert ContextBudget.effective(CONFIGURED, -1, null) == CONFIGURED;
    }

    @Test
    void 窗口大于配置预算时不放大() {
        /* 放大预算是一个独立的行为变更；而这个数由用户手填、填大了不构成任何保证 */
        assert ContextBudget.effective(CONFIGURED, 128_000, null) == CONFIGURED
                : "只封顶不放大：填 128k 不该把预算顶到 128k";
        assert ContextBudget.effective(CONFIGURED, 200_000, 4_096) == CONFIGURED;
    }

    @Test
    void 小窗口按输出预留与安全边际收紧() {
        /* 32k 窗口 + 声明输出 8k → 32000 - 8000 - 1024 = 22976，小于配置的 24000，故收紧 */
        assert ContextBudget.effective(CONFIGURED, 32_000, 8_192) == 32_000 - 8_192 - 1_024
                : "实际 " + ContextBudget.effective(CONFIGURED, 32_000, 8_192);
    }

    @Test
    void 未声明输出上限时按窗口四分之一预留() {
        /* 8k 窗口 + 未声明输出 → 8000 - 2000 - 1024 = 4976 */
        int got = ContextBudget.effective(8_000, 8_000, null);
        assert got == 8_000 - 2_000 - 1_024 : "实际 " + got;
    }

    @Test
    void 输出预留声明得比窗口还大时不采信() {
        /* 填得比窗口还大的 maxTokens 显然是笔误；采信它会让可用输入变成负数，
           把问题从「输出被截断」放大成「整轮装不下」 */
        int got = ContextBudget.effective(CONFIGURED, 16_000, 32_000);
        assert got == 16_000 - 4_000 - 1_024 : "应按比例预留，而不是按 32k 预留；实际 " + got;
    }

    @Test
    void 极小窗口退到下限而不是负数() {
        /* 用户把窗口填成 1000：算出来是负的。返回负数会让 ContextAssembler 的降级链
           退到什么都不剩；退到下限至少保得住「系统提示 + 最近一轮 + 本轮提问」那一档 */
        assert ContextBudget.effective(CONFIGURED, 1_000, null) == 512
                : "实际 " + ContextBudget.effective(CONFIGURED, 1_000, null);
        assert ContextBudget.effective(CONFIGURED, 1, 1) == 512;
    }

    @Test
    void 恰好等于配置预算时不做多余收紧() {
        /* 24000 窗口 + 未声明输出 → 24000 - 6000 - 1024 = 16976，确实小于 24000，应收紧；
           这里要的是"取小"这个动作本身不出错，不是"一定不变" */
        int got = ContextBudget.effective(CONFIGURED, CONFIGURED, null);
        assert got == CONFIGURED - 6_000 - 1_024 : "实际 " + got;
    }
}
