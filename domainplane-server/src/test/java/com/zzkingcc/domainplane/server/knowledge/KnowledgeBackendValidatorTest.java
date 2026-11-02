package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
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
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略门控矩阵：strategy(keyword/vector/hybrid) × capabilities，不满足<b>启动失败</b>。
 *
 * <p>这套校验的全部价值在于"配错了"与"知识库是空的"不能长得一样：运行期空结果会让
 * 用户反复换问法，而启动失败会把运维直接带到 yaml 出错的那一行。测试因此钉三类事：
 * 矩阵本身（哪种策略要哪种能力）、错误码与文案（点名后端与能力，可定位）、
 * fail-fast 的完整形态（两个后端 id 都解析，typo 在任何策略下都启动即炸）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("知识库装配校验：策略门控矩阵 + 启动失败语义")
class KnowledgeBackendValidatorTest {

    private static final StoreCapabilities FULL =
            new StoreCapabilities(true, true, true, true, true);
    private static final StoreCapabilities KEYWORD_ONLY =
            new StoreCapabilities(true, false, true, true, true);
    private static final StoreCapabilities VECTOR_ONLY =
            new StoreCapabilities(false, true, false, true, true);

    private static final KeywordSearcher KW = (collection, queryText, topK) -> List.of();
    private static final VectorSearcher VEC = (collection, queryVector, topK) -> List.of();

    private static final EmbeddingModel MODEL = new EmbeddingModel() {
        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return new Response<>(new Embedding(new float[4]));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return new Response<>(segments.stream()
                    .map(s -> (Embedding) new Embedding(new float[4])).toList());
        }

        @Override
        public int dimension() {
            return 4;
        }
    };

    private static KnowledgeBackend backend(String id, StoreCapabilities caps) {
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
                return Optional.of(new StubStore(id));
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

    private static RetrievalPlan plan(Strategy strategy, KnowledgeBackendRegistry registry) {
        return new RetrievalPlan(strategy, registry, "kw-id", 20, "vec-id", 30);
    }

    private static KnowledgeBackendValidator validator(Strategy strategy,
                                                       KnowledgeBackendRegistry registry,
                                                       EmbeddingModel model) {
        return new KnowledgeBackendValidator(plan(strategy, registry), model);
    }

    private static KnowledgeBaseException expectFailure(KnowledgeBackendValidator v) {
        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class, v::validate);
        assertEquals(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED, e.getErrorCode());
        assertEquals(60007, e.getCode(), "装配错误必须是 60007（部署配置错误），不是运行时错误码");
        return e;
    }

    // ==================== 矩阵本体 ====================

    @Test
    @DisplayName("backends 为空：第一条就炸，文案指明至少配一个后端")
    void emptyBackends_fails() {
        KnowledgeBaseException e = expectFailure(validator(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of()), MODEL));
        assertTrue(e.getMessage().contains("backends"), "文案要能定位到配置段：" + e.getMessage());
    }

    @Test
    @DisplayName("keyword 策略 + keyword-only 后端：通过（不需要 EmbeddingModel）")
    void keywordStrategy_keywordOnlyBackend_passes() {
        KnowledgeBackendValidator v = validator(Strategy.KEYWORD,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", KEYWORD_ONLY), backend("vec-id", VECTOR_ONLY))),
                null);
        v.validate(); // 不抛即通过
    }

    @Test
    @DisplayName("keyword 策略但 keyword 后端只声明向量能力：炸，点名后端与缺失能力")
    void keywordStrategy_vectorOnlyKeywordBackend_fails() {
        KnowledgeBaseException e = expectFailure(validator(Strategy.KEYWORD,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", VECTOR_ONLY), backend("vec-id", VECTOR_ONLY))),
                null));
        assertTrue(e.getMessage().contains("kw-id"), "要点名是哪个后端：" + e.getMessage());
        assertTrue(e.getMessage().contains("keyword"), "要点名缺哪种能力：" + e.getMessage());
        assertTrue(e.getMessage().contains("keyword=false"), "能力声明值要进文案：" + e.getMessage());
    }

    @Test
    @DisplayName("vector 策略 + 向量后端但无 EmbeddingModel：炸（查询向量无处可算）")
    void vectorStrategy_missingEmbeddingModel_fails() {
        KnowledgeBaseException e = expectFailure(validator(Strategy.VECTOR,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", KEYWORD_ONLY), backend("vec-id", VECTOR_ONLY))),
                null));
        assertTrue(e.getMessage().contains("EmbeddingModel"), e.getMessage());
    }

    @Test
    @DisplayName("vector 策略 + 向量后端 + EmbeddingModel：通过")
    void vectorStrategy_complete_passes() {
        validator(Strategy.VECTOR, new MapKnowledgeBackendRegistry(List.of(
                backend("kw-id", KEYWORD_ONLY), backend("vec-id", VECTOR_ONLY))), MODEL)
                .validate();
    }

    @Test
    @DisplayName("hybrid 策略缺任一路能力即炸（keyword 侧缺 → 建议降级 vector）")
    void hybridStrategy_missingKeywordCapability_fails() {
        KnowledgeBaseException e = expectFailure(validator(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", VECTOR_ONLY), backend("vec-id", VECTOR_ONLY))),
                MODEL));
        assertTrue(e.getMessage().contains("HYBRID"), e.getMessage());
        assertTrue(e.getMessage().contains("kw-id"), e.getMessage());
    }

    @Test
    @DisplayName("hybrid 策略缺向量能力同样炸（文案建议降级 keyword）")
    void hybridStrategy_missingVectorCapability_fails() {
        KnowledgeBaseException e = expectFailure(validator(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(
                        backend("kw-id", KEYWORD_ONLY), backend("vec-id", KEYWORD_ONLY))),
                MODEL));
        assertTrue(e.getMessage().contains("vec-id"), e.getMessage());
        assertTrue(e.getMessage().contains("vector"), e.getMessage());
    }

    @Test
    @DisplayName("hybrid 策略全齐 + 模型在位：通过")
    void hybridStrategy_complete_passes() {
        validator(Strategy.HYBRID, new MapKnowledgeBackendRegistry(List.of(
                backend("kw-id", KEYWORD_ONLY), backend("vec-id", VECTOR_ONLY))), MODEL)
                .validate();
    }

    // ==================== fail-fast：id 拼错必须启动即炸 ====================

    @Test
    @DisplayName("keyword-backend 指向未注册 id：炸，文案带出拼错的 id")
    void typoKeywordBackendId_fails() {
        KnowledgeBaseException e = expectFailure(validator(Strategy.HYBRID,
                new MapKnowledgeBackendRegistry(List.of(backend("vec-id", VECTOR_ONLY))), MODEL));
        assertTrue(e.getMessage().contains("kw-id"), "typo 的 id 必须出现在文案里：" + e.getMessage());
        assertTrue(e.getMessage().contains("未注册"), e.getMessage());
    }

    @Test
    @DisplayName("keyword 策略下 vector-backend 拼错同样炸（两个 id 都解析，不留到运行期）")
    void typoVectorBackendId_failsEvenUnderKeywordStrategy() {
        RetrievalPlan p = new RetrievalPlan(Strategy.KEYWORD,
                new MapKnowledgeBackendRegistry(List.of(backend("kw-id", KEYWORD_ONLY))),
                "kw-id", 20, "vec-typo", 30);
        KnowledgeBaseException e = expectFailure(new KnowledgeBackendValidator(p, null));
        assertTrue(e.getMessage().contains("vec-typo"), e.getMessage());
    }

    // ==================== 桩 ====================

    private static final class StubStore implements KnowledgeStore {

        private final String name;

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
