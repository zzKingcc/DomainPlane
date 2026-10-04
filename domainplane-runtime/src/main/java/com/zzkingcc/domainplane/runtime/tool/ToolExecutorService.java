package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.agent.CallerContext;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.TraceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地工具执行的<b>超时与隔离</b>层。
 *
 * <p>为什么需要它：远程工具走 HTTP，{@code HttpRequest.timeout()} 天然兜底；
 * 而本地 Bean 工具是在<b>编排线程上直接调用</b>的 —— 一个死循环或永久阻塞的方法
 * 会占住一个编排线程直到进程结束。编排池 max 32，几十个这样的调用就能让整个服务停摆，
 * 而且不报错、不释放，日志上只表现为"突然变慢"。</p>
 *
 * <p>做法是把执行挪到独立的有界线程池，用 {@code future.get(timeout)} 卡上限。
 * 但有三个 ThreadLocal 是编排在<b>自己线程</b>上绑的（{@link ToolInvocationContext}
 * 审计身份、{@link RetrievalScope} 检索域、{@link TraceId} 排障标识），换线程执行它们就丢了 ——
 * 检索域丢失会让知识库从"按本轮域过滤"退化成"全库检索"（越权读），
 * 审计身份丢失会让工具调用查不到是谁发的。所以这里必须显式<b>搬运并复原</b>。</p>
 *
 * <p>超时只能让调用方<b>不再等</b>，不能真正杀死死循环的线程（除非它响应中断）。
 * 所以池必须是<b>有界且独立</b>的：卡死的工具最多占满工具池，编排线程照常释放，
 * 不会把编排池一起拖死。池满时直接拒（回喂模型"系统繁忙"），不排队等 ——
 * 排队等于把超时压力转嫁给编排线程。</p>
 *
 * @author zzkingcc
 */
public class ToolExecutorService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutorService.class);

    /** 单次调用的默认上限（毫秒）；与远程工具的 30s 对齐，避免两侧口径不一致 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * 未显式注入隔离层时（单元测试、最小装配）使用的共享默认实例。
     *
     * <p>共享而非各建一个：它只在<b>真的提交了任务</b>时才创建线程（线程池懒创建），
     * 不用的实例零开销；而一旦每处new 一个，测试里就会散落一批关不掉的池。</p>
     */
    private static final ToolExecutorService SHARED_DEFAULT = create(64, DEFAULT_TIMEOUT_MS);

    public static ToolExecutorService sharedDefault() {
        return SHARED_DEFAULT;
    }

    private final ExecutorService pool;
    private final long timeoutMs;

    public ToolExecutorService(ExecutorService pool, long timeoutMs) {
        this.pool = pool;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    /**
     * 建一个<b>有界且独立</b>的隔离池。
     *
     * <p>用零队列 + {@code SynchronousQueue}：任务<b>不排队</b>，池满立刻拒。
     * 排队等于把超时压力原样转嫁给编排线程（提交线程会阻塞在入队上），
     * 那就等于没做隔离 —— 编排池满的根源会被这个"缓冲"掩盖成变慢而不是拒绝。</p>
     *
     * <p>线程为 daemon：残留的死循环工具线程不阻止 JVM 退出。</p>
     */
    public static ToolExecutorService create(int maxThreads, long timeoutMs) {
        int threads = Math.max(1, maxThreads);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "domainplane-tool-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                // 满即拒：由 call() 转成 ToolRejectedException 回喂模型，不静默丢任务
                new ThreadPoolExecutor.AbortPolicy());
        return new ToolExecutorService(pool, timeoutMs);
    }

    /** 执行上限（毫秒），供启动日志与排障 */
    public long timeoutMs() {
        return timeoutMs;
    }

    /**
     * 在受控线程上执行一次工具调用，带超时。
     *
     * @param task 工具执行体；其内部读到的三个上下文与调用线程一致
     * @return 工具返回值
     * @throws ToolTimeoutException   超过上限（已尝试中断）
     * @throws ToolRejectedException  隔离池已满（不排队，直接拒）
     * @throws Exception              工具自身抛出的异常，原样上抛
     */
    public String call(String toolName, Callable<String> task) throws Exception {
        // 搬运三个上下文：调用线程上的现状 → 在工具线程复原 → 结束后清理
        ContextSnapshot snapshot = ContextSnapshot.capture();

        Future<String> future;
        try {
            future = pool.submit(() -> {
                snapshot.applyToCurrentThread();
                try {
                    return task.call();
                } finally {
                    // 工具线程是池化复用的，不清会把上下文留给下一个任务 —— 那会造成
                    // "这一轮的审计身份被记到下一轮工具调用上"这种查不出来的串号
                    ToolInvocationContext.clear();
                    RetrievalScope.clear();
                    TraceId.end();
                }
            });
        } catch (RejectedExecutionException e) {
            throw new ToolRejectedException(toolName);
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 调用线程（编排线程）被中断：这通常是会话被 stop 了。把中断标志还原回去再上抛，
            // 绝不能吞成"工具失败"——那会让 stop 请求变成一次普通的工具报错
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            // cancel(true) 发中断：工具若在 sleep / wait / 阻塞 IO 上会就此退出。
            // 不响应中断的（纯死循环）只能继续占着线程 —— 这正是池必须独立且有界的原因
            future.cancel(true);
            log.warn("[工具执行] 工具[{}] 超过 {}ms 上限，已放弃等待并发出中断。"
                    + "若该工具不响应中断，其线程仍会占用隔离池直到进程结束",
                    toolName, timeoutMs);
            throw new ToolTimeoutException(toolName, timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                // 工具里的 OOM / StackOverflow 不该被吞成"执行失败"再回喂模型，直接上抛
                throw err;
            }
            throw e;
        }
    }

    /** 超过执行上限 */
    public static class ToolTimeoutException extends Exception {
        private final long timeoutMs;

        ToolTimeoutException(String toolName, long timeoutMs) {
            super("工具 " + toolName + " 执行超过 " + timeoutMs + "ms 上限，已放弃等待");
            this.timeoutMs = timeoutMs;
        }

        public long timeoutMs() {
            return timeoutMs;
        }
    }

    /** 隔离池已满 */
    public static class ToolRejectedException extends Exception {
        ToolRejectedException(String toolName) {
            super("工具 " + toolName + " 执行请求被拒（工具执行池已满，系统繁忙）");
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    /**
     * 调用线程上下文的搬运包。
     *
     * <p>为什么必须搬运：工具执行被挪到隔离线程后 {@code ThreadLocal} 天然不跟随，
     * 而这三个每一个都不能丢 ——
     * {@link RetrievalScope} 丢了会让检索从"本轮域 + 祖先链"退化成全库检索（越权读）；
     * {@link ToolInvocationContext} 丢了会让审计查不到租户与用户；
     * {@link TraceId} 丢了会让工具侧日志与服务端这次调用对不上，排障线索断掉。</p>
     */
    record ContextSnapshot(CallerContext caller, String traceId, String domain) {

        static ContextSnapshot capture() {
            // traceId 有两个来源，且**不等价**：编排层 bind 进来的是本次调用的 traceId（权威），
            // TraceId.current() 是链路入口 begin 出来的那个。RemoteToolExecutor 也是先读前者、
            // 为空才回落后者（见其 invoke 方法），这里必须同一口径，否则审计里的 traceId
            // 与工具日志里的 traceId 会指向两条不同的链路
            String traceId = ToolInvocationContext.traceId();
            if (traceId == null || traceId.isBlank()) {
                traceId = TraceId.current();
            }
            return new ContextSnapshot(captureCaller(), traceId, RetrievalScope.current());
        }

        /** 从 {@link ToolInvocationContext} 三个读接口反推出完整的 caller（它不暴露对象本身） */
        private static CallerContext captureCaller() {
            String tenant = ToolInvocationContext.tenantId();
            String user = ToolInvocationContext.userId();
            if (tenant == null && user == null) {
                return null;
            }
            return CallerContext.of(null, tenant, user);
        }

        /** 在隔离线程上复原。复原不完整比不复原更危险，所以逐项显式设置 */
        void applyToCurrentThread() {
            // 判据只能读快照自己的字段：ToolInvocationContext.traceId() 读的是**工具线程**上的值，
            // 此刻必然为 null，用它做判据会让"只有 traceId 没有 caller"的情况整段跳过绑定
            if (caller != null || traceId != null) {
                ToolInvocationContext.bind(caller, traceId);
            }
            if (domain != null) {
                RetrievalScope.bind(domain);
            }
            // TraceId.begin 用传入值覆盖生成值，null/空白时它会自动生成一个 ——
            // 这正是"忘了 begin"的兜底语义，工具线程上应当同样成立
            TraceId.begin(traceId);
        }
    }
}