package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模型装配
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiModelConfiguration {

    @Bean
    public ChatModel openAiChatModel(LlmModelHolder holder) {
        log.info("[AI模型装配] 注册 ChatModel 代理（支持运行时热替换）");
        return holder.chatModel();
    }

    @Bean
    public StreamingChatModel openAiStreamingChatModel(LlmModelHolder holder) {
        log.info("[AI模型装配] 注册 StreamingChatModel 代理（支持运行时热替换）");
        return holder.streamingChatModel();
    }

    @Bean
    public EmbeddingModel openAiEmbeddingModel(LlmModelHolder holder) {
        log.info("[AI模型装配] 注册 EmbeddingModel 代理（支持运行时热替换）");
        return holder.embeddingModel();
    }
}
