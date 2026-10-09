package com.zzkingcc.domainplane.server.model;

import java.util.List;

/**
 * 内置服务商预设 —— 管控台「添加模型」时的快速填充清单。
 *
 * 为什么要有：用户手工填地址要记住各家网关的路径段（{@code /v1}、{@code /compatible-mode/v1}），
 * 填错的表现是 404，而报错里看不出是地址少了一段。同时"协议类型"这一项
 * 完全没有记忆成本可言——填了地址也不知道该选哪个。
 *
 * 为什么不从 langchain4j 取：它是 Java 库，不提供任何 provider 注册表或默认地址表。
 * 那张表只能自己维护 —— 好在它是一次性的静态清单，不随运行期变化。
 *
 * 与「服务商连接库」（{@link ProviderConnectionStore}）的分工：
 *
 *   • 预设 —— 系统内置、只读、不落盘，负责"平台默认值 + 协议类型"；
 *   • 连接库 —— 用户自己的连接（含私有部署地址），负责"复用已配好的那一套"。
 *
 * 两者不重叠：预设给的是公共平台的规范地址，连接库给的是用户环境里真实可用的地址。
 *
 * @author zzkingcc
 */
public final class ModelPresets {

    private ModelPresets() {
    }

    /**
     * 一条预设。
     *
     * @param id           协议类型名（{@link ProviderType#name()}），选中后直接写进档案
     * @param label        下拉里显示的名字
     * @param baseUrl      默认服务商地址
     * @param needApiKey   是否需要密钥；本地端点为false，界面据此不必强制填写
     * @param note         补充说明（可空）
     */
    public record Preset(String id, String label, String baseUrl, boolean needApiKey, String note) {

        /** 协议类型（认不出来的值退回归 OpenAI 兼容） */
        public ProviderType providerType() {
            return ProviderType.parse(id);
        }
    }

    /**
     * 全部预设。
     *
     * 除 Anthropic 与 Ollama 外，其余厂商都提供 OpenAI 兼容入口，因此协议一律
     * {@code OPENAI_COMPATIBLE}，预设之间只差默认地址。
     *
     * 末项 vLLM 不带默认地址，承担"自定义"的角色 —— 私有部署（中转网关、内网 vLLM）
     * 用户不该被预设堵死，选中后地址栏留空由用户填。
     */
    private static final List<Preset> ALL = List.of(
            new Preset("OPENAI_COMPATIBLE", "OpenAI 官方",
                    "https://api.openai.com/v1", true, null),
            new Preset("ANTHROPIC", "Anthropic 官方（Claude）",
                    "https://api.anthropic.com", true, "原生 /v1/messages 协议"),
            new Preset("OPENAI_COMPATIBLE", "Google Gemini",
                    "https://generativelanguage.googleapis.com/v1beta/openai", true,
                    "走 Google 的 OpenAI 兼容入口"),
            new Preset("OPENAI_COMPATIBLE", "xAI Grok",
                    "https://api.x.ai/v1", true, null),
            new Preset("OPENAI_COMPATIBLE", "DeepSeek 官方",
                    "https://api.deepseek.com/v1", true, null),
            new Preset("OPENAI_COMPATIBLE", "Kimi（Moonshot）",
                    "https://api.moonshot.cn/v1", true, null),
            new Preset("OPENAI_COMPATIBLE", "阿里云百炼（通义千问）",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", true,
                    "百炼同时提供多种模型；此入口按 OpenAI 协议调用"),
            new Preset("OPENAI_COMPATIBLE", "小米 MiMo",
                    "https://api.xiaomimimo.com/v1", true, null),
            new Preset("OPENAI_COMPATIBLE", "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4", true, null),
            new Preset("OLLAMA", "Ollama（本地）",
                    "http://localhost:11434", false, "本地部署，无需密钥；地址按实际部署调整"),
            new Preset("OPENAI_COMPATIBLE", "vLLM（自建）",
                    "", true, "自建服务，地址与模型名自行填写"),
            new Preset("OPENAI_COMPATIBLE", "硅基流动 SiliconFlow",
                    "https://api.siliconflow.cn/v1", true, "支持 Qwen / DeepSeek / GLM 等，开源模型较多"),
            new Preset("OPENAI_COMPATIBLE", "OpenRouter",
                    "https://openrouter.ai/api/v1", true, "一个 Key 访问多家模型"));

    /** 全部预设（不可变） */
    public static List<Preset> all() {
        return ALL;
    }
}