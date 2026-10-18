package com.zzkingcc.domainplane.runtime.conversation;

/**
 * 装配预算的封顶计算：把"平台想给多少"与"模型能吃多少"取小。
 *
 * <p><b>为什么需要它</b>：{@link ContextAssembler} 的预算是一个与模型无关的常量
 * （{@code domainplane.memory.assembly-budget}，默认 24000）。一旦本轮解析到的模型窗口更小
 * （自建端点上的小模型、只开 8k/16k 的老模型），装配结果照样按 24000 拼出来，
 * 请求打到服务商才以一句"context length exceeded"失败，而平台此时既认不出这是超限，
 * 也说不清是哪一步导致的（统一落进 {@code ORCHESTRATION_FAILED}）。</p>
 *
 * <p><b>只封顶，不放大</b>：模型声明的窗口比配置预算大时，预算维持配置值不变。两个理由，</p>
 *
 * <ul>
 *   <li>放大预算是一个独立的行为变更（给模型看更多历史），该单独决策、单独验证，
 *       不该"顺手"随这个字段一起生效；</li>
 *   <li>这个数由用户手填，<b>填大了不构成任何保证</b>。真实的 token 计数由服务商算，
 *       用户对着文档抄的数字与平台的估算都对不上它。于是它最可靠的用法是护栏：
 *       填小了会被采纳，填大了不该被当成"还能再塞"。</li>
 * </ul>
 *
 * <p>全部是纯函数：预算是"这轮给模型看多少"的唯一决策点之一，值得有测试盯着。</p>
 *
 * @author zzkingcc
 */
public final class ContextBudget {

    /**
     * 档案未声明单次最大输出时，按上下文窗口的这一比例预留生成空间。
     *
     * <p>为什么不按"未声明 = 不预留"：那样可用输入就等于整个窗口，而模型总归要产出回答，
     * 于是必然在生成阶段撞上限，表现为"输入刚好装下、回答被截断"，比直接失败更难察觉。
     * 取 1/4 是与主流服务商"输出上限约为窗口 1/4"的保守一侧对齐。</p>
     */
    private static final double DEFAULT_OUTPUT_SHARE = 0.25;

    /**
     * 安全边际（token）。
     *
     * <p>装配层算的是估算值，真实计数在服务商那边。差一点就顶到上限的装配没有意义：
     * 成功了也是侥幸，失败了却要用户重来一轮。留一格余量比"用满"划算。</p>
     */
    private static final int SAFETY_MARGIN = 1_024;

    /**
     * 封顶后的下限：再小的上限也要留出"系统提示 + 本轮提问"这一份。
     *
     * <p>用户把窗口填成 1000 这种量级时，{@code 窗口 - 预留 - 边际} 会算出非正数。
     * 那种情况不该返回 0 或负数（装配层的降级链会退到什么都不剩），而是退到
     * {@link ContextAssembler} 里"最坏只留最近 2 条"的那一档，这一轮大概率仍会失败，
     * 但失败面最小，且用户从提示里能看出"是我把窗口填小了"。</p>
     */
    private static final int MIN_BUDGET = 512;

    private ContextBudget() {
    }

    /**
     * 算出本轮实际可用的装配预算。
     *
     * @param configuredBudget 平台配置的装配预算（{@code ContextAssembler#totalBudget()}）
     * @param contextWindow    模型声明的上下文窗口（token）；{@code null} / 非正数 = 未声明
     * @param maxOutputTokens  档案声明的单次最大输出；{@code null} = 未声明，按比例预留
     * @return 实际预算；模型未声明窗口时原样返回 {@code configuredBudget}
     */
    public static int effective(int configuredBudget, Integer contextWindow, Integer maxOutputTokens) {
        if (contextWindow == null || contextWindow <= 0) {
            return configuredBudget;
        }
        int reserve = reserveOf(contextWindow, maxOutputTokens);
        int usable = contextWindow - reserve - SAFETY_MARGIN;
        return Math.min(configuredBudget, Math.max(MIN_BUDGET, usable));
    }

    /**
     * 为生成预留多少 token。
     *
     * <p>档案显式声明了单次最大输出就用它，那是用户自己定的上限，比按比例猜更准。
     * 但只在它小于窗口时采信：填得比窗口还大的值显然是笔误，
     * 采信它会让可用输入变成负数，把问题从"输出被截断"放大成"整轮装不下"。</p>
     */
    private static int reserveOf(int contextWindow, Integer maxOutputTokens) {
        if (maxOutputTokens != null && maxOutputTokens > 0 && maxOutputTokens < contextWindow) {
            return maxOutputTokens;
        }
        return (int) (contextWindow * DEFAULT_OUTPUT_SHARE);
    }
}
