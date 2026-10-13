package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.common.util.TokenizerProfile;
import com.zzkingcc.domainplane.domain.memory.DualConstraintChatMemory;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationArchive;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationSummary;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisHistorySearch;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import com.zzkingcc.domainplane.runtime.message.JsonContentSerializer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
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

import java.util.LinkedHashSet;

/**
 * 会话记忆配置
 *
 * 上限与保留期取自 {@code domainplane.memory.*}。
 * M2 起记忆不再因上限拒绝新一轮（改为召回装配逐级降级，会话无限增长），
 * 双约束上限退化为内部自检口径；Redis 侧靠 RDB+AOF 保住
 * （启动期由 {@code RedisPersistenceAudit} 自检并告警）。
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
        log.info("[会话记忆] RedisChatMemoryStore 初始化：maxMessages={} 条（≈{} 轮问答），"
                        + "maxTokens={} tokens，TTL={}；上限只作内部自检，超限由装配层降级而非拒绝",
                memoryProperties.getMaxMessages(), memoryProperties.getMaxMessages() / 2,
                memoryProperties.getMaxTokens(), ttlText);
        return new RedisChatMemoryStore(stringRedisTemplate, memoryProperties.getTtl());
    }

    /**
     * Redis 会话归档 Bean（按轮存全流程，无封顶无淘汰）。
     *
     * <p>与记忆是两套键、两种生命周期：记忆默认永久、检查点 24h、归档永久。
     * 归档不复用记忆的 {@code ttl}：那是"消息列表"的保留期，
     * 而归档装的是全流程档案，被一个"记忆只保留 30 天"的配置顺带删掉说不通。
     * 真要保留期就单独立项。</p>
     */
    @Bean
    public RedisConversationArchive redisConversationArchive(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate) {
        log.info("[会话归档] RedisConversationArchive 初始化：键前缀 domainplane:chat:archive:，"
                + "按轮归档全流程，无封顶无淘汰，TTL=永久");
        return new RedisConversationArchive(stringRedisTemplate);
    }

    /**
     * Redis 会话历史倒排检索 Bean（M3）。
     *
     * <p>依赖归档：命中候选后要按 turnId 读轮记录才能组装卡片。
     * 索引是派生数据：写失败只告警、查不到走子串扫描，删域时必须抛。</p>
     *
     * <p>分词口径（权重 / 追加停用词）来自 {@code domainplane.memory.recall.*}（M4 调优入口）。
     * 两者作用面不同：<b>追加停用词</b>同时决定写索引与查询两侧的切词结果，改完需重放归档重建；
     * <b>权重</b>只在查询打分时生效，不参与切词，改完即时生效、无需重建。</p>
     */
    @Bean
    public RedisHistorySearch redisHistorySearch(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            RedisConversationArchive redisConversationArchive,
            MemoryProperties memoryProperties) {
        TokenizerProfile profile = new TokenizerProfile(
                memoryProperties.getRecallWordWeight(),
                memoryProperties.getRecallBigramWeight(),
                memoryProperties.getRecallUnigramWeight(),
                new LinkedHashSet<>(memoryProperties.getRecallExtraStopWords()));
        log.info("[会话检索] RedisHistorySearch 初始化：键前缀 domainplane:conv:idx: / domainplane:conv:terms:，"
                        + "一词根一 ZSET，member/score=turnId；分词权重 word={} bigram={} unigram={}，追加停用词 {} 个",
                profile.wordWeight(), profile.bigramWeight(), profile.unigramWeight(),
                profile.extraStopWords().size());
        return new RedisHistorySearch(stringRedisTemplate, redisConversationArchive, profile);
    }

    /**
     * Redis 会话滚动摘要 Bean（M4-R1）。
     *
     * <p>与归档 / 索引同族的 {@code domainplane:conv:*} 前缀，但生命周期特殊：
     * 索引是<b>可重建</b>的派生数据，摘要是<b>有损压缩</b>的产物：过期后重放归档
     * 也复原不出同一份措辞，所以按"档案级"对待（不设 TTL），只有删域时随会话一起清。</p>
     */
    @Bean
    public RedisConversationSummary redisConversationSummary(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate) {
        log.info("[会话摘要] RedisConversationSummary 初始化：键前缀 domainplane:conv:sum:，"
                + "一会话一 Hash（text + upTo），TTL=永久（删域级联清理）");
        return new RedisConversationSummary(stringRedisTemplate);
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
     *
     * <p>必须把 {@link Content} 也注册上：langchain4j 的消息类与内容类都不是
     * {@code Serializable}，注册了 {@link ChatMessage} 只覆盖了"消息壳"，
     * 消息里的内容块（{@code TextContent} / {@code ImageContent}）仍会退回 Java 序列化。
     * 单文本消息走字符串旁路所以一直没暴露，多模态消息一进图状态，
     * {@code CompiledGraph.cloneState} 深拷贝时就会抛 {@code NotSerializableException} 炸掉整轮。</p>
     */
    @Bean
    public ObjectStreamStateSerializer<MessagesState<ChatMessage>> graphStateSerializer() {
        var serializer = new ObjectStreamStateSerializer<MessagesState<ChatMessage>>(MessagesState::new);
        serializer.mapper()
                .register(ToolExecutionRequest.class, new ToolExecutionRequestSerializer())
                .register(ChatMessage.class, new ChatMesssageSerializer())
                .register(Content.class, new JsonContentSerializer());
        log.info("[检查点] ObjectStreamStateSerializer 初始化，已注册 ChatMessage/ToolExecutionRequest/Content 序列化器");
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
