package com.zzkingcc.domainplane.infrastructure.kb.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-03（平移 + 扩充）：SPI 的失败姿态必须逐条守住。
 *
 * <p>守的是一个具体的静默错误：{@code exists} 查询失败时若返回 {@code false}，
 * {@code deleteCollection} 会认为"没有东西可删"直接跳过，删域流程照样往下走并
 * 报告成功，域没了索引还在。而索引名是<b>域路径的哈希</b>，同一路径域将来重建时
 * 索引名相同，这份本该删掉的知识内容会原样复活，全程零报错。</p>
 *
 * <p>对照组是 {@code listCollections}：它<b>必须保持 fail-open</b>。
 * 同一批改动不能顺手把它也改成抛异常，那是管控台列表页，ES 抖一下代价是多显示一行空，
 * 抛异常的代价是页面整个打不开。两种处理方式并存，正是"看接收方是人还是机器"的判据。</p>
 *
 * <p>从 {@code EsIndexManagerFailClosedTest} 平移：静态工具方法变成
 * {@link EsKnowledgeStore} 实例方法，{@code EsAccessException} 换成 SPI 的
 * {@link StoreAccessException}（文案断言随消息文本同步更新，语义一条不改）。</p>
 *
 * <p>另从旧 {@code VectorReindexerTest} 迁入 {@code scan} 翻页用例：旧
 * {@code preserveAll} 的 search_after 翻页逻辑搬进了适配器的
 * {@link EsKnowledgeStore#scan}，"必须看 total 判取完、拿不到游标宁可中止"
 * 两条纪律的锁随代码一起搬。</p>
 *
 * @author zzkingcc
 */
@DisplayName("I-03 存储 SPI：破坏性路径 fail-closed，只读展示 fail-open，scan 翻页不截断")
class EsKnowledgeStoreFailClosedTest {

    // ==================== 桩 ====================

    /**
     * 可编程的 indices 客户端桩。
     *
     * <p>{@code exists(ExistsRequest)} / {@code delete(DeleteIndexRequest)} / {@code get(GetIndexRequest)}
     * 三个非 final 方法在生产代码里正是被直接调用的那三个（Function 版是 final 的桥接），
     * 所以覆盖它们能真实命中 {@link EsKnowledgeStore} 的执行路径。</p>
     */
    private static class StubIndices extends ElasticsearchIndicesClient {

        /** 存在性查询的答案；为 null 时改为抛异常（模拟 ES 不可达 / 超时 / 集群 red） */
        Boolean existsAnswer;
        IOException existsFailure;
        /** 删除操作的失败原因；null 表示删除成功 */
        IOException deleteFailure;
        /** 枚举索引返回的索引名；null 表示抛异常 */
        List<String> listAnswer;
        IOException listFailure;

        final AtomicInteger existsCalls = new AtomicInteger();
        final AtomicInteger deleteCalls = new AtomicInteger();
        final AtomicInteger listCalls = new AtomicInteger();
        final List<String> deletedIndices = new ArrayList<>();

        StubIndices() {
            super((ElasticsearchTransport) null);
        }

        @Override
        public BooleanResponse exists(ExistsRequest request) throws IOException {
            existsCalls.incrementAndGet();
            if (existsFailure != null) {
                throw existsFailure;
            }
            return new BooleanResponse(Boolean.TRUE.equals(existsAnswer));
        }

        @Override
        public DeleteIndexResponse delete(DeleteIndexRequest request) throws IOException {
            deleteCalls.incrementAndGet();
            // ES 客户端的 DeleteIndexRequest.index() 是 List（单删也用列表传）
            deletedIndices.addAll(request.index());
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            // DeleteIndexResponse 只有 acknowledged/shards 字段，索引名在 request 上
            return DeleteIndexResponse.of(b -> b.acknowledged(true));
        }

        @Override
        public GetIndexResponse get(GetIndexRequest request) throws IOException {
            listCalls.incrementAndGet();
            if (listFailure != null) {
                throw listFailure;
            }
            Map<String, IndexState> indices = new LinkedHashMap<>();
            for (String name : listAnswer) {
                indices.put(name, null);
            }
            return GetIndexResponse.of(b -> b.indices(indices));
        }
    }

    /**
     * 客户端桩：indices 走上面的桩，search 可编程（scan 翻页用）。
     *
     * <p>{@code search(SearchRequest, Class)} 是生产代码实际调用的那个重载，
     * 按传入的 {@code search_after} 游标切分数据，从而能验证"是否翻页"。</p>
     */
    private static class StubClient extends ElasticsearchClient {

        final StubIndices indices = new StubIndices();

        /** 全量数据：collection 名 → 该 collection 所有文档的 _source */
        final Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        /** ES 未返回 sort 值（用来验证"翻不了页就必须炸"） */
        boolean omitSort;
        /** ES 每页只返回 1 条（模拟 index.max_result_window 等单页上限） */
        boolean alwaysReturnPartialPage;
        /** 第几次 search 就抛异常（1-based；null = 不抛） */
        Integer failOnCall;
        /** 每页大小（用来在小页上验证翻页） */
        int pageSize = 2;

        int searchCalls;

        StubClient() {
            super((ElasticsearchTransport) null);
        }

        @Override
        public ElasticsearchIndicesClient indices() {
            return indices;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <TDocument> SearchResponse<TDocument> search(SearchRequest request, Class<TDocument> tDocumentClass) {
            searchCalls++;
            if (failOnCall != null && searchCalls == failOnCall) {
                throw new RuntimeException("Connection refused");
            }
            List<String> requested = request.index();
            String collection = requested.get(0);
            List<Map<String, Object>> all = data.getOrDefault(collection, List.of());

            // size=0 是 ScanIterator 开头的总数读取：只给 total，不给 hits
            Integer size = request.size();
            if (size != null && size == 0) {
                return (SearchResponse<TDocument>) responseOf(collection, List.of(), all.size());
            }

            int from = 0;
            List<FieldValue> after = request.searchAfter();
            if (after != null && !after.isEmpty()) {
                // 桩按 "_doc" 的序号语义定位：sort 值就是全局序号
                from = (int) after.get(0).longValue() + 1;
            }
            int want = size == null ? pageSize : Math.min(pageSize, size);
            if (alwaysReturnPartialPage) {
                want = 1;
            }
            int to = Math.min(from + want, all.size());
            List<Hit<Map>> hits = new ArrayList<>();
            for (int i = from; i < to; i++) {
                Hit<Map> h = new Hit.Builder<Map>()
                        .index(collection)
                        .source(all.get(i))
                        .sort(omitSort ? List.of() : List.of(FieldValue.of((long) i)))
                        .build();
                hits.add(h);
            }
            return (SearchResponse<TDocument>) responseOf(collection, hits, all.size());
        }

        private static SearchResponse<Map> responseOf(String index, List<Hit<Map>> hits, long total) {
            HitsMetadata<Map> meta = new HitsMetadata.Builder<Map>()
                    .hits(hits)
                    .total(t -> t.value(total).relation(TotalHitsRelation.Eq))
                    .build();
            return new SearchResponse.Builder<Map>()
                    .took(1L)
                    .timedOut(false)
                    .shards(s -> s.total(1).successful(1).failed(0))
                    .hits(meta)
                    .build();
        }

        void put(String collection, List<Map<String, Object>> sources) {
            data.put(collection, sources);
        }
    }

    private static EsKnowledgeStore store(StubClient client) {
        return new EsKnowledgeStore(client);
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

    // ==================== exists：查询失败必须抛 ====================

    @Test
    @DisplayName("exists 查询失败时抛 StoreAccessException，绝不返回 false")
    void existsThrowsWhenQueryFails() {
        StubClient client = new StubClient();
        client.indices.existsFailure = new IOException("Connection refused");

        StoreAccessException e = assertThrows(StoreAccessException.class,
                () -> store(client).exists("domainplane_kb_abc123"));

        // 文案必须点明"无法确认 ≠ 不存在"，否则排障时会误判成索引本来就没有
        assertTrue(e.getMessage().contains("无法确认"), "文案要说明这不是'不存在'：" + e.getMessage());
        assertTrue(e.getMessage().contains("domainplane_kb_abc123"), "文案要带上索引名：" + e.getMessage());
        assertNotNull(e.getCause(), "原始异常要保留，否则无法判断是超时还是连接被拒");
        assertEquals("Connection refused", e.getCause().getMessage());
    }

    @Test
    @DisplayName("exists 查询失败时是抛异常，不是返回 false（显式对照）")
    void existsDoesNotSilentlyReturnFalse() {
        StubClient client = new StubClient();
        client.indices.existsFailure = new IOException("timeout");

        // 这一条是整组测试的核心断言：把"抛"改成"返回 false"时，本测试必须红
        assertThrows(StoreAccessException.class,
                () -> store(client).exists("idx"),
                "查询失败被当成'不存在'会让删域静默跳过索引删除");
    }

    @Test
    @DisplayName("exists 正常返回 true / false 时如实透传")
    void existsPassesThroughRealAnswers() {
        StubClient client = new StubClient();
        client.indices.existsAnswer = true;
        assertTrue(store(client).exists("idx"));

        client.indices.existsAnswer = false;
        assertFalse(store(client).exists("idx"));

        assertEquals(2, client.indices.existsCalls.get(), "每次调用只该问一次");
    }

    // ==================== deleteCollection：任一步失败都必须中止 ====================

    @Test
    @DisplayName("deleteCollection：存在性查询失败时中止，且不发出删除请求")
    void deleteCollectionAbortsWhenExistsQueryFails() {
        StubClient client = new StubClient();
        client.indices.existsAnswer = true;             // 假设索引确实在
        client.indices.existsFailure = new IOException("cluster red");

        assertThrows(StoreAccessException.class,
                () -> store(client).deleteCollection("domainplane_kb_abc123"));

        // 关键：不能在"没问到答案"的情况下还发删除，那是把决策建立在未知上
        assertEquals(0, client.indices.deleteCalls.get(), "存在性没确认前不得发出删除请求");
    }

    @Test
    @DisplayName("deleteCollection：删除本身失败时抛 StoreAccessException")
    void deleteCollectionAbortsWhenDeleteFails() {
        StubClient client = new StubClient();
        client.indices.existsAnswer = true;
        client.indices.deleteFailure = new IOException("index locked");

        StoreAccessException e = assertThrows(StoreAccessException.class,
                () -> store(client).deleteCollection("domainplane_kb_abc123"));

        assertTrue(e.getMessage().contains("删除"), "文案要能区分'查失败'与'删失败'：" + e.getMessage());
        assertEquals(1, client.indices.deleteCalls.get());
        assertEquals(List.of("domainplane_kb_abc123"), client.indices.deletedIndices);
    }

    @Test
    @DisplayName("deleteCollection：确认不存在时返回 false（不抛，这是真的'没东西可删'）")
    void deleteCollectionReturnsFalseWhenConfirmedAbsent() {
        StubClient client = new StubClient();
        client.indices.existsAnswer = false;

        assertFalse(store(client).deleteCollection("idx"));
        assertEquals(0, client.indices.deleteCalls.get(), "不存在时不该发删除请求");
    }

    @Test
    @DisplayName("deleteCollection：正常路径返回 true")
    void deleteCollectionReturnsTrueOnSuccess() {
        StubClient client = new StubClient();
        client.indices.existsAnswer = true;

        assertTrue(store(client).deleteCollection("domainplane_kb_abc123"));
        assertEquals(1, client.indices.deleteCalls.get());
    }

    // ==================== listCollections：必须保持 fail-open ====================

    @Test
    @DisplayName("listCollections：ES 异常时返回空列表而不是抛异常（只读展示 fail-open）")
    void listCollectionsStaysFailOpen() {
        StubClient client = new StubClient();
        client.indices.listFailure = new IOException("Connection refused");

        // 与 exists 刻意相反：这里返回空列表是对的
        assertEquals(List.of(), store(client).listCollections());
        assertEquals(1, client.indices.listCalls.get());
    }

    @Test
    @DisplayName("listCollections：正常时返回按字典序排好的 collection 名")
    void listCollectionsReturnsSortedNames() {
        StubClient client = new StubClient();
        client.indices.listAnswer = List.of("domainplane_kb_c", "domainplane_kb_a", "domainplane_kb_b");

        assertEquals(List.of("domainplane_kb_a", "domainplane_kb_b", "domainplane_kb_c"),
                store(client).listCollections());
    }

    // ==================== scan：翻页不能截断（从旧 preserveAll 迁入） ====================

    @Test
    @DisplayName("scan：每页返回不足请求条数时仍翻页取完（不能当\"已取完\"）")
    void scanKeepsPagingWhenPagesAreNotFull() {
        // 这是真实缺陷的形状：ES 侧有自己的单页上限（index.max_result_window / 分片限制），
        // 实际返回可能小于请求的 size。若按"这页满不满"判断是否取完，大索引会被静默截断，
        // 而重灌下一步就要删索引，截掉的那部分原文永久丢失，且零报错。
        StubClient client = new StubClient();
        client.pageSize = 2;
        client.alwaysReturnPartialPage = true;
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            docs.add(chunk("原文-" + i, i, "doc" + i));
        }
        client.put("kb1", docs);

        List<KbChunk> scanned = drain(store(client).scan("kb1", 2));

        assertEquals(9, scanned.size(), "9 条必须一条不少");
        assertEquals("原文-8", scanned.get(8).text());
        // 1 次总数读取 + 每页 1 条共 9 页
        assertTrue(client.searchCalls >= 10, "每页只给 1 条时至少 10 次 search，实际 " + client.searchCalls);
    }

    @Test
    @DisplayName("scan：切片数超过一页时翻页取完，不静默截断")
    void scanPaginatesUntilExhausted() {
        StubClient client = new StubClient();
        client.pageSize = 2;                            // 故意用小页逼出翻页
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            docs.add(chunk("原文-" + i, i, "doc" + i));
        }
        client.put("kb1", docs);

        List<KbChunk> scanned = drain(store(client).scan("kb1", 2));

        assertEquals(7, scanned.size(), "7 条必须一条不少 —— 截断后就要删索引，尾部原文永久丢失");
        // 1 次总数读取 + 3 满页 + 1 不足页
        assertEquals(5, client.searchCalls, "1 次总数 + 7 条 / 每页 2 条 = 5 次 search，实际 " + client.searchCalls);
        assertEquals("原文-6", scanned.get(6).text(), "最后一条必须是原文-6，而不是原文-1");
    }

    @Test
    @DisplayName("scan：ES 不返回 sort 值时炸掉，绝不当作\"已取完\"")
    void scanFailsWhenCannotPaginate() {
        StubClient client = new StubClient();
        client.pageSize = 2;
        client.omitSort = true;
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            docs.add(chunk("原文-" + i, i, "doc" + i));
        }
        client.put("kb1", docs);

        // 满页后拿不到游标 → 再取下一页就会从头覆盖，丢数据；宁可中止
        assertThrows(StoreAccessException.class,
                () -> drain(store(client).scan("kb1", 2)));
    }

    @Test
    @DisplayName("scan：忽略空 text 的脏文档，但其余照常保留")
    void scanSkipsBlankText() {
        StubClient client = new StubClient();
        client.pageSize = 10;
        client.put("kb1", List.of(
                chunk("有效", 0, "d0"),
                chunk("   ", 1, "d1"),
                chunk(null, 2, "d2"),
                chunk("也有效", 3, "d3")));

        List<KbChunk> scanned = drain(store(client).scan("kb1", 10));

        assertEquals(2, scanned.size(), "空正文不能进重灌队列，否则会把空向量写进索引");
        assertEquals("有效", scanned.get(0).text());
        assertEquals("也有效", scanned.get(1).text());
    }

    @Test
    @DisplayName("scan：读取失败即中止（绝不返回部分结果）")
    void scanFailsClosedOnReadError() {
        StubClient client = new StubClient();
        client.put("kb1", List.of(chunk("a", 0, "d0")));
        client.failOnCall = 1;                          // 第一次 search（总数读取）就失败

        // 若改成"跳过失败的那个继续"，上层就会删掉没保住原文的索引 → 永久丢数据
        assertThrows(StoreAccessException.class,
                () -> drain(store(client).scan("kb1", 10)));
    }

    private static List<KbChunk> drain(java.util.Iterator<List<KbChunk>> scan) {
        List<KbChunk> out = new ArrayList<>();
        while (scan.hasNext()) {
            out.addAll(scan.next());
        }
        return out;
    }

    // ==================== 异常类型契约 ====================

    @Test
    @DisplayName("StoreAccessException 是 RuntimeException，捕获它的地方必须当成失败")
    void storeAccessExceptionIsUncheckedButFailClosed() {
        StoreAccessException e = new StoreAccessException("msg");
        assertSame(RuntimeException.class, StoreAccessException.class.getSuperclass(),
                "继承 unchecked 只是为了不污染调用链签名，不代表可以忽略");
        assertEquals("msg", e.getMessage());
        assertEquals("cause", new StoreAccessException("msg", new IllegalStateException("cause")).getCause().getMessage());
    }
}
