package com.zzkingcc.domainplane.runtime.cancellation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同会话串行的载体：{@code Set.add} 的原子性即"独占获取"。
 *
 * <p>这条语义错了会同时坏两件事——并发请求没被挡住（记忆与断点是单份资源），
 * 或者被挡住之后标记没释放（会话永久锁死，只能重启）。</p>
 * @author zzkingcc
 */
class CancellationRegistryTest {

    @Test
    @DisplayName("同一会话只允许一轮在执行，释放后可再次进入")
    void onlyOneRunnerPerSession() {
        CancellationRegistry registry = new CancellationRegistry();

        assertTrue(registry.tryMarkRunning("s-1"));
        assertFalse(registry.tryMarkRunning("s-1"), "同一会话不应允许并发进入");
        registry.unmarkRunning("s-1");
        assertTrue(registry.tryMarkRunning("s-1"), "释放后应可再次进入");
    }

    @Test
    @DisplayName("不同会话互不影响")
    void sessionsAreIndependent() {
        CancellationRegistry registry = new CancellationRegistry();

        assertTrue(registry.tryMarkRunning("s-1"));
        assertTrue(registry.tryMarkRunning("s-2"));
        registry.unmarkRunning("s-1");

        assertEquals(1, registry.runningCount());
    }

    @Test
    @DisplayName("并发抢占时只有一个赢家")
    void concurrentAcquireHasSingleWinner() throws InterruptedException {
        CancellationRegistry registry = new CancellationRegistry();
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (registry.tryMarkRunning("s-1")) {
                    winners.incrementAndGet();
                }
                finished.countDown();
            }).start();
        }
        start.countDown();
        finished.await();

        assertEquals(1, winners.get(), "并发抢占只应有一个赢家");
        assertEquals(1, registry.runningCount());
    }

    @Test
    @DisplayName("停止标志与执行中标记互相独立")
    void stopFlagIsIndependentOfRunningMark() {
        CancellationRegistry registry = new CancellationRegistry();

        registry.requestStop("s-1");
        assertTrue(registry.isCancelled("s-1"));
        assertTrue(registry.tryMarkRunning("s-1"), "停止标志不应影响执行中标记");

        registry.clear("s-1");
        assertFalse(registry.isCancelled("s-1"));
        assertEquals(1, registry.runningCount(), "clear 只清停止标志，不动执行中标记");
    }

    /**
     * R-03 的核心契约：<b>排队期间的 stop 必须活到任务真正开始执行</b>。
     *
     * <p>编排任务先进线程池排队，用户可以在排队期间点停止。若清停止标志的动作发生在
     * "任务出队后"，那次停止就被抹掉了 —— 任务照跑到底，界面上显示"停止中"却什么都没发生。
     * 不报错、不留痕，只表现为"停止偶尔不灵"。</p>
     */
    @Test
    @DisplayName("清理放在提交前时，排队期间的 stop 能一直活到执行期")
    void stopRaisedWhileQueuedSurvivesUntilExecution() {
        CancellationRegistry registry = new CancellationRegistry();

        // ① 提交前清理上一轮的残留
        registry.prepareNewRound("s-1");
        assertFalse(registry.isCancelled("s-1"), "新一轮开始前应无停止标志");

        // ② 任务在队列里等着，用户点了停止
        registry.requestStop("s-1");
        assertTrue(registry.isCancelled("s-1"));

        // ③ 任务出队开始执行 —— 执行期**不再**清标志，停止请求得以生效
        assertTrue(registry.isCancelled("s-1"),
                "任务开始执行时不得清除停止标志，否则排队期间的停止请求会静默丢失");
    }

    @Test
    @DisplayName("准备新一轮会清掉上一轮遗留的标志")
    void prepareNewRoundClearsPreviousFlag() {
        CancellationRegistry registry = new CancellationRegistry();

        registry.requestStop("s-1");
        registry.prepareNewRound("s-1");

        assertFalse(registry.isCancelled("s-1"), "上一轮遗留的停止标志会误杀新一轮");
        assertEquals(0, registry.runningCount(), "prepareNewRound 不碰执行中标记");
    }

    @Test
    @DisplayName("无残留时不产生日志噪音也不改变状态")
    void prepareNewRoundIsIdempotent() {
        CancellationRegistry registry = new CancellationRegistry();

        registry.prepareNewRound("s-1");
        registry.prepareNewRound("s-1");

        assertFalse(registry.isCancelled("s-1"));
    }
}
