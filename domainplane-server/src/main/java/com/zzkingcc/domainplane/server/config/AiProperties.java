package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型配置（统一前缀 {@code domainplane.ai}）。
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.ai")
public class AiProperties {

    /** 非流式对话模型（用于非流式场景；当前编排主用流式） */
    private Chat chat = new Chat();

    /** 文本向量模型（知识库向量化 + 检索） */
    private Embedding embedding = new Embedding();

    @Data
    public static class Chat {
        /** 兼容 OpenAI 的基地址（如 DashScope 兼容模式地址） */
        private String baseUrl;
        /** API Key */
        private String apiKey;
        /** 模型名（不写代码默认：未配置即 null，管控台填写优先，避免脚手架遗留的默认值在页面冒出来） */
        private String modelName;
        /** 采样温度（不写代码默认：未配置即 null，管控台填写优先） */
        private Double temperature;
        /** 单次最大 token（不写代码默认：未配置即 null，管控台填写优先） */
        private Integer maxTokens;
    }

    @Data
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        /**
         * 文本向量模型名。
         * 刻意不设代码默认值：向量模型的维度直接决定 ES 索引 mapping，
         */
        private String modelName;

        /**
         * 向量维度，管控台填写优先。
         */
        private Integer dimensions;
    }
}
