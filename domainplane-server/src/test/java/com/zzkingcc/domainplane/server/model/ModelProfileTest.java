package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.server.model.ModelProfileSettings.ProfileData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型档案的类型语义 —— 端点族与模态**都可多选**。
 *
 * 最要紧的一条：多模态模型（如同时能聊天和生图）会命中多个端点族，两者不互斥。
 *
 * 断言用 {@code assert} 语句（与项目其余测试一致，surefire 默认开启 -ea）。
 *
 * @author zzkingcc
 */
class ModelProfileTest {

    private static ModelProfile profile(List<String> endpoints, List<String> input,
                                        List<String> output, Integer dimensions) {
        return new ModelProfile("a", endpoints, input, output, "https://api.example.com/v1",
                "sk-test", "m", null, null, null, null, dimensions, null, null);
    }

    @Test
    void 端点族为空时保持未声明且不猜成对话模型() {
        ModelProfile p = profile(null, null, null, null);
        assert p.endpoints().isEmpty()
                : "端点族为空应保持未声明，不该默认补 chat，实际 " + p.endpoints();
        assert !p.isChat()
                : "未声明类型时不能当对话模型 —— 否则向量 / 生图模型会被误当成对话模型，"
                        + "能绑到域却当不了向量模型，而且全程没有报错";
        assert !p.isEmbedding() : "未声明类型时也不能当向量模型";
    }

    @Test
    void 集合字段为空时不抛异常且不是null() {
        ModelProfile p = profile(List.of(), List.of(), List.of(), null);
        assert p.input().isEmpty() && p.output().isEmpty()
                : "空集合应被保留为空集合，不允许是 null";
    }

    @Test
    void 多模态模型可同时命中多个端点且互不排斥() {
        ModelProfile p = profile(
                List.of(ModelProfile.EP_CHAT, ModelProfile.EP_IMAGES),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), null);

        assert p.endpoints().contains(ModelProfile.EP_CHAT)
                && p.endpoints().contains(ModelProfile.EP_IMAGES)
                : "两个端点族都应在，实际 " + p.endpoints();
        assert p.isChat() : "含 chat 就应能走对话端点";
        assert p.input().contains(ModelProfile.MOD_IMAGE) : "图片是输入模态，应保留";
    }

    @Test
    void 纯向量模型不算对话模型() {
        ModelProfile p = profile(List.of(ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING), 1024);
        assert !p.isChat() : "只有 embedding 端点时应判定为非对话模型";
        assert p.dimensions() != null && p.dimensions() == 1024 : "向量维度应保留，实际 " + p.dimensions();
    }

    @Test
    void 与配置文件互转时字段都不丢() {
        ModelProfile p = new ModelProfile("gpt4o", List.of(ModelProfile.EP_CHAT, ModelProfile.EP_TTS),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_AUDIO),
                "https://api.example.com/v1", "sk-test", "gpt-4o", 0.5, 100,
                Boolean.FALSE, Boolean.TRUE, null, List.of("backup"), null);

        ModelProfile back = ProfileData.from(p).toProfile(p.alias());
        assert back.endpoints().equals(p.endpoints()) : "端点族应往返一致： " + back.endpoints();
        assert back.input().equals(p.input()) : "输入模态应往返一致： " + back.input();
        assert back.output().equals(p.output()) : "输出模态应往返一致： " + back.output();
        assert back.fallbacks().equals(p.fallbacks()) : "降级链应往返一致： " + back.fallbacks();
        assert Boolean.FALSE.equals(back.thinking()) && Boolean.TRUE.equals(back.returnThinking())
                : "思考开关应往返一致（false 是「显式关闭」，不能在往返里被抹成 null —— "
                        + "那等于用户关掉的思考又自己打开了），实际 thinking=" + back.thinking()
                        + ", returnThinking=" + back.returnThinking();
    }

    @Test
    void 思考开关参与指纹() {
        ModelProfile on = profile(List.of(ModelProfile.EP_CHAT), null, null, null);
        ModelProfile off = new ModelProfile(on.alias(), on.endpoints(), on.input(), on.output(),
                on.baseUrl(), on.apiKey(), on.modelName(), null, null, Boolean.FALSE, null, null, null, null);

        assert !on.fingerprint().equals(off.fingerprint())
                : "思考开关变了指纹就必须变，否则缓存里那份旧客户端照旧在用 —— "
                        + "表现为「保存成功、开关也变了，思考却还在烧」";
    }

    @Test
    void 只换回写开关时其余字段原样带走() {
        ModelProfile p = new ModelProfile("a", List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT),
                "https://api.example.com/v1", "sk-test", "m", 0.5, 100,
                Boolean.FALSE, null, 1024, List.of("backup"), Map.of("top_p", 0.9));

        ModelProfile on = p.withReturnThinking(Boolean.TRUE);
        assert Boolean.TRUE.equals(on.returnThinking()) : "开关应换成 true，实际 " + on.returnThinking();
        assert on.endpoints().equals(p.endpoints()) && on.input().equals(p.input())
                && on.output().equals(p.output()) : "端点族与模态应原样带走";
        assert on.baseUrl().equals(p.baseUrl()) && on.apiKey().equals(p.apiKey())
                && on.modelName().equals(p.modelName()) : "连接信息应原样带走";
        assert on.temperature().equals(p.temperature()) && on.maxTokens().equals(p.maxTokens())
                && on.thinking().equals(p.thinking()) : "调优值与思考模式应原样带走";
        assert on.dimensions().equals(p.dimensions()) && on.fallbacks().equals(p.fallbacks())
                && on.params().equals(p.params()) : "维度 / 降级链 / 私有参数应原样带走";
        assert !on.fingerprint().equals(p.fingerprint())
                : "回写开关变了指纹就必须变，否则缓存里那份旧客户端照旧在用 —— "
                        + "表现为「保存成功、开关也变了，思考却照旧不回传」";
    }

    // ==================== 私有调优参数（服务商适配） ====================

    @Test
    void 私有调优参数参与指纹() {
        ModelProfile base = profile(List.of(ModelProfile.EP_CHAT), null, null, null);
        ModelProfile tuned = new ModelProfile(base.alias(), base.endpoints(), base.input(), base.output(),
                base.baseUrl(), base.apiKey(), base.modelName(), null, null, null, null, null, null,
                Map.of("reasoning_effort", "high"));

        assert !base.fingerprint().equals(tuned.fingerprint())
                : "调优参数变了指纹就必须变，否则缓存里那份旧客户端照旧在用 —— "
                        + "表现为「保存成功、参数也变了，行为却还在按旧的来」";

        /* 空 params 与改造前逐字节一致：存量缓存不被打醒 */
        ModelProfile same = new ModelProfile(base.alias(), base.endpoints(), base.input(), base.output(),
                base.baseUrl(), base.apiKey(), base.modelName(), null, null, null, null, null, null, null);
        assert base.fingerprint().equals(same.fingerprint())
                : "未适配档案（params 为空）的指纹不能因这次改造变化，否则全网缓存被打醒";
    }

    @Test
    void params剔除null值且不可变() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("verbosity", "low");
        in.put("reasoning_effort", null); // null 的语义是「不发送该参数」
        ModelProfile p = new ModelProfile("a", List.of(ModelProfile.EP_CHAT), null, null,
                "https://api.example.com/v1", "sk", "m", null, null, null, null, null, null, in);

        assert p.params().size() == 1 && "low".equals(p.params().get("verbosity"))
                : "null 值应被剔除（Map.copyOf 遇 null 直接 NPE），实际 " + p.params();
        try {
            p.params().put("x", 1);
            throw new AssertionError("params 应不可变");
        } catch (UnsupportedOperationException expected) {
            // 预期：不可变 map
        }
    }

    @Test
    void params与配置文件互转不丢() {
        ModelProfile p = new ModelProfile("gpt4o", List.of(ModelProfile.EP_CHAT),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_TEXT),
                "https://api.example.com/v1", "sk-test", "gpt-5.6-sol", null, null,
                null, null, null, null,
                Map.of("reasoning_effort", "high", "verbosity", "low"));

        ModelProfile back = ProfileData.from(p).toProfile(p.alias());
        assert back.params().equals(p.params())
                : "私有调优参数应往返一致： " + back.params() + " != " + p.params();
    }

    // ==================== 采样参数 ====================

    @Test
    void 未声明温度时保持null而不是替服务商决定() {
        ModelProfile p = profile(List.of(ModelProfile.EP_CHAT), null, null, null);

        assert p.temperature() == null
                : "未声明就保持 null：调用方据此不发送 temperature，由服务商默认决定，实际 " + p.temperature();
    }

    @Test
    void 声明了温度就以声明值为准() {
        ModelProfile p = new ModelProfile("a", List.of(ModelProfile.EP_CHAT), null, null,
                "https://api.example.com/v1", "sk", "m", 0.1, null, null, null, null, null, null);

        assert p.temperature() == 0.1 : "声明值应原样保留，实际 " + p.temperature();
    }

    @Test
    void 未声明最大token时不限制() {
        ModelProfile p = profile(List.of(ModelProfile.EP_CHAT), null, null, null);

        assert p.maxTokens() == null : "原始声明值应保持 null，实际 " + p.maxTokens();
        assert p.effectiveMaxTokens() == null
                : "留空就是「不限制」：调用方据此不发送 max_tokens，由模型上下文决定";
    }

    @Test
    void 声明了最大token就以声明值为准() {
        ModelProfile p = new ModelProfile("a", List.of(ModelProfile.EP_CHAT), null, null,
                "https://api.example.com/v1", "sk", "m", null, 2048, null, null, null, null, null);

        assert p.effectiveMaxTokens() == 2048 : "显式声明就该设限，实际 " + p.effectiveMaxTokens();
    }
}
