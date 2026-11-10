package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 添加模型改成「只手选输入 / 输出模态」之后的契约。
 *
 * <p>三件事必须钉住：</p>
 * <ul>
 *   <li>保存是纯登记：{@code metadataReader} 与真实的 {@code capabilityProbe} 都不给，
 *       只要还留着一次上游读取 / 探测就会 NPE 或发出真请求；</li>
 *   <li>端点族由模态本地反查得出，<b>对话模型永远不会变成向量</b> —— 这正是被修的那个 bug；
 *       一个模态都不选则端点族留空，档案落成「不可用」；</li>
 *   <li>卡片上唯一花 token 的「探测」是<b>整体覆盖</b>手选值，而批量补齐仍是并集，
 *       两条路不能混。</li>
 * </ul>
 */
class ModelProfileManualModalityTest {

    @TempDir
    Path tempDir;

    private ModelProfileRegistry registry;

    /** metadataReader / holder / domainRegistry / knowledgeBase 全传 null：save 与 detect 都不该碰它们 */
    private AdminModelProfileController controller(ModelCapabilityProbe probe) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        StorageLocations storage = new StorageLocations(env);
        ModelProfileStore store = new ModelProfileStore(storage);
        registry = new ModelProfileRegistry(store);
        return new AdminModelProfileController(registry, store, null, null,
                probe, null, null);
    }

    /** 模拟新弹窗：只提交手选模态，<b>刻意不 setEndpoints</b> */
    private static AdminModelProfileController.ProfileBody body(String alias,
                                                                List<String> input,
                                                                List<String> output) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias(alias);
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("model-" + alias);
        b.setInput(input);
        b.setOutput(output);
        return b;
    }

    private ModelProfile saved(String alias) {
        return registry.profile(alias).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> view(AdminModelProfileController c, String alias) {
        List<Map<String, Object>> profiles =
                (List<Map<String, Object>>) c.list().get("profiles");
        return profiles.stream().filter(p -> alias.equals(p.get("alias"))).findFirst().orElseThrow();
    }

    /** 固定的探测结论：替代会真发请求、真花 token 的探测器 */
    private static final class FakeProbe extends ModelCapabilityProbe {
        private final ProbeResult result;

        FakeProbe(ProbeResult result) { this.result = result; }

        @Override
        public ProbeResult probeCapabilities(String baseUrl, String apiKey, String modelName) {
            return result;
        }
    }

    private static ModelCapabilityProbe.ProbeResult probe(List<String> endpoints,
                                                          List<String> input,
                                                          List<String> output) {
        return new ModelCapabilityProbe.ProbeResult(endpoints, input, output, Map.of(), Map.of(), "测试桩结论");
    }

    // ==================== 保存：零上游 + 本地反查 ====================

    @Test
    @DisplayName("手选 text→text 反查出 chat：模态原样落盘、可绑域，全程零上游请求")
    void manualTextModalitiesDeriveChatAndAreBindable() {
        AdminModelProfileController c = controller(new ModelCapabilityProbe());

        c.save(body("chat-a", List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT)));

        ModelProfile p = saved("chat-a");
        assertEquals(List.of(ModelProfile.EP_CHAT), p.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT), p.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), p.output());
        assertNull(registry.bind("default.sales", List.of("chat-a")),
                "反查出端点族 + 模态齐备，这份档案应该当场就能绑域");
    }

    @Test
    @DisplayName("对话模型绝不会被反查成向量：既不带 embedding 族，也进不了向量槽")
    void chatDeclarationNeverBecomesEmbedding() {
        AdminModelProfileController c = controller(new ModelCapabilityProbe());

        c.save(body("chat-b", List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT)));

        ModelProfile p = saved("chat-b");
        assertFalse(p.isEmbedding());
        assertFalse(p.output().contains(ModelProfile.MOD_EMBEDDING));
        assertEquals(List.of(ModelProfile.EP_CHAT), p.endpoints());
        assertNotNull(registry.setEmbeddingAlias("chat-b"),
                "这正是原来那个 bug 的后果：对话档案被当成向量档案启用");
    }

    @Test
    @DisplayName("手选 text→向量 反查出 embedding：能进向量槽，不会被塞一个 chat")
    void vectorDeclarationDerivesEmbeddingOnly() {
        AdminModelProfileController c = controller(new ModelCapabilityProbe());

        c.save(body("vec", List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING)));

        ModelProfile p = saved("vec");
        assertEquals(List.of(ModelProfile.EP_EMBEDDING), p.endpoints());
        assertFalse(p.isChat());
        assertNull(registry.setEmbeddingAlias("vec"));
    }

    @Test
    @DisplayName("多模态手选 text+image→text 保留「可看图」信息，不被基线抹成只有 text")
    void visionDeclarationKeepsImageInput() {
        AdminModelProfileController c = controller(new ModelCapabilityProbe());

        c.save(body("vision", List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                List.of(ModelProfile.MOD_TEXT)));

        ModelProfile p = saved("vision");
        assertEquals(List.of(ModelProfile.EP_CHAT), p.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), p.input());
        assertTrue(p.domainBindable());
    }

    // ==================== 保存：什么都不选 ====================

    @Test
    @DisplayName("一个模态都不选：档案照存但标「不可用」，绑不了域也进不了向量槽")
    void noSelectionKeepsCardButMarksItUnusable() {
        AdminModelProfileController c = controller(new ModelCapabilityProbe());

        c.save(body("blank", List.of(), List.of()));

        ModelProfile p = saved("blank");
        assertTrue(p.endpoints().isEmpty());
        assertTrue(p.input().isEmpty());
        assertTrue(p.output().isEmpty());
        assertFalse(p.identified());
        assertFalse(p.domainBindable());
        Map<String, Object> v = view(c, "blank");
        assertEquals(Boolean.FALSE, v.get("declared"), "卡片上的「不可用」徽标读这个字段");
        assertEquals(Boolean.FALSE, v.get("bindable"));
        assertEquals(List.of(), v.get("bindableFamilies"));
        assertNotNull(registry.setEmbeddingAlias("blank"));
    }

    @Test
    @DisplayName("显式传 endpoints（含空数组）走老契约：不反查，模态仍原样保存")
    void explicitEndpointsSkipDerivation() {
        AdminModelProfileController c = controller(new ModelCapabilityProbe());
        AdminModelProfileController.ProfileBody b =
                body("script", List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT));
        b.setEndpoints(List.of());

        c.save(b);

        assertTrue(saved("script").endpoints().isEmpty(),
                "脚本显式声明「没有端点族」时不该被本地反查改写");
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved("script").input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved("script").output());
    }

    // ==================== 探测写回：默认只补不删，覆盖需显式选择 ====================

    @Test
    @DisplayName("默认探测只补不删：实测没确认到的旧模态必须留着")
    void detectMergesByDefaultAndKeepsOlderDeclarations() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(ModelProfile.EP_CHAT),
                        List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT))));

        c.save(body("mixed", List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_EMBEDDING)));
        assertEquals(List.of(ModelProfile.EP_CHAT, ModelProfile.EP_EMBEDDING),
                saved("mixed").endpoints(), "手选反查得到 chat + embedding");

        c.detect("mixed", "confirmed");

        ModelProfile after = saved("mixed");
        assertEquals(List.of(ModelProfile.EP_CHAT, ModelProfile.EP_EMBEDDING), after.endpoints(),
                "默认是并集：这次没确认到 embedding 不等于它不存在，旧声明必须留着");
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), after.input(),
                "手选的 image 输入不该因为一次没探到就被删");
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_EMBEDDING), after.output());
    }

    @Test
    @DisplayName("显式选覆盖时，实测结论整体替换手选模态，误标的向量族一并被换掉")
    void detectOverridesManualModalities() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(ModelProfile.EP_CHAT),
                        List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT))));

        /* 用户手选了一个看着像「既能对话又产向量」的组合：反查会得到 chat + embedding */
        c.save(body("mixed", List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_EMBEDDING)));
        assertEquals(List.of(ModelProfile.EP_CHAT, ModelProfile.EP_EMBEDDING),
                saved("mixed").endpoints());

        c.detect("mixed", "override");

        ModelProfile after = saved("mixed");
        assertEquals(List.of(ModelProfile.EP_CHAT), after.endpoints(),
                "显式选了覆盖：实测没确认到的端点族不该留着");
        assertEquals(List.of(ModelProfile.MOD_TEXT), after.input(),
                "实测只有 text 输入，手选的 image 被覆盖");
        assertEquals(List.of(ModelProfile.MOD_TEXT), after.output(),
                "实测只有 text 输出，手选的 embedding 被覆盖");
    }

    @Test
    @DisplayName("覆盖模式下部分确认也算结论：没确认到输入侧就清空，旧的模态不能靠『没读到』续命")
    void partialDetectionStillReplacesBothSides() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(ModelProfile.EP_IMAGES), List.of(), List.of(ModelProfile.MOD_IMAGE))));

        c.save(body("img", List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_AUDIO),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_SCORE, ModelProfile.MOD_IMAGE)));
        assertEquals(List.of(ModelProfile.EP_CHAT, ModelProfile.EP_RERANK,
                        ModelProfile.EP_IMAGES, ModelProfile.EP_ASR), saved("img").endpoints(),
                "手选多模态被反查成四个端点族 —— 正是要被探测纠正的那份旧声明");

        c.detect("img", "override");

        ModelProfile after = saved("img");
        assertEquals(List.of(ModelProfile.EP_IMAGES), after.endpoints());
        assertTrue(after.input().isEmpty(),
                "实测没确认到任何输入模态：空表也是结论，不能让手选的 text / audio 留着");
        assertEquals(List.of(ModelProfile.MOD_IMAGE), after.output(),
                "旧的 text / score 输出一并被覆盖掉");
    }

    @Test
    @DisplayName("探测一条都没确认到时保留手选值：网络抖动不能把一份能用的档案清空")
    void failedDetectionDoesNotWipeManualDeclaration() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(), List.of(), List.of())));

        c.save(body("flaky", List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                List.of(ModelProfile.MOD_TEXT)));

        c.detect("flaky", "confirmed");

        ModelProfile after = saved("flaky");
        assertEquals(List.of(ModelProfile.EP_CHAT), after.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), after.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), after.output());
    }

    @Test
    @DisplayName("当前向量槽档案不参与覆盖：探测不能把它改成不再声明 embedding")
    void currentEmbeddingProfileIsNotOverriddenByDetection() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(ModelProfile.EP_CHAT),
                        List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT))));

        c.save(body("vec", List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING)));
        assertNull(registry.setEmbeddingAlias("vec"));

        c.detect("vec", "override");

        assertTrue(saved("vec").isEmbedding(),
                "槽位还指着它；撤掉 embedding 会让知识库用一个用不了的档案");
    }

    // ==================== 手动移除能力（纠正误标） ====================

    private static AdminModelProfileController.FamiliesBody families(String alias, String... items) {
        AdminModelProfileController.FamiliesBody b = new AdminModelProfileController.FamiliesBody();
        b.setAlias(alias);
        b.setFamilies(List.of(items));
        return b;
    }

    @Test
    @DisplayName("手动移除向量能力：只清该族与它的专属输出，其它字段不动")
    void removeEmbeddingFamily() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(), List.of(), List.of())));

        c.save(body("dual", List.of(ModelProfile.MOD_TEXT),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_EMBEDDING)));
        assertTrue(saved("dual").isEmbedding());

        c.removeFamilies(families("dual", ModelProfile.EP_EMBEDDING));

        ModelProfile after = saved("dual");
        assertFalse(after.endpoints().contains(ModelProfile.EP_EMBEDDING), "向量族应被移除");
        assertFalse(after.output().contains(ModelProfile.MOD_EMBEDDING), "它的专属输出要一并清掉");
        assertTrue(after.endpoints().contains(ModelProfile.EP_CHAT), "chat 不能被连坐");
        assertEquals(List.of(ModelProfile.MOD_TEXT), after.input(), "输入模态不动");
        assertEquals("sk-test", after.apiKey(), "连接信息 / Key 一律不变");
        assertEquals("model-dual", after.modelName());
    }

    @Test
    @DisplayName("移除 chat 时保留 text 输出（asr 也产出文本），但媒体输入随之清掉")
    void removeChatKeepsSharedTextModality() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(), List.of(), List.of())));

        AdminModelProfileController.ProfileBody b =
                body("multi", List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_AUDIO),
                        List.of(ModelProfile.MOD_TEXT));
        b.setEndpoints(List.of(ModelProfile.EP_CHAT, ModelProfile.EP_ASR));
        c.save(b);

        c.removeFamilies(families("multi", ModelProfile.EP_CHAT));

        ModelProfile after = saved("multi");
        assertFalse(after.endpoints().contains(ModelProfile.EP_CHAT));
        assertEquals(List.of(ModelProfile.EP_ASR), after.endpoints(), "asr 不能被连坐");
        assertEquals(List.of(ModelProfile.MOD_TEXT), after.input(),
                "音频输入只走对话端点，撤了 chat 就该清掉；text 是 asr 也收的，保留");
        assertEquals(List.of(ModelProfile.MOD_TEXT), after.output(),
                "asr 产出文本，text 输出不能跟着 chat 一起消失");
    }

    @Test
    @DisplayName("移除唯一的 chat 后模态一并清空：卡片不该还挂着做不到的能力")
    void removeOnlyChatClearsModalities() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(), List.of(), List.of())));

        AdminModelProfileController.ProfileBody b =
                body("solo", List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE,
                        ModelProfile.MOD_AUDIO), List.of(ModelProfile.MOD_TEXT));
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));
        c.save(b);

        c.removeFamilies(families("solo", ModelProfile.EP_CHAT));

        ModelProfile after = saved("solo");
        assertTrue(after.endpoints().isEmpty(), "chat 已移除");
        assertTrue(after.input().isEmpty(),
                "没有端点族，输入模态就没有承载者：" + after.input());
        assertTrue(after.output().isEmpty(), "输出同理：" + after.output());
    }

    @Test
    @DisplayName("当前向量档案不许手动移除向量能力：换向量身份要走槽位切换")
    void currentEmbeddingProfileCannotDropEmbedding() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(), List.of(), List.of())));

        c.save(body("vec", List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING)));
        assertNull(registry.setEmbeddingAlias("vec"));

        BaseException e = assertThrows(BaseException.class,
                () -> c.removeFamilies(families("vec", ModelProfile.EP_EMBEDDING)));
        assertTrue(e.getMessage().contains("向量"), "要说清为什么不让删：" + e.getMessage());
        assertTrue(saved("vec").isEmbedding(), "拒绝之后档案必须原样");
    }

    @Test
    @DisplayName("未知端点族被拒，别静默当成空操作")
    void unknownFamilyIsRejected() {
        AdminModelProfileController c = controller(new FakeProbe(
                probe(List.of(), List.of(), List.of())));
        c.save(body("m", List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT)));

        BaseException e = assertThrows(BaseException.class,
                () -> c.removeFamilies(families("m", "nope")));
        assertTrue(e.getMessage().contains("未知"), e.getMessage());
    }
}
