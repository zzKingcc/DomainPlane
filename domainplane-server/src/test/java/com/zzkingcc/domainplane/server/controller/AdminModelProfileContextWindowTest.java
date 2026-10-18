package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
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
 * 上下文长度的保存口（PUT /admin/model-profiles/context-window）。
 *
 * 口径（与前端卡片上那个输入框一一对应）：
 *   • 只动 contextWindow 一个字段，其余（连接信息 / 调优值 / 端点族 / 模态 / 降级链）原样带走；
 *   • 不要求重填 API Key；
 *   • <b>不要求命中适配</b>：这是它与 PUT /model-profiles/tuning 的分界线：
 *     标准 OpenAI / DeepSeek 这类最常见的档案不命中适配，从那条路根本改不到；
 *   • 除"正整数"外不做取值校验：不做范围校验是刻意的（填得对不对是用户的事），
 *     但非正数要拦：那是类型问题，流进装配预算会把预算算成负数；
 *   • 传 null = 清除声明（回到"未声明"，装配预算按配置值）。
 *
 * 控制器其余依赖（holder / domainRegistry / knowledgeBaseService）不参与本路径，传 null。
 *
 * @author zzkingcc
 */
class AdminModelProfileContextWindowTest {

    @TempDir
    Path tempDir;

    /** 最近一次 {@link #controller} 装配出的注册表 —— 断言落盘结果用它 */
    private ModelProfileRegistry lastRegistry;

    private AdminModelProfileController controller() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ModelProfileStore store = new ModelProfileStore(new StorageLocations(env));
        ModelProfileRegistry registry = new ModelProfileRegistry(store);
        lastRegistry = registry;
        return new AdminModelProfileController(registry, store,
                null, null, new ModelAdapterRegistry(new DefaultResourceLoader()),
                new ModelCapabilityProbe(), null, null);
    }

    /** 先建一份对话档案（端点族显式声明，绕过元数据读取；未命中适配，params 为空） */
    private void seed(AdminModelProfileController c) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias("p");
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("some-model");
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));
        b.setInput(List.of(ModelProfile.MOD_TEXT));
        b.setOutput(List.of(ModelProfile.MOD_TEXT));
        b.setTemperature(0.7);
        c.save(b);
    }

    private static AdminModelProfileController.ContextWindowBody body(String alias, Integer window) {
        AdminModelProfileController.ContextWindowBody b = new AdminModelProfileController.ContextWindowBody();
        b.setAlias(alias);
        b.setContextWindow(window);
        return b;
    }

    @Test
    void 落盘且只改这一个字段() {
        AdminModelProfileController c = controller();
        seed(c);

        Map<String, Object> r = c.updateContextWindow(body("p", 32_000));
        assertEquals(0, r.get("code"));

        ModelProfile saved = lastRegistry.profile("p").orElseThrow(AssertionError::new);
        assertEquals(32_000, saved.contextWindow(), "应落盘为 32k");
        assertEquals("https://api.example.com/v1", saved.baseUrl(), "连接信息应原样带走");
        assertEquals("some-model", saved.modelName(), "模型名应原样带走");
        assertEquals(Double.valueOf(0.7), saved.temperature(), "调优值不该被这个动作碰到");
        assertTrue(saved.params().isEmpty(), "私有参数不该被这个动作碰到");
        assertTrue(saved.input().contains(ModelProfile.MOD_TEXT), "输入模态不该被这个动作碰到");

        /* 回带的视图给前端就地刷新用，也必须是新值 */
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) r.get("profile");
        assertEquals(32_000, view.get("contextWindow"));
    }

    @Test
    void 不要求命中适配() {
        /* 本用例的档案刻意未命中任何适配文件（some-model @ api.example.com）：
           若本接口照搬 tuning 的 byModelName() 门禁，这里就会抛"不支持参数调优" */
        AdminModelProfileController c = controller();
        seed(c);

        c.updateContextWindow(body("p", 8_192));
        assertEquals(8_192, lastRegistry.profile("p").orElseThrow(AssertionError::new).contextWindow(),
                "上下文长度对任何可用档案都应可改，不设适配门禁");
    }

    @Test
    void 传null清除声明() {
        AdminModelProfileController c = controller();
        seed(c);
        c.updateContextWindow(body("p", 64_000));

        Map<String, Object> r = c.updateContextWindow(body("p", null));
        assertEquals(0, r.get("code"));
        assertNull(lastRegistry.profile("p").orElseThrow(AssertionError::new).contextWindow(),
                "null = 清除声明（回到「未声明」，装配预算按配置值）");
        assertTrue(String.valueOf(r.get("message")).contains("清除"), "回执要说清是清除，实际：" + r.get("message"));
    }

    @Test
    void 非正数被拒且不落盘() {
        AdminModelProfileController c = controller();
        seed(c);
        c.updateContextWindow(body("p", 32_000));

        for (Integer bad : List.of(0, -1)) {
            BaseException e = assertThrows(BaseException.class, () -> c.updateContextWindow(body("p", bad)));
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("正整数"), "原因要点明是取值形态，实际：" + e.getMessage());
        }
        assertEquals(32_000, lastRegistry.profile("p").orElseThrow(AssertionError::new).contextWindow(),
                "被拒的请求不得改动已存值");
    }

    @Test
    void 别名不存在被拒() {
        AdminModelProfileController c = controller();

        BaseException e = assertThrows(BaseException.class,
                () -> c.updateContextWindow(body("nope", 32_000)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("档案不存在"), "原因要点名是哪个别名，实际：" + e.getMessage());
    }

    @Test
    void 请求体缺失或别名为空被拒() {
        AdminModelProfileController c = controller();
        seed(c);

        BaseException e1 = assertThrows(BaseException.class, () -> c.updateContextWindow(null));
        assertEquals(ErrorCode.INVALID_PARAMETER, e1.getErrorCode());
        assertTrue(e1.getMessage().contains("别名不能为空"), "实际：" + e1.getMessage());

        BaseException e2 = assertThrows(BaseException.class, () -> c.updateContextWindow(body("   ", 1)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e2.getErrorCode());
        assertTrue(e2.getMessage().contains("别名不能为空"), "实际：" + e2.getMessage());
    }

    @Test
    void 新建档案即可带上上下文长度() {
        /* ProfileBody 也收了这一项：卡片只能改已存档案，而"整体覆盖"语义要求它在建立时也有入口，
           否则脚本 / 集成方只能建完再补一次 PUT */
        AdminModelProfileController c = controller();
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias("q");
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("some-model");
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));
        b.setContextWindow(128_000);
        c.save(b);

        assertEquals(128_000, lastRegistry.profile("q").orElseThrow(AssertionError::new).contextWindow());
    }

    @Test
    void 落盘后重新装载仍在() {
        AdminModelProfileController c = controller();
        seed(c);
        c.updateContextWindow(body("p", 200_000));

        /* 走一遍完整的存 → 读：models.json 走的是 ProfileData，不是 record 本身，
           漏加那个字段会让它"保存成功、重启即丢" */
        ModelProfileStore store = new ModelProfileStore(new StorageLocations(envOf(tempDir)));
        ModelProfileRegistry reloaded = new ModelProfileRegistry(store);
        assertEquals(200_000, reloaded.profile("p").orElseThrow(AssertionError::new).contextWindow());
    }

    private static StandardEnvironment envOf(Path dir) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", dir.resolve("config").toString());
        props.put("domainplane.logging.path", dir.resolve("log").toString());
        props.put("domainplane.export.path", dir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }
}
