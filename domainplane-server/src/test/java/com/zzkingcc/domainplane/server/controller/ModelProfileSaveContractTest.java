package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.config.AiProperties;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
import com.zzkingcc.domainplane.server.model.ModelMetadataReader;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import com.zzkingcc.domainplane.server.model.VectorIdentity;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import com.zzkingcc.domainplane.server.settings.LlmSettingsStore;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** 供"落盘往返"用例重建一个 registry：验证新字段真的写进了 models.json 并能读回来 */
    private ModelProfileStore lastStore;

    /** metadataReader 传 null 是刻意的：save 一次都不该碰它 */
    private AdminModelProfileController controller(ModelMetadataReader metadataReader) {
        return build(metadataReader, null);
    }

    /** 只有「换向量模型要二次确认」这组用例才需要装一个带内容的知识库桩 */
    private AdminModelProfileController build(ModelMetadataReader metadataReader,
                                              KnowledgeBaseService knowledgeBase) {
        return build(metadataReader, knowledgeBase, false);
    }

    /**
     * @param withHolder 需要走到"切换真的执行"的用例才装真实 holder。
     *                   传 false 时 holder 为 null：那些用例一旦越过二次确认就会 NPE ——
     *                   这比"mock 一个 holder 再断言没被调用"更硬，连对象都不给。
     */
    private AdminModelProfileController build(ModelMetadataReader metadataReader,
                                              KnowledgeBaseService knowledgeBase,
                                              boolean withHolder) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        StorageLocations storage = new StorageLocations(env);
        ModelProfileStore store = new ModelProfileStore(storage);
        ModelProfileRegistry registry = new ModelProfileRegistry(store);
        lastRegistry = registry;
        lastStore = store;
        /* 无配置文件时 reload 只会记一条 INFO 然后三个实例留空，不发任何上游请求 */
        LlmModelHolder holder = withHolder
                ? new LlmModelHolder(new AiProperties(), new LlmSettingsStore(storage), registry)
                : null;
        return new AdminModelProfileController(registry, store, holder, metadataReader,
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

    // ==================== 换的是档案，还是模型？ ====================

    /** 一个固定的向量服务商地址：同一模型 / 换模型两种情形都从它出发 */
    private static final String VEC_URL = "https://api.siliconflow.cn/v1";

    /**
     * 有内容的知识库桩：只要有人动索引就直接抛。
     *
     * <p>比"事后断言返回值为空"更硬 —— 断言写漏了还能悄悄过，抛是躲不掉的。</p>
     */
    private static KnowledgeBaseService contentKb() {
        RagProperties rag = new RagProperties();
        return new KnowledgeBaseService(null, rag, null, null, null, null, null, null) {
            @Override public List<String> existingIndices() { return List.of("kb_test"); }
            @Override public long totalIndexedDocuments() { return 3L; }
            @Override public Map<String, Integer> rebuildPreservingText(int dims) {
                throw new AssertionError("这次切换不该重灌知识库（dims=" + dims + "）");
            }
            @Override public List<String> purgeAll() {
                throw new AssertionError("这次切换不该清空知识库");
            }
        };
    }

    private static AdminModelProfileController.ProfileBody vecBody(String alias, String modelName,
                                                                   Integer dimensions) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias(alias);
        b.setApiKey("sk-test");
        b.setBaseUrl(VEC_URL);
        b.setModelName(modelName);
        b.setDimensions(dimensions);
        b.setEndpoints(List.of(ModelProfile.EP_EMBEDDING));
        return b;
    }

    private static AdminModelProfileController.EmbeddingBody embeddingRequest(String alias, String mode) {
        AdminModelProfileController.EmbeddingBody b = new AdminModelProfileController.EmbeddingBody();
        b.setAlias(alias);
        b.setMode(mode);
        return b;
    }

    @Test
    @DisplayName("删了重建同一个模型（别名被加 -2 后缀）：不弹确认、不重灌，向量原样复用")
    void sameModelUnderANewAliasReusesVectors() {
        AdminModelProfileController c = build(null, contentKb(), true);
        c.save(vecBody("bge", "BAAI/bge-m3", 1024));
        assertNull(lastRegistry.setEmbeddingAlias("bge"));
        /* 前端 deriveAlias 遇重名会加 -2：删掉 bge 再添加同一个模型，拿到的就是 bge-2。
           旧判据（比别名）在这里会把一次零变更的操作判成换模型，逼用户全库重灌。 */
        c.save(vecBody("bge-2", "BAAI/bge-m3", 1024));

        Map<String, Object> out = c.selectEmbeddingModel(embeddingRequest("bge-2", null));

        assertFalse(out.containsKey("requiresConfirmation"),
                "地址 / 模型名 / 维度都没变，只是换了个档案名：不该让用户面对重灌确认框");
        assertEquals("bge-2", lastRegistry.embeddingAlias(), "档案本身确实换过去了");
        assertEquals(Boolean.TRUE, out.get("vectorsReused"), "前端据此确认索引被复用，不会自己去点重灌");
        assertNull(out.get("reindexed"), "没有重灌");
        assertNull(out.get("removedIndices"), "也没有清空");
        String message = String.valueOf(out.get("message"));
        assertTrue(message.contains("不需要重灌"),
                "必须明确交代「没弹框是因为不用重灌」，否则用户会怀疑漏了一步、然后手动补一次全量 embed：" + message);
    }

    @Test
    @DisplayName("换了模型名（维度相同）仍要二次确认：判据收窄不能把真保护一起放掉")
    void differentModelStillRequiresConfirmation() {
        AdminModelProfileController c = build(null, contentKb(), false);
        c.save(vecBody("bge", "BAAI/bge-m3", 1024));
        assertNull(lastRegistry.setEmbeddingAlias("bge"));
        c.save(vecBody("bce", "netease-youdao/bce-embedding-base_v1", 1024));

        Map<String, Object> ask = c.selectEmbeddingModel(embeddingRequest("bce", null));

        assertEquals(Boolean.TRUE, ask.get("requiresConfirmation"),
                "维度一样不代表向量可比：这是最容易靠直觉放过的一种错配");
        assertEquals("bge", lastRegistry.embeddingAlias(), "没拿到用户表态前不能留下已切换");
    }

    @Test
    @DisplayName("当前向量档案不能从「保存」改掉模型名：这条路没有重灌确认，只能拒")
    void editingVectorIdentityThroughSaveIsRejected() {
        AdminModelProfileController c = controller(null);
        c.save(vecBody("vec", "BAAI/bge-m3", 1024));
        assertNull(lastRegistry.setEmbeddingAlias("vec"));

        BaseException e = assertThrows(BaseException.class,
                () -> c.save(vecBody("vec", "netease-youdao/bce-embedding-base_v1", 1024)));

        assertTrue(e.getMessage().contains("新建一份档案"),
                "拒绝的同时必须给出正确路径，否则用户只会觉得这条路被封死了：" + e.getMessage());
        assertEquals("BAAI/bge-m3", lastRegistry.profile("vec").orElseThrow().modelName(),
                "被拒的保存不能已经把新模型名落盘");
    }

    @Test
    @DisplayName("换一把 Key 不改变向量：当前向量档案照常保存，不该被那道门禁拦住")
    void rotatingApiKeyOnCurrentVectorProfileIsAllowed() {
        AdminModelProfileController c = controller(null);
        c.save(vecBody("vec", "BAAI/bge-m3", 1024));
        assertNull(lastRegistry.setEmbeddingAlias("vec"));

        AdminModelProfileController.ProfileBody rotated = vecBody("vec", "BAAI/bge-m3", 1024);
        rotated.setApiKey("sk-rotated");
        c.save(rotated);

        assertEquals("sk-rotated", lastRegistry.profile("vec").orElseThrow().apiKey(),
                "Key 不属于向量身份：换 Key 的诉求是连接层排障，不是换模型");
    }

    // ==================== 删了重建：槽位被清空之后还认得出来吗 ====================

    @Test
    @DisplayName("删掉向量档案再建一份同样的模型选上：不弹确认（删档案不动索引，身份被记着）")
    void deleteThenRecreateSameModelDoesNotAskAgain() {
        AdminModelProfileController c = build(null, contentKb(), true);
        c.save(vecBody("bge", "BAAI/bge-m3", 1024));
        assertNull(lastRegistry.setEmbeddingAlias("bge"));
        /* 正常由 selectEmbeddingModel 的成功路径写入；这里直接预置，把用例聚焦在"删除之后" */
        lastRegistry.markEmbeddingIdentity(VectorIdentity.of(lastRegistry.profile("bge").orElseThrow()));

        assertTrue(lastRegistry.delete("bge").deleted(), "删档案会级联清空槽位 —— 这是身份的用武之地");
        assertNull(lastRegistry.embeddingAlias());
        c.save(vecBody("bge-2", "BAAI/bge-m3", 1024));

        Map<String, Object> out = c.selectEmbeddingModel(embeddingRequest("bge-2", null));

        assertFalse(out.containsKey("requiresConfirmation"),
                "槽位被清空过也应该认得出来：身份与档案增删无关，只与索引里那批向量由谁算的有关");
        assertEquals(Boolean.TRUE, out.get("vectorsReused"));
    }

    @Test
    @DisplayName("删了重建但换成了别的模型：仍然弹确认 —— 记录不能把真变更一起吞掉")
    void deleteThenRecreateDifferentModelStillAsks() {
        AdminModelProfileController c = build(null, contentKb(), false);
        c.save(vecBody("bge", "BAAI/bge-m3", 1024));
        assertNull(lastRegistry.setEmbeddingAlias("bge"));
        lastRegistry.markEmbeddingIdentity(VectorIdentity.of(lastRegistry.profile("bge").orElseThrow()));

        assertTrue(lastRegistry.delete("bge").deleted());
        c.save(vecBody("bce", "netease-youdao/bce-embedding-base_v1", 1024));

        Map<String, Object> ask = c.selectEmbeddingModel(embeddingRequest("bce", null));

        assertEquals(Boolean.TRUE, ask.get("requiresConfirmation"),
                "维度相同也不行：换模型就是换向量空间");
    }

    @Test
    @DisplayName("向量身份要写进 models.json：copyOf 漏字段会让它每次保存都凭空消失")
    void embeddingIdentitySurvivesPersistAndReload() {
        AdminModelProfileController c = controller(null);
        c.save(vecBody("vec", "BAAI/bge-m3", 1024));
        assertNull(lastRegistry.setEmbeddingAlias("vec"));
        lastRegistry.markEmbeddingIdentity(VectorIdentity.of(lastRegistry.profile("vec").orElseThrow()));

        /* 再存一份别的档案：走的是 copyOf → 漏拷就会把 identity 抹成 null */
        c.save(vecBody("other", "BAAI/bge-m3", 1024));
        assertEquals(Integer.valueOf(1024), lastRegistry.embeddingIdentity().dimensions());

        /* 换一个 registry 从同一份文件读回来：验证 record 能被 Jackson 反序列化 */
        ModelProfileRegistry reloaded = new ModelProfileRegistry(lastStore);
        assertEquals(VectorIdentity.of(lastRegistry.profile("vec").orElseThrow()),
                reloaded.embeddingIdentity(),
                "这个字段丢了不会有任何报错，只会表现为「删了重建又要重灌一次」，查起来毫无头绪");
    }

    @Test
    @DisplayName("重复点选当前档案不补记身份：老配置的库可能是别的模型算的，别一次误点就抹平疑问")
    void reselectingSameAliasDoesNotRecordIdentity() {
        AdminModelProfileController c = build(null, null, true);
        c.save(vecBody("vec", "BAAI/bge-m3", 1024));
        /* 直接写槽位 = 模拟老配置：有选中的档案，但没有 embeddingIdentity 这段记录 */
        assertNull(lastRegistry.setEmbeddingAlias("vec"));
        assertNull(lastRegistry.embeddingIdentity());

        c.selectEmbeddingModel(embeddingRequest("vec", null));

        assertNull(lastRegistry.embeddingIdentity(),
                "别名没变、身份也没变，没有发生任何切换：这时补记等于凭空断言"
                        + "「库里那批向量就是这个模型算的」，而老配置恰恰无法确认这一点");
    }
}
