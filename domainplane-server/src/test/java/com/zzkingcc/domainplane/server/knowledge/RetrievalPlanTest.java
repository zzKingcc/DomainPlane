package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.store.DedupKey;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackendRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索计划：域链 → 通道规格。它只回答"查什么"，不碰 Spring、不建检索器实例，
 * 所以可以被完整单测——这是把通道解析从 {@code DomainChannelProvider} 抽出来的目的。
 *
 * <p>钉的行为分四组：① 域链展开（由根向下、depth 距查询域的距离、collection 命名）；
 * ② 策略门控（KEYWORD 不开向量路、VECTOR 不开关键词路，"根本不开"不是"开了不用"）；
 * ③ 未绑域通配（sourceId 用通配符；后端不支持通配时向量路跳过、关键词路枚举降级）；
 * ④ B4 取值源纪律（维度只读 vector 后端、登记视角以 keyword 后端为权威）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("检索计划：域链展开 / 策略门控 / 通配降级 / 取值源纪律")
class RetrievalPlanTest {

    private static final KeywordSearcher KW = (collection, queryText, topK) -> List.of();
    private static final VectorSearcher VEC = (collection, queryVector, topK) -> List.of();

    /** 可指定 collection 清单与能力声明的后端桩 */
    private static KnowledgeBackend backend(String id, StoreCapabilities caps,
                                            List<String> collections, KnowledgeStore store) {
        return new KnowledgeBackend() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public StoreCapabilities capabilities() {
                return caps;
            }

            @Override
            public Optional<KnowledgeStore> store() {
                return Optional.ofNullable(store);
            }

            @Override
            public Optional<KeywordSearcher> keyword() {
                return caps.keyword() ? Optional.of(KW) : Optional.empty();
            }

            @Override
            public Optional<VectorSearcher> vector() {
                return caps.vector() ? Optional.of(VEC) : Optional.empty();
            }
        };
    }

    private static final StoreCapabilities FULL =
            new StoreCapabilities(true, true, true, true, true);
    private static final StoreCapabilities FULL_NO_WILDCARD =
            new StoreCapabilities(true, true, false, true, true);

    private static RetrievalPlan hybridPlan(KnowledgeBackend... backends) {
        return new RetrievalPlan(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(backends)), "kb", 20, "kb", 30);
    }

    // ==================== 域链展开 ====================

    @Test
    @DisplayName("域链由根向下逐层展开，depth = 距查询域的距离（自身为 0）")
    void channels_spanDomainChainWithDepth() {
        RetrievalPlan plan = hybridPlan(backend("kb", FULL, List.of(), new StubStore("kb")));

        List<RetrievalPlan.ChannelSpec> specs = plan.channels("default.sales.eu");

        List<String> chain = Domains.chainOf("default.sales.eu");
        assertEquals(3, chain.size());
        assertEquals(chain.size() * 2, specs.size(), "hybrid：每个来源两路（向量 + 关键词）");

        // 顺序：逐来源「先向量后关键词」
        assertEquals(List.of(
                        chain.get(0), chain.get(0), chain.get(1), chain.get(1), chain.get(2), chain.get(2)),
                specs.stream().map(RetrievalPlan.ChannelSpec::sourceId).toList());
        assertEquals(List.of(2, 2, 1, 1, 0, 0),
                specs.stream().map(RetrievalPlan.ChannelSpec::depth).toList());
        assertEquals(List.of(Modality.VECTOR, Modality.KEYWORD,
                        Modality.VECTOR, Modality.KEYWORD, Modality.VECTOR, Modality.KEYWORD),
                specs.stream().map(RetrievalPlan.ChannelSpec::modality).toList());
    }

    @Test
    @DisplayName("collection = KbIndexes.nameOf(域)，locationId = backendId|collection|modality")
    void channels_carryCollectionAndLocation() {
        RetrievalPlan plan = hybridPlan(backend("kb", FULL, List.of(), new StubStore("kb")));

        RetrievalPlan.ChannelSpec spec = plan.channels("default.sales").get(0);

        assertEquals(KbIndexes.nameOf("default"), spec.collection());
        assertEquals("kb|" + KbIndexes.nameOf("default") + "|VECTOR", spec.locationId());
        assertEquals(30, spec.topK(), "向量路 topK 取 vector 后端配置");
        assertNotNull(spec.vectorSearcher());
        assertNull(spec.keywordSearcher(), "向量规格不挂关键词检索器");

        RetrievalPlan.ChannelSpec kwSpec = plan.channels("default.sales").get(1);
        assertEquals(20, kwSpec.topK(), "关键词路 topK 取 keyword 后端配置");
        assertNotNull(kwSpec.keywordSearcher());
        assertNull(kwSpec.vectorSearcher());
    }

    // ==================== 策略门控 ====================

    @Test
    @DisplayName("KEYWORD 策略：向量通道根本不开（不是开了不用）")
    void keywordStrategy_opensNoVectorChannel() {
        RetrievalPlan plan = new RetrievalPlan(Strategy.KEYWORD,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kb", FULL, List.of(), new StubStore("kb")))),
                "kb", 20, "kb", 30);

        List<RetrievalPlan.ChannelSpec> specs = plan.channels("default.sales.eu");

        assertEquals(3, specs.size());
        assertTrue(specs.stream().allMatch(s -> s.modality() == Modality.KEYWORD));
        assertTrue(specs.stream().allMatch(s -> s.vectorSearcher() == null));
    }

    @Test
    @DisplayName("VECTOR 策略：关键词通道根本不开")
    void vectorStrategy_opensNoKeywordChannel() {
        RetrievalPlan plan = new RetrievalPlan(Strategy.VECTOR,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kb", FULL, List.of(), new StubStore("kb")))),
                "kb", 20, "kb", 30);

        List<RetrievalPlan.ChannelSpec> specs = plan.channels("default.sales.eu");

        assertEquals(3, specs.size());
        assertTrue(specs.stream().allMatch(s -> s.modality() == Modality.VECTOR));
        assertTrue(specs.stream().allMatch(s -> s.keywordSearcher() == null));
    }

    // ==================== 未绑域通配 ====================

    @Test
    @DisplayName("域为 null（管控台预览）：sourceId 与 collection 都用通配符，depth 0")
    void nullDomain_wildcardChannels() {
        RetrievalPlan plan = hybridPlan(backend("kb", FULL, List.of(), new StubStore("kb")));

        List<RetrievalPlan.ChannelSpec> specs = plan.channels(null);

        assertEquals(2, specs.size(), "通配检索只有一对通道：跨库算一个来源");
        for (RetrievalPlan.ChannelSpec s : specs) {
            assertEquals(KbIndexes.WILDCARD, s.sourceId());
            assertEquals(KbIndexes.WILDCARD, s.collection());
            assertEquals(0, s.depth());
        }
    }

    @Test
    @DisplayName("后端不支持通配：向量路跳过、关键词路枚举 collection 降级")
    void noWildcardSupport_vectorSkipped_keywordEnumerates() {
        StubStore store = new StubStore("kb");
        store.collections = List.of("domainplane_kb_a", "domainplane_kb_b");
        RetrievalPlan plan = hybridPlan(backend("kb", FULL_NO_WILDCARD, store.collections, store));

        List<RetrievalPlan.ChannelSpec> specs = plan.channels(null);

        assertEquals(2, specs.size(), "两个 collection 各一条关键词通道");
        assertTrue(specs.stream().allMatch(s -> s.modality() == Modality.KEYWORD));
        assertEquals(List.of("domainplane_kb_a", "domainplane_kb_b"),
                specs.stream().map(RetrievalPlan.ChannelSpec::collection).toList());
        assertEquals("kb|domainplane_kb_a|KEYWORD", specs.get(0).locationId());
    }

    // ==================== B4 取值源纪律 ====================

    @Test
    @DisplayName("keywordStore 读 keyword 后端、vectorStore 读 vector 后端（分开配置时各归各）")
    void stores_resolveFromTheirOwnBackend() {
        StubStore kwStore = new StubStore("kw-id");
        StubStore vecStore = new StubStore("vec-id");
        RetrievalPlan plan = new RetrievalPlan(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", FULL, List.of(), kwStore),
                        backend("vec-id", FULL, List.of(), vecStore))),
                "kw-id", 20, "vec-id", 30);

        assertSameStore(kwStore, plan.keywordStore(), "文档登记视角的权威是 keyword 后端");
        assertSameStore(vecStore, plan.vectorStore(), "向量维度只从 vector 后端读");
    }

    @Test
    @DisplayName("后端不提供存储能力：取值抛 60007（应被启动期校验拦截）")
    void store_missingCapability_throws60007() {
        RetrievalPlan plan = new RetrievalPlan(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", FULL, List.of(), null))),
                "kw-id", 20, "vec-id", 30);

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class, plan::keywordStore);
        assertEquals(60007, e.getCode());
        assertTrue(e.getMessage().contains("kw-id"), e.getMessage());
    }

    @Test
    @DisplayName("后端未注册：取值抛 60007，文案列出可用 id")
    void store_unregisteredBackend_throws60007() {
        RetrievalPlan plan = new RetrievalPlan(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", FULL, List.of(), new StubStore("kw-id")))),
                "gone-id", 20, "vec-id", 30);

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class, plan::keywordStore);
        assertEquals(60007, e.getCode());
        assertTrue(e.getMessage().contains("gone-id"), e.getMessage());
        assertTrue(e.getMessage().contains("kw-id"), "可用 id 要列出来，便于排障：" + e.getMessage());
    }

    @Test
    @DisplayName("backendsEmpty：空注册表为 true（校验第一条就查它）")
    void backendsEmpty_reflectsRegistry() {
        assertTrue(new RetrievalPlan(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of()), "kb", 20, "kb", 30).backendsEmpty());
        assertFalse(hybridPlan(backend("kb", FULL, List.of(), new StubStore("kb"))).backendsEmpty());
    }

    private static void assertSameStore(KnowledgeStore expected, KnowledgeStore actual, String message) {
        assertTrue(expected == actual, message + "（期望同一实例，实际 " + actual + "）");
    }

    // ==================== 桩 ====================

    private static final class StubStore implements KnowledgeStore {

        private final String name;
        List<String> collections = List.of();

        StubStore(String name) {
            this.name = name;
        }

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
