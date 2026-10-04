package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.domain.memory.DualConstraintChatMemory;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.bsc.langgraph4j.langchain4j.serializer.std.ChatMesssageSerializer;
import org.bsc.langgraph4j.langchain4j.serializer.std.ToolExecutionRequestSerializer;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 会话记忆配置
 *
 * <p>上限与保留期取自 {@code domainplane.memory.*}。一期语义：记忆<b>只增不淘汰</b>、默认<b>永久不过期</b>
 * ——到上限后由入口拒绝新一轮（{@code 30004}），调用方必须换 {@code sessionId}；Redis 侧靠 RDB+AOF
 * 保住（启动期由 {@code RedisPersistenceAudit} 自检并告警）。</p>
 *
 * @author zzkingcc
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfiguration.class);

    /**
     * Redis 会话记忆存储 Bean
     */
    @Bean
    public RedisChatMemoryStore redisChatMemoryStore(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            MemoryProperties memoryProperties) {
        String ttlText = memoryProperties.getTtl() != null
                ? memoryProperties.getTtl().toMinutes() + " 分钟" : "永久";
        log.info("[会话记忆] RedisChatMemoryStore 初始化，双约束（只增不淘汰）：maxMessages={} 条（≈{} 轮问答），"
                        + "maxTokens={} tokens，TTL={}；到上限后入口拒绝新一轮（30004），需换 sessionId",
                memoryProperties.getMaxMessages(), memoryProperties.getMaxMessages() / 2,
                memoryProperties.getMaxTokens(), ttlText);
        return new RedisChatMemoryStore(stringRedisTemplate, memoryProperties.getTtl());
    }

    /**
     * 多会话记忆 Provider
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore redisChatMemoryStore,
                                                 MemoryProperties memoryProperties) {
        return memoryId -> new DualConstraintChatMemory(
                memoryId,
                memoryProperties.getMaxMessages(),
                memoryProperties.getMaxTokens(),
                redisChatMemoryStore
        );
    }

    /**
     * Graph 状态序列化器 Bean
     */
    @Bean
    public ObjectStreamStateSerializer<MessagesState<ChatMessage>> graphStateSerializer() {
        var serializer = new ObjectStreamStateSerializer<MessagesState<ChatMessage>>(MessagesState::new);
        serializer.mapper()
                .register(ToolExecutionRequest.class, new ToolExecutionRequestSerializer())
                .register(ChatMessage.class, new ChatMesssageSerializer());
        log.info("[检查点] ObjectStreamStateSerializer 初始化，已注册 ChatMessage/ToolExecutionRequest 序列化器");
        return serializer;
    }

    /**
     * Graph 检查点持久化 Bean
     */
    @Bean
    public RedisCheckpointSaver redisCheckpointSaver(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> graphStateSerializer,
            MemoryProperties memoryProperties) {
        log.info("[检查点] RedisCheckpointSaver 初始化,用于 graph interrupt/resume 断点续跑, TTL={}",
                memoryProperties.getCheckpointTtl());
        return new RedisCheckpointSaver(stringRedisTemplate, graphStateSerializer,
                memoryProperties.getCheckpointTtl());
    }
}
