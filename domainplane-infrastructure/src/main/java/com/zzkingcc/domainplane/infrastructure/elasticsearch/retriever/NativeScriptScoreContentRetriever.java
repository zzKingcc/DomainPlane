package com.zzkingcc.domainplane.infrastructure.elasticsearch.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 召回设置 - 余弦相似度检索器
 * @author zzkingcc
 */
public class NativeScriptScoreContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(NativeScriptScoreContentRetriever.class);

    private final ElasticsearchClient esClient;
    private final String indexName;
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScoreRaw;

    public NativeScriptScoreContentRetriever(
            ElasticsearchClient esClient,
            String indexName,
            EmbeddingModel embeddingModel,
            int maxResults,
            double minScore) {
        this.esClient = esClient;
        this.indexName = indexName;
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScoreRaw = minScore + 1.0;//偏差回归
    }

    //召回结果
    @Override
    public List<Content> retrieve(dev.langchain4j.rag.query.Query query) {
        //类型转换，es适配
        float[] qv = embeddingModel.embed(query.text()).content().vector();
        Double[] qVec = new Double[qv.length];
        for (int i = 0; i < qv.length; i++) {
            qVec[i] = (double) qv[i];
        }

        Map<String, JsonData> params = new HashMap<>();
        params.put("query_vector", JsonData.of(qVec));

        co.elastic.clients.elasticsearch._types.Script script =
                new co.elastic.clients.elasticsearch._types.Script.Builder()
                        .source(ss -> ss.scriptString(
                                "cosineSimilarity(params.query_vector, 'vector') + 1.0"))
                        .params(params)
                        .build();

        co.elastic.clients.elasticsearch._types.query_dsl.Query scriptScoreQuery =
                new co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder()
                        .scriptScore(ss -> ss
                                .query(q -> q.matchAll(m -> m))
                                .script(script))
                        .build();

        try {
            // 一域一索引：域边界由「查哪个索引」保证，查询里不含 metadata.domains 过滤。
            // allowNoIndices / ignoreUnavailable：域链上某个祖先域可能还没有索引，此时应安静地返回空，
            // 而不是抛 index_not_found 把整轮检索打断。
            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(indexName)
                            .ignoreUnavailable(true)
                            .allowNoIndices(true)
                            .size(maxResults)
                            .minScore(minScoreRaw)//匹配数量
                            .source(src -> src.filter(f -> f.includes("text", "metadata")))
                            .query(scriptScoreQuery),
                    Map.class);

            List<Content> out = new ArrayList<>();
            resp.hits().hits().forEach(h -> {
                String text = "";
                if (h.source() != null && h.source().get("text") != null) {
                    text = h.source().get("text").toString();
                }
                if (!text.isBlank()) {
                    TextSegment segment = TextSegment.from(text);
                    // ES script_score 写入 metadata，供后续分数融合使用
                    segment.metadata().put(EsMetadata.RAW_SCORE, h.score());
                    // 元数据整体搬运（含 chunk_seq，排序稳定性靠它）
                    EsMetadata.copy(h.source(), segment);
                    out.add(Content.from(segment));
                }
            });

            log.info("[ES检索] 查询完成，设定{}条，命中{}条", minScoreRaw , out.size());
            return out;
        } catch (IOException e) {
            log.error("[ES检索] 查询异常: {}", e.getMessage(), e);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_SEARCH_ERROR, "ES 向量检索异常: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 同 KeyMatch：4xx/5xx 以 ElasticsearchException 形式抛出，不属于 IOException，
            // 不在这里归类就会绕过 ErrorCode，上层拿不到可展示的错误语义。
            log.error("[ES检索] 查询失败: {}", e.getMessage(), e);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_SEARCH_ERROR,
                    "ES 向量检索失败: " + e.getMessage(), e);
        }
    }
}
