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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索计划：域链 → 知识体 → 通道规格。它只回答"查什么"，不碰 Spring、不建检索器实例，
 * 所以可以被完整单测——这是把通道解析从 {@code DomainChannelProvider} 抽出来的目的。
 *
 * <p>钉的行为分四组：</p>
 * <ol>
 *   <li><b>域链 × 绑体</b>：沿域链逐层找"绑了体的域"，一个体一个来源；两路都配的体开两路，
 *       同名集合同一路只开一次（多域绑同一个体时不能把同一份数据查两遍）；</li>
 *   <li><b>策略门控</b>：KEYWORD 不开向量路、VECTOR 不开关键词路，"根本不开"不是"开了不用"；</li>
 *   <li><b>未绑域通配</b>：sourceId 用通配符；后端不支持通配时向量路跳过、关键词路枚举降级；</li>
 *   <li><b>取值源纪律</b>：维度只读 vector 后端、登记视角以 keyword 后端为权威。</li>
 * </ol>
 *
 * @author zzkingcc
 */
@DisplayName("检索计划：域链×绑体 / 策略门控 / 通配降级 / 取值源纪律")
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

    /**
     * 域 → 体 的桩：只登记显式绑过的域，<b>不继承父域</b>（继承是域链展开这一步做的，
     * 解析器本身就该是"问谁答谁"）。
     */
    private static final class StubLookup implements RetrievalPlan.BodyLookup {
        /** domain → bodyId */
        final Map<String, String> bound = new LinkedHashMap<>();
        String keywordId = "kw-id";
        String vectorId = "vec-id";
        int keywordTopK = 20;
        int vectorTopK = 30;

        StubLookup bind(String domain, String bodyId) {
            bound.put(domain, bodyId);
            return this;
        }

        @Override
        public List<RetrievalPlan.BodyBinding> bodiesOf(String domain) {
            String bodyId = bound.get(domain);
            return bodyId == null ? List.of() : bindingsOf(bodyId);
        }

        @Override
        public RetrievalPlan.BodyBinding platformBody(String kind) {
            List<RetrievalPlan.BodyBinding> list = bindingsOf("kb-platform");
            return list.stream().filter(b -> b.kind().equals(kind)).findFirst().orElse(null);
        }

        @Override
        public String bodyIdOf(String domain) {
            return bound.get(domain);
        }

        @Override
        public List<RetrievalPlan.BodyBinding> bodiesOfBody(String bodyId) {
            return bindingsOf(bodyId);
        }

        private List<RetrievalPlan.BodyBinding> bindingsOf(String bodyId) {
            List<RetrievalPlan.BodyBinding> out = new ArrayList<>(2);
            if (vectorId != null) {
                out.add(new RetrievalPlan.BodyBinding(bodyId, "vector", vectorId, vectorTopK,
                        KbIndexes.nameOfBody(bodyId, bodyId)));
            }
            if (keywordId != null) {
                out.add(new RetrievalPlan.BodyBinding(bodyId, "keyword", keywordId, keywordTopK,
                        KbIndexes.nameOfBody(bodyId, bodyId)));
            }
            return List.copyOf(out);
        }
    }

    private static RetrievalPlan planWithLookup(StubLookup lookup, Strategy strategy,
                                                KnowledgeBackend... backends) {
        return new RetrievalPlan(strategy, new MapKnowledgeBackendRegistry(List.of(backends)), lookup);
    }

    // ==================== 域链 × 绑体 ====================

    @Test
    @DisplayName("域链逐层找绑了体的域：depth = 距查询域的距离，每个体两路")
    void channels_spanDomainChainOfBodies() {
        StubLookup lookup = new StubLookup()
                .bind("default", "kb-root")
                .bind("default.sales", "kb-sales");
        RetrievalPlan plan = planWithLookup(lookup, Strategy.HYBRID,
                backend("kw-id", FULL, List.of(), new StubStore("kw")),
                backend("vec-id", FULL, List.of(), new StubStore("vec")));

        List<RetrievalPlan.ChannelSpec> specs = plan.channels("default.sales.eu");

        // 链上 3 个域，只有 2 个绑了体；未绑体的叶子域不产生空来源
        assertEquals(4, specs.size(), "2 个体 × 2 路");
        assertEquals(List.of("default", "default", "default.sales", "default.sales"),
                specs.stream().map(RetrievalPlan.ChannelSpec::sourceId).toList(),
                "sourceId 是贡献绑定的域；展开顺序沿用域链（根在前），融合层按 depth 折损");
        assertEquals(List.of(2, 2, 1, 1), specs.stream().map(RetrievalPlan.ChannelSpec::depth).toList(),
                "depth = 距查询域的距离（default.sales.eu 自身 0，default.sales 1，default 2）");
        assertEquals(List.of(Modality.VECTOR, Modality.KEYWORD, Modality.VECTOR, Modality.KEYWORD),
                specs.stream().map(RetrievalPlan.ChannelSpec::modality).toList(),
                "来源内顺序与改造前一致：先向量后关键词");
    }

    @Test
    @DisplayName("同一份数据只开一次：两个域绑同一个体，或上下两层的体指向同一个集合")
    void channels_dedupeByLocation() {
        StubLookup lookup = new StubLookup()
                .bind("default", "kb-shared")
                .bind("default.sales", "kb-shared");
        RetrievalPlan plan = planWithLookup(lookup, Strategy.HYBRID,
                backend("kw-id", FULL, List.of(), new StubStore("kw")),
                backend("vec-id", FULL, List.of(), new StubStore("vec")));

        List<RetrievalPlan.ChannelSpec> specs = plan.channels("default.sales");

        assertEquals(2, specs.size(), "同一个体的同一路不能被查两遍（否则重复召回、白烧两次超时）");
        assertEquals(List.of("default.sales", "default.sales"),
                specs.stream().map(RetrievalPlan.ChannelSpec::sourceId).toList(),
                "去重留最近的 depth（同数据命中时优先算近的），来源 id 也随之取近的那个域");
        assertEquals(List.of(0, 0), specs.stream().map(RetrievalPlan.ChannelSpec::depth).toList());
    }

    @Test
    @DisplayName("collection = 体冻结的索引名，locationId = backendId|collection|modality")
    void channels_carryCollectionAndLocation() {
        StubLookup lookup = new StubLookup().bind("default.sales", "kb-sales");
        RetrievalPlan plan = planWithLookup(lookup, Strategy.HYBRID,
                backend("kw-id", FULL, List.of(), new StubStore("kw")),
                backend("vec-id", FULL, List.of(), new StubStore("vec")));

        RetrievalPlan.ChannelSpec spec = plan.channels("default.sales").get(0);
        String index = KbIndexes.nameOfBody("kb-sales", "kb-sales");

        assertEquals(index, spec.collection(), "集合名来自体，不再来自域");
        assertEquals("vec-id|" + index + "|VECTOR", spec.locationId());
        assertEquals("default.sales", spec.sourceId(),
                "融合用的来源 id 仍是域（多个域共用一个体时，命中深度按各自距离算）");
        assertEquals(30, spec.topK(), "向量路 topK 取该路配置");
        assertNotNull(spec.vectorSearcher());
        assertNull(spec.keywordSearcher(), "向量规格不挂关键词检索器");

        RetrievalPlan.ChannelSpec kwSpec = plan.channels("default.sales").get(1);
        assertEquals(20, kwSpec.topK(), "关键词路 topK 取该路配置");
        assertNotNull(kwSpec.keywordSearcher());
        assertNull(kwSpec.vectorSearcher());
    }

    // ==================== 策略门控 ====================

    @Test
    @DisplayName("KEYWORD 策略：向量通道根本不开（不是开了不用）")
    void keywordStrategy_opensNoVectorChannel() {
        StubLookup lookup = new StubLookup()
                .bind("default", "kb-root")
                .bind("default.sales", "kb-sales");
        RetrievalPlan plan = planWithLookup(lookup, Strategy.KEYWORD,
                backend("kw-id", FULL, List.of(), new StubStore("kw")),
                backend("vec-id", FULL, List.of(), new StubStore("vec")));

        List<RetrievalPlan.ChannelSpec> specs = plan.channels("default.sales.eu");

        assertEquals(2, specs.size());
        assertTrue(specs.stream().allMatch(s -> s.modality() == Modality.KEYWORD));
        assertTrue(specs.stream().allMatch(s -> s.vectorSearcher() == null));
    }

    @Test
    @DisplayName("VECTOR 策略：关键词通道根本不开")
    void vectorStrategy_opensNoKeywordChannel() {
        StubLookup lookup = new StubLookup()
                .bind("default", "kb-root")
                .bind("default.sales", "kb-sales");
        RetrievalPlan plan = planWithLookup(lookup, Strategy.VECTOR,
                backend("kw-id", FULL, List.of(), new StubStore("kw")),
                backend("vec-id", FULL, List.of(), new StubStore("vec")));

        List<RetrievalPlan.ChannelSpec> specs = plan.channels("default.sales.eu");

        assertEquals(2, specs.size());
        assertTrue(specs.stream().allMatch(s -> s.modality() == Modality.VECTOR));
        assertTrue(specs.stream().allMatch(s -> s.keywordSearcher() == null));
    }

    // ==================== 未绑域通配 ====================

    @Test
    @DisplayName("域为 null（诊断 / 通配）：sourceId 与 collection 都用通配符，depth 0")
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
    @DisplayName("后端不提供存储能力：取值抛 60007（保存存储配置时的自检本该给过警告）")
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
