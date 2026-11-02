package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.domain.rag.store.DedupKey;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.FanOutStore;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写入扇出的收敛判据：<b>看物理地址，不看后端 id</b>。
 *
 * <p>管控台里关键字槽和向量槽是两条独立配置，用户可以把同一台 ES 的地址在两处各填一遍。
 * 那两个后端是不同的对象、却是同一份数据——按对象引用比会扇出成两路，同一批切片被写两遍。
 * 这组测试钉的就是"同地址必须收敛、不同地址必须扇出"。</p>
 *
 * @author zzkingcc
 */
@DisplayName("检索计划：写入扇出按物理地址收敛")
class RetrievalPlanWriteStoreTest {

    private static final StoreCapabilities FULL = new StoreCapabilities(true, true, true, true, true);
    private static final KeywordSearcher KW = (collection, queryText, topK) -> List.of();
    private static final VectorSearcher VEC = (collection, queryVector, topK) -> List.of();

    private static KnowledgeBackend backend(String id, String location, KnowledgeStore store) {
        return new KnowledgeBackend() {
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
                return FULL;
            }

            @Override
            public Optional<KnowledgeStore> store() {
                return Optional.of(store);
            }

            @Override
            public Optional<KeywordSearcher> keyword() {
                return Optional.of(KW);
            }

            @Override
            public Optional<VectorSearcher> vector() {
                return Optional.of(VEC);
            }
        };
    }

    private static RetrievalPlan plan(KnowledgeBackend kw, KnowledgeBackend vec) {
        return new RetrievalPlan(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(kw, vec)),
                kw.id(), 20, vec.id(), 30);
    }

    @Test
    @DisplayName("两个连接指向同一台实例（地址相同）：写入只落一份，不扇出")
    void sameLocation_noFanOut() {
        StubStore kwStore = new StubStore();
        RetrievalPlan plan = plan(
                backend("es-kw", "http://es.internal:9200", kwStore),
                backend("es-vec", "http://es.internal:9200", new StubStore()));

        assertSame(kwStore, plan.writeStore(), "同地址 = 同一份数据，直接写关键字那个 store");
    }

    @Test
    @DisplayName("两个连接指向不同实例：必须扇出（否则另一条路静默召回不到）")
    void differentLocation_fanOut() {
        RetrievalPlan plan = plan(
                backend("es-kw", "http://es.internal:9200", new StubStore()),
                backend("qdrant-vec", "http://qdrant.internal:6333", new StubStore()));

        assertTrue(plan.writeStore() instanceof FanOutStore,
                "不同实例必须两路都写，实际 " + plan.writeStore().getClass().getSimpleName());
    }

    @Test
    @DisplayName("地址未填（location 为空）按不同处理：宁可写入时报未配置，也不静默只写一半")
    void blankLocation_treatedAsDifferent() {
        RetrievalPlan plan = plan(
                backend("es-kw", null, new StubStore()),
                backend("es-vec", null, new StubStore()));

        assertTrue(plan.writeStore() instanceof FanOutStore);
    }

    /** 只够用来比较同一性的最小实现 */
    private static final class StubStore implements KnowledgeStore {

        @Override
        public StoreCapabilities capabilities() {
            return FULL;
        }

        @Override
        public boolean exists(String collection) {
            return true;
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
            return List.of();
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
            return List.of();
        }

        @Override
        public List<DocumentAgg> documents(String collection, int limit) {
            return List.of();
        }

        @Override
        public Iterator<List<KbChunk>> scan(String collection, int batch) {
            return List.<List<KbChunk>>of().iterator();
        }
    }
}
