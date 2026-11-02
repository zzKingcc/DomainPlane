package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「自定义参数（JSON）」文本框的保存口（PUT /admin/model-profiles/custom-params）。
 *
 * <p>钉三件事：</p>
 * <ol>
 *   <li><b>未命中适配也能存</b> —— 这是整条路线的兑现点。旧口径下控制器在保存时拒绝非空
 *       params，通道（{@code effectiveParams} 原样下发）造好了却走不通；
 *       现在 30 个模型里 29 个靠这条路活着。</li>
 *   <li><b>标准键冲突即拒，且文案指向标准字段</b> —— 与其让两条路静默发两遍，
 *       不如在保存时把话说清楚。文案里必须出现"标准"字样，否则用户只会以为这个键不能用，
 *       不会知道该去哪填。</li>
 *   <li><b>坏 JSON 的提示带位置</b> —— 收文本而不是收 map 的全部理由就在这里：
 *       让 Spring 绑成 map 的话，解析错误发生在参数绑定阶段，原始文本已经拿不到了，
 *       用户只能对着一坨文本自己找那个多余的逗号。</li>
 * </ol>
 *
 * 控制器其余依赖（holder / domainRegistry / knowledgeBaseService）不参与本路径，传 null。
 *
 * @author zzkingcc
 */
class CustomParamsEndpointTest {

    @TempDir
    Path tempDir;

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

    /** 建一份未命中适配的对话档案（端点族显式声明，绕过元数据读取） */
    private void seed(AdminModelProfileController c) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias("p");
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("some-unregistered-model");
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));
        c.save(b);
    }

    private Map<String, Object> save(AdminModelProfileController c, String json) {
        AdminModelProfileController.CustomParamsBody body = new AdminModelProfileController.CustomParamsBody();
        body.setAlias("p");
        body.setParamsJson(json);
        return c.updateCustomParams(body);
    }

    // ==================== 存得下 ====================

    @Test
    @DisplayName("未命中适配的档案也能存自定义参数 —— 这条路以前是堵死的")
    void unmatchedProfileCanStoreParams() {
        AdminModelProfileController c = controller();
        seed(c);

        save(c, "{\"enable_thinking\": true, \"repetition_penalty\": 1.05}");

        ModelProfile saved = lastRegistry.profile("p").orElseThrow();
        assertEquals(2, saved.params().size());
        assertEquals(Boolean.TRUE, saved.params().get("enable_thinking"));
        assertEquals(1.05, saved.params().get("repetition_penalty"));
    }

    @Test
    @DisplayName("空白 = 清除全部自定义参数（不是留下空 map，也不是报格式错）")
    void blankClearsParams() {
        AdminModelProfileController c = controller();
        seed(c);
        save(c, "{\"a\": 1}");
        assertEquals(1, lastRegistry.profile("p").orElseThrow().params().size());

        Map<String, Object> out = save(c, "   ");
        assertTrue(lastRegistry.profile("p").orElseThrow().params().isEmpty(),
                "清空文本框再保存就该真的清掉：留着残值等于用户再也删不掉它");
        assertEquals("已清除 p 的自定义参数", out.get("message"));
    }

    @Test
    @DisplayName("只动 params 一个字段：连接信息 / 标准字段 / 端点族原样带走")
    void onlyParamsChange() {
        AdminModelProfileController c = controller();
        seed(c);
        ModelProfile before = lastRegistry.profile("p").orElseThrow();

        save(c, "{\"a\": 1}");
        ModelProfile after = lastRegistry.profile("p").orElseThrow();

        assertEquals(before.baseUrl(), after.baseUrl());
        assertEquals(before.apiKey(), after.apiKey());
        assertEquals(before.modelName(), after.modelName());
        assertEquals(before.endpoints(), after.endpoints());
        assertEquals(before.temperature(), after.temperature());
    }

    // ==================== 标准键冲突 ====================

    @Test
    @DisplayName("填 temperature / max_tokens 被拒，且文案指向标准字段")
    void stdKeyConflictRejectedWithGuidance() {
        AdminModelProfileController c = controller();
        seed(c);

        for (String key : List.of("temperature", "max_tokens", "top_p", "response_format")) {
            String json = "{\"" + key + "\": 1}";
            BaseException e = assertThrows(BaseException.class, () -> save(c, json),
                    "标准键「" + key + "」必须被拒");
            assertTrue(e.getMessage().contains(key), "文案要点出是哪个键：" + e.getMessage());
            assertTrue(e.getMessage().contains("标准"),
                    "文案必须指向标准字段，否则用户不知道该去哪填：" + e.getMessage());
        }
    }

    // ==================== 坏 JSON ====================

    @Test
    @DisplayName("坏 JSON 的提示带行列位置，不只是\"格式错误\"")
    void malformedJsonReportsPosition() {
        AdminModelProfileController c = controller();
        seed(c);

        BaseException e = assertThrows(BaseException.class, () -> save(c, "{\"a\": 1,, \"b\": 2}"));
        assertTrue(e.getMessage().contains("第"), "解析错误要带位置：" + e.getMessage());
        assertTrue(e.getMessage().contains("合法 JSON"), e.getMessage());
    }

    @Test
    @DisplayName("顶层传数组 / 标量单独给一句：那是\"填法错了\"，不是\"这一行写错了\"")
    void nonObjectTopLevelExplained() {
        AdminModelProfileController c = controller();
        seed(c);

        BaseException arr = assertThrows(BaseException.class, () -> save(c, "[\"a\", \"b\"]"));
        assertTrue(arr.getMessage().contains("JSON 对象"), arr.getMessage());
        assertTrue(arr.getMessage().contains("数组"), arr.getMessage());

        BaseException scalar = assertThrows(BaseException.class, () -> save(c, "42"));
        assertTrue(scalar.getMessage().contains("JSON 对象"), scalar.getMessage());
    }

    // ==================== 闸门接管未命中分支 ====================

    @Test
    @DisplayName("黑名单键经接口一样被拒：前端可被绕过，闸门必须落在服务端")
    void blacklistRejectedThroughEndpoint() {
        AdminModelProfileController c = controller();
        seed(c);

        BaseException e = assertThrows(BaseException.class, () -> save(c, "{\"model\": \"gpt-9\"}"));
        assertTrue(e.getMessage().contains("model"), e.getMessage());
    }
}
