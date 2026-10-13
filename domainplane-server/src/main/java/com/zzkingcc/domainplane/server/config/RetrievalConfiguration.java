package com.zzkingcc.domainplane.server.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.domain.rag.fusion.AdaptiveFusionStrategy;
import com.zzkingcc.domainplane.domain.rag.fusion.FusionConfig;
import com.zzkingcc.domainplane.domain.rag.retriever.CompositeRetriever;
import com.zzkingcc.domainplane.server.knowledge.DomainChannelProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检索配置：混合检索的编排与融合。
 *
 * 召回通道不是固定的两路，而是按当前域动态解析：一域一索引，检索域 D 时
 * 对 D 自身与祖先链上的每个索引各召回「向量 + 关键词」两路（见 {@link DomainChannelProvider}）。
 * 域链越长通道越多，因此融合必须能在"单来源"与"多来源"之间切换：
 *
 *   • 结果实际只来自一个索引 → {@code DefaultFusionStrategy}（分数制，同索引内 BM25 可比）；
 *   • 结果来自多个索引 → {@code RrfFusionStrategy}（排名制，规避 BM25 跨索引不可比）。
 *
 * {@link AdaptiveFusionStrategy} 就是这条双轨的自动切换入口。
 *
 * 知识库索引在首次上传时按需建出（见 {@code KnowledgeBaseService#ensureIndex}）：
 * 域是运行期由用户创建的，索引跟着域走，没有统一的"启动期建索引"这一步。
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({RagProperties.class, RetrievalProperties.class})
public class RetrievalConfiguration {

    /**
     * 混合检索专用线程池
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService retrievalExecutor(RetrievalProperties retrievalProps) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "domainplane-retrieval-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                retrievalProps.getCorePoolSize(),
                retrievalProps.getMaxPoolSize(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(retrievalProps.getQueueCapacity()),
                factory);

        log.info("[检索配置] 检索线程池 core={}, max={}, queue={}, parallel={}, timeout={}ms",
                retrievalProps.getCorePoolSize(), retrievalProps.getMaxPoolSize(),
                retrievalProps.getQueueCapacity(), retrievalProps.isParallel(),
                retrievalProps.getTimeoutMs());
        return executor;
    }

    /**
     * 域通道提供者：按当前域解析出"该查哪些索引、每个索引走哪两路召回"。
     *
     * 提成独立 bean 而不是内联 new 进 {@link CompositeRetriever}：
     * 删域流程要用它失效检索器缓存（索引删了但还在缓存里会被旧检索器继续命中），
     * 那是与"查询"无关的另一条使用路径。内联构造时它拿不到 bean 引用，
     * {@code AdminDomainController} 就只能声明一个根本不存在类型的依赖：
     * 于是 ES 未配置、检索器未装配的场景下整个应用起不来。
     */
    @Bean
    @Lazy
    public DomainChannelProvider domainplaneDomainChannelProvider(
            @Qualifier("domainplaneElasticsearchClient") ElasticsearchClient esClient,
            EmbeddingModel embeddingModel,
            RetrievalProperties retrievalProps) {
        return new DomainChannelProvider(esClient, embeddingModel, retrievalProps);
    }

    /**
     * 组合检索器：按当前域解析通道（域链上每个索引 × 2 路）→ 召回编排 → 双轨融合重排。
     */
    @Bean
    @Lazy
    public ContentRetriever myContentRetriever(
            @Qualifier("domainplaneDomainChannelProvider") DomainChannelProvider channelProvider,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
            RetrievalProperties retrievalProps,
            @Qualifier("retrievalExecutor") ExecutorService retrievalExecutor) {

        FusionConfig fusion = new FusionConfig(
                retrievalProps.getVectorWeight(),
                retrievalProps.getKeywordWeight(),
                retrievalProps.getTitleBoost(),
                retrievalProps.getFileNameBoost(),
                retrievalProps.getTopN(),
                retrievalProps.getRrfK(),
                retrievalProps.getAncestorDecay());

        // parallel=false 时传 null,检索器退化为串行召回
        Executor executor = retrievalProps.isParallel() ? retrievalExecutor : null;

        log.info("[检索配置] 混合检索：向量 Top{}（minScore {}）/ 关键词 Top{}（minimum_should_match={}），"
                        + "融合 topN={}，RRF k={}，层级衰减={}",
                retrievalProps.getVectorTopK(), retrievalProps.getVectorMinScore(),
                retrievalProps.getKeywordTopK(), retrievalProps.getMinimumShouldMatch(),
                retrievalProps.getTopN(), retrievalProps.getRrfK(), retrievalProps.getAncestorDecay());

        return new CompositeRetriever(
                channelProvider,
                fusion, executor, retrievalProps.getTimeoutMs(), new AdaptiveFusionStrategy());
    }
}
