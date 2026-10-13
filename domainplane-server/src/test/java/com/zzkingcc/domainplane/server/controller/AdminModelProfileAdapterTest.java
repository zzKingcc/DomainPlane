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
 * 保存时的适配复核（设计文档 §5.3）：「没适配就不能调优」对直接调 API 同样成立。
 *
 * 前端只是不渲染工具栏，绕过前端 POST 上来的 params 在这里被挡住：
 *   • 命中适配 → params 必须过 schema（未知键 / 越界一律拒绝）；
 *   • 未命中 → params 必须为空。
 *
 * 控制器其余依赖（holder / domainRegistry / knowledgeBaseService）不参与保存路径
 * （dimensions 为空时跳过探测），传 null 即可把这条关卡单独测住。
 *
 * @author zzkingcc
 */
class AdminModelProfileAdapterTest {

    @TempDir
    Path tempDir;

    /** 最近一次 {@link #controller} 装配出的注册表 —— 需要直接塞档案（绕过保存路径）的用例用它 */
    private ModelProfileRegistry lastRegistry;

    private AdminModelProfileController controller(ModelAdapterRegistry adapterRegistry) {
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
                null, null, adapterRegistry, null, null);
    }

    private static AdminModelProfileController.ProfileBody body(String baseUrl, String modelName,
                                                                Map<String, Object> params) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias("p");
        b.setApiKey("sk-test");
        b.setBaseUrl(baseUrl);
        b.setModelName(modelName);
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));
        b.setParams(params);
        return b;
    }

    @Test
    void 未命中适配传params被拒() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        BaseException e = assertThrows(BaseException.class,
                () -> c.save(body("https://api.example.com/v1", "some-model", Map.of("top_p", 0.9))));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("未做适配"),
                "拒绝原因要说清是「未适配」而不是别的，实际：" + e.getMessage());
    }

    @Test
    void 命中适配传未登记键被拒() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        BaseException e = assertThrows(BaseException.class,
                () -> c.save(body("https://api.openai.com/v1", "gpt-5.6-sol", Map.of("top_p", 0.9))));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("reasoningEffort") && e.getMessage().contains("verbosity"),
                "拒绝原因要列出可用键，实际：" + e.getMessage());
    }

    @Test
    void 命中适配传ResponsesAPI专属档位被拒() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        BaseException e = assertThrows(BaseException.class,
                () -> c.save(body("https://api.openai.com/v1", "gpt-5.6-terra",
                        Map.of("reasoningEffort", "max"))));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("reasoningEffort"),
                "拒绝原因要点名是哪个参数，实际：" + e.getMessage());
    }

    @Test
    void GPT6上保存max与Astra上保存none均被拒() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        /* max 档 GPT-6 起仅 Responses API 可用，Chat Completions 基底不登记 → 拒 */
        BaseException e1 = assertThrows(BaseException.class,
                () -> c.save(body("https://api.openai.com/v1", "gpt-6-sol",
                        Map.of("reasoningEffort", "max"))));
        assertEquals(ErrorCode.INVALID_PARAMETER, e1.getErrorCode());
        assertTrue(e1.getMessage().contains("reasoningEffort"), "实际：" + e1.getMessage());

        /* none 档 Astra / 6.1-Sol 已移除 → 拒 */
        BaseException e2 = assertThrows(BaseException.class,
                () -> c.save(body("https://api.openai.com/v1", "gpt-6-astra",
                        Map.of("reasoningEffort", "none"))));
        assertEquals(ErrorCode.INVALID_PARAMETER, e2.getErrorCode());
        assertTrue(e2.getMessage().contains("reasoningEffort"), "实际：" + e2.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void GPT6Sol保存none成功且摘要只列有值的项() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        Map<String, Object> saved = c.save(body("https://api.openai.com/v1", "gpt-6-sol",
                Map.of("reasoningEffort", "none")));
        assertEquals(0, saved.get("code"));

        Map<String, Object> list = c.list();
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) list.get("profiles");
        assertEquals(1, profiles.size());
        Map<String, Object> view = profiles.get(0);
        assertEquals(Map.of("reasoningEffort", "none"), view.get("params"),
                "私有调优参数要原样落档并回显");

        Map<String, Object> adapter = (Map<String, Object>) view.get("adapter");
        assertEquals("openai-gpt-6-sol", adapter.get("id"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) adapter.get("items");
        /* 适配登记了 reasoningEffort / temperature / max_tokens 三项，但温度与最大输出这次都没填，
           items 只列**有值**的项 —— 没填 = 服务商默认，不该在摘要里编一个值出来 */
        assertEquals(1, items.size(), "只列有值的项，实际：" + items);
        assertEquals("none", items.get(0).get("value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void DeepSeek保存none成功且摘要只列有值的项() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        Map<String, Object> saved = c.save(body("https://api.deepseek.com/v1", "deepseek-flash",
                Map.of("reasoningEffort", "none")));
        assertEquals(0, saved.get("code"));

        Map<String, Object> list = c.list();
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) list.get("profiles");
        assertEquals(1, profiles.size());
        Map<String, Object> view = profiles.get(0);
        assertEquals(Map.of("reasoningEffort", "none"), view.get("params"),
                "私有调优参数要原样落档并回显");

        Map<String, Object> adapter = (Map<String, Object>) view.get("adapter");
        assertEquals("deepseek-flash", adapter.get("id"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) adapter.get("items");
        assertEquals(1, items.size(), "只列有值的项（DeepSeek 登记了思考强度 / 温度 / 单次最大输出）");
        assertEquals("none", items.get(0).get("value"));
    }

    @Test
    void DeepSeek上保存未登记的兼容别名medium被拒() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        /* medium 是官方兼容别名（请求时会被映射到 high），但不是登记枚举值，保存时就要显式选 high */
        BaseException e = assertThrows(BaseException.class,
                () -> c.save(body("https://api.deepseek.com/v1", "deepseek-v4-pro",
                        Map.of("reasoningEffort", "medium"))));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("reasoningEffort"), "实际：" + e.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 命中适配传合法参数保存成功且视图下发适配摘要() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        Map<String, Object> saved = c.save(body("https://api.openai.com/v1", "gpt-5.6-sol",
                Map.of("reasoningEffort", "high", "verbosity", "low")));
        assertEquals(0, saved.get("code"));

        Map<String, Object> list = c.list();
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) list.get("profiles");
        assertEquals(1, profiles.size());
        Map<String, Object> view = profiles.get(0);
        assertEquals(Map.of("reasoningEffort", "high", "verbosity", "low"), view.get("params"),
                "私有调优参数要原样落档并回显");

        Map<String, Object> adapter = (Map<String, Object>) view.get("adapter");
        assertNotNull(adapter, "命中适配的对话档案要下发 adapter 摘要（供卡片展示）");
        assertEquals("openai-gpt-5.6-sol", adapter.get("id"));
        assertTrue(String.valueOf(adapter.get("label")).contains("Sol"), "摘要带 label，实际：" + adapter.get("label"));

        List<Map<String, Object>> items = (List<Map<String, Object>>) adapter.get("items");
        Map<String, Object> byKey = new LinkedHashMap<>();
        items.forEach(it -> byKey.put(String.valueOf(it.get("key")), it.get("value")));
        assertEquals("high", byKey.get("reasoningEffort"));
        assertEquals("low", byKey.get("verbosity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 适配摘要下发参数schema供卡片渲染可编辑控件() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));
        c.save(body("https://api.deepseek.com/v1", "deepseek-flash", Map.of("reasoningEffort", "high")));

        Map<String, Object> adapter = (Map<String, Object>) firstProfile(c).get("adapter");
        assertNotNull(adapter.get("docUrl"), "官方文档链接要下发（卡片绿框里有入口）");

        /* 卡片右侧要放可编辑控件：控件类型 / 枚举选项 / min / max 只能从 schema 得到。
           直调 list() 拿到的是内存里的 ParamSpec 原件（没走 HTTP，不过 JSON） */
        List<ModelAdapterRegistry.ParamSpec> params =
                (List<ModelAdapterRegistry.ParamSpec>) adapter.get("params");
        Map<String, ModelAdapterRegistry.ParamSpec> byKey = new LinkedHashMap<>();
        params.forEach(p -> byKey.put(p.key(), p));
        assertEquals(8, params.size(),
                "DeepSeek 登记 思考强度 / 温度 / 最大输出 / top_p / 停止序列 / 返回格式 / 对数概率 / 候选个数，实际："
                        + byKey.keySet());

        assertEquals("enum", byKey.get("reasoningEffort").type());
        assertFalse(byKey.get("reasoningEffort").std());
        /* 呈现方式是 schema 的一部分：思考强度要渲染成分档进度条，前端据此选控件，不本地写死 key */
        assertEquals("bar", byKey.get("reasoningEffort").display(), "思考强度按分档进度条呈现（options 顺序即档位）");
        assertNull(byKey.get("topP").display(), "没写 display 的参数按 type 走默认控件");

        ModelAdapterRegistry.ParamSpec temp = byKey.get("temperature");
        assertEquals("number", temp.type());
        assertTrue(temp.std(), "温度是标准参数：值落 ModelProfile 老字段");
        assertEquals("temperature", temp.wire());
        assertEquals(0.0, temp.min());
        assertEquals(2.0, temp.max());
        /* 条件也走 schema 下发：前端按它决定控件灰不灰，前端不本地复刻任何厂商规则 */
        assertNotNull(temp.enabledWhen(), "温度只在非思考模式下可填，这条约束必须随 schema 下发");
        assertEquals("reasoningEffort", temp.enabledWhen().key());
        assertEquals(List.of("none"), temp.enabledWhen().values());
        /* 官方默认值随 schema 下发：界面空态据此显示「默认 X」，前端不本地复刻任何厂商默认值 */
        assertEquals(1.0, ((Number) temp.defaultValue()).doubleValue(), 1e-9,
                "温度默认 1（官方）也要随 schema 下发");
        assertEquals("high", byKey.get("reasoningEffort").defaultValue(),
                "思考强度默认 high 随 schema 下发");
        assertEquals("text", byKey.get("responseFormat").defaultValue());
        assertNull(byKey.get("maxTokens").defaultValue(), "没写 default 的参数界面才回落到「服务商默认」");

        ModelAdapterRegistry.ParamSpec maxTokens = byKey.get("maxTokens");
        assertEquals("int", maxTokens.type());
        assertTrue(maxTokens.std());
        assertEquals("max_tokens", maxTokens.wire());

        /* 值的线上形状也要下发：response_format 是嵌套对象，stop 是数组 */
        assertEquals("type", byKey.get("responseFormat").wireWrap());
        assertEquals("stringList", byKey.get("stop").type());
        assertNull(byKey.get("topP").wireWrap(), "没写 wireWrap 的参数不带包装");
    }

    // ==================== PUT /admin/model-profiles/tuning（只改调优） ====================

    @Test
    @SuppressWarnings("unchecked")
    void tuningOnly只改调优三件套连接信息原样不动() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));
        c.save(body("https://api.deepseek.com/v1", "deepseek-flash", Map.of("reasoningEffort", "high")));

        Map<String, Object> before = firstProfile(c);
        Map<String, Object> r = c.updateTuning(tuning("p", Map.of("reasoningEffort", "none"), 0.7, 4096));

        assertEquals(0, r.get("code"));
        assertEquals("tuned", r.get("action"));
        assertTrue(String.valueOf(r.get("message")).contains("调优"), "实际：" + r.get("message"));

        Map<String, Object> after = firstProfile(c);
        assertEquals(Map.of("reasoningEffort", "none"), after.get("params"), "私有参数被替换");
        assertEquals(0.7, after.get("temperature"));
        assertEquals(4096, after.get("maxTokens"));

        /* 连接信息 / 端点族 / 模态必须逐项与改之前一致：接口压根不收这些字段，
           不是"前端不发"，而是"后端没有入口可以改" */
        for (String field : List.of("baseUrl", "modelName", "apiKeyMasked", "endpoints",
                "input", "output", "dimensions", "fallbacks", "usable", "alias")) {
            assertEquals(before.get(field), after.get(field), field + " 不该被调优改动");
        }

        /* 回带的视图也带上了新值，卡片可据此就地重画 */
        Map<String, Object> view = (Map<String, Object>) r.get("profile");
        assertEquals(0.7, view.get("temperature"));
        assertEquals(Map.of("reasoningEffort", "none"), view.get("params"));
    }

    @Test
    void tuningOnly传null即清除调优值() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));
        c.save(body("https://api.deepseek.com/v1", "deepseek-flash", Map.of("reasoningEffort", "none")));
        c.updateTuning(tuning("p", Map.of("reasoningEffort", "none"), 0.7, 4096));

        /* 整份替换语义：null / 空 map = 清除（回到服务商默认）。
           若按"null = 不动"处理，用户在卡片上清空温度再保存会发现清不掉。 */
        c.updateTuning(tuning("p", Map.of(), null, null));

        Map<String, Object> view = firstProfile(c);
        assertEquals(Map.of(), view.get("params"));
        assertNull(view.get("temperature"));
        assertNull(view.get("maxTokens"));
    }

    @Test
    void tuningOnly拒绝未适配档案() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));
        c.save(body("https://api.example.com/v1", "some-model", null));

        BaseException e = assertThrows(BaseException.class,
                () -> c.updateTuning(tuning("p", Map.of(), 0.5, null)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("未做适配"), "实际：" + e.getMessage());
    }

    @Test
    void tuningOnly按schema拒绝越界温度() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));
        c.save(body("https://api.deepseek.com/v1", "deepseek-flash", null));

        /* 标准参数落在 ModelProfile 老字段里，不在 params map 里 ——
           不摊平进同一套 schema 校验就会漏掉越界把关（前端 number 控件的 min/max 只是填表便利）。
           这里带上 none 让温度处于"可填"状态，测的才是值域而不是条件。 */
        BaseException e = assertThrows(BaseException.class,
                () -> c.updateTuning(tuning("p", Map.of("reasoningEffort", "none"), 5.0, null)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("temperature"), "实际：" + e.getMessage());

        BaseException e2 = assertThrows(BaseException.class,
                () -> c.updateTuning(tuning("p", Map.of(), null, 0)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e2.getErrorCode());
        assertTrue(e2.getMessage().contains("maxTokens"), "实际：" + e2.getMessage());
    }

    // ==================== 条件参数：按思考模式决定哪些能填 ====================

    @Test
    void 保存路径按思考模式拒绝不生效的参数() {
        /* 两侧各放行一个、拒绝一个：温度只在非思考模式可填，top_p 只在思考模式可调。
           每个断言用独立控制器：本组只问"这一份档案能不能存"，别让彼此的状态串味 */
        final String ds = "deepseek-flash";

        BaseException badTemp = assertThrows(BaseException.class, () -> controller(reg())
                .save(body(DS_URL, ds, Map.of("reasoningEffort", "high", "temperature", 0.3))));
        assertTrue(badTemp.getMessage().contains("temperature")
                        && badTemp.getMessage().contains("reasoningEffort"),
                "拒绝原因要带 key 与它依赖的参数，实际：" + badTemp.getMessage());

        BaseException badTopP = assertThrows(BaseException.class, () -> controller(reg())
                .save(body(DS_URL, ds, Map.of("reasoningEffort", "none", "topP", 0.98))));
        assertTrue(badTopP.getMessage().contains("topP")
                        && badTopP.getMessage().contains("reasoningEffort"),
                "实际：" + badTopP.getMessage());
    }

    @Test
    void 保存路径放行当下可填的参数() {
        assertEquals(0, controller(reg()).save(body(DS_URL, "deepseek-flash",
                Map.of("reasoningEffort", "none", "temperature", 0.3))).get("code"),
                "非思考模式下温度可填");

        assertEquals(0, controller(reg()).save(body(DS_URL, "deepseek-flash",
                Map.of("reasoningEffort", "high", "topP", 0.98,
                        "logprobs", true, "topLogprobs", 5))).get("code"),
                "思考模式下 top_p 与配套的 logprobs 可填");
    }

    @Test
    void 保存路径默认按思考模式判定条件() {
        /* 思考强度留空 = 服务商默认 high = 思考模式：温度不可填、top_p 可填（missing=high） */
        BaseException e = assertThrows(BaseException.class, () -> controller(reg())
                .save(body(DS_URL, "deepseek-flash", Map.of("temperature", 0.3))));
        assertTrue(e.getMessage().contains("temperature"), "实际：" + e.getMessage());

        assertEquals(0, controller(reg()).save(body(DS_URL, "deepseek-flash", Map.of("topP", 0.98))).get("code"),
                "top_p 的 enabledWhen 带 missing=high，依赖未填时按思考模式判定");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tuningOnly保存全套新参数并回显() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));
        c.save(body("https://api.deepseek.com/v1", "deepseek-flash", null));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reasoningEffort", "high");
        params.put("topP", 0.98);
        params.put("stop", List.of("###", "STOP"));
        params.put("responseFormat", "json_object");
        params.put("logprobs", true);
        params.put("topLogprobs", 5);
        assertEquals(0, c.updateTuning(tuning("p", params, null, 4096)).get("code"));

        Map<String, Object> view = firstProfile(c);
        assertEquals(params, view.get("params"), "整套私有参数原样落档");
        assertEquals(4096, view.get("maxTokens"));

        Map<String, Object> adapter = (Map<String, Object>) view.get("adapter");
        List<Map<String, Object>> items = (List<Map<String, Object>>) adapter.get("items");
        Map<String, Object> byKey = new LinkedHashMap<>();
        items.forEach(it -> byKey.put(String.valueOf(it.get("key")), it.get("value")));
        assertEquals(List.of("###", "STOP"), byKey.get("stop"), "数组值要能存能回显");
        assertEquals("json_object", byKey.get("responseFormat"));
        assertEquals(5, byKey.get("topLogprobs"));
    }

    @Test
    void tuningOnly拒绝条件不成立的值() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));
        c.save(body("https://api.deepseek.com/v1", "deepseek-flash", Map.of("reasoningEffort", "high")));

        /* 卡片上把思考强度从 high 改成 none 却没清 top_p：前端会清空被禁项，
           绕过前端的请求在这里被挡住 */
        BaseException e = assertThrows(BaseException.class, () -> c.updateTuning(tuning("p",
                Map.of("reasoningEffort", "none", "topP", 0.98), null, null)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("topP"), "实际：" + e.getMessage());

        assertEquals(0, c.updateTuning(tuning("p",
                Map.of("reasoningEffort", "none", "temperature", 0.7), 0.7, null)).get("code"),
                "切到非思考模式后温度可填");
    }

    @Test
    void tuningOnly档案不存在与别名为空均被拒() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        BaseException missing = assertThrows(BaseException.class,
                () -> c.updateTuning(tuning("nope", Map.of(), null, null)));
        assertTrue(missing.getMessage().contains("档案不存在"), "实际：" + missing.getMessage());

        BaseException blank = assertThrows(BaseException.class,
                () -> c.updateTuning(tuning("  ", Map.of(), null, null)));
        assertTrue(blank.getMessage().contains("别名"), "实际：" + blank.getMessage());
    }

    @Test
    void 保存路径同样复核标准参数() {
        AdminModelProfileController c = controller(new ModelAdapterRegistry(new DefaultResourceLoader()));

        /* 带上 none 让温度处于可填状态，测的才是值域复核而不是条件 */
        AdminModelProfileController.ProfileBody b =
                body("https://api.deepseek.com/v1", "deepseek-flash", Map.of("reasoningEffort", "none"));
        b.setTemperature(9.0);

        BaseException e = assertThrows(BaseException.class, () -> c.save(b));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("temperature"), "实际：" + e.getMessage());
    }

    // ==================== 适配摘要的显隐：端点族未声明也要给 ====================

    @Test
    @SuppressWarnings("unchecked")
    void 端点族未声明时仍下发适配摘要以免绿框整块消失() {
        AdminModelProfileController c = controller(reg());
        /* 直接落一份"未声明类型"的档案 —— 标准 OpenAI 规范的服务商（OpenAI / DeepSeek 官方）就是这样：
           模型清单读不出类型，保存后 endpoints 为空。 */
        assertNull(lastRegistry.save(new ModelProfile("p", List.of(), List.of(), List.of(),
                "https://api.deepseek.com/v1", "sk-test", "deepseek-flash",
                null, null, null, null, null, List.of(), Map.of())));

        Map<String, Object> view = firstProfile(c);
        assertTrue(((List<?>) view.get("endpoints")).isEmpty(), "前提：这份档案没有端点族");
        assertNotNull(view.get("adapter"),
                "端点族未声明也要下发适配摘要 —— 否则卡片上的绿色工具框整块消失，"
                        + "而 OpenAI / DeepSeek 官方恰恰是内置预设里唯一有适配的两家");
        assertEquals("deepseek-flash", ((Map<String, Object>) view.get("adapter")).get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 已声明的向量档案不下发对话适配摘要() {
        AdminModelProfileController c = controller(reg());
        /* 向量档案：命中适配靠 host 兜底（同为 api.deepseek.com），但调优语义在 embedding 族、P0 未登记，
           把 chat 族的参数摆上去是张冠李戴 */
        assertNull(lastRegistry.save(new ModelProfile("v", List.of(ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING),
                "https://api.deepseek.com/v1", "sk-test", "deepseek-embedding",
                null, null, null, null, null, List.of(), Map.of())));

        /* 向量档案单独成段，从 embeddingProfiles 里取 */
        List<Map<String, Object>> views = (List<Map<String, Object>>) c.list().get("embeddingProfiles");
        assertEquals(1, views.size());
        assertNull(views.get(0).get("adapter"), "已声明的向量档案不该下发 chat 族的调优摘要");
    }

    // ==================== 辅助 ====================

    /** DeepSeek 官方地址：条件参数的用例都落在这家 */
    private static final String DS_URL = "https://api.deepseek.com/v1";

    private static ModelAdapterRegistry reg() {
        return new ModelAdapterRegistry(new DefaultResourceLoader());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstProfile(AdminModelProfileController c) {
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) c.list().get("profiles");
        assertEquals(1, profiles.size(), "本组用例都只存一份档案");
        return profiles.get(0);
    }

    private static AdminModelProfileController.TuningBody tuning(String alias, Map<String, Object> params,
                                                                Double temperature, Integer maxTokens) {
        AdminModelProfileController.TuningBody b = new AdminModelProfileController.TuningBody();
        b.setAlias(alias);
        b.setParams(params);
        b.setTemperature(temperature);
        b.setMaxTokens(maxTokens);
        return b;
    }
}
