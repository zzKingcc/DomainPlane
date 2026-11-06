package com.zzkingcc.domainplane.runtime.tool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一轮对话里 {@code generateImage} 的调用预算。
 *
 * <p>防的是模型「画一张 → seeImage 复核 → 不满意 → 换个 prompt 再画」的自主纠错循环
 * 没有终止条件：编排图上 agent→tools 的回边是无限的，模型只能靠自己在思考里写
 * 「别再循环了」才收手。一轮跑六次生图，耗时与费用都翻几倍，最后用户还只看到一堆图。</p>
 *
 * <p>与 {@link RoundSearchBudget} 同一套做法：对象由编排层每轮新建，挂在
 * {@code StreamContext} 上随本轮生灭（不需要清理钩子，也不会泄漏），
 * 经 {@link ToolInvocationContext} 按引用搬到工具线程，
 * {@link #tryAcquire()} 原子扣减，跨多个 agent→tools 循环扣的是同一个桶。</p>
 *
 * <p><b>为什么不用「按 sessionId 的全局计数」</b>：那需要额外定义「一轮何时结束」并
 * 处理审批暂停 / 断流 / 多实例，漏清一次就永久占着额度。挂在本轮上下文上，
 * 生命周期由 JVM 自己管，是最短的正确解。</p>
 *
 * <p><b>预算用尽不是失败</b>：工具回一句「从已有编号里选一张」而不是抛异常，
 * 模型才有机会收敛到最终答复；抛异常会把整轮变成 ERROR，一张图都交付不了。</p>
 *
 * @author zzkingcc
 */
public final class ImageGenBudget {

    /** 每轮最多生成几次（含复核后重画）。初稿 + 两次修正足够，再多是模型在碰运气 */
    public static final int MAX_CALLS = 3;

    private final AtomicInteger remaining = new AtomicInteger(MAX_CALLS);

    /**
     * 尝试占用一次生成额度。
     *
     * @return {@code true} = 占用成功；{@code false} = 已达本轮上限
     */
    public boolean tryAcquire() {
        return remaining.decrementAndGet() >= 0;
    }

    /** 剩余额度（日志 / 可观测） */
    public int remaining() {
        return Math.max(0, remaining.get());
    }
}
