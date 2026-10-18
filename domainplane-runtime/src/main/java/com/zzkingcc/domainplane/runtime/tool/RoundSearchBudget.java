package com.zzkingcc.domainplane.runtime.tool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一轮对话里历史检索工具（{@code read_history} / {@code recall_history}）的调用预算。
 *
 * <p>防的是模型把检索当抛球游戏：每轮先搜十次再答。两个工具合计每轮最多
 * {@link #MAX_CALLS} 次，超了工具返回一句明确的"已达本轮检索上限"，
 * 让模型基于已有内容回答，而不是无限检索把延迟与 token 都烧掉。</p>
 *
 * <p>对象由编排层每轮新建，随 {@link ToolInvocationContext} 绑定、
 * 经工具隔离层原样搬运到工具线程；{@link #tryAcquire()} 是原子的，
 * 同一轮里多次工具调用（哪怕跨 agent→tools 多个循环）扣的是同一个桶。</p>
 *
 * @author zzkingcc
 */
public final class RoundSearchBudget {

    /** 每轮合计检索上限（两个工具共用） */
    public static final int MAX_CALLS = 3;

    private final AtomicInteger remaining;

    public RoundSearchBudget() {
        this.remaining = new AtomicInteger(MAX_CALLS);
    }

    /**
     * 尝试占用一次检索额度。
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
