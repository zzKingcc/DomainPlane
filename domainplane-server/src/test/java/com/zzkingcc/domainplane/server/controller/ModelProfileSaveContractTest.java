package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
import com.zzkingcc.domainplane.server.model.ModelMetadataReader;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保存路径的新契约：零上游请求、能力由用户声明、模态按族补齐。
 *
 * <p>构造控制器时 {@code metadataReader} 传 null：save 里只要还留着一次元数据读取，
 * 这里就会 NPE。这条比"mock 一个 reader 再断言没被调用"更硬 —— 连对象都不给。</p>
 *
 * <p>第二组用例盯住最容易静默失效的一处：撤掉自动识别后，
 * {@code ModelProfileRegistry#validateForFamily} 仍要求端点族已知且输入 / 输出模态非空。
 * 只删识别、不补基线模态，用户会看到"保存成功"，然后到域空间页绑不了域。</p>
 */
class ModelProfileSaveContractTest {

    @TempDir
    Path tempDir;

    private ModelProfileRegistry lastRegistry;

    /** metadataReader 传 null 是刻意的：save 一次都不该碰它 */
    private AdminModelProfileController controller(ModelMetadataReader metadataReader) {
        return build(metadataReader, null);
    }

    /** 只有「换向量模型要二次确认」这组用例才需要装一个带内容的知识库桩 */
    private AdminModelProfileController build(ModelMetadataReader metadataReader,
                                              KnowledgeBaseService knowledgeBase) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ModelProfileStore store = new ModelProfileStore(new StorageLocations(env));
        ModelProfileRegistry registry = new ModelProfileRegistry(store);
        lastRegistry = registry;
        return new AdminModelProfileController(registry, store, null, metadataReader,
                new ModelAdapterRegistry(new DefaultResourceLoader()),
                new ModelCapabilityProbe(), null, knowledgeBase);
    }

    private static AdminModelProfileController.ProfileBody body(String alias, List<String> endpoints) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias(alias);
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("model-" + alias);
        b.setEndpoints(endpoints);
        return b;
    }

    private ModelProfile saved(String alias) {
        return lastRegistry.profile(alias).orElseThrow();
    }

    // ==================== 零上游请求 ====================

    @Test
    @DisplayName("没勾用途也能保存：整条 save 路径零上游请求，不再造「未声明类型」的失败状态")
    void saveWithoutDeclaredEndpointsNeverTouchesUpstream() {
        AdminModelProfileController c = controller(null);

        assertNull(c.save(body("plain", null)).get("note"),
                "保存不再返回「类型没读到」的原因：类型由用户决定，不是读出来的");

        ModelProfile p = saved("plain");
        assertTrue(p.endpoints().isEmpty(), "没勾就是没声明，原样落盘");
        assertTrue(p.input().isEmpty());
        assertTrue(p.output().isEmpty());
        assertEquals("model-plain", p.modelName());
    }

    @Test
    @DisplayName("勾了 embedding 也不再测量维度：维度留空由装配期实测，保存不拒绝")
    void embeddingSaveDoesNotMeasureDimension() {
        AdminModelProfileController c = controller(null);

        c.save(body("vec", List.of(ModelProfile.EP_EMBEDDING)));

        assertEquals(null, saved("vec").dimensions(),
                "保存路径不再回填原生维度；空维度由 LlmModelHolder 装配时实测");
        assertNull(lastRegistry.setEmbeddingAlias("vec"), "声明了 embedding 就能进向量槽位");
    }

    // ==================== 基线模态 ====================

    @Test
    @DisplayName("勾了 chat 就补 text→text，否则新档案绑不了域")
    void declaredChatGetsBaselineModalitiesAndIsBindable() {
        AdminModelProfileController c = controller(null);

        c.save(body("chat-a", List.of(ModelProfile.EP_CHAT)));

        ModelProfile p = saved("chat-a");
        assertEquals(List.of(ModelProfile.MOD_TEXT), p.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), p.output());
        assertNull(lastRegistry.bind("default.sales", List.of("chat-a")),
                "模态补齐后应当能绑域；漏补的话校验会以「模态未知」拒绝，而保存却报成功");
        assertEquals(List.of("chat-a"), lastRegistry.resolve("default.sales").aliases());
    }

    @Test
    @DisplayName("多选取模态并集：chat + embedding 同时可绑域、可进向量槽")
    void multipleFamiliesMergeTheirBaselineModalities() {
        AdminModelProfileController c = controller(null);

        c.save(body("dual", List.of(ModelProfile.EP_CHAT, ModelProfile.EP_EMBEDDING)));

        ModelProfile p = saved("dual");
        assertEquals(List.of(ModelProfile.MOD_TEXT), p.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_EMBEDDING), p.output());
        assertNull(lastRegistry.bind("default.sales", List.of("dual")));
        assertNull(lastRegistry.setEmbeddingAlias("dual"));
    }

    @Test
    @DisplayName("非 text 输入 / 输出的族也各有基线：asr 是 audio→text，rerank 输出 score")
    void asymmetricFamiliesGetCorrectBaselines() {
        AdminModelProfileController c = controller(null);

        c.save(body("asr", List.of(ModelProfile.EP_ASR)));
        c.save(body("rr", List.of(ModelProfile.EP_RERANK)));

        assertEquals(List.of(ModelProfile.MOD_AUDIO), saved("asr").input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved("asr").output());
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved("rr").input());
        assertEquals(List.of(ModelProfile.MOD_SCORE), saved("rr").output());
    }

    @Test
    @DisplayName("脚本显式传的模态不被基线覆盖")
    void explicitModalitiesArePreserved() {
        AdminModelProfileController c = controller(null);
        AdminModelProfileController.ProfileBody b = body("vision", List.of(ModelProfile.EP_CHAT));
        b.setInput(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE));
        b.setOutput(List.of(ModelProfile.MOD_TEXT));

        c.save(b);

        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), saved("vision").input(),
                "用户 / 集成方手填的更准，不能被一次本地映射抹成只有 text");
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved("vision").output());
    }

    // ==================== 按需识别 ====================

    @Test
    @DisplayName("识别按钮命中本地适配时零上游请求：结果只用来预勾，不参与保存")
    void suggestUsesLocalDeclarationWithoutUpstream() {
        AdminModelProfileController c = controller(null);
        AdminModelProfileController.ProfileBody b = body("bge", null);
        b.setBaseUrl("https://api.siliconflow.cn/v1");
        b.setModelName("BAAI/bge-m3");

        Map<String, Object> out = c.suggest(b);

        assertEquals("adapter", out.get("source"));
        assertEquals(List.of(ModelProfile.EP_EMBEDDING), out.get("endpoints"));
        assertEquals(List.of(ModelProfile.MOD_TEXT), out.get("input"));
        assertEquals(List.of(ModelProfile.MOD_EMBEDDING), out.get("output"));
        assertFalse(lastRegistry.allProfiles().stream().anyMatch(p -> p.alias().equals("bge")),
                "suggest 是只读建议，不该顺手写一份档案");
    }

    // ==================== 列表契约 ====================

    @Test
    @DisplayName("list 只回一张全量表 + assignments，不再下发第二份向量清单")
    void listReturnsOneTableAndSlotAssignment() {
        AdminModelProfileController c = controller(null);
        c.save(body("chat-a", List.of(ModelProfile.EP_CHAT)));
        c.save(body("vec", List.of(ModelProfile.EP_EMBEDDING)));
        lastRegistry.setEmbeddingAlias("vec");

        Map<String, Object> out = c.list();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) out.get("profiles");
        @SuppressWarnings("unchecked")
        Map<String, Object> assignments = (Map<String, Object>) out.get("assignments");

        assertEquals(2, profiles.size(), "向量档案与对话档案在同一张表里，不再分成两段");
        assertFalse(out.containsKey("embeddingProfiles"), "第二份清单撤掉，前端不用再去重");
        assertEquals("vec", assignments.get("embedding"));
        assertEquals("vec", out.get("embeddingAlias"), "旧字段保留给预览桩与脚本");
    }

    // ==================== 向量槽二次确认 ====================

    @Test
    @DisplayName("换向量模型要二次确认时返回成功信封：code=0，否则前端会把它当失败")
    void embeddingSwitchConfirmationIsNotAnErrorEnvelope() {
        RagProperties rag = new RagProperties();
        KnowledgeBaseService kb = new KnowledgeBaseService(null, rag, null, null, null, null, null, null) {
            @Override public List<String> existingIndices() { return List.of("kb_test"); }
            @Override public long totalIndexedDocuments() { return 3L; }
        };
        AdminModelProfileController c = build(null, kb);
        c.save(body("old-vec", List.of(ModelProfile.EP_EMBEDDING)));
        c.save(body("new-vec", List.of(ModelProfile.EP_EMBEDDING)));
        assertNull(lastRegistry.setEmbeddingAlias("old-vec"));

        AdminModelProfileController.EmbeddingBody req = new AdminModelProfileController.EmbeddingBody();
        req.setAlias("new-vec");
        Map<String, Object> ask = c.selectEmbeddingModel(req);

        assertEquals(Integer.valueOf(0), ask.get("code"),
                "确认是「成功但要再表态」：非零 code 会被前端 normalize 判失败并 reject，"
                        + "requiresConfirmation 分支根本走不到");
        assertEquals(Boolean.TRUE, ask.get("success"));
        assertEquals(Boolean.TRUE, ask.get("requiresConfirmation"));
        assertEquals("old-vec", lastRegistry.embeddingAlias(),
                "没拿到用户表态前不能留下「已切换」状态");
    }
}
