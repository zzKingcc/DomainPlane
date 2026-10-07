package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.server.model.ModelProfileSettings.ProfileData;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 模型档案的「三维能力画像」语义 —— 端点族 / 模态 / 布尔能力**都可多选**。
 *
 * <p>最要紧的一条：多模态模型（如同时能聊天和生图）会命中多个端点族，
 * 三者<b>不互斥</b>，历史上这里被误做成过单值 type。</p>
 *
 * <p>断言用 {@code assert} 语句（与项目其余测试一致，surefire 默认开启 -ea）。</p>
 *
 * @author zzkingcc
 */
class ModelProfileTest {

    private static ModelProfile profile(List<String> endpoints, List<String> input,
                                        List<String> output, List<String> capabilities,
                                        Integer dimensions) {
        return new ModelProfile("a", endpoints, input, output,
                "https://api.example.com/v1", "sk-test", "m", null, null, null, null, dimensions,
                capabilities, null);
    }

    @Test
    void 端点族为空时保持未声明且不猜成对话模型() {
        ModelProfile p = profile(null, null, null, null, null);
        assert p.endpoints().isEmpty()
                : "端点族为空应保持未声明，不该默认补 chat，实际 " + p.endpoints();
        assert !p.isChat()
                : "未声明类型时不能当对话模型 —— 否则向量 / 生图模型会被误当成对话模型，"
                        + "能绑到域却当不了向量模型，而且全程没有报错";
        assert !p.isEmbedding() : "未声明类型时也不能当向量模型";
    }

    @Test
    void 三个维度都为空时不抛异常且不是null() {
        ModelProfile p = profile(List.of(), List.of(), List.of(), null, null);
        assert p.input().isEmpty() && p.output().isEmpty() && p.capabilities().isEmpty()
                : "空集合应被保留为空集合，不允许是 null";
    }

    @Test
    void 多模态模型可同时命中多个端点且互不排斥() {
        ModelProfile p = profile(
                List.of(ModelProfile.EP_CHAT, ModelProfile.EP_IMAGES),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                List.of(ModelProfile.CAP_STREAMING, ModelProfile.CAP_TOOLS), null);

        assert p.endpoints().contains(ModelProfile.EP_CHAT)
                && p.endpoints().contains(ModelProfile.EP_IMAGES)
                : "两个端点族都应在，实际 " + p.endpoints();
        assert p.isChat() : "含 chat 就应能走对话端点";
        assert p.input().contains(ModelProfile.MOD_IMAGE) : "图片是输入模态，应保留";
        assert p.capabilities().contains(ModelProfile.CAP_TOOLS) : "工具调用应保留";
    }

    @Test
    void 纯向量模型不算对话模型() {
        ModelProfile p = profile(List.of(ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING),
                List.of(), 1024);
        assert !p.isChat() : "只有 embedding 端点时应判定为非对话模型";
        assert p.dimensions() != null && p.dimensions() == 1024 : "向量维度应保留，实际 " + p.dimensions();
    }

    @Test
    void 与配置文件互转时三个维度都不丢() {
        ModelProfile p = new ModelProfile("gpt4o",
                List.of(ModelProfile.EP_CHAT, ModelProfile.EP_TTS),
                List.of(ModelProfile.MOD_TEXT),
                List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_AUDIO),
                "https://api.example.com/v1", "sk-test", "gpt-4o", 0.5, 100,
                Boolean.FALSE, Boolean.TRUE, null,
                List.of(ModelProfile.CAP_STREAMING, ModelProfile.CAP_REASONING), List.of("backup"));

        ModelProfile back = ProfileData.from(p).toProfile(p.alias());
        assert back.endpoints().equals(p.endpoints()) : "端点族应往返一致： " + back.endpoints();
        assert back.input().equals(p.input()) : "输入模态应往返一致： " + back.input();
        assert back.output().equals(p.output()) : "输出模态应往返一致： " + back.output();
        assert back.capabilities().equals(p.capabilities()) : "能力应往返一致： " + back.capabilities();
        assert back.fallbacks().equals(p.fallbacks()) : "降级链应往返一致： " + back.fallbacks();
        assert Boolean.FALSE.equals(back.thinking()) && Boolean.TRUE.equals(back.returnThinking())
                : "思考开关应往返一致（false 是「显式关闭」，不能在往返里被抹成 null —— "
                        + "那等于用户关掉的思考又自己打开了），实际 thinking=" + back.thinking()
                        + ", returnThinking=" + back.returnThinking();
    }

    @Test
    void 思考开关参与指纹() {
        ModelProfile on = profile(List.of(ModelProfile.EP_CHAT), null, null, null, null);
        ModelProfile off = new ModelProfile(on.alias(), on.endpoints(), on.input(), on.output(),
                on.baseUrl(), on.apiKey(), on.modelName(), null, null,
                Boolean.FALSE, null, null, null, null);

        assert !on.fingerprint().equals(off.fingerprint())
                : "思考开关变了指纹就必须变，否则缓存里那份旧客户端照旧在用 —— "
                        + "表现为「保存成功、开关也变了，思考却还在烧」";
    }

    @Test
    void 未探测到思考能力时不标记() {
        ModelProfile p = profile(List.of(ModelProfile.EP_CHAT), null, null,
                List.of(ModelProfile.CAP_TOOLS), null);

        assert !p.capabilities().contains(ModelProfile.CAP_REASONING)
                : "没读到 reasoning 元数据就不该标成支持思考：管控台据此不显示思考开关，"
                        + "而不是显示成「已关闭」让用户以为关掉了、实际每轮还在烧思考 token";
    }

    // ==================== 采样参数默认值 ====================

    /** 变异验证点：把 {@code effectiveTemperature} 改回直接返回 {@code temperature}（即 null），
     *  {@link #未声明温度时按0_5而不是交给服务商默认()} 就会变红。 */
    @Test
    void 未声明温度时按0_5而不是交给服务商默认() {
        ModelProfile p = profile(List.of(ModelProfile.EP_CHAT), null, null, null, null);

        assert p.temperature() == null : "原始声明值应保持 null（未声明），实际 " + p.temperature();
        assert p.effectiveTemperature() == ModelProfile.DEFAULT_TEMPERATURE
                : "未声明时必须落到固定默认值；不设 temperature 的话各家端点各给各的"
                + "（常见 0.7 / 1.0），换个服务商行为就变了且没有提示，实际 " + p.effectiveTemperature();
        assert ModelProfile.DEFAULT_TEMPERATURE == 0.5 : "默认温度应为 0.5，实际 " + ModelProfile.DEFAULT_TEMPERATURE;
    }

    @Test
    void 声明了温度就以声明值为准() {
        ModelProfile p = new ModelProfile("a", List.of(ModelProfile.EP_CHAT), null, null,
                "https://api.example.com/v1", "sk", "m", 0.1, null, null, null, null, null, null);

        assert p.effectiveTemperature() == 0.1 : "显式声明不该被默认值盖掉，实际 " + p.effectiveTemperature();
    }

    @Test
    void 未声明最大token时不限制() {
        ModelProfile p = profile(List.of(ModelProfile.EP_CHAT), null, null, null, null);

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
