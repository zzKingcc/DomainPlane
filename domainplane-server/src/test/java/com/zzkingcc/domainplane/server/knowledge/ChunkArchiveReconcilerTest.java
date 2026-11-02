package com.zzkingcc.domainplane.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.DedupKey;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties.Strategy;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 锁（一审修订）：原文档案的双向对账——孤儿回收 + 缺档补写。
 *
 * <p>只回收孤儿不够：archive 写入序是"ingest 成功 → 写 archive，失败只告警"，
 * 于是"索引有文档、archive 无原文"的缺档会静默丢文档（换模型重灌时这篇文档
 * 凭空消失）。两个方向都钉：① 孤儿（archive 有、登记表无）必须删——留着会让
 * 重灌把已删文档原样复活；② 缺档（登记表有、archive 无）必须从索引补写。
 * 另钉两条护栏语义：登记表读不到时整轮跳过（不拿对账制造数据丢失）、
 * 年龄护栏跳过在写窗口（不误删刚写完还没登记的档）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("档案对账：孤儿回收 / 缺档补写 / 登记表故障整轮跳过")
class ChunkArchiveReconcilerTest {

    private static final String KB = "kb1";

    @TempDir
    Path tmp;

    LocalBlobStore blob;
    ObjectMapper mapper;
    ChunkArchive archive;
    StubStore store;
    RetrievalPlan plan;
    ChunkArchiveReconciler reconciler;

    @BeforeEach
    void setUp() {
        blob = new LocalBlobStore(tmp);
        mapper = new ObjectMapper();
        archive = new ChunkArchive(blob, mapper);
        store = new StubStore();
        plan = planOver(store);
        reconciler = new ChunkArchiveReconciler(archive, plan, 5L);
    }

    private static KbChunk chunk(String id, String text, String docId) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.DOC_ID, docId);
        return new KbChunk(id, text, meta, null);
    }

    private void archiveDoc(String docId, String text) {
        archive.write(KB, docId, List.of(chunk("c-" + docId, text, docId)));
    }

    // ==================== 孤儿：archive 有、登记表无 → 删 ====================

    @Test
    @DisplayName("孤儿档案被回收（留着会让重灌复活已删文档）")
    void orphans_deleted() {
        store.collections = List.of(KB);
        store.docs.put(KB, List.of(new DocumentAgg("d1", "a.pdf", "default.sales", 1)));
        archiveDoc("d1", "在册文档");
        archiveDoc("d2", "已删除的文档");

        ChunkArchiveReconciler.Report r = reconciler.reconcileCollection(KB, Duration.ZERO);

        assertEquals(1, r.orphans());
        assertEquals(0, r.missing());
        assertEquals(0, r.failed());
        assertEquals(Set.of("d1"), archive.docIdsOf(KB), "孤儿必须被删掉");
    }

    // ==================== 缺档：登记表有、archive 无 → 补写 ====================

    @Test
    @DisplayName("缺档从索引精确读回并补写（scan 的存在理由）")
    void missing_backfilledFromStore() {
        store.collections = List.of(KB);
        store.docs.put(KB, List.of(
                new DocumentAgg("d1", "a.pdf", "default.sales", 1),
                new DocumentAgg("d3", "c.pdf", "default.sales", 2)));
        store.byDoc.put("d3", List.of(chunk("x1", "补写-1", "d3"), chunk("x2", "补写-2", "d3")));
        archiveDoc("d1", "在册文档");

        ChunkArchiveReconciler.Report r = reconciler.reconcileCollection(KB, Duration.ZERO);

        assertEquals(1, r.missing());
        assertEquals(0, r.orphans());
        assertEquals(Set.of("d1", "d3"), archive.docIdsOf(KB), "缺档必须补写，否则换模型重灌丢文档");
        Optional<List<KbChunk>> back = archive.read(KB, "d3");
        assertTrue(back.isPresent());
        assertEquals(2, back.get().size());
        assertEquals("补写-1", back.get().get(0).text());
    }

    @Test
    @DisplayName("登记表说有病档、索引实际没有：补写空档无意义，留给下轮")
    void missing_butIndexEmpty_notBackfilled() {
        store.collections = List.of(KB);
        store.docs.put(KB, List.of(new DocumentAgg("d3", "c.pdf", "default.sales", 2)));
        // store.byDoc 没有 d3：删除与刷新的竞态，索引里已经没有了

        ChunkArchiveReconciler.Report r = reconciler.reconcileCollection(KB, Duration.ZERO);

        assertEquals(1, r.missing(), "记了 missing（指标口径：确实缺）");
        assertTrue(archive.docIdsOf(KB).isEmpty(), "读不回原文就不写空档");
    }

    // ==================== 护栏：年龄窗口 / 登记表故障 ====================

    @Test
    @DisplayName("刚写的孤儿档在护栏窗口内：跳过不删（防撞'刚写完还没登记'）")
    void youngOrphan_skipped() {
        store.collections = List.of(KB);
        store.docs.put(KB, List.of(new DocumentAgg("d1", "a.pdf", "default.sales", 1)));
        archiveDoc("d1", "在册文档");
        archiveDoc("d2", "刚补写还没登记的档");

        ChunkArchiveReconciler.Report r = reconciler.reconcileCollection(KB);   // 默认 5 分钟护栏

        assertEquals(1, r.skippedYoung());
        assertEquals(0, r.orphans(), "护栏窗口内的孤儿不删");
        assertEquals(Set.of("d1", "d2"), archive.docIdsOf(KB));
    }

    @Test
    @DisplayName("登记表读不到：整轮跳过该 collection（不拿对账制造数据丢失）")
    void registryFailure_skipsWholeCollection() {
        store.collections = List.of(KB);
        store.failDocuments = true;
        archiveDoc("d1", "在册文档");
        archiveDoc("d2", "疑似孤儿");

        ChunkArchiveReconciler.Report r = reconciler.reconcileCollection(KB, Duration.ZERO);

        assertEquals(1, r.failed());
        assertEquals(0, r.orphans(), "读不到登记表就删全部档案 = 拿对账制造数据丢失");
        assertEquals(Set.of("d1", "d2"), archive.docIdsOf(KB), "档案必须原样保留");
    }

    // ==================== 整轮：collection 已消失的残留按孤儿回收 ====================

    @Test
    @DisplayName("reconcileAll：collection 已不在登记表的档案按孤儿回收")
    void reconcileAll_reapsStaleCollections() {
        store.collections = List.of(KB);
        store.docs.put(KB, List.of(new DocumentAgg("d1", "a.pdf", "default.sales", 1)));
        archiveDoc("d1", "在册文档");
        archive.write("kb-gone", "d9", List.of(chunk("c9", "删域残留", "d9")));
        // 关护栏的对账器：残留档也可能是老的，但本测试要的是"按孤儿处理"的路径
        ChunkArchiveReconciler noGuard = new ChunkArchiveReconciler(archive, plan, 0L);

        ChunkArchiveReconciler.Report r = noGuard.reconcileAll();

        assertEquals(1, r.orphans());
        assertEquals(Set.of(KB), archive.inventory().keySet(), "kb-gone 的残留必须被回收");
        assertTrue(archive.docIdsOf("kb-gone").isEmpty());
    }

    // ==================== 桩 ====================

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

    private static final class StubStore implements KnowledgeStore {

        List<String> collections = List.of();
        final Map<String, List<DocumentAgg>> docs = new LinkedHashMap<>();
        final Map<String, List<KbChunk>> byDoc = new LinkedHashMap<>();
        boolean failDocuments;

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
        }

        @Override
        public boolean deleteCollection(String collection) {
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
            if (failDocuments) {
                throw new RuntimeException("登记表读取失败：ES 抖动");
            }
            return docs.getOrDefault(collection, List.of());
        }

        @Override
        public Iterator<List<KbChunk>> scan(String collection, int batch) {
            return List.<List<KbChunk>>of().iterator();
        }
    }
}
