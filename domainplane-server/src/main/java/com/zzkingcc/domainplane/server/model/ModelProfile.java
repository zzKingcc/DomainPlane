package com.zzkingcc.domainplane.server.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * 模型档案 —— 一个 OpenAI 兼容端点的一份配置。
 *
 * <p>名字叫"档案"而不是"模型"：同一个模型可以被配成多份档案（不同 Key、不同温度、不同额度），
 * 域绑定的是档案而不是模型名。</p>
 *
 * <p><b>能力需要显式声明</b>：换了个便宜模型之后"工具静默失效"是这里最容易踩的坑，
 * 所以 {@link #capabilities()} 由使用者写出来，而不是靠试错发现。</p>
 *
 * <p><b>三个正交维度</b>（多模态模型可同时命中多个，<b>不互斥</b>）：</p>
 * <ul>
 *   <li>{@link #endpoints()} —— 端点族："怎么调它"，如 {@code chat} / {@code embedding}；</li>
 *   <li>{@link #input()} / {@link #output()} —— 模态："能吃什么 / 产出什么"，如 text / image / audio；</li>
 *   <li>{@link #capabilities()} —— 布尔能力：{@code streaming} / {@code tools}。</li>
 * </ul>
 *
 * @param alias        别名（唯一键，如 {@code default} / {@code smart}）
 * @param endpoints    端点族（可多选；<b>空 = 未声明</b>，不再默认按 chat 处理）
 * @param input        输入模态（可多选）
 * @param output       输出模态（可多选）
 * @param baseUrl      服务商地址（OpenAI 兼容，通常带 {@code /v1}）
 * @param apiKey       API Key
 * @param modelName    模型名
 * @param temperature  温度（可空 = 用服务商默认）
 * @param maxTokens    单次最大输出 token（可空 = 用服务商默认）
 * @param dimensions   向量维度（仅 embedding 用；可空 = 按模型默认，探测可得）
 * @param capabilities 布尔能力：{@code streaming} / {@code tools}
 * @param fallbacks    降级链：本档案不可用时依次尝试的别名（M3 生效，先只存）
 * @author zzkingcc
 */
public record ModelProfile(String alias,
                           List<String> endpoints,
                           List<String> input,
                           List<String> output,
                           String baseUrl,
                           String apiKey,
                           String modelName,
                           Double temperature,
                           Integer maxTokens,
                           Integer dimensions,
                           List<String> capabilities,
                           List<String> fallbacks) {

    /** 端点族：对话（/chat/completions） */
    public static final String EP_CHAT = "chat";
    /** 端点族：向量（/embeddings） */
    public static final String EP_EMBEDDING = "embedding";
    /** 端点族：重排（/rerank） */
    public static final String EP_RERANK = "rerank";
    /** 端点族：生图（/images/generations） */
    public static final String EP_IMAGES = "images";
    /** 端点族：语音合成（/audio/speech） */
    public static final String EP_TTS = "tts";
    /** 端点族：语音识别（/audio/transcriptions） */
    public static final String EP_ASR = "asr";
    /** 端点族：生视频（无统一端点） */
    public static final String EP_VIDEO = "video";

    /** 模态：文本 */
    public static final String MOD_TEXT = "text";
    /** 模态：图片 */
    public static final String MOD_IMAGE = "image";
    /** 模态：音频 */
    public static final String MOD_AUDIO = "audio";
    /** 模态：视频 */
    public static final String MOD_VIDEO = "video";
    /** 模态：文件 */
    public static final String MOD_FILE = "file";
    /** 模态：向量（仅输出侧） */
    public static final String MOD_EMBEDDING = "embedding";

    /** 支持流式输出（对话链路必需） */
    public static final String CAP_STREAMING = "streaming";
    /** 支持工具调用（function calling）——不支持的模型不会调任何工具 */
    public static final String CAP_TOOLS = "tools";

    public ModelProfile {
        /* 端点族为空<b>不再默认补 chat</b>：类型只能来自提供商元数据或用户声明。
           猜成 chat 会让向量 / 生图模型被当成对话模型 —— 能绑到域、却当不了向量模型，
           而且全程没有任何报错。空就是"未声明"，由 isChat() / isEmbedding() 如实返回 false。 */
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        input = input == null ? List.of() : List.copyOf(input);
        output = output == null ? List.of() : List.copyOf(output);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }

    /**
     * 能不能走对话端点 —— 模型档案解析链（域 → 档案）只认这个
     *
     * <p>域绑定只认对话档案：向量档案即使被绑进 {@code domainBindings} 也不会被解析链取到，
     * 向量模型走 {@code ModelProfileSettings.embeddingAlias} 那一条全局单选。</p>
     */
    public boolean isChat() {
        return endpoints.contains(EP_CHAT);
    }

    /** 能不能走向量端点 —— 只有这类档案能被选为全局向量模型 */
    public boolean isEmbedding() {
        return endpoints.contains(EP_EMBEDDING);
    }

    /** 必填项是否齐备 */
    public boolean isUsable() {
        return hasText(baseUrl) && hasText(apiKey) && hasText(modelName);
    }

    /**
     * 是否支持工具调用 —— 域绑定到不支持工具的档案时应当告警（而不是拒绝）。
     */
    public boolean supportsTools() {
        return capabilities.stream().anyMatch(c -> CAP_TOOLS.equalsIgnoreCase(c));
    }

    /**
     * 档案指纹 —— 客户端缓存的键。
     *
     * <p>含 Key 的哈希：<b>Key 一轮换，指纹就变</b>，于是自然拿到新实例，
     * 既不需要手工清缓存，也不会出现"改了 Key 却还在用旧连接"。</p>
     */
    public String fingerprint() {
        String raw = String.join("|",
                nz(baseUrl), nz(modelName),
                String.valueOf(temperature), String.valueOf(maxTokens),
                sha256(nz(apiKey)));
        return sha256(raw).substring(0, 16);
    }

    /** 脱敏后的 Key（供管控台展示） */
    public String maskedApiKey() {
        if (!hasText(apiKey)) {
            return "";
        }
        String key = apiKey.trim();
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 缺失能力声明时的提示（给管控台用） */
    public String capabilityHint() {
        if (capabilities.isEmpty()) {
            return "未声明能力：无法判断是否支持工具调用，建议补上 " + CAP_TOOLS + " / " + CAP_STREAMING;
        }
        if (!supportsTools()) {
            return "未声明 " + CAP_TOOLS + "：模型将不会调用任何工具";
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极不可能发生（JDK 必带 SHA-256）；退化为原串，宁可缓存不命中也不能抛
            return raw;
        }
    }
}
