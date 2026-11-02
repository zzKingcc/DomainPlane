package com.zzkingcc.domainplane.infrastructure.kb.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 版 {@link VectorSearcher}：余弦相似度向量检索。
 *
 * 从 {@code NativeScriptScoreContentRetriever} 平移。**只收向量不收文本**：
 * 向量化已上移到编排层（{@code QueryVectorResolver}），一轮检索一次 embed 供全部
 * 向量通道复用——旧实现每条向量通道各 embed 一次，域链 5 层 = 5 次相同 query 的调用。
 *
 * 分数红线（一审修订 A7，一个数都不许改，否则基线比对必失败且会被误读成
 * "融合算法被改坏"）：
 *
 *   1. 脚本仍是 {@code cosineSimilarity(params.query_vector, 'vector') + 1.0}；
 *   2. {@code minScoreRaw = minScore + 1.0} 偏移照旧，且**留在本类内**不外泄
 *      （SPI 的入参里看不到它，配置里的 minScore 按原始余弦理解）；
 *   3. {@code size} / {@code _source} 过滤字段照旧；
 *   4. "text 为空白则丢弃该 hit"的过滤照旧（下沉在 {@link EsSourceCodec#toHit}）。
 *
 * @author zzkingcc
 */
@Slf4j
public class EsVectorSearcher implements VectorSearcher {

    private final ElasticsearchClient esClient;
    /** Painless 值域里的阈值：原始余弦 + 1.0 偏移。适配器内部方言，不进 SPI 入参 */
    private final double minScoreRaw;

    public EsVectorSearcher(ElasticsearchClient esClient, double minScore) {
        this.esClient = esClient;
        this.minScoreRaw = minScore + 1.0; // 偏差回归
    }

    @Override
    public List<KbHit> search(String collection, float[] queryVector, int topK) {
        Double[] qVec = new Double[queryVector.length];
        for (int i = 0; i < queryVector.length; i++) {
            qVec[i] = (double) queryVector[i];
        }

        Map<String, JsonData> params = new HashMap<>();
        params.put("query_vector", JsonData.of(qVec));

        Script script = new Script.Builder()
                .source(ss -> ss.scriptString(
                        "cosineSimilarity(params.query_vector, 'vector') + 1.0"))
                .params(params)
                .build();

        Query scriptScoreQuery = new Query.Builder()
                .scriptScore(ss -> ss
                        .query(q -> q.matchAll(m -> m))
                        .script(script))
                .build();

        try {
            // 一域一索引：域边界由「查哪个 collection」保证。
            // allowNoIndices / ignoreUnavailable：祖先域还没有索引时安静返回空。
            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(collection)
                            .ignoreUnavailable(true)
                            .allowNoIndices(true)
                            .size(topK)
                            .minScore(minScoreRaw) // 匹配数量
                            .source(src -> src.filter(f -> f.includes("text", "metadata")))
                            .query(scriptScoreQuery),
                    Map.class);

            List<KbHit> out = new ArrayList<>();
            resp.hits().hits().forEach(h -> {
                KbHit hit = EsSourceCodec.toHit(h.id(), h.source(), scoreOf(h));
                if (hit != null) {
                    out.add(hit);
                }
            });

            log.info("[ES检索] 查询完成，设定{}条，命中{}条", topK, out.size());
            return out;
        } catch (Exception e) {
            // 同关键词路：4xx/5xx 以 ElasticsearchException 形式抛出，不属于 IOException，
            // 不在这里归类就会绕过 ErrorCode 分类，上层拿不到可展示的错误语义
            log.error("[ES向量检索] 查询失败: {}", e.getMessage(), e);
            throw new StoreAccessException("ES 向量检索失败: " + e.getMessage(), e);
        }
    }

    private static double scoreOf(co.elastic.clients.elasticsearch.core.search.Hit<Map> h) {
        return h.score() == null ? 0.0 : h.score();
    }
}
