package com.zzkingcc.domainplane.runtime.model;

/**
 * 本轮主模型声明的分档上限 —— 目前只有"上下文窗口"与"单次最大输出"两项。
 *
 * <p>与 {@link VisionTarget} 同一个理由放在内核侧：内核（编排层）需要"这个模型能吃多少"
 * 才能给装配预算封顶，但这两个数从哪来（档案字段、将来可能的自动识别）是服务端的实现
 * 细节，内核只该拿到结果。</p>
 *
 * <p>两个分量都可为 {@code null}，语义是"该档案没声明这一项"，不是"为零"：
 * 调用方（{@code ContextBudget}）遇到 {@code null} 就不做这一侧的收紧。
 * 把未声明当成零，会让所有没填过的档案装配预算被压到下限。</p>
 *
 * @param contextWindow    上下文窗口（token，含输入与输出）；{@code null} = 未声明
 * @param maxOutputTokens  单次最大输出 token；{@code null} = 未声明（由服务商默认决定）
 * @author zzkingcc
 */
public record ModelLimits(Integer contextWindow, Integer maxOutputTokens) {

    /** 两个上限都未声明 —— 调用方据此不做任何收紧 */
    public static ModelLimits unknown() {
        return new ModelLimits(null, null);
    }
}
