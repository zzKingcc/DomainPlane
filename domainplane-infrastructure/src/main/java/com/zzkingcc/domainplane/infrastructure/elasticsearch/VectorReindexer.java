package com.zzkingcc.domainplane.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 换向量模型时保留原文地重灌索引。
 *
 * 为什么需要它：ES 的 {@code dense_vector} 维度是 mapping 的不可变参数，
 * 维度变了只能删索引重建。但索引里除了向量还有 {@code text}（BM25 原文）与
 * {@code metadata}（{@code doc_id} / {@code file_name} / {@code chunk_seq} …）：
 * 原文明明还在。直接 {@code deleteIndex + create} 等于把可复用的东西一起扔了，
 * 用户得把每个文档重新上传一遍，而切片是服务端算出来的、本可以就地重算向量。
 *
 * 做法：先把各索引的 {@code text + metadata} 捞进内存（不含 vector），
 * 删索引、按新维度重建 mapping，再用新向量模型对原文重新向量化写回。
 * 切片与元数据原样保留，所以 BM25 通道的召回结果不会变，只有向量换了。
 *
 * 失败语义是fail-closed：任一步失败立即抛，中止在"还有东西没被删"的位置。
 * 特别注意先全部捞完再开始删：一个索引一个索引地"捞→删→重灌"，
 * 中途失败会留下前面几个索引已换新向量、后面几个还是老向量的混合状态，
 * 而向量空间不同、维度相同的两个模型混在一个索引里，检索结果会静默失真。
 *
 * @author zzkingcc
 */
public final class VectorReindexer {

    private static final Logger log = LoggerFactory.getLogger(VectorReindexer.class);

    /** 单次从 ES 取回的文档数上限（与 {@code deleteExportFiles} 同量级，够用且不至于打爆堆） */
    private static final int FETCH_BATCH = 10_000;

    /** 向量化单批行数上限（百炼text-embedding-v2 限制 25 行） */
    private static final int EMBEDDING_BATCH = 25;

    /** 翻页用的排序字段：{@code _doc} 是 ES 推荐的 scroll-free 分页顺序（无需额外字段、随写有效） */
    private static final String SORT_FIELD = "_doc";

    private VectorReindexer() {
    }

    /**
     * 一条被保留下来的切片：正文 + 全部元数据（不含向量）。
     *
     * @param text     切片正文（BM25 与向量共用的原文）
     * @param metadata {@code _source.metadata} 的原始内容
     * @param domain   归属域（索引名是哈希，反查不回来，只能顺手存下）
     */
    public record PreservedChunk(String text, Map<String, Object> metadata, String domain) {
    }

    /**
     * 重建结果。
     *
     * @param preserved 各索引捞回来的条数
     * @param reindexed 各索引实际写回的条数
     * @implNote 两者必须逐项相等；不等就意味着丢切片（捞取被截断、或写回中途失败），
     * 而向量缺失的切片在向量检索里等于凭空消失：所以刻意把它作为可核对的返回值暴露出去。
     */
    public record ReindexResult(Map<String, Integer> preserved, Map<String, Integer> reindexed) {
    }

    /**
     * 把一批索引的原文捞出来（删索引之前调用）。
     *
     * 翻页到取完为止：单个索引的切片数没有上限（单文件就允许 2000 片），
     * 只取第一页等于静默丢弃尾部原文：而后面就要删索引了，那部分数据就此不可恢复。
     * 所以用 {@code search_after} 一页一页取，直到返回不足一页。
     *
     * @return 索引名 → 该索引的切片列表（无文档的索引不出现在结果里）
     */
    public static Map<String, List<PreservedChunk>> preserveAll(ElasticsearchClient esClient,
                                                                List<String> indices) {
        Map<String, List<PreservedChunk>> preserved = new LinkedHashMap<>();
        for (String index : indices) {
            List<PreservedChunk> chunks = new ArrayList<>();
            List<FieldValue> cursor = null;
            // 已从 ES 消费掉的文档数（含被跳过的脏文档）：翻页判据用它跟 total 比，
            // 用 chunks.size() 会因脏文档而误判成"取完了"
            long consumed = 0L;
            try {
                while (true) {
                    final List<FieldValue> after = cursor;
                    SearchRequest.Builder req = new SearchRequest.Builder()
                            .index(index)
                            .size(FETCH_BATCH)
                            // 必须显式开启：ES 默认只把 total 精确统计到 1 万条（relation=gte），
                            // 下面按 total 判"取完了"会在大索引上提前 break：而紧接着就要删索引重建
                            .trackTotalHits(t -> t.enabled(true))
                            .sort(so -> so.field(f -> f.field(SORT_FIELD).order(SortOrder.Asc)))
                            .source(src -> src.filter(f -> f.includes("text", "metadata")));
                    if (after != null) {
                        req.searchAfter(after);
                    }
                    SearchResponse<Map> resp = esClient.search(req.build(), Map.class);

                    List<Hit<Map>> hits = resp.hits().hits();
                    for (Hit<Map> hit : hits) {
                        Map<String, Object> source = hit.source();
                        if (source == null) {
                            continue;
                        }
                        Object text = source.get("text");
                        if (!(text instanceof String s) || s.isBlank()) {
                            continue;
                        }
                        Object metaObj = source.get("metadata");
                        Map<String, Object> meta = metaObj instanceof Map<?, ?> m
                                ? new LinkedHashMap<>((Map<String, Object>) m)
                                : new LinkedHashMap<>();
                        Object domain = meta.get("domain");
                        chunks.add(new PreservedChunk(s, meta,
                                domain == null ? null : domain.toString()));
                    }
                    consumed += hits.size();

                    // 判"取完了"必须看 total，不能看"这页是不是满页"：
                    // ES 侧有自己的单页上限（index.max_result_window / 分片限制），实际返回条数
                    // 可能小于我们请求的 size。那种情况下"不足一页"不等于"没有更多"，
                    // 按页大小判断会在大索引上静默截断：而下一步就要删索引，截掉的部分永久丢失。
                    long total = resp.hits().total() == null ? consumed : resp.hits().total().value();
                    if (consumed >= total) {
                        break;
                    }
                    if (hits.isEmpty()) {
                        // 没取完却已经读不出东西了：再发一次也是空转，直接中止而不是死循环
                        throw new IllegalStateException("ES 未返回 sort 值，无法安全翻页（已取 "
                                + consumed + " 条 / 共 " + total + " 条，若就此截断会丢原文）");
                    }
                    List<FieldValue> next = hits.get(hits.size() - 1).sort();
                    if (next == null || next.isEmpty()) {
                        // 还没取完却拿不到游标：再翻一页就会从头覆盖，丢数据；宁可中止
                        throw new IllegalStateException("ES 未返回 sort 值，无法安全翻页（已取 "
                                + consumed + " 条 / 共 " + total + " 条，若就此截断会丢原文）");
                    }
                    cursor = new ArrayList<>(next);
                }
                preserved.put(index, chunks);
                log.info("[向量重建] 索引[{}] 已保留 {} 条切片原文（ES 侧共 {} 条）",
                        index, chunks.size(), consumed);
            } catch (Exception e) {
                //捞取失败必须炸：这一批内容保不住，后面删索引就等于永久丢数据
                throw new IllegalStateException(
                        "读取索引[" + index + "]的原文失败，已中止（原文未能保留，不能继续删索引）：" + e.getMessage(), e);
            }
        }
        return preserved;
    }

    /**
     * 用新向量模型对保留下来的原文重新向量化并写回（索引已按新维度重建之后调用）。
     *
     * @param storeFor 按索引名取向量存储（与服务端灌库同一条路径，元数据搬运只有一份实现）
     */
    public static ReindexResult reindex(ElasticsearchClient esClient,
                                 EmbeddingModel embeddingModel,
                                 Map<String, List<PreservedChunk>> preserved,
                                 java.util.function.Function<String, EmbeddingStore> storeFor) {
        Map<String, Integer> reindexed = new LinkedHashMap<>();
        for (Map.Entry<String, List<PreservedChunk>> entry : preserved.entrySet()) {
            String index = entry.getKey();
            List<PreservedChunk> chunks = entry.getValue();
            if (chunks.isEmpty()) {
                reindexed.put(index, 0);
                continue;
            }
            if (embeddingModel == null) {
                throw new IllegalStateException("向量模型不可用，无法重灌索引[" + index + "]");
            }
            EmbeddingStore store = storeFor.apply(index);
            int written = 0;
            try {
                int total = chunks.size();
                for (int start = 0; start < total; start += EMBEDDING_BATCH) {
                    int end = Math.min(start + EMBEDDING_BATCH, total);
                    List<PreservedChunk> batch = chunks.subList(start, end);

                    List<TextSegment> segments = new ArrayList<>(batch.size());
                    for (PreservedChunk c : batch) {
                        segments.add(segmentOf(c));
                    }
                    var resp = embeddingModel.embedAll(segments);
                    List<Embedding> embeddings = resp.content();
                    if (embeddings.size() != batch.size()) {
                        // 不能只写一半：那样索引里会留下"有些切片有向量、有些没有"的半成品，
                        // 而向量缺失的切片在向量检索里等于凭空消失
                        throw new IllegalStateException("向量模型返回的条数与请求不符（请求 "
                                + batch.size() + " 条，返回 " + embeddings.size() + " 条）");
                    }
                    for (int i = 0; i < batch.size(); i++) {
                        store.add(embeddings.get(i), segments.get(i));
                    }
                    written += batch.size();
                }
                log.info("[向量重建] 索引[{}] 重灌完成：{}/{} 条", index, written, chunks.size());
            } catch (Exception e) {
                throw new IllegalStateException("索引[" + index + "]重灌失败（已写入 " + written
                        + "/" + chunks.size() + " 条，其余索引未处理）：" + e.getMessage(), e);
            }
            reindexed.put(index, written);
        }
        Map<String, Integer> preservedCounts = new LinkedHashMap<>();
        preserved.forEach((index, list) -> preservedCounts.put(index, list.size()));
        return new ReindexResult(preservedCounts, reindexed);
    }

    /**
     * 把保留的切片还原成 {@link TextSegment}，元数据按键名搬回。
     *
     * 必须搬齐：{@code doc_id} 决定"删文档"能不能删干净，{@code chunk_seq}/{@code chunk_total}
     * 是融合阶段的稳定排序二级键，缺了会让同一份知识每次检索顺序都不同。
     */
    private static TextSegment segmentOf(PreservedChunk chunk) {
        TextSegment segment = TextSegment.from(chunk.text());
        for (Map.Entry<String, Object> e : chunk.metadata().entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof Integer i) {
                segment.metadata().put(e.getKey(), i);
            } else if (v instanceof Number n) {
                // ES 的 integer 字段取回来是 Long；不收窄成 int 的话下游 getInteger 取不到
                segment.metadata().put(e.getKey(), n.intValue());
            } else {
                segment.metadata().put(e.getKey(), v.toString());
            }
        }
        return segment;
    }
}