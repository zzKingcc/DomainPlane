package com.zzkingcc.domainplane.server.model;

import java.util.Locale;

/**
 * 服务商协议类型 —— 决定用哪个 langchain4j 适配器把档案变成模型客户端。
 *
 * 为什么需要它：各家虽然接口长得像，但协议并不真的一样。
 * OpenAI 系暴露 {@code /v1/chat/completions}，Anthropic 走 {@code /v1/messages}
 * 且鉴权头不同，Ollama 走 {@code /api/chat} 且没有密钥概念。协议差异由
 * langchain4j 各适配模块负责，本项目只负责"这份档案该交给谁"。
 *
 * 刻意只列四个：其余全落 {@link #OPENAI_COMPATIBLE}。
 * 官方适配了二十来家，但绝大多数都有 OpenAI 兼容入口，为每家开一个枚举值
 * 只会把"要不要走兼容"这个真正的判断，淹没在一堆用不上的分支里。
 */
public enum ProviderType {

    /**
     * OpenAI 兼容协议（默认）。
     *
     * 覆盖 DeepSeek、Kimi、Grok、MiMo、vLLM、硅基流动、OpenRouter、百炼兼容模式、
     * LM Studio、OneAPI / NewAPI 等各类中转网关 —— 只要对方暴露
     * {@code /v1/chat/completions}，就走这一个。
     *
     * 这是默认值而非唯一值：老档案没有这个字段时按这里处理，
     * 保证改造前后行为一致。
     */
    OPENAI_COMPATIBLE,

    /** Anthropic 官方：{@code /v1/messages}，鉴权头与请求体结构均不同。 */
    ANTHROPIC,

    /** Ollama 本地：{@code /api/chat}，无需密钥，思考开关走 {@code think} 字段。 */
    OLLAMA,

    /**
     * 阿里云百炼 / 通义原生：走 DashScope 模块。
     *
     * 暂不可用 —— langchain4j 的 dashscope 模块停更在 0.36.2（2024-11），
     * 与本项目的 1.18.x 不在同一依赖世代，强行引入会拖进一整套旧 core 并破坏依赖树。
     * 工厂不注册该分支，选中即抛异常；管控台的协议下拉已移除，也没有预设用它。
     *
     * 百炼本身提供了 OpenAI 兼容入口（{@code /compatible-mode/v1}），
     * 现阶段把百炼配成 {@link #OPENAI_COMPATIBLE} 即可拿到对话与向量能力，
     * 代价是拿不到原生模块才有的 audio 模态。等 1.x 官方出稳定版再补这个分支。
     */
    DASHSCOPE;

    /**
     * 宽松解析：空值 / 认不出来的值一律归 {@link #OPENAI_COMPATIBLE}。
     *
     * 不抛异常是刻意的：这份值会从 {@code models.json} 里读出来，
     * 而那个文件允许用户手工编辑。认不出来就退回兼容协议 ——
     * 老档案的行为与改造前完全相同，比因为一个拼错的枚举名让整张档案用不了强。
     */
    public static ProviderType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (ProviderType type : values()) {
            if (type.name().equals(key)) {
                return type;
            }
        }
        return OPENAI_COMPATIBLE;
    }
}