package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「端点族未声明」= 未识别：既绑不了域、也解析不出模型，必须先「探测」补齐类型与模态。
 *
 * 背景：元数据读不出类型时（标准 OpenAI 规范的服务商，OpenAI / DeepSeek 官方都是）档案停在
 * {@code endpoints == []}。旧实现把这种档案按「可能对话」放行，未识别也能绑域：于是绑上去的
 * 模型是什么类型、能吃什么全然不知，下游（多模态 / 向量）行为不可预测。现收紧为：
 *
 *   • 绑域门禁 = {@link ModelProfile#domainBindable()}（类型已知 + 模态已知 + 能当对话模型用）；
 *   • 未识别的档案靠「探测」补齐：探测打 {@code /chat/completions}，OpenAI / DeepSeek 官方必通，
 *     所以可用性并没有丢，只是把"默默放行"换成了"显式确认一次"。未探过的档案会先被报为未就绪。
 *
 * @author zzkingcc
 */
class ModelProfileUnspecifiedTypeTest {

    @TempDir
    Path tempDir;

    private ModelProfileRegistry registry() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new ModelProfileRegistry(new ModelProfileStore(new StorageLocations(env)));
    }

    /** 端点族未声明的档案 —— 标准 OpenAI 规范的服务商保存下来就是这个样子 */
    private static ModelProfile unspecified(String alias, String modelName) {
        return new ModelProfile(alias, List.of(), List.of(), List.of(),
                "https://api.deepseek.com/v1", "sk-test", modelName,
                null, null, null, null, null, List.of(), Map.of());
    }

    /** 探测补齐了类型与模态的对话档案（探测路径产出的形态） */
    private static ModelProfile detectedChat(String alias, String modelName) {
        return new ModelProfile(alias, List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT),
                "https://api.deepseek.com/v1", "sk-test", modelName,
                null, null, null, null, null, List.of(), Map.of());
    }

    private static ModelProfile declaredEmbedding(String alias, String modelName) {
        return new ModelProfile(alias, List.of(ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING),
                "https://api.deepseek.com/v1", "sk-test", modelName,
                null, null, null, null, null, List.of(), Map.of());
    }

    // ==================== 判据本身 ====================

    @Test
    void 未声明类型算可能对话但不等于已确认对话() {
        ModelProfile p = unspecified("p", "deepseek-chat");

        assertTrue(p.mayBeChat(), "未声明 = 不知道，mayBeChat 仍按「可能对话」回答（否则调优框等整块消失）");
        assertFalse(p.isChat(), "isChat() 如实回答「未确认是对话模型」—— 两个问题分开答");
        assertFalse(p.isEmbedding(), "也不能因为「不知道」就顺带算成向量模型");
        assertFalse(p.identified(), "端点族为空 = 未识别");
        assertFalse(p.domainBindable(), "未识别不能绑域 —— 这是本次收紧的核心");
    }

    @Test
    void 已声明非对话的档案不算可能对话() {
        ModelProfile image = new ModelProfile("i", List.of(ModelProfile.EP_IMAGES),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_IMAGE),
                "https://api.example.com/v1", "sk-test", "flux",
                null, null, null, null, null, List.of(), Map.of());

        assertFalse(image.mayBeChat(), "已确认是生图档案，不该再当对话模型用");
        assertFalse(declaredEmbedding("v", "bge-m3").mayBeChat(), "已确认是向量档案，同样不该");
    }

    @Test
    void 同时声明chat与embedding的档案仍算对话() {
        ModelProfile dual = new ModelProfile("d",
                List.of(ModelProfile.EP_CHAT, ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT),
                "https://api.example.com/v1", "sk-test", "dual",
                null, null, null, null, null, List.of(), Map.of());

        assertTrue(dual.mayBeChat());
        assertTrue(dual.domainBindable(), "含 chat 且模态齐备 → 可绑域");
    }

    @Test
    void 已识别且模态齐备的档案可绑域() {
        ModelProfile p = detectedChat("p", "deepseek-chat");
        assertTrue(p.identified());
        assertTrue(p.domainBindable());
    }

    @Test
    void 模态未知的档案不可绑域() {
        /* endpoints 显式声明 [chat]，但 input/output 留空（API 直连可达）：类型名义上已识别，
           输入输出模态仍未知，这正是用户抱怨的那半句话（「输入输出模态无法确定…最好禁止绑定」）。 */
        ModelProfile p = new ModelProfile("p", List.of(ModelProfile.EP_CHAT), List.of(), List.of(),
                "https://x/v1", "k", "m", null, null, null, null, null, List.of(), Map.of());

        assertTrue(p.identified(), "类型已知");
        assertFalse(p.domainBindable(), "模态未知不能绑域：只查端点族不够");
    }

    // ==================== 绑定：新门禁 ====================

    @Test
    void 未声明类型的档案不能绑到域上() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(unspecified("p", "deepseek-chat")));

        String reason = registry.bind("default", List.of("p"));

        assertNotNull(reason, "端点族未声明 = 未识别，不允许绑域");
        assertTrue(reason.contains("尚未识别"),
                "拒绝原因要说清是未识别、并引导去「探测」，实际：" + reason);
        assertTrue(registry.resolve("default").aliases().isEmpty(), "拒绝后不该留下半截绑定");
    }

    @Test
    void 探测补齐后可以绑到域上() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(detectedChat("p", "deepseek-chat")));

        assertNull(registry.bind("default", List.of("p")),
                "探测补齐类型与模态后即可绑域 —— 放宽没有丢，只是改成显式确认");
        assertEquals(List.of("p"), registry.resolve("default").aliases());
    }

    @Test
    void 已声明向量的档案仍然绑不了域() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(declaredEmbedding("v", "bge-m3")));

        String reason = registry.bind("default", List.of("v"));

        assertNotNull(reason, "已确认非对话的档案不该能绑到域上");
        assertTrue(reason.contains("不是对话模型"), "拒绝原因要说清为什么，实际：" + reason);
    }

    // ==================== 解析：新门禁 ====================

    @Test
    void 解析器拒绝未声明类型的档案() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(unspecified("p", "deepseek-chat")));
        assertNotNull(registry.bind("default", List.of("p")), "绑定时就已拒绝");

        DefaultModelResolver resolver = new DefaultModelResolver(registry,
                new ModelClientFactory(new ModelAdapterRegistry(new DefaultResourceLoader())));

        assertThrows(NotConfiguredException.class, () -> resolver.streamingChat("default"),
                "未识别档案不再被解析成一个来路不明的模型");
    }

    @Test
    void 解析器能取到探测过的档案() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(detectedChat("p", "deepseek-chat")));
        assertNull(registry.bind("default", List.of("p")));

        DefaultModelResolver resolver = new DefaultModelResolver(registry,
                new ModelClientFactory(new ModelAdapterRegistry(new DefaultResourceLoader())));

        assertNotNull(resolver.streamingChat("default"), "探测过的对话档案照常解析得出模型");
    }

    // ==================== 就绪判据 ====================

    @Test
    void 未声明类型不算对话模型就绪() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(unspecified("p", "deepseek-chat")));

        assertFalse(registry.hasChatModel(),
                "未识别的档案绑不了域，就不该被报成「对话模型已就绪」—— 两者同一口径");
    }

    @Test
    void 探测过的档案算对话模型就绪() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(detectedChat("p", "deepseek-chat")));

        assertTrue(registry.hasChatModel(), "探测过的可绑对话档案 → 对话模型已就绪");
    }

    @Test
    void 已声明向量的档案不算对话模型就绪() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(declaredEmbedding("v", "bge-m3")));

        assertFalse(registry.hasChatModel(), "只有向量档案时不该报「对话模型已就绪」");
    }

    // ==================== 并集写回：探测只增不减 ====================

    @Test
    void 探测写回只增不减() {
        /* 用户 / 元数据已声明能吃 image；本次探测只确认到 text（image 探针这次失败，如网络抖动）。
           并集写回必须保留 image：用整体替换会把已有声明抹掉，这正是要防的回归。 */
        ModelProfile base = new ModelProfile("p", List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), List.of(ModelProfile.MOD_TEXT),
                "https://x/v1", "k", "m", null, null, null, null, null, List.of(), Map.of());

        ModelProfile merged = base.withConfirmed(List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT));

        assertTrue(merged.input().contains(ModelProfile.MOD_IMAGE), "失败的探针不能抹掉已有声明");
        assertTrue(merged.input().contains(ModelProfile.MOD_TEXT));
        assertEquals(2, merged.input().size(), "并集不该引入未确认的值");
    }
}
