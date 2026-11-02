package com.zzkingcc.domainplane.infrastructure.kb.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.zzkingcc.domainplane.common.util.LogSanitizer;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ES 版 {@link KeywordSearcher}：BM25 关键词检索。
 *
 * 从 {@code KeywordMatchContentRetriever} 平移，行为逐字节等价：
 * 四字段权重、{@code minimumShouldMatch}、{@code ignoreUnavailable /
 * allowNoIndices}、"text 为空白则丢弃"全部原样。变化只有两处——
 * 出口从 {@code List<Content>} 变成 {@code List<KbHit>}（metadata 搬运下沉给
 * {@link EsSourceCodec}），异常从 {@code KnowledgeBaseException} 变成
 * {@link StoreAccessException}（SPI 契约，见 {@code domain.rag.store} 包文档）。
 *
 * 多字段权重一次性表达"哪里命中更重要"，而不是把字段拆成多张排名表：
 * 表数保持 = 索引 × 模态，融合阶段的按模态均摊权重才不会被字段数稀释。
 *
 * @author zzkingcc
 */
@Slf4j
public class EsKeywordSearcher implements KeywordSearcher {

    /**
     * 参与 BM25 的字段与权重。
     *
     *   • {@code text} —— 正文（切片时已把 section_path 前置进正文，标题词天然在内）；
     *   • {@code text.standard} —— 标准分词，救英文/数字/标识符（ik 会把它们切碎）；
     *   • {@code metadata.section_path} —— 完整层级路径，命中说明整条上下文相关；
     *   • {@code metadata.section_title} —— 末级标题，最直接的"这条讲的就是这个"。
     */
    private static final List<String> SEARCH_FIELDS = List.of(
            "text^1.0",
            "text.standard^0.8",
            "metadata.section_path^1.5",
            "metadata.section_title^2.0"
    );

    private final ElasticsearchClient esClient;
    private final String minimumShouldMatch;

    public EsKeywordSearcher(ElasticsearchClient esClient, String minimumShouldMatch) {
        this.esClient = esClient;
        this.minimumShouldMatch = minimumShouldMatch;
    }

    @Override
    public List<KbHit> search(String collection, String queryText, int topK) {
        Query matchQuery = new Query.Builder()
                .multiMatch(m -> {
                    m.query(queryText)
                            .fields(SEARCH_FIELDS)
                            .type(TextQueryType.BestFields);
                    // 压单字命中噪音：查询被切成 N 个词时，至少要命中这么多个才算一次匹配，
                    // 否则"会"、"在"这类单字就能把整库文档拉回来。
                    if (minimumShouldMatch != null && !minimumShouldMatch.isBlank()) {
                        m.minimumShouldMatch(minimumShouldMatch);
                    }
                    return m;
                })
                .build();

        try {
            // 一域一索引：域边界由「查哪个 collection」保证，查询里不含 metadata.domains 过滤。
            // allowNoIndices / ignoreUnavailable：域链上某个祖先域可能还没有索引，此时应安静地
            // 返回空，而不是抛 index_not_found 把整轮检索打断。
            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(collection)
                            .ignoreUnavailable(true)
                            .allowNoIndices(true)
                            .size(topK)
                            .source(src -> src.filter(f -> f.includes("text", "metadata")))
                            .query(matchQuery),
                    Map.class);

            List<KbHit> out = new ArrayList<>();
            resp.hits().hits().forEach(h -> {
                KbHit hit = EsSourceCodec.toHit(h.id(), h.source(), scoreOf(h));
                if (hit != null) {
                    out.add(hit);
                }
            });

            // queryText 是用户提问原文，只记长度不记内容
            log.info("[ES关键词检索] 查询完成，查询{}，设定{}条，命中{}条",
                    LogSanitizer.describeUserText(queryText), topK, out.size());
            return out;
        } catch (Exception e) {
            // IOException 与 ElasticsearchException（4xx/5xx，运行时异常）都要接住转成
            // StoreAccessException：直接逃逸会绕过 ErrorCode 分类，上层给不出可行动提示
            log.error("[ES关键词检索] 查询失败: {}", e.getMessage(), e);
            throw new StoreAccessException("ES 关键词检索失败: " + e.getMessage(), e);
        }
    }

    private static double scoreOf(co.elastic.clients.elasticsearch.core.search.Hit<Map> h) {
        return h.score() == null ? 0.0 : h.score();
    }
}
