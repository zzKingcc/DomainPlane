package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型服务商设置（管控台可编辑）
 * @author zzkingcc
 */
@Data
public class LlmSettings {

    /** 对话模型服务商地址（OpenAI 兼容），如 https://dashscope.aliyuncs.com/compatible-mode/v1 */
    private String chatBaseUrl;

    /** 对话模型 API Key */
    private String chatApiKey;

    /** 对话模型名，如 qwen-plus */
    private String chatModelName;

    /** 采样温度 */
    private Double chatTemperature;

    /** 单次最大 token */
    private Integer chatMaxTokens;

    /** 对话模型能力（探测得出：streaming / tools / vision；仅展示用） */
    private List<String> chatCapabilities = new ArrayList<>();

    /** 向量模型服务商地址（留空则复用对话模型地址） */
    private String embeddingBaseUrl;

    /** 向量模型 API Key（留空则复用对话模型 Key） */
    private String embeddingApiKey;

    /** 向量模型名，如 text-embedding-v3。注意维度须与 ES 索引一致 */
    private String embeddingModelName;

    /**
     * 向量维度（可空）。
     */
    private Integer embeddingDimensions;

    /** 向量模型能力（探测得出；仅展示用） */
    private List<String> embeddingCapabilities = new ArrayList<>();

    // ===== 派生值 =====

    /** 向量地址（未单独填写时回落到对话地址） */
    @JsonIgnore
    public String effectiveEmbeddingBaseUrl() {
        return hasText(embeddingBaseUrl) ? embeddingBaseUrl : chatBaseUrl;
    }

    /** 向量 Key（未单独填写时回落到对话 Key） */
    @JsonIgnore
    public String effectiveEmbeddingApiKey() {
        return hasText(embeddingApiKey) ? embeddingApiKey : chatApiKey;
    }

    /**
     * 是否已具备最基本的可用条件。
     */
    @JsonIgnore
    public boolean isUsable() {
        return hasText(chatBaseUrl) && hasText(chatApiKey) && hasText(chatModelName);
    }

    /**
     * 向量能力是否可用。
     */
    @JsonIgnore
    public boolean isEmbeddingUsable() {
        return hasText(effectiveEmbeddingBaseUrl()) && hasText(effectiveEmbeddingApiKey())
                && hasText(embeddingModelName);
    }

    /**
     * 对话 Key 的掩码形式，供管控台展示。
     */
    @JsonIgnore
    public String getMaskedChatApiKey() {
        return mask(chatApiKey);
    }

    /** 向量 Key 的掩码形式（同样不落盘） */
    @JsonIgnore
    public String getMaskedEmbeddingApiKey() {
        return mask(embeddingApiKey);
    }

    private static String mask(String key) {
        if (!hasText(key)) {
            return null;
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "******";
        }
        return k.substring(0, 4) + "******" + k.substring(k.length() - 4);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
