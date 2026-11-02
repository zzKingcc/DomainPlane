package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户自助填参（"用户声明、我透传"）的落点验证。
 *
 * <p>钉的是这条路线里最容易被悄悄改坏的三件事：</p>
 * <ol>
 *   <li><b>原样下发</b> —— 未命中适配时，档案里的 params 必须<b>一个键不删、一个值不改</b>地
 *       进请求体。这是整条路线的兑现点：用户填了什么，服务商就得收到什么。
 *       这条分支以前的注释写着"正常应为空"，因为控制器那时拒绝非空；
 *       现在 30 个模型里 29 个走它 —— 从"没有测试"变成"必须有测试"。</li>
 *   <li><b>闸门拦得住</b> —— 黑名单、形状、量三道闸门的拒绝必须落在<b>保存时</b>，
 *       而不是等到对话时。它拦的不是"值对不对"（那是服务商的事），
 *       是"这个键归谁管"与"这坨东西会不会让请求体失控"。</li>
 *   <li><b>拒绝原因可读</b> —— 每一条拒绝都要带出是哪个键、超了什么限。
 *       "参数不合法"这种文案等于让用户对着一坨 JSON 自己猜。</li>
 * </ol>
 *
 * <p>用真实注册表（{@code classpath:adapters/*.json}）而不是桩：归档 29 份之后只剩
 * deepseek-flash 一份，任何非 DeepSeek 的模型名都走未命中分支 —— 这正是要测的那条路。</p>
 *
 * @author zzkingcc
 */
class CustomParamsPassthroughTest {

    private static final String BASE_URL = "https://api.example.com/v1";
    private static final String MODEL = "some-unregistered-model";

    private final ModelAdapterRegistry registry = new ModelAdapterRegistry(new DefaultResourceLoader());
    private final ModelClientFactory factory = new ModelClientFactory(registry);

    private static ModelProfile profile(Map<String, Object> params) {
        return new ModelProfile("p", List.of(ModelProfile.EP_CHAT), List.of("text"), List.of("text"),
                BASE_URL, "sk-test", MODEL, null, null, null, null, null, null, List.of(), params,
                ModelProfile.PROTOCOL_OPENAI);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 先钉住前提：这个模型名确实没被任何适配登记，否则下面测的就不是"未命中"这条分支 */
    @Test
    @DisplayName("测试用的模型名必须未命中适配（否则下面的断言测的是另一条分支）")
    void fixtureIsUnmatched() {
        assertFalse(registry.match(BASE_URL, MODEL).matched(),
                "测试夹具失效：some-unregistered-model 被适配登记了，改用别的模型名");
    }

    // ==================== ① 原样下发 ====================

    @Test
    @DisplayName("未命中适配时，档案 params 一个键不删、一个值不改地进请求体")
    void passesThroughVerbatim() {
        Map<String, Object> in = params(
                "enable_thinking", true,
                "repetition_penalty", 1.05,
                "service_tier", "auto",
                "stop_sequences", List.of("<|end|>", "<|user|>"));
        assertEquals(in, factory.effectiveParams(profile(in)),
                "透传的兑现点：用户填什么，服务商就该收到什么，平台不做任何改写");
    }

    @Test
    @DisplayName("一层扁平对象原样下发：厂商私有参数里 {type: ...} 这种写法是常例")
    void passesThroughFlatObject() {
        Map<String, Object> in = params("extra_body", params("type", "json_object", "strict", true));
        Map<String, Object> out = factory.effectiveParams(profile(in));
        assertEquals(in, out);
        assertEquals("json_object", ((Map<?, ?>) out.get("extra_body")).get("type"));
    }

    @Test
    @DisplayName("空 params 不下发任何自定义参数（不是下发一个空 map）")
    void emptyParamsYieldsEmpty() {
        assertTrue(factory.effectiveParams(profile(Map.of())).isEmpty());
    }

    // ==================== ② 黑名单 ====================

    @Test
    @DisplayName("黑名单：model / messages / stream 这些请求骨架不许碰")
    void blacklistRejectsRequestSkeleton() {
        for (String key : List.of("model", "messages", "stream", "prompt")) {
            String err = CustomParamsGate.validate(params(key, "whatever"));
            assertNotNull(err, "黑名单键「" + key + "」必须被拒");
            assertTrue(err.contains(key), "拒绝文案要点出是哪个键：" + err);
        }
    }

    @Test
    @DisplayName("黑名单：凭证与地址类键不许碰 —— 否则档案不再是可信的配置单元")
    void blacklistRejectsCredentialAndAddress() {
        for (String key : List.of("api_key", "authorization", "base_url", "url", "endpoint")) {
            String err = CustomParamsGate.validate(params(key, "https://evil.example"));
            assertNotNull(err, "黑名单键「" + key + "」必须被拒");
        }
    }

    @Test
    @DisplayName("黑名单按大小写与两端空白归一：Api_Key / MODEL 照样拦住")
    void blacklistIsCaseAndWhitespaceInsensitive() {
        assertNotNull(CustomParamsGate.validate(params("Api_Key", "x")));
        assertNotNull(CustomParamsGate.validate(params("MODEL", "gpt-9")));
        assertNotNull(CustomParamsGate.validate(params("  api_key  ", "x")));
    }

    @Test
    @DisplayName("形似但不越界的键放行：model_name 不是 model，不能被误伤")
    void lookalikeKeysAreAllowed() {
        assertNull(CustomParamsGate.validate(params("model_name", "x", "streaming", true,
                "messages_token_limit", 4096)));
    }

    // ==================== ③ 形状 ====================

    @Test
    @DisplayName("标量 / 字符串数组 / 一层扁平对象收；两层的嵌套结构拒")
    void shapeLimits() {
        assertNull(CustomParamsGate.validate(params("a", 1, "b", true, "c", "s")));
        assertNull(CustomParamsGate.validate(params("a", List.of("x", "y"))));
        assertNull(CustomParamsGate.validate(params("a", params("k", "v"))));

        String nested = CustomParamsGate.validate(params("a", params("k", params("deep", 1))));
        assertNotNull(nested, "两层嵌套必须被拒");
        assertTrue(nested.contains("嵌套过深"), nested);

        String arrInObj = CustomParamsGate.validate(params("a", params("k", List.of("x"))));
        assertNotNull(arrInObj, "对象里套数组必须被拒");
    }

    @Test
    @DisplayName("数组元素必须是字符串：数字数组会让服务商收到一堆它不认的类型")
    void arrayElementsMustBeStrings() {
        String err = CustomParamsGate.validate(params("a", List.of(1, 2, 3)));
        assertNotNull(err);
        assertTrue(err.contains("只允许字符串元素"), err);
    }

    @Test
    @DisplayName("超长字符串被拒：值超长八成是误粘贴，而不是一个合法参数")
    void tooLongStringRejected() {
        String err = CustomParamsGate.validate(params("a", "x".repeat(CustomParamsGate.MAX_STRING_LENGTH + 1)));
        assertNotNull(err);
        assertTrue(err.contains("超长"), err);
    }

    @Test
    @DisplayName("NaN / Infinity 被拒：Jackson 会写出裸标识符，服务商收到的是一段无法解析的请求体")
    void nonFiniteNumbersRejected() {
        assertNotNull(CustomParamsGate.validate(params("a", Double.NaN)));
        assertNotNull(CustomParamsGate.validate(params("a", Double.POSITIVE_INFINITY)));
    }

    @Test
    @DisplayName("非法键名被拒：空键、数字开头、带空格、带引号")
    void badKeyShapeRejected() {
        assertNotNull(CustomParamsGate.validate(params("", "v")));
        assertNotNull(CustomParamsGate.validate(params("   ", "v")));
        assertNotNull(CustomParamsGate.validate(params("1abc", "v")));
        assertNotNull(CustomParamsGate.validate(params("a b", "v")));
        /* 点号是合法的（厂商私有参数里 a.b 常见），这里只排除真正能破坏 JSON 结构的字符 */
        assertNull(CustomParamsGate.validate(params("extra.body.type", "v")));
    }

    // ==================== ④ 量 ====================

    @Test
    @DisplayName("键数超限被拒，文案带上限与当前值")
    void tooManyKeysRejected() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i <= CustomParamsGate.MAX_KEYS; i++) {
            m.put("k" + i, i);
        }
        String err = CustomParamsGate.validate(m);
        assertNotNull(err);
        assertTrue(err.contains(String.valueOf(CustomParamsGate.MAX_KEYS)), err);
        /* 恰好在上限之内必须放行：上限是防膨胀，不是砍一刀 */
        Map<String, Object> ok = new LinkedHashMap<>(m);
        ok.remove("k" + CustomParamsGate.MAX_KEYS);
        assertNull(CustomParamsGate.validate(ok));
    }

    @Test
    @DisplayName("体积超限被拒：一个键就把 4KB 撑爆同样要拦（体积是整体的，不是按键摊的）")
    void tooManyBytesRejected() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            m.put("k" + i, "x".repeat(600));
        }
        String err = CustomParamsGate.validate(m);
        assertNotNull(err);
        assertTrue(err.contains("体积"), err);
    }

    @Test
    @DisplayName("空与 null 一律放行（清除自定义参数的合法输入）")
    void emptyIsAlwaysFine() {
        assertNull(CustomParamsGate.validate(null));
        assertNull(CustomParamsGate.validate(Map.of()));
    }
}
