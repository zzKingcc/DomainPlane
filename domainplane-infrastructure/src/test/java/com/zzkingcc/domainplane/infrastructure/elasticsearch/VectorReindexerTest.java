package com.zzkingcc.domainplane.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.transport.ElasticsearchTransport;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量重建：<b>保留原文</b>重灌索引。
 *
 * <p>这组测试守的是一个会静默毁数据的实现走偏：保留原文重灌的整个价值在于
 * "捞回来的原文一条都不能少"。三个具体的丢数据方式：</p>
 * <ol>
 *   <li><b>不翻页</b>：只取第一页（1 万条）就删索引重建，尾部原文永久丢失；</li>
 *   <li><b>捞取失败仍往下走</b>：某个索引读不到却继续删，删掉的是没保住的原文；</li>
 *   <li><b>写回数与捞回数不等</b>：部分切片没写回向量，在向量检索里等于凭空消失。</li>
 * </ol>
 *
 * <p>三者都不会报错，只会让检索结果悄悄变差，所以必须用测试钉死。</p>
 *
 * @author zzkingcc
 */
@DisplayName("向量重建：原文必须一条不丢（翻页 / fail-closed / 写回数核对）")
class VectorReindexerTest {

    // ==================== 桩 ====================

    /**
     * 可编程的 search 桩。
     *
     * <p>{@code search(SearchRequest, Class)} 是生产代码实际调用的那个重载，
     * 按传入的 {@code search_after} 游标切分数据，从而能验证"是否翻页"。</p>
     */
    private static class StubClient extends ElasticsearchClient {

        /** 全量数据：索引名 → 该索引所有文档的 _source */
        final Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        /** ES 未返回 sort 值（用来验证"翻不了页就必须炸"） */
        boolean omitSort;
        /**
         * ES 每页只返回 1 条（模拟 index.max_result_window 等单页上限）。
         *
         * <p>用来钉住"不足一页 ≠ 已取完"：按页大小判断是否翻页的实现会在这里丢数据。</p>
         */
        boolean alwaysReturnPartialPage;
        /** 第几次 search 就抛异常（1-based；null = 不抛） */
        Integer failOnCall;
        /** 每次 search 的页大小（用来在小页上验证翻页） */
        int pageSize = 2;

        int calls;

        StubClient() {
            super((ElasticsearchTransport) null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <TDocument> SearchResponse<TDocument> search(SearchRequest request, Class<TDocument> tDocumentClass) {
            calls++;
            if (failOnCall != null && calls == failOnCall) {
                throw new RuntimeException("Connection refused");
            }
            List<String> indices = request.index();
            String index = indices.get(0);
            List<Map<String, Object>> all = data.getOrDefault(index, List.of());

            int from = 0;
            List<FieldValue> after = request.searchAfter();
            if (after != null && !after.isEmpty()) {
                // 桩按 "_doc" 的序号语义定位：sort 值就是全局序号
                from = (int) after.get(0).longValue() + 1;
            }
            int to = Math.min(from + (alwaysReturnPartialPage ? 1 : pageSize), all.size());
            List<Hit<Map>> hits = new ArrayList<>();
            for (int i = from; i < to; i++) {
                Hit<Map> h = new Hit.Builder<Map>()
                        .index(index)
                        .source(all.get(i))
                        .sort(omitSort ? List.of() : List.of(FieldValue.of((long) i)))
                        .build();
                hits.add(h);
            }
            HitsMetadata<Map> meta = new HitsMetadata.Builder<Map>()
                    .hits(hits)
                    .total(t -> t.value((long) all.size()).relation(TotalHitsRelation.Eq))
                    .build();
            return new SearchResponse.Builder<TDocument>()
                    .took(1L)
                    .timedOut(false)
                    .shards(s -> s.total(1).successful(1).failed(0))
                    .hits((HitsMetadata<TDocument>) (HitsMetadata<?>) meta)
                    .build();
        }

        void put(String index, List<Map<String, Object>> sources) {
            data.put(index, sources);
        }
    }

    private static Map<String, Object> chunk(String text, int seq, String docId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chunk_seq", seq);
        meta.put("chunk_total", 10);
        meta.put("doc_id", docId);
        meta.put("file_name", "a.pdf");
        meta.put("domain", "default.a");
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("text", text);
        src.put("metadata", meta);
        return src;
    }

    /** 记录写回内容的 store 桩（langchain4j 1.18.1：只有 4 个抽象方法需实现） */
    private static class RecordingStore implements EmbeddingStore<TextSegment> {

        final List<String> texts = new ArrayList<>();
        final List<Map<String, Object>> metas = new ArrayList<>();

        @Override
        public String add(Embedding embedding) {
            throw new UnsupportedOperationException("本测试只走带 segment 的写入路径");
        }

        @Override
        public void add(String id, Embedding embedding) {
            throw new UnsupportedOperationException("本测试只走带 segment 的写入路径");
        }

        @Override
        public String add(Embedding embedding, TextSegment segment) {
            texts.add(segment.text());
            Metadata md = segment.metadata();
            Map<String, Object> copy = new LinkedHashMap<>();
            md.toMap().forEach((k, v) -> {
                if (v != null) {
                    copy.put(k, v);
                }
            });
            metas.add(copy);
            return "id-" + texts.size();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            throw new UnsupportedOperationException("本测试只走逐条写入路径");
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return new EmbeddingSearchResult<>(List.of());
        }
    }

    /** 固定维度、条数与输入一致的向量模型桩 */
    private static class FixedEmbeddingModel implements EmbeddingModel {

        final int dim = 4;

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return new Response<>(new Embedding(new float[dim]));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> out = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                out.add(new Embedding(new float[dim]));
            }
            return new Response<>(out);
        }

        @Override
        public int dimension() {
            return dim;
        }
    }

    // ==================== 翻页：不能只取第一页 ====================

    @Test
    @DisplayName("preserveAll：ES 每页返回不足请求条数时仍翻页取完（不能当\"已取完\"）")
    void preserveKeepsPagingWhenPagesAreNotFull() {
        // 这是真实缺陷的形状：ES 侧有自己的单页上限（index.max_result_window / 分片限制），
        // 实际返回可能小于请求的 size。若按"这页满不满"判断是否取完，大索引会被静默截断，
        // 而下一步就要删索引，截掉的那部分原文永久丢失，且零报错。
        StubClient client = new StubClient();
        client.pageSize = 2;
        client.alwaysReturnPartialPage = true;
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            docs.add(chunk("原文-" + i, i, "doc" + i));
        }
        client.put("kb1", docs);

        Map<String, List<VectorReindexer.PreservedChunk>> preserved =
                VectorReindexer.preserveAll(client, List.of("kb1"));

        assertEquals(9, preserved.get("kb1").size(), "9 条必须一条不少");
        assertEquals("原文-8", preserved.get("kb1").get(8).text());
        assertTrue(client.calls >= 5, "每页只给 2 条时至少要 5 次 search，实际 " + client.calls);
    }

    @Test
    @DisplayName("preserveAll：切片数超过一页时翻页取完，不静默截断")
    void preserveAllPaginatesUntilExhausted() {
        StubClient client = new StubClient();
        client.pageSize = 2;                        // 故意用小页逼出翻页
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            docs.add(chunk("原文-" + i, i, "doc" + i));
        }
        client.put("kb1", docs);

        Map<String, List<VectorReindexer.PreservedChunk>> preserved =
                VectorReindexer.preserveAll(client, List.of("kb1"));

        List<VectorReindexer.PreservedChunk> chunks = preserved.get("kb1");
        assertEquals(7, chunks.size(), "7 条必须一条不少 —— 截断后就要删索引，尾部原文永久丢失");
        assertEquals(4, client.calls, "7 条 / 每页 2 条 = 4 次 search（3 满页 + 1 不足页）");
        assertEquals("原文-6", chunks.get(6).text(), "最后一条必须是原文-6，而不是原文-1");
    }

    @Test
    @DisplayName("preserveAll：ES 不返回 sort 值时炸掉，绝不当作\"已取完\"")
    void preserveAllFailsWhenCannotPaginate() {
        StubClient client = new StubClient();
        client.pageSize = 2;
        client.omitSort = true;
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            docs.add(chunk("原文-" + i, i, "doc" + i));
        }
        client.put("kb1", docs);

        // 满页后拿不到游标 → 再取下一页就会从头覆盖，丢数据；宁可中止
        assertThrows(IllegalStateException.class,
                () -> VectorReindexer.preserveAll(client, List.of("kb1")));
    }

    @Test
    @DisplayName("preserveAll：忽略空 text 的脏文档，但其余照常保留")
    void preserveAllSkipsBlankText() {
        StubClient client = new StubClient();
        client.pageSize = 10;
        client.put("kb1", List.of(
                chunk("有效", 0, "d0"),
                chunk("   ", 1, "d1"),
                chunk(null, 2, "d2"),
                chunk("也有效", 3, "d3")));

        Map<String, List<VectorReindexer.PreservedChunk>> preserved =
                VectorReindexer.preserveAll(client, List.of("kb1"));

        assertEquals(2, preserved.get("kb1").size(), "空正文不能进重灌队列，否则会把空向量写进索引");
        assertEquals("有效", preserved.get("kb1").get(0).text());
        assertEquals("也有效", preserved.get("kb1").get(1).text());
    }

    // ==================== fail-closed：捞不到就不能往下走 ====================

    @Test
    @DisplayName("preserveAll：某个索引读取失败即中止（绝不返回部分结果）")
    void preserveAllFailsClosedOnReadError() {
        StubClient client = new StubClient();
        client.put("kb1", List.of(chunk("a", 0, "d0")));
        client.failOnCall = 1;

        // 若改成"跳过失败的那个继续"，上层就会删掉没保住原文的索引 → 永久丢数据
        assertThrows(IllegalStateException.class,
                () -> VectorReindexer.preserveAll(client, List.of("kb1")));
    }

    // ==================== 重灌：写回数与捞回数必须一致 ====================

    @Test
    @DisplayName("reindex：preserved 逐项等于 reindexed（完整性可核对）")
    void reindexReportsCountsForCrossCheck() {
        StubClient client = new StubClient();
        Map<String, List<VectorReindexer.PreservedChunk>> preserved = new LinkedHashMap<>();
        preserved.put("kb1", List.of(
                new VectorReindexer.PreservedChunk("a", Map.of("chunk_seq", 0), "default"),
                new VectorReindexer.PreservedChunk("b", Map.of("chunk_seq", 1), "default")));

        RecordingStore store = new RecordingStore();
        VectorReindexer.ReindexResult result = VectorReindexer.reindex(
                client, new FixedEmbeddingModel(), preserved, idx -> store);

        assertEquals(Map.of("kb1", 2), result.preserved());
        assertEquals(Map.of("kb1", 2), result.reindexed(),
                "写回数必须与捞回数相等 —— 不等就意味着有切片在向量检索里凭空消失");
    }

    @Test
    @DisplayName("reindex：元数据原样搬回（含 long→int 收窄），doc_id/chunk_seq 不能丢")
    void reindexPreservesMetadataWithNarrowing() {
        StubClient client = new StubClient();
        // 模拟从 ES 取回的 metadata：integer 取回来是 Long
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chunk_seq", 3L);
        meta.put("doc_id", "doc-abc");
        meta.put("section_title", "第一章");
        Map<String, List<VectorReindexer.PreservedChunk>> preserved = new LinkedHashMap<>();
        preserved.put("kb1", List.of(new VectorReindexer.PreservedChunk("正文", meta, "default")));

        RecordingStore store = new RecordingStore();
        VectorReindexer.reindex(client, new FixedEmbeddingModel(), preserved, idx -> store);

        Map<String, Object> written = store.metas.get(0);
        assertEquals(3, written.get("chunk_seq"),
                "ES 的 integer 取回来是 Long，不收窄成 int 下游 getInteger 取不到");
        assertEquals("doc-abc", written.get("doc_id"), "doc_id 决定删文档能不能删干净");
        assertEquals("第一章", written.get("section_title"));
        assertEquals("正文", store.texts.get(0));
    }

    @Test
    @DisplayName("reindex：向量模型不可用时抛，不静默跳过索引")
    void reindexFailsWhenEmbeddingModelMissing() {
        StubClient client = new StubClient();
        Map<String, List<VectorReindexer.PreservedChunk>> preserved = new LinkedHashMap<>();
        preserved.put("kb1", List.of(new VectorReindexer.PreservedChunk("a", Map.of(), "default")));

        assertThrows(IllegalStateException.class,
                () -> VectorReindexer.reindex(client, null, preserved, idx -> new RecordingStore()));
    }

    @Test
    @DisplayName("reindex：空索引不消耗向量模型，reindexed 记 0")
    void reindexHandlesEmptyIndex() {
        StubClient client = new StubClient();
        Map<String, List<VectorReindexer.PreservedChunk>> preserved = new LinkedHashMap<>();
        preserved.put("kb1", List.of());

        VectorReindexer.ReindexResult result = VectorReindexer.reindex(
                client, null, preserved, idx -> {
                    throw new AssertionError("空索引不该去建 store");
                });

        assertEquals(Map.of("kb1", 0), result.reindexed());
        assertTrue(result.preserved().get("kb1") == 0);
    }
}
