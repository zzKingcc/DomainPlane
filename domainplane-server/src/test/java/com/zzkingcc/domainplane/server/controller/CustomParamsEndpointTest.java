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

    // ==================== 卡片可见性 ====================

    /**
     * {@code customParams.applies} 是「这张卡片该不该出参数框」的唯一判据。
     *
     * <p>它曾经等价于"命中了适配"，后来前端改成自己拼 {@code chat || 无 endpoints}，
     * 用的是 {@code bindable}（比 {@code mayBeChat()} 严）：于是"声明了 chat 但输入模态没填"
     * 的半识别档案一条框都没有 —— 卡片上看着正常，就是没有地方填参数。</p>
     *
     * <p>现在结论只在服务端算一次。两类档案必须分开：对话 / 未识别 → 有框；
     * 图像 / 重排 / TTS 这些值根本到不了请求体的族 → 没有框（给了框才是骗人）。</p>
     */
    @Test
    @DisplayName("applies 只对可能对话的档案为真：对话有框，图像与重排没有")
    void appliesFollowsMayBeChat() {
        AdminModelProfileController c = controller();

        seedWith(c, "chat", List.of(ModelProfile.EP_CHAT));
        seedWith(c, "img", List.of(ModelProfile.EP_IMAGES));
        seedWith(c, "rr", List.of(ModelProfile.EP_RERANK));

        Map<String, Object> out = c.list();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) out.get("profiles");

        assertEquals(Boolean.TRUE, customParams(profiles, "chat").get("applies"));
        assertEquals(Boolean.FALSE, customParams(profiles, "img").get("applies"),
                "生图档案的值不进请求体，给框等于骗人");
        assertEquals(Boolean.FALSE, customParams(profiles, "rr").get("applies"));
    }

    /**
     * 「端点族没声明」那一档不在本测试里造：判据本身由
     * {@code ModelProfileUnspecifiedTypeTest} 钉住（未声明 → {@code mayBeChat()} 为真），
     * 而 {@code applies} 只是它的转发，不该各算一套。
     * 保存不再自动识别后，未声明是用户主动留空的结果，不是识别失败。
     *
     * <p>{@code schemaDriven} 与 {@code applies} 是两件事：前者问"这一族还有没有 schema 编控件"，
     * 后者问"该不该出 JSON 框"。对话族已无 schema，若这里为真，框会被判成"另有入口"而藏起来，
     * 结果是一张卡片上一个参数入口都没有。</p>
     */
    @Test
    @DisplayName("schemaDriven 只剩向量族：对话档案永远是 false，别被适配挡掉框")
    void schemaDrivenOnlyForEmbedding() {
        AdminModelProfileController c = controller();
        seedWith(c, "chat", List.of(ModelProfile.EP_CHAT));

        Map<String, Object> out = c.list();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) out.get("profiles");

        assertEquals(Boolean.FALSE, customParams(profiles, "chat").get("schemaDriven"),
                "对话族已无 schema；这里为真会把 JSON 框藏起来，等于没地方填");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> customParams(List<Map<String, Object>> profiles, String alias) {
        for (Map<String, Object> p : profiles) {
            if (alias.equals(p.get("alias"))) {
                return (Map<String, Object>) p.get("customParams");
            }
        }
        return fail("档案清单里没有 " + alias);
    }

    private void seedWith(AdminModelProfileController c, String alias, List<String> endpoints) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias(alias);
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("m-" + alias);
        b.setEndpoints(endpoints);
        c.save(b);
    }

    // ==================== 标准键冲突 ====================

    @Test
    @DisplayName("填 temperature / max_tokens / dimensions 被拒，且文案指向标准字段")
    void stdKeyConflictRejectedWithGuidance() {
        AdminModelProfileController c = controller();
        seed(c);

        for (String key : List.of("temperature", "max_tokens", "dimensions")) {
            String json = "{\"" + key + "\": 1}";
            BaseException e = assertThrows(BaseException.class, () -> save(c, json),
                    "标准键「" + key + "」必须被拒");
            assertTrue(e.getMessage().contains(key), "文案要点出是哪个键：" + e.getMessage());
            assertTrue(e.getMessage().contains("标准"),
                    "文案必须指向标准字段，否则用户不知道该去哪填：" + e.getMessage());
        }
    }

    /**
     * 对话族摘掉适配 schema 之后，这些键**没有第二个入口**：以前它们被拒是因为
     * 「命中适配时还能从滑杆走到 builder」，那条路已经不存在了。继续拒等于纯丢参数 ——
     * 用户在 JSON 框里填 top_p 被拒、卡片上又找不到地方填，只能以为平台不支持。
     */
    @Test
    @DisplayName("原先因适配滑杆被拒的键，现在必须放行")
    void previouslyStdOnlyKeysNowAccepted() {
        AdminModelProfileController c = controller();
        seed(c);

        for (String key : List.of("top_p", "top_logprobs", "seed", "stop",
                "frequency_penalty", "presence_penalty", "response_format")) {
            String json = "{\"" + key + "\": 1}";
            assertDoesNotThrow(() -> save(c, json),
                    "「" + key + "」已无第二个入口，不应再被闸门拒绝");
            assertEquals(1, lastRegistry.profile("p").orElseThrow().params().get(key),
                    "「" + key + "」要真的落进 params");
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
