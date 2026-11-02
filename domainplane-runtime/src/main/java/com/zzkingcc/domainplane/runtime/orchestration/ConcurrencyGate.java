package com.zzkingcc.domainplane.runtime.orchestration;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编排的显式并发闸门。
 *
 * <p><b>它为什么必须存在</b>：换成虚拟线程之后，承载执行的是"每轮一个虚拟线程"，
 * 那个执行器没有队列上限 —— 原先由平台线程池（core 8 / queue 200）顺带承担的<b>背压</b>职责
 * 就此消失。少了这道闸门，"服务过载"的失败形态会从快速失败（{@code SYSTEM_BUSY}）
 * 变成内存里堆积几十万个同时阻塞在 IO 上的虚拟线程，最终是 OOM 而不是一条拒绝事件。</p>
 *
 * <p>因此这里刻意保留旧 semantics：<b>取不到许可 = 立即拒绝</b>，不排队。
 * 编排任务排队没有意义 —— 用户盯着流式输出等答复，排队 30 秒再开跑，等到的回答已经过期。
 * 快速失败让调用方有机会退避重试，也让人能看见系统已经饱和。</p>
 *
 * <p>三个计数（在飞峰值 / 累计拒绝）是给运维的唯一线索：闸门满了之后，
 * 被拒的请求在开发机上看起来就像"时好时坏"，没有计数就无法区分是偶发还是长期饱和。</p>
 */
public final class ConcurrencyGate {

    private final Semaphore permits;
    private final int limit;
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();

    public ConcurrencyGate(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("并发闸门上限必须为正数，实际 " + limit);
        }
        this.limit = limit;
        this.permits = new Semaphore(limit);
    }

    /**
     * 尝试为本轮取得一个许可。
     *
     * @return true = 可以开跑；false = 已达上限，调用方应按 {@code SYSTEM_BUSY} 拒绝本轮
     */
    public boolean tryAcquire() {
        boolean acquired = permits.tryAcquire();
        if (acquired) {
            peak.accumulateAndGet(limit - permits.availablePermits(), Math::max);
        } else {
            rejected.incrementAndGet();
        }
        return acquired;
    }

    /** 归还许可；必须与 {@link #tryAcquire()} 成功后的那一轮成对出现在 finally 里 */
    public void release() {
        permits.release();
    }

    public int limit() {
        return limit;
    }

    /** 当前在执行的轮数 */
    public int inFlight() {
        return limit - permits.availablePermits();
    }

    /** 启动以来的在飞峰值 */
    public int peakInFlight() {
        return peak.get();
    }

    /** 累计被闸门拒掉的轮数 */
    public long rejectedCount() {
        return rejected.get();
    }
}
