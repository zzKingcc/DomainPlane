package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.ConversationSummaryStore;
import com.zzkingcc.domainplane.api.conversation.ConversationSummaryStore.Stored;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 滚动摘要的调度与生成（方案 §5.4 方式 B：闭轮后异步 + 阈值门）。
 *
 * <p><b>为什么异步且带阈值</b>：摘要是一次额外的模型调用。若每轮都同步做，就等于把
 * 每轮的延迟与成本都加一份，而"老轮次的压缩"根本不需要实时，它只在某几轮滑出窗口、
 * 且数量攒够阈值之后才有意义。所以：闭轮后触发，检查"滑出窗口且未进摘要"的轮次是否达标，
 * 达标才真正发起调用。</p>
 *
 * <p><b>单飞</b>：同一会话同时只允许一个未完成的摘要任务。不这么做的话，
 * 用户连点几轮会并发起多个调用，它们各自基于"自己那一份旧摘要"生成，
 * 谁都可能后写覆盖先写，把已经并进去的轮次丢掉（摘要倒退）。</p>
 *
 * <p><b>失败只告警</b>：摘要是可选的增强，生成失败、写失败、读归档失败都不影响本轮对话，
 * 装配侧读不到摘要就退化为 digest 拼接（M2 行为），历史一条都不会丢。</p>
 *
 * <p>本类不依赖具体模型：{@link Summarizer} 由服务端装配（内核定契约、服务端定模型），
 * 未注入时整个机制静默关闭。</p>
 *
 * @author zzkingcc
 */
public class RollingSummaryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RollingSummaryService.class);

    /** 摘要生成器：给"已有摘要 + 新增素材"，返回新的摘要正文 */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(String previousSummary, String material);
    }

    /**
     * 行为开关与阈值（全部来自 {@code domainplane.memory.summary.*}）。
     *
     * @param enabled          总开关；关掉即整段退化为 digest 拼接
     * @param minNewTurns      攒够多少"滑出窗口且未摘要"的轮次才发起一次调用
     * @param maxMaterialTurns 一次最多把多少条老轮次喂给模型（成本上界）
     */
    public record Options(boolean enabled, int minNewTurns, int maxMaterialTurns) {

        public static final Options DISABLED = new Options(false, 1, 1);

        public Options {
            minNewTurns = Math.max(1, minNewTurns);
            maxMaterialTurns = Math.max(1, maxMaterialTurns);
        }
    }

    private final ConversationSummaryStore store;
    private final ConversationArchive archive;
    private final Summarizer summarizer;
    private final Options options;

    /** 自己的线程池（{@code null} 表示用的是外部注入的 executor，关停不归本类管） */
    private final ExecutorService ownedExecutor;
    private final Executor executor;

    /** 单飞标记：正在生成摘要的会话 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** 用外部 executor（单测注入直接执行器，跑成同步语义） */
    public RollingSummaryService(ConversationSummaryStore store, ConversationArchive archive,
                                Summarizer summarizer, Options options, Executor executor) {
        this.store = store;
        this.archive = archive;
        this.summarizer = summarizer;
        this.options = options == null ? Options.DISABLED : options;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    /** 自建线程池（生产装配）；线程是守护线程，{@link #close()} 会关停 */
    public RollingSummaryService(ConversationSummaryStore store, ConversationArchive archive,
                                Summarizer summarizer, Options options, int workers) {
        int n = Math.max(1, workers);
        this.store = store;
        this.archive = archive;
        this.summarizer = summarizer;
        this.options = options == null ? Options.DISABLED : options;
        this.ownedExecutor = Executors.newFixedThreadPool(n, daemonFactory());
        this.executor = this.ownedExecutor;
    }

    private static ThreadFactory daemonFactory() {
        AtomicLong seq = new AtomicLong();
        return runnable -> {
            Thread t = new Thread(runnable, "domainplane-summary-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ==================== 读（装配层用） ====================

    /**
     * 读该会话当前的滚动摘要；关掉 / 未装配 / 读不到都返回空。
     *
     * <p>永远不抛：装配层拿到空就走 digest 拼接，不能因为摘要不可用而让整轮请求失败。</p>
     */
    public Optional<Stored> load(String sessionKey) {
        if (!options.enabled() || store == null || sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        try {
            return store.load(sessionKey);
        } catch (Exception e) {
            log.warn("[会话摘要] 读取会话[{}]摘要失败，本轮退化为 digest 拼接：{}", sessionKey, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 写（闭轮后触发） ====================

    /**
     * 闭轮后触发一次"按需摘要"。
     *
     * <p>只做两件事：单飞占位、把实际判断与调用丢给线程池。判断与调用都在后台完成，
     * 闭轮路径上不能因为要不要摘要而多等一次归档读。</p>
     *
     * @return 是否真的提交了任务（未开启 / 已有任务在跑 / 线程池已满都返回 false）
     */
    public boolean schedule(String sessionKey, int windowTurns) {
        if (!options.enabled() || store == null || archive == null || summarizer == null) {
            return false;
        }
        if (sessionKey == null || sessionKey.isBlank()) {
            return false;
        }
        if (!running.add(sessionKey)) {
            log.debug("[会话摘要] 会话[{}] 已有摘要在生成，跳过本次触发（单飞）", sessionKey);
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    generate(sessionKey, windowTurns);
                } catch (Exception e) {
                    log.warn("[会话摘要] 会话[{}] 生成摘要失败（不影响对话）：{}", sessionKey, e.getMessage());
                } finally {
                    running.remove(sessionKey);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // 提交失败必须把单飞标记还回去，否则这个会话再也不会被摘要
            running.remove(sessionKey);
            log.warn("[会话摘要] 会话[{}] 摘要任务提交失败（线程池已满或已关停），跳过本次", sessionKey);
            return false;
        }
    }

    /**
     * 真正的判断 + 生成。
     *
     * <p>读一次归档尾部即可同时算出"窗口边界"与"待摘要轮次"：滑出窗口的那一段里，
     * 轮号大于 {@code upTo} 的，就是还要并进摘要的增量。</p>
     */
    private void generate(String sessionKey, int windowTurns) {
        Stored existing = store.load(sessionKey).orElse(null);
        long upTo = existing == null ? 0L : existing.upToTurnId();

        int window = Math.max(0, windowTurns);
        List<TurnRecord> tail = archive.recentTurns(sessionKey, window + options.maxMaterialTurns());
        if (tail == null || tail.isEmpty()) {
            return;
        }
        int skip = Math.max(0, tail.size() - window);
        List<TurnRecord> pending = new ArrayList<>();
        long newest = upTo;
        for (int i = 0; i < skip; i++) {
            TurnRecord t = tail.get(i);
            if (t == null || t.isOpen()) {
                continue;
            }
            if (t.flags() != null && t.flags().aborted()) {
                continue;
            }
            if (t.turnId() <= upTo) {
                continue;
            }
            if (t.digest() == null || t.digest().isBlank()) {
                continue;
            }
            pending.add(t);
            newest = Math.max(newest, t.turnId());
        }

        if (pending.size() < options.minNewTurns()) {
            log.debug("[会话摘要] 会话[{}] 待摘要轮次 {} < 阈值 {}，本次不调模型",
                    sessionKey, pending.size(), options.minNewTurns());
            return;
        }

        String previous = existing == null ? "" : existing.text();
        String material = renderMaterial(pending);
        String text = summarizer.summarize(previous, material);
        if (text == null || text.isBlank()) {
            log.warn("[会话摘要] 会话[{}] 摘要器返回空，放弃本次更新（保留旧摘要与进度）", sessionKey);
            return;
        }
        store.save(sessionKey, new Stored(text.trim(), newest));
        log.info("[会话摘要] 会话[{}] 摘要已更新：并入 {} 轮（截至第 {} 轮），正文 {} 字符",
                sessionKey, pending.size(), newest, text.trim().length());
    }

    /**
     * 待摘要轮次 → 喂给模型的素材。
     *
     * <p>用现成的 digest（问 60 字 + 答 140 字）而不是回答全文：素材长度因此有硬上界，
     * 单次调用的成本与延迟都可预估。老轮次的全文本来就该由 {@code read_history}
     * 按需去取，不该进摘要这一步。</p>
     */
    static String renderMaterial(List<TurnRecord> turns) {
        StringBuilder sb = new StringBuilder();
        for (TurnRecord t : turns) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("第 ").append(t.turnId()).append(" 轮：").append(t.digest());
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (ownedExecutor != null) {
            // shutdownNow 会丢掉队列里还没开始的任务，那些任务的 finally 永远不会执行：
            // 单飞标记若只靠任务自己还，这些会话的摘要会永久静默降级（再也不会被触发）。
            ownedExecutor.shutdownNow();
        }
        running.clear();
    }
}
