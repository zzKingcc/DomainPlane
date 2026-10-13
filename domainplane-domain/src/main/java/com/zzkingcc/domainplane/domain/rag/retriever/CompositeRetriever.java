package com.zzkingcc.domainplane.domain.rag.retriever;

import com.zzkingcc.domainplane.domain.rag.fusion.DefaultFusionStrategy;
import com.zzkingcc.domainplane.domain.rag.fusion.FusionConfig;
import com.zzkingcc.domainplane.domain.rag.fusion.FusionStrategy;
import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 混合检索编排器：对「多个索引 × 两路模态」的通道集合做召回编排
 * （串行 / 并行 + 超时，失败互不影响），把每路结果组成排名表，融合重排交给
 * {@link FusionStrategy}。
 *
 * 本类只负责「召回编排 + 失败处理」，不内联融合算法，也不知道 ES：
 * 通道由 {@link ChannelProvider} 按当前域解析给出（多索引时 = 域链上各域的索引 × 2）。
 * 这样融合策略（分数制 / RRF）可以自由替换而不动编排。
 *
 * @author zzkingcc
 */
public class CompositeRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(CompositeRetriever.class);

    /** 超时兜底（未配置时的默认值） */
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    /** 串行模式的临时线程：daemon，避免检索线程拖住 JVM 退出 */
    private static final ThreadFactory SERIAL_FACTORY = r -> {
        Thread t = new Thread(r, "domainplane-retrieve-serial");
        t.setDaemon(true);
        return t;
    };

    /**
     * 一条召回通道：某个索引上的某一路模态。
     *
     * @param sourceId  来源标识（索引名）
     * @param modality  模态
     * @param retriever 该通道的检索器
     * @param depth     该来源距查询域的距离（0 = 查询域自身）；融合阶段据此做层级衰减
     */
    public record Channel(String sourceId, Modality modality, ContentRetriever retriever, int depth) {

        /** 单来源（或来源即查询域自身）的简写。 */
        public Channel(String sourceId, Modality modality, ContentRetriever retriever) {
            this(sourceId, modality, retriever, 0);
        }
    }

    /**
     * 通道提供者：按当前检索域解析出本轮要召回的通道集合。
     *
     * 未绑定域或该域链上没有任何索引时返回空集合，检索即"未命中"。
     */
    public interface ChannelProvider {
        List<Channel> channels();
    }

    private final ChannelProvider channelProvider;
    private final FusionConfig fusion;
    private final FusionStrategy fusionStrategy;
    /** 并行召回线程池;null 表示串行执行 */
    private final Executor executor;
    /** 单通道召回超时(毫秒);<=0 表示取默认兜底值 */
    private final long timeoutMs;

    public CompositeRetriever(ChannelProvider channelProvider,
                              FusionConfig fusion,
                              Executor executor,
                              long timeoutMs) {
        this(channelProvider, fusion, executor, timeoutMs, null);
    }

    /**
     * 全参构造器：部署方可传入自定义 {@link FusionStrategy} 替换融合算法。
     */
    public CompositeRetriever(ChannelProvider channelProvider,
                              FusionConfig fusion,
                              Executor executor,
                              long timeoutMs,
                              FusionStrategy fusionStrategy) {
        this.channelProvider = channelProvider;
        this.fusion = fusion == null ? FusionConfig.defaults() : fusion;
        this.executor = executor;
        this.timeoutMs = timeoutMs;
        this.fusionStrategy = fusionStrategy == null ? new DefaultFusionStrategy() : fusionStrategy;
    }

    @Override
    public List<Content> retrieve(Query query) {
        long start = System.currentTimeMillis();

        List<Channel> channels = channelProvider.channels();
        if (channels == null || channels.isEmpty()) {
            log.info("[组合检索] 当前域链上没有可召回的索引，返回空结果");
            return List.of();
        }

        // 整轮共享一个 deadline：超时是「这次检索多久还不返回」的总预算，不是每条通道各等一次。
        // 域链深 5 就是 10 条通道，按每通道各等 5s 算，单次检索会拖到 50s：而编排线程池
        // （core 8 / max 32）扛不住几个这样的请求。串行模式同样受它约束，否则一条卡死的通道
        // 就能无限期占住线程。
        long deadline = start + effectiveTimeoutMs();

        List<RankedList> lists = new ArrayList<>(channels.size());
        int succeeded = 0;

        if (executor == null) {
            // 串行模式也必须放进一个线程里跑，否则调用线程会被阻塞在通道上、无法被超时中断：
            // 那正是「串行模式完全没有超时保护」的根因。用一个临时单线程池：
            // 整轮通道在同一个线程上排队（仍是串行），但每条都能被 cancel(true) 打断。
            try (ExecutorService serial = Executors.newSingleThreadExecutor(SERIAL_FACTORY)) {
                for (Channel ch : channels) {
                    ChannelResult r = retrieveSafely(ch, query, deadline, serial);
                    if (!r.failed()) {
                        succeeded++;
                    }
                    lists.add(new RankedList(ch.sourceId(), ch.modality(), ch.depth(), r.contents()));
                }
            }
        } else {
            List<CompletableFuture<ChannelResult>> futures = new ArrayList<>(channels.size());
            for (Channel ch : channels) {
                futures.add(CompletableFuture.supplyAsync(() -> retrieveDirectly(ch, query), executor));
            }
            for (int i = 0; i < channels.size(); i++) {
                Channel ch = channels.get(i);
                ChannelResult r = await(futures.get(i), ch, deadline);
                if (!r.failed()) {
                    succeeded++;
                }
                lists.add(new RankedList(ch.sourceId(), ch.modality(), ch.depth(), r.contents()));
            }
        }

        if (succeeded == 0) {
            // 全部通道失败 ≠ 没有相关内容：这里若返回空列表，"检索链路坏了"会和"真没命中"
            // 长得一模一样：调用方会换个问法反复试，而不是去查配置。
            throw new IllegalStateException("知识库检索全部通道失败（通道数 " + channels.size()
                    + "），无法判断是否存在相关内容");
        }

        List<Content> result = fusionStrategy.fuse(query.text(), lists, fusion);
        log.info("[组合检索] 通道={}（命中 {}），融合耗时 {}ms，预算 {}ms",
                channels.size(), succeeded, System.currentTimeMillis() - start, effectiveTimeoutMs());
        return result;
    }

    private long effectiveTimeoutMs() {
        return timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    /** 剩余预算；已耗尽时给 1ms，让 future.get 立即返回而不是白等一个完整超时 */
    private static long remaining(long deadline) {
        long left = deadline - System.currentTimeMillis();
        return left > 1 ? left : 1;
    }

    /**
     * 单通道召回结果
     */
    private record ChannelResult(List<Content> contents, boolean failed) {

        static ChannelResult ok(List<Content> contents) {
            return new ChannelResult(contents == null ? List.of() : contents, false);
        }

        static ChannelResult failure() {
            return new ChannelResult(List.of(), true);
        }
    }

    /**
     * 在调用线程上直接召回，仅做异常隔离。
     *
     * 用于并行分支：那里每条通道已经提交到 {@code executor}，超时由
     * {@link #await} 按整轮 deadline 控制，不需要再包一层线程。
     */
    private ChannelResult retrieveDirectly(Channel channel, Query query) {
        try {
            return ChannelResult.ok(channel.retriever().retrieve(query));
        } catch (Exception e) {
            log.warn("[组合检索] 通道[{}·{}]检索异常,本次降级为忽略该路: {}",
                    channel.sourceId(), channel.modality(), e.getMessage());
            return ChannelResult.failure();
        }
    }

    /**
     * 串行模式下的安全召回：单通道异常不影响其它通道，且受整轮 deadline 约束。
     *
     * 通道提交到 {@code pool}（一个临时单线程池）而不是在调用线程上直接跑：
     * 直接跑的话调用线程会被阻塞在通道上，超时无从生效，那正是串行模式
     * 完全没有超时保护的根因。
     */
    private ChannelResult retrieveSafely(Channel channel, Query query, long deadline, ExecutorService pool) {
        Future<ChannelResult> task = null;
        try {
            task = pool.submit(() -> ChannelResult.ok(channel.retriever().retrieve(query)));
            return task.get(remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (task != null) {
                task.cancel(true);
            }
            log.warn("[组合检索] 通道[{}·{}]检索异常或超时,本次降级为忽略该路: {}",
                    channel.sourceId(), channel.modality(), e.getMessage());
            return ChannelResult.failure();
        }
    }

    /**
     * 并行模式下等待单通道结果：超时或异常都记为"该路失败"，并取消该路任务。
     *
     * 传进来的是整轮 deadline 剩下的预算，不是配置的完整超时值。
     */
    private ChannelResult await(CompletableFuture<ChannelResult> future, Channel channel, long deadline) {
        try {
            return future.get(remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("[组合检索] 通道[{}·{}]检索失败(超时或异常),本次降级为忽略该路: {}",
                    channel.sourceId(), channel.modality(), e.getMessage());
            return ChannelResult.failure();
        }
    }
}
