package com.zzkingcc.domainplane.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.DedupKey;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackendRegistry;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties.Strategy;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换向量模型 / 换后端时的三阶段重灌：全捞（对账 → 读 archive，archive 全失兜底 scan）
 * → 重建（deleteCollection → ensureCollection(newDims)）→ 重灌（25 条/批 embed 后写回）。
 *
 * <p>与旧实现的根本差别是原文来源：旧实现从<b>索引</b>捞回（ES 的隐性福利，换后端即失效），
 * 新实现从 {@link ChunkArchive} 读——索引降格为可重建的派生数据。本测试因此钉三类
 * 会静默毁数据的走偏：① 写回数与捞回数不等（向量缺失的切片在向量检索里凭空消失）；
 * ② 条数不符仍写半批（索引里留下"有些切片有向量、有些没有"的半成品）；
 * ③ 重建中途失败还继续写（部分新向量、部分老向量混在一个索引里，检索静默失真）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("三阶段重灌：原文一条不丢 / 批内条数核对 / 失败即中止")
class VectorReindexerTest {

    @TempDir
    Path tmp;

    LocalBlobStore blob;
    ObjectMapper mapper;
    ChunkArchive archive;
    StubStore store;
    RetrievalPlan plan;

    @BeforeEach
    void setUp() {
        blob = new LocalBlobStore(tmp);
        mapper = new ObjectMapper();
        archive = new ChunkArchive(blob, mapper);
        store = new StubStore();
        plan = planOver(store);
    }

    // ==================== 主路径：从 archive 捞回 ====================

    @Test
    @DisplayName("全流程：对账(ZERO 护栏) → 删旧重建(新维度) → 25 条/批 embed → 写回数核对")
    void reindexFromArchive_fullCycle() {
        store.collections = List.of("kb1");
        store.docs.put("kb1", List.of(
                new DocumentAgg("d1", "a.pdf", "default.sales", 3),
                new DocumentAgg("d2", "b.pdf", "default.sales", 2)));
        archive.write("kb1", "d1", List.of(
                chunk("c1", "原文-1", "d1"), chunk("c2", "原文-2", "d1"), chunk("c3", "原文-3", "d1")));
        archive.write("kb1", "d2", List.of(chunk("c4", "原文-4", "d2"), chunk("c5", "原文-5", "d2")));
        FixedModel model = new FixedModel(4);
        CountingReconciler reconciler = new CountingReconciler(archive, plan);

        VectorReindexer.ReindexResult result =
                VectorReindexer.reindex(archive, reconciler, store, model, 4);

        assertEquals(Map.of("kb1", 5), result.preserved(), "捞回条数");
        assertEquals(Map.of("kb1", 5), result.reindexed(),
                "写回数必须与捞回数相等——不等就意味着有切片在向量检索里凭空消失");

        assertEquals(List.of("kb1"), reconciler.reconciled, "重灌前必须先对账（孤儿不清会复活已删文档）");
        assertEquals(List.of(Duration.ZERO), reconciler.guards, "显式操作关护栏：不该被'文件太新'挡下");
        assertEquals(List.of("kb1"), store.deleted, "先删后建");
        assertEquals(List.of("kb1:4"), store.ensured, "按新维度重建");

        int written = store.written.stream().mapToInt(List::size).sum();
        assertEquals(5, written);
        assertTrue(store.written.stream().flatMap(List::stream)
                        .allMatch(c -> c.vector() != null && c.vector().length == 4),
                "写回的切片必须带新维度的向量");
        assertEquals("d1", store.written.get(0).get(0).metadata().get(ChunkMetadataKeys.DOC_ID),
                "doc_id 决定删文档能不能删干净");
    }

    @Test
    @DisplayName("archive 全失：兜底从索引 scan，并按 doc_id 补写档案（下次重灌就有档了）")
    void archiveLost_fallsBackToScanAndBackfills() {
        store.collections = List.of("kb1");
        store.docs.put("kb1", List.of());   // 登记表为空：对账补不出东西 → 走 scan 兜底
        store.scanPages = List.of(
                List.of(chunk("c1", "原文-1", "d1"), chunk("c2", "原文-2", "d1")),
                List.of(chunk("c3", "原文-3", "d2")));

        VectorReindexer.ReindexResult result = VectorReindexer.reindex(
                archive, new CountingReconciler(archive, plan), store, new FixedModel(4), 4);

        assertEquals(Map.of("kb1", 3), result.preserved());
        assertEquals(Set.of("d1", "d2"), archive.docIdsOf("kb1"),
                "scan 兜底捞回的原文要顺手补写档案，否则每次换模型都要扫全库");
    }

    @Test
    @DisplayName("没有任何 collection：空结果，不碰向量模型")
    void noCollections_emptyResult() {
        VectorReindexer.ReindexResult result = VectorReindexer.reindex(
                archive, new CountingReconciler(archive, plan), store, null, 4);

        assertTrue(result.preserved().isEmpty());
        assertTrue(result.reindexed().isEmpty());
        assertTrue(store.written.isEmpty());
    }

    // ==================== fail-closed：三种静默毁数据的走偏 ====================

    @Test
    @DisplayName("向量模型返回条数与请求不符：立即抛，一条都不写（不留半成品）")
    void embeddingCountMismatch_throwsBeforeAnyWrite() {
        store.collections = List.of("kb1");
        store.docs.put("kb1", List.of(new DocumentAgg("d1", "a.pdf", "default.sales", 2)));
        archive.write("kb1", "d1", List.of(chunk("c1", "原文-1", "d1"), chunk("c2", "原文-2", "d1")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VectorReindexer.reindex(archive,
                        new CountingReconciler(archive, plan), store, new MiscountModel(4), 4));

        assertTrue(e.getMessage().contains("条数与请求不符"), e.getMessage());
        assertTrue(store.written.isEmpty(),
                "半成品比没有更糟：有些切片有向量、有些没有，向量检索里等于凭空消失");
    }

    @Test
    @DisplayName("重建阶段失败（删不掉）：立即中止，不进入重灌")
    void rebuildFailure_abortsBeforeAnyWrite() {
        store.collections = List.of("kb1");
        store.docs.put("kb1", List.of(new DocumentAgg("d1", "a.pdf", "default.sales", 1)));
        store.failDelete = true;
        archive.write("kb1", "d1", List.of(chunk("c1", "原文-1", "d1")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VectorReindexer.reindex(archive,
                        new CountingReconciler(archive, plan), store, new FixedModel(4), 4));

        assertTrue(e.getMessage().contains("kb1"), "要点名是哪个 collection：" + e.getMessage());
        assertTrue(store.written.isEmpty(), "重建失败还写新向量 = 混合态索引，检索静默失真");
    }

    @Test
    @DisplayName("25 条/批：30 条切片分两批 embed、两次写回（百炼单批 25 行上限）")
    void embeddingBatching_25PerBatch() {
        store.collections = List.of("kb1");
        store.docs.put("kb1", List.of(new DocumentAgg("d1", "a.pdf", "default.sales", 30)));
        List<KbChunk> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(chunk("c" + i, "原文-" + i, "d1"));
        }
        archive.write("kb1", "d1", many);
        FixedModel model = new FixedModel(4);

        VectorReindexer.reindex(archive, new CountingReconciler(archive, plan), store, model, 4);

        assertEquals(List.of(25, 5), model.batchSizes, "30 条 = 25 + 5 两批");
        assertEquals(2, store.written.size());
    }

    @Test
    @DisplayName("跨后端重灌：源 ≠ 目标，数据从源（archive 兜底 scan）搬到目标，源不被改动")
    void crossBackend_reindexesSourceToTarget() {
        StubStore source = new StubStore();
        StubStore target = new StubStore();
        source.collections = List.of("kb1");
        source.docs.put("kb1", List.of(new DocumentAgg("d1", "a.pdf", "default.sales", 3)));
        archive.write("kb1", "d1", List.of(
                chunk("c1", "原文-1", "d1"), chunk("c2", "原文-2", "d1"), chunk("c3", "原文-3", "d1")));
        RetrievalPlan srcPlan = planOver(source);
        FixedModel model = new FixedModel(4);

        VectorReindexer.ReindexResult result = VectorReindexer.reindex(
                archive, new CountingReconciler(archive, srcPlan), source, target, model, 4);

        assertEquals(Map.of("kb1", 3), result.reindexed(), "目标后端写回 3 条");
        assertEquals(List.of("kb1"), target.deleted, "只动目标后端");
        assertEquals(List.of("kb1:4"), target.ensured);
        assertEquals(3, target.written.stream().mapToInt(List::size).sum());
        assertTrue(source.written.isEmpty(), "源后端只读、不写");
        assertTrue(source.deleted.isEmpty(), "先全部捞完再动源：源必须完好（'再来一次'的依据）");
    }

    // ==================== 桩 ====================

    private static KbChunk chunk(String id, String text, String docId) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.DOC_ID, docId);
        meta.put(ChunkMetadataKeys.CHUNK_SEQ, "0");
        return new KbChunk(id, text, meta, null);
    }

    /** 计划：keyword/vector 两路都指向同一个 stub store（对账经它取登记表） */
    private static RetrievalPlan planOver(KnowledgeStore store) {
        KnowledgeBackend backend = new KnowledgeBackend() {
            @Override
            public String id() {
                return "kb";
            }

            @Override
            public StoreCapabilities capabilities() {
                return new StoreCapabilities(true, true, true, true, true);
            }

            @Override
            public Optional<KnowledgeStore> store() {
                return Optional.of(store);
            }

            @Override
            public Optional<com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher> keyword() {
                return Optional.empty();
            }

            @Override
            public Optional<com.zzkingcc.domainplane.domain.rag.store.VectorSearcher> vector() {
                return Optional.empty();
            }
        };
        return new RetrievalPlan(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(backend)), "kb", 20, "kb", 30);
    }

    /** 记录对账调用的 reconciler（护栏值 + 调用顺序可见） */
    private static final class CountingReconciler extends ChunkArchiveReconciler {

        final List<String> reconciled = new ArrayList<>();
        final List<Duration> guards = new ArrayList<>();

        CountingReconciler(ChunkArchive archive, RetrievalPlan plan) {
            super(archive, plan, noBodies(), 0L);
        }

        /** 对账桩不关心知识体：任何集合都按"没有对应体"处理，走平台默认关键字存储 */
        private static KnowledgeBodyResolver noBodies() {
            return new KnowledgeBodyResolver(null, null, null, null) {
                @Override
                public String bodyIdOfIndex(String index) {
                    return null;
                }

                @Override
                public java.util.Set<String> allIndices() {
                    return java.util.Set.of();
                }
            };
        }

        @Override
        public Report reconcileCollection(String collection, Duration minAge) {
            reconciled.add(collection);
            guards.add(minAge);
            return super.reconcileCollection(collection, minAge);
        }
    }

    private static final class FixedModel implements EmbeddingModel {

        private final int dim;
        final List<Integer> batchSizes = new ArrayList<>();

        FixedModel(int dim) {
            this.dim = dim;
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return new Response<>(new Embedding(new float[dim]));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            batchSizes.add(segments.size());
            return new Response<>(segments.stream()
                    .map(s -> (Embedding) new Embedding(new float[dim])).toList());
        }

        @Override
        public int dimension() {
            return dim;
        }
    }

    /** 条数与请求不符的模型桩：永远只返回 1 条 */
    private static final class MiscountModel implements EmbeddingModel {

        private final int dim;

        MiscountModel(int dim) {
            this.dim = dim;
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return new Response<>(new Embedding(new float[dim]));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return new Response<>(List.of(new Embedding(new float[dim])));
        }

        @Override
        public int dimension() {
            return dim;
        }
    }

    private static final class StubStore implements KnowledgeStore {

        List<String> collections = List.of();
        final Map<String, List<DocumentAgg>> docs = new LinkedHashMap<>();
        final Map<String, List<KbChunk>> byDoc = new LinkedHashMap<>();
        List<List<KbChunk>> scanPages = List.of();
        boolean failDelete;

        final List<String> deleted = new ArrayList<>();
        final List<String> ensured = new ArrayList<>();
        final List<List<KbChunk>> written = new ArrayList<>();

        @Override
        public StoreCapabilities capabilities() {
            return new StoreCapabilities(true, true, true, true, true);
        }

        @Override
        public boolean exists(String collection) {
            return collections.contains(collection);
        }

        @Override
        public void ensureCollection(String collection, int dims) {
            ensured.add(collection + ":" + dims);
        }

        @Override
        public boolean deleteCollection(String collection) throws StoreAccessException {
            if (failDelete) {
                throw new StoreAccessException("删除 collection[" + collection + "]失败：集群只读");
            }
            deleted.add(collection);
            return true;
        }

        @Override
        public List<String> listCollections() {
            return collections;
        }

        @Override
        public Integer currentVectorDims(String collection) {
            return 4;
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public void diagnose(String collection) {
        }

        @Override
        public void write(String collection, List<KbChunk> chunks) {
            written.add(chunks);
        }

        @Override
        public int deleteByDocId(String collection, String docId) {
            return 0;
        }

        @Override
        public Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes) {
            return Set.of();
        }

        @Override
        public long count(String collection) {
            return 0;
        }

        @Override
        public long countByDocId(String collection, String docId) {
            return 0;
        }

        @Override
        public List<KbChunk> readByDocId(String collection, String docId) {
            return byDoc.getOrDefault(docId, List.of());
        }

        @Override
        public List<DocumentAgg> documents(String collection, int limit) {
            return docs.getOrDefault(collection, List.of());
        }

        @Override
        public Iterator<List<KbChunk>> scan(String collection, int batch) {
            return scanPages.iterator();
        }
    }
}
