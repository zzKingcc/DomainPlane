package com.zzkingcc.domainplane.server.model;

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
 * 「端点族未声明」既不能当成「确定非对话」，也不能当成「确定是对话」 —— 两侧的取舍各自钉住。
 *
 * 背景：元数据读不出类型时（标准 OpenAI 规范的服务商，OpenAI / DeepSeek 官方都是）档案停在
 * {@code endpoints == []}。这不是一条边缘路径：内置预设里唯一有适配的两家正是这种形态。
 *
 *   • 放宽的一侧（{@link ModelProfile#mayBeChat()}）：绑定 / 解析 / 卡片调优框都按「可以当对话模型用」处理。
 *     按「已确认」来判，这些档案既绑不上域也解析不出模型 —— 等于建了一份永远用不了的档案；
 *   • 收紧的一侧（{@link ModelProfile#isEmbedding()}）：选全局向量模型仍然只认已声明 embedding 的档案。
 *     选错会让整库向量维度对不上、检索静默失效，那个方向必须继续从严。
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

        assertTrue(p.mayBeChat(), "未声明 = 不知道，按「可能对话」处理（否则整个平台用不了）");
        assertFalse(p.isChat(), "isChat() 仍如实回答「未确认是对话模型」—— 两个问题分开答");
        assertFalse(p.isEmbedding(), "也不能因为「不知道」就顺带算成向量模型");
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
    }

    // ==================== 绑定：放宽的一侧 ====================

    @Test
    void 未声明类型的档案可以绑到域上() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(unspecified("p", "deepseek-chat")));

        assertNull(registry.bind("default", List.of("p")),
                "端点族未声明时绑不了域，等于 OpenAI / DeepSeek 官方一个模型都用不了");
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

    // ==================== 解析：放宽的一侧 ====================

    @Test
    void 解析器能取到未声明类型的档案() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(unspecified("p", "deepseek-chat")));
        assertNull(registry.bind("default", List.of("p")));

        DefaultModelResolver resolver = new DefaultModelResolver(registry,
                new ModelClientFactory(new ModelAdapterRegistry(new DefaultResourceLoader())));

        assertNotNull(resolver.streamingChat("default"),
                "按 isChat() 筛会把这类档案整条链筛掉，域永远解析不出模型");
    }

    @Test
    void 对话模型就绪判据把未声明类型算在内() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(unspecified("p", "deepseek-chat")));

        assertTrue(registry.hasChatModel(),
                "「对话模型是否就绪」若按 isChat() 判，标准规范的服务商会被报成未配置");
    }

    @Test
    void 已声明向量的档案不算对话模型就绪() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(declaredEmbedding("v", "bge-m3")));

        assertFalse(registry.hasChatModel(), "只有向量档案时不该报「对话模型已就绪」");
    }
}
