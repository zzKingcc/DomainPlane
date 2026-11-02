package com.zzkingcc.domainplane.infrastructure.kb.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;

import java.util.Optional;

/**
 * ES 后端注册单元：三样能力全占（store + keyword + vector）。
 *
 * {@code capabilities = (keyword=true, vector=true, wildcard=true, storesText=true,
 * reindexInPlace=true)}：
 *
 *   • wildcard —— ES 的索引模式（{@code domainplane_kb_*}）天然支持通配检索，
 *     管控台全库预览走的就是这条路；
 *   • storesText —— 索引里存着切片原文，换模型重灌理论上不依赖 ChunkArchive
 *     （但 archive 仍是权威源，见设计第 10 节）；
 *   • reindexInPlace —— delete + ensure 同一后端完成，重灌第二阶段的前提。
 *
 * 构造参数里的 minScore / minimumShouldMatch 是**后端自己的配置**
 * （{@code domainplane.knowledge.backends.<id>.{vector,keyword}}）：
 * ES 方言（{@code +1.0} 偏移、40%~50% 有效区间）关在适配器内部，SPI 入参看不到。
 * topK 不在构造参数里——它是通用配置，由通道层每次调用 {@code search} 时传入。
 *
 * @author zzkingcc
 */
public class EsKnowledgeBackend implements KnowledgeBackend {

    private final String id;
    private final String location;
    private final EsKnowledgeStore store;
    private final EsKeywordSearcher keyword;
    private final EsVectorSearcher vector;

    public EsKnowledgeBackend(String id,
                              ElasticsearchClient esClient,
                              double vectorMinScore,
                              String keywordMinimumShouldMatch) {
        this(id, id, esClient, vectorMinScore, keywordMinimumShouldMatch);
    }

    /**
     * @param location 物理实例标识（如 {@code http://es.internal:9200}）。同一个 ES 集群
     *                 在管控台里被关键字槽和向量槽各绑一次时，两个后端的 location 相同，
     *                 写入扇出据此收敛成一路
     */
    public EsKnowledgeBackend(String id,
                              String location,
                              ElasticsearchClient esClient,
                              double vectorMinScore,
                              String keywordMinimumShouldMatch) {
        this.id = id;
        this.location = location == null || location.isBlank() ? id : location;
        this.store = new EsKnowledgeStore(esClient);
        this.keyword = new EsKeywordSearcher(esClient, keywordMinimumShouldMatch);
        this.vector = new EsVectorSearcher(esClient, vectorMinScore);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String location() {
        return location;
    }

    @Override
    public StoreCapabilities capabilities() {
        return store.capabilities();
    }

    @Override
    public Optional<KnowledgeStore> store() {
        return Optional.of(store);
    }

    @Override
    public Optional<KeywordSearcher> keyword() {
        return Optional.of(keyword);
    }

    @Override
    public Optional<VectorSearcher> vector() {
        return Optional.of(vector);
    }
}
