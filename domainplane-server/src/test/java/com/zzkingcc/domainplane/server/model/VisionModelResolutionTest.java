package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.runtime.model.VisionTarget;
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
 * 视觉模型解析：本轮带图时，图到底走哪条路由 {@code visionModel} 的一次解析定死。
 *
 * <p>要守住的是 {@code primary} 这一个标志的准确性：判错的两个方向后果都不轻：</p>
 *
 * <ul>
 *   <li>该为 {@code true} 却判成 {@code false}：主模型明明能看图，却绕一圈委派，
 *       平白多一次模型调用、多一点延迟；</li>
 *   <li>该为 {@code false} 却判成 {@code true}：图片直接进了一个纯文本模型的消息，
 *       上游要么报参数错误，要么静默忽略：后者更糟，模型会对着它看不到的图编答案。</li>
 * </ul>
 *
 * @author zzkingcc
 */
class VisionModelResolutionTest {

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

    private static DefaultModelResolver resolver(ModelProfileRegistry registry) {
        return new DefaultModelResolver(registry,
                new ModelClientFactory(new ModelAdapterRegistry(new DefaultResourceLoader())));
    }

    /** 视觉对话模型：输入模态含 image：判据就是它，不新增字段 */
    private static ModelProfile visionChat(String alias, String modelName) {
        return new ModelProfile(alias, List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), List.of(ModelProfile.MOD_TEXT),
                "https://api.example.com/v1", "sk-test", modelName,
                null, null, null, null, null, List.of(), Map.of());
    }

    /** 纯文本对话模型 */
    private static ModelProfile textChat(String alias, String modelName) {
        return new ModelProfile(alias, List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT),
                "https://api.example.com/v1", "sk-test", modelName,
                null, null, null, null, null, List.of(), Map.of());
    }

    @Test
    void 主模型本身是视觉模型时标记为primary() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(visionChat("v", "gpt-4o")));
        assertNull(registry.bind("default", List.of("v")));

        VisionTarget target = resolver(registry).visionModel("default").orElseThrow();

        assertEquals("v", target.alias());
        assertEquals("gpt-4o", target.modelName());
        assertTrue(target.primary(), "域链首项即视觉模型 → 本轮图片直接进它的消息上下文");
        assertNotNull(target.model(), "解析结果必须带一个可直接调用的非流式模型");
    }

    @Test
    void 主模型是纯文本时视觉子模型不带primary() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(textChat("t", "deepseek-chat")));
        assertNull(registry.save(visionChat("v", "gpt-4o")));
        assertNull(registry.bind("default", List.of("t", "v")));

        VisionTarget target = resolver(registry).visionModel("default").orElseThrow();

        assertEquals("v", target.alias());
        assertFalse(target.primary(), "它是主模型之外的视觉子模型 → 只能作为 seeImage 的委派目标");
    }

    @Test
    void 整条链没有视觉模型时返回空() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(textChat("t", "deepseek-chat")));
        assertNull(registry.bind("default", List.of("t")));

        assertTrue(resolver(registry).visionModel("default").isEmpty(),
                "没有视觉模型 → 带图请求无处可去，由编排层如实拒绝（而不是把图静默丢掉）");
    }

    @Test
    void 域未绑定任何模型时返回空() {
        assertTrue(resolver(registry()).visionModel("default").isEmpty());
    }

    @Test
    void 沿祖先域继承的视觉模型同样解析得到() {
        ModelProfileRegistry registry = registry();
        assertNull(registry.save(visionChat("v", "gpt-4o")));
        // 绑定写在祖先域上，解析从叶子域出发：域路径一律是「从根域 default 出发的完整路径」
        assertNull(registry.bind("default.shop", List.of("v")));

        VisionTarget target = resolver(registry).visionModel("default.shop.order.detail").orElseThrow();

        assertEquals("v", target.alias(), "绑定沿域链回落，解析也沿域链回落 —— 两者必须同一口径");
        assertTrue(target.primary());
    }
}
