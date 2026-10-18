package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文窗口字段（{@code ModelProfile#contextWindow}）的语义。
 *
 * <p>这个字段最容易出的两种错都在"复制一份"和"归一"上，不在它自己的取值：</p>
 * <ul>
 *   <li>某条复制方法（withDetected / withConfirmed / withTuning / withDimensions /
 *       withReturnThinking）漏传它：表现是"保存后那个值凭空消失"，且全程不报错；</li>
 *   <li>被顺手加进指纹或绑域门禁：前者让用户改个数字就打醒一份新客户端，
 *       后者让一个填错的数字把档案变成绑不上域。</li>
 * </ul>
 *
 * <p>断言用 {@code assert} 语句（与项目其余测试一致，surefire 默认开启 -ea）。</p>
 *
 * @author zzkingcc
 */
class ModelProfileContextWindowTest {

    /** 一份各字段齐备的档案，上下文窗口为 32000 */
    private static ModelProfile base() {
        return new ModelProfile("a", List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT),
                "https://api.example.com/v1", "sk-test", "m",
                0.5, 2048, 32_000, Boolean.TRUE, Boolean.TRUE, null, List.of("fb"),
                Map.of("enable_thinking", true));
    }

    @Test
    void 每个复制方法都把上下文窗口带过去() {
        ModelProfile p = base();

        assertContext(p.withDetected(List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT), true), "withDetected");
        assertContext(p.withConfirmed(List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT)), "withConfirmed");
        assertContext(p.withTuning(0.9, 4096, Map.of()), "withTuning");
        assertContext(p.withTuning(0.9, 4096, 1024, Map.of()), "withTuning(带维度)");
        assertContext(p.withDimensions(768), "withDimensions");
        assertContext(p.withReturnThinking(Boolean.FALSE), "withReturnThinking");
    }

    private static void assertContext(ModelProfile copy, String method) {
        assert copy.contextWindow() != null && copy.contextWindow() == 32_000
                : method + " 之后上下文窗口丢了，实际 " + copy.contextWindow()
                        + " —— 漏传的表现是「保存后那个值凭空消失」，且全程不报错";
    }

    @Test
    void 专用复制方法只换这一个字段() {
        ModelProfile p = base();
        ModelProfile cleared = p.withContextWindow(128_000);
        assert cleared.contextWindow() == 128_000 : "应换成新值，实际 " + cleared.contextWindow();
        assert cleared.maxTokens() == 2048 : "输出上限不该被这个动作碰到";
        assert cleared.temperature() == 0.5 : "温度不该被这个动作碰到";
        assert cleared.returnThinking() : "思考回写不该被这个动作碰到";
        assert cleared.params().equals(Map.of("enable_thinking", true)) : "私有参数不该被这个动作碰到";

        assertNull(p.withContextWindow(null).contextWindow(), "传 null = 清除声明");
    }

    @Test
    void 未声明与非正数都归一为未声明() {
        ModelProfile p = base();
        assertNull(p.withContextWindow(null).effectiveContextWindow(), "未声明应返回 null（= 平台不知道）");
        assertNull(p.withContextWindow(0).effectiveContextWindow(), "0 是填错，不是「上下文为零」");
        assertNull(p.withContextWindow(-1).effectiveContextWindow(),
                "负数若不归一，流进装配预算会把预算算成负数，降级链会一路退到只剩系统提示 + 本轮提问");
        assertEquals(8_192, p.withContextWindow(8_192).effectiveContextWindow());
    }

    @Test
    void 不参与绑域门禁() {
        ModelProfile p = base();
        assertTrue(p.domainBindable(), "前缀条件：这份档案本来就能绑域");
        assertTrue(p.withContextWindow(-1).domainBindable(),
                "填错一个数字不该让档案绑不上域 —— 那比不填更糟");
        assertTrue(p.withContextWindow(null).domainBindable(), "清除声明同样不影响绑域");
    }

    @Test
    void 不进指纹() {
        ModelProfile p = base();
        String before = p.fingerprint();
        assertEquals(before, p.withContextWindow(8_192).fingerprint(),
                "改上下文长度不该打醒新客户端：它不在建客户端时被消费，纳入只会白丢一份连接池");
        assertEquals(before, p.withContextWindow(null).fingerprint());
    }

    @Test
    void 持久化往返不丢字段() {
        ModelProfile p = base();
        ModelProfile roundTrip = ModelProfileSettings.ProfileData.from(p).toProfile(p.alias());
        assertEquals(32_000, roundTrip.contextWindow(),
                "models.json 走的是 ProfileData，不是 record 本身；漏加字段会让它落盘即丢");

        ModelProfile cleared = p.withContextWindow(null);
        assertNull(ModelProfileSettings.ProfileData.from(cleared).toProfile("a").contextWindow(),
                "未声明应往返为 null（老文件没有这一项，读出来也必须是 null）");
    }

    @Test
    void 老文件缺字段时反序列化为未声明() {
        /* 直接构造 ProfileData（模拟老 models.json）：不打这一枪，
           "加字段"就没被证明对存量档案零影响 */
        ModelProfileSettings.ProfileData old = new ModelProfileSettings.ProfileData();
        old.setBaseUrl("https://api.example.com/v1");
        old.setApiKey("sk-test");
        old.setModelName("m");
        old.setEndpoints(new java.util.ArrayList<>(List.of(ModelProfile.EP_CHAT)));
        old.setInput(new java.util.ArrayList<>(List.of(ModelProfile.MOD_TEXT)));
        old.setOutput(new java.util.ArrayList<>(List.of(ModelProfile.MOD_TEXT)));
        ModelProfile loaded = old.toProfile("a");
        assertNull(loaded.contextWindow(), "老档案没有这一项 → 未声明 → 装配预算维持配置值");
        assertTrue(loaded.domainBindable(), "老档案的可用性不该因此变化");
    }

    @Test
    void 兼容构造器默认未声明() {
        /* 既有调用方与测试一律走 14 参重载（见 record 里的兼容构造器）：
           它必须把新字段落成 null，而不是让人以为"默认给了一个数" */
        ModelProfile legacy = new ModelProfile("a", List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT),
                "https://api.example.com/v1", "sk-test", "m",
                null, null, null, null, null, new java.util.ArrayList<>(), new LinkedHashMap<>());
        assertNull(legacy.contextWindow());
        assertNull(legacy.effectiveContextWindow());
    }
}
