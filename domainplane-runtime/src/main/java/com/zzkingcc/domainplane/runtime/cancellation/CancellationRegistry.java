package com.zzkingcc.domainplane.runtime.cancellation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务取消信号中心。
 *
 * 停止标志与"执行中"标记都按会话状态键（(域, sessionId)）存放，与断点、记忆同键 ——
 * 这三者必须同域，否则会出现"停到了另一个域的同名会话"。
 *
 * 清停止标志的时机是本类最容易出错的地方。编排任务要先进线程池排队，
 * 而用户可以在"排队中"就点停止。若在任务开始执行时才清一次标志，
 * 那次停止就被抹掉了 —— 用户看着界面上的"停止中"，任务照跑到底。
 * 此类故障不报错、不留痕，只表现为"停止偶尔不灵"。
 *
 * 因此把清理唯一入口定为 {@link #prepareNewRound(String)}，且必须在提交进线程池之前
 * 调用（见 {@code AgentOrchestrationService#orchestrate}）。执行线程内一律不再清标志 ——
 * 那一刻清除，等于抹掉排队期间用户的停止请求。
 *
 * @author zzkingcc
 */
@Component
public class CancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(CancellationRegistry.class);

    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /**
     * 请求停止指定会话的任务。
     *
     * 可以在任务提交前也可以在执行中调，两种时机都正确：
     * 前者靠 {@link #prepareNewRound} 的调用顺序保住，后者由执行线程直接读到。
     *
     * @param sessionKey 会话状态键（(域, sessionId)），不是裸 sessionId
     * @return true=本次设置成功(之前未停止); false=该会话已处于停止状态(幂等)
     */
    public boolean requestStop(String sessionKey) {
        return flagOf(sessionKey).compareAndSet(false, true);
    }

    private AtomicBoolean flagOf(String sessionKey) {
        return flags.computeIfAbsent(sessionKey, k -> new AtomicBoolean(false));
    }

    /**
     * 检查会话是否已被请求停止
     *
     * @param sessionKey 会话状态键（(域, sessionId)）
     * @return true=已被请求停止
     */
    public boolean isCancelled(String sessionKey) {
        AtomicBoolean flag = flags.get(sessionKey);
        return flag != null && flag.get();
    }

    /**
     * 新一轮的唯一准备动作：清掉上一轮遗留的停止标志。
     *
     * 调用时机是硬约束：必须在把任务提交进线程池之前。若挪到任务开始执行时再调，
     * 排队期间用户点的停止会被这一次清理抹掉 —— 停止请求静默丢失，不报错、不留痕。
     *
     * 与 {@link #tryMarkRunning(String)} 的顺序同样重要：本方法会清标志，
     * 所以必须排在 {@code tryMarkRunning} 之前，否则被拒绝的并发请求会把正在跑那轮的
     * 停止标志抹掉。
     */
    public void prepareNewRound(String sessionKey) {
        if (flags.remove(sessionKey) != null) {
            log.debug("[取消信号] 会话[{}] 开始新一轮，清除上一轮遗留的停止标志", sessionKey);
        }
    }

    /**
     * 清除会话的停止标志（无条件）。
     *
     * 不要用它当"新一轮开始前的清理"—— 编排流程中那样用会在任务开始时抹掉
     * 排队期间的停止请求。本轮结束后由 {@code finally} 调用它是安全的：此刻没有并发的新一轮，
     * 若真有，也会被 {@code tryMarkRunning} 挡住。
     */
    public void clear(String sessionKey) {
        flags.remove(sessionKey);
    }

    /**
     * 执行中标记集合（同会话串行的载体）。
     */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /**
     * 尝试把会话标记为"执行中"。
     *
     * @return true=获取成功，本次可以执行；false=已有在跑的一轮，应拒绝本次请求
     */
    public boolean tryMarkRunning(String sessionKey) {
        return running.add(sessionKey);
    }

    /** 释放"执行中"标记；只在成功获取标记的那一轮的 finally 中调用 */
    public void unmarkRunning(String sessionKey) {
        running.remove(sessionKey);
    }

    /** 当前正在执行的会话状态键快照（删域等运维动作据此找出"在飞的那一轮"） */
    public Set<String> runningKeys() {
        return Set.copyOf(running);
    }

    /**
     * 请求给定会话停止，并等待它们真的停下来。
     *
     * 为什么"置标志"不够：停止标志只会被执行线程在下一个检查点读到，
     * 而在那之前它仍可能把记忆与断点写回 Redis。所以删域这类破坏性动作必须等到
     * "执行中"标记真正被释放 —— 编排的 {@code finally} 是先写完记忆 / 断点，
     * 再释放标记，所以标记消失就等于这一轮不会再写任何东西了。
     *
     * 停不下来的唯一原因是那一轮正卡在长耗时工具调用里（远程工具 30s、本地工具另有上限），
     * 那属于"还在用已撤销的授权干活"，必须让调用方看到而不是默默往下删。
     *
     * @param sessionKeys 要停的会话状态键
     * @param timeout     最长等待时长
     * @return true=已全部停下；false=超时后仍有会话在跑
     */
    public boolean requestStopAndAwait(Collection<String> sessionKeys, Duration timeout) {
        if (sessionKeys.isEmpty()) {
            return true;
        }
        sessionKeys.forEach(this::requestStop);
        Set<String> pending = new HashSet<>(sessionKeys);
        // 不做无限等待，也不做固定 sleep 轮询的忙等：按固定间隔复查，
        // 每次只探测仍在跑的那几个，够用且不占 CPU
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            pending.removeIf(key -> !running.contains(key));
            if (pending.isEmpty()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[取消信号] 等待会话 {} 停止时被中断，按仍在运行处理", pending);
                return false;
            }
        }
    }

    /** 等待期间的复查间隔 */
    private static final long POLL_INTERVAL_MILLIS = 50;

    /** 当前正在执行的会话数（供管控台指标读取；进程内数值，不跨实例聚合） */
    public int runningCount() {
        return running.size();
    }
}