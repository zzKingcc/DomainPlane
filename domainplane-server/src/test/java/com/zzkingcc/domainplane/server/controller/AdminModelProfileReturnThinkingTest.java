package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
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
 * 「是否回写思考内容」开关的保存口（PUT /admin/model-profiles/return-thinking）。
 *
 * 口径（与前端那颗卡片开关一一对应）：
 *   • 拨到开 / 关都落盘，且只动 returnThinking 一个字段；
 *   • 不要求重填 API Key —— 卡片上只有脱敏值，为拨一颗开关让用户再输一遍密钥没有道理；
 *   • 不要求命中适配 —— 思考是对话流的概念，与"这家服务商有没有登记调优 schema"无关；
 *   • 别名不存在 / 请求体缺失一律拒绝，且原因说得清是哪一个。
 *
 * 控制器其余依赖（holder / domainRegistry / knowledgeBaseService）不参与本路径，传 null。
 *
 * @author zzkingcc
 */
class AdminModelProfileReturnThinkingTest {

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
                null, null, new ModelAdapterRegistry(new DefaultResourceLoader()), null, null);
    }

    /** 先建一份对话档案（端点族显式声明，绕过元数据读取；未命中适配，params 为空） */
    private void seed(AdminModelProfileController c) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias("p");
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("some-model");
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));
        c.save(b);
    }

    private static AdminModelProfileController.ReturnThinkingBody body(String alias, Boolean on) {
        AdminModelProfileController.ReturnThinkingBody b = new AdminModelProfileController.ReturnThinkingBody();
        b.setAlias(alias);
        b.setReturnThinking(on);
        return b;
    }

    @Test
    void 拨到开落盘且只改这一个字段() {
        AdminModelProfileController c = controller();
        seed(c);

        Map<String, Object> r = c.updateReturnThinking(body("p", Boolean.TRUE));
        assertEquals(0, r.get("code"));
        assertEquals("已保存 p 的思考回写开关", r.get("message"));

        ModelProfile saved = lastRegistry.profile("p").orElseThrow(AssertionError::new);
        assertTrue(saved.returnThinking(), "开关应落盘为 true");
        assertEquals("https://api.example.com/v1", saved.baseUrl(), "连接信息应原样带走");
        assertEquals("some-model", saved.modelName(), "模型名应原样带走");
        assertNull(saved.temperature(), "调优值不应被这颗开关碰到");
        assertTrue(saved.params().isEmpty(), "私有参数不应被这颗开关碰到");

        /* 回带的视图给前端就地刷新用，也必须是新值 */
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) r.get("profile");
        assertEquals(Boolean.TRUE, view.get("returnThinking"));
    }

    @Test
    void 再拨到关同样落盘() {
        AdminModelProfileController c = controller();
        seed(c);
        c.updateReturnThinking(body("p", Boolean.TRUE));

        c.updateReturnThinking(body("p", Boolean.FALSE));
        ModelProfile saved = lastRegistry.profile("p").orElseThrow(AssertionError::new);
        assertFalse(saved.returnThinking(), "再拨一次应落盘为 false");
    }

    @Test
    void 请求体没带开关时按关闭处理() {
        AdminModelProfileController c = controller();
        seed(c);

        c.updateReturnThinking(body("p", null));
        ModelProfile saved = lastRegistry.profile("p").orElseThrow(AssertionError::new);
        assertFalse(saved.returnThinking(),
                "缺失即关闭：开关的默认态就是关，没带上与明确关它是同一件事");
    }

    @Test
    void 别名不存在被拒() {
        AdminModelProfileController c = controller();

        BaseException e = assertThrows(BaseException.class,
                () -> c.updateReturnThinking(body("nope", Boolean.TRUE)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("档案不存在"), "原因要点名是哪个别名，实际：" + e.getMessage());
    }

    @Test
    void 请求体缺失或别名为空被拒() {
        AdminModelProfileController c = controller();
        seed(c);

        BaseException e1 = assertThrows(BaseException.class, () -> c.updateReturnThinking(null));
        assertEquals(ErrorCode.INVALID_PARAMETER, e1.getErrorCode());
        assertTrue(e1.getMessage().contains("别名不能为空"), "实际：" + e1.getMessage());

        BaseException e2 = assertThrows(BaseException.class,
                () -> c.updateReturnThinking(body("   ", Boolean.TRUE)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e2.getErrorCode());
        assertTrue(e2.getMessage().contains("别名不能为空"), "实际：" + e2.getMessage());
    }
}
