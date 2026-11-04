package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.HistoryVectorIndex;
import com.zzkingcc.domainplane.api.conversation.TextEmbedder;
import com.zzkingcc.domainplane.common.util.TokenizerProfile;
import com.zzkingcc.domainplane.domain.memory.DualConstraintChatMemory;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisAssemblyReportStore;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationArchive;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationSummary;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisHistorySearch;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisHistoryVectorIndex;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisSessionStorage;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import com.zzkingcc.domainplane.runtime.message.JsonContentSerializer;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
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
 * （保存 Redis 配置时由 {@code RedisPersistenceAudit} 自检并告警）。
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
        log.info("[会话记忆] RedisChatMemoryStore 初始化：键前缀 domainplane:chat:memory:，"
                        + "TTL={}，只增不减（不做条数 / Token 封顶）", ttlText);
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
            MemoryProperties memoryProperties,
            RedisHistoryVectorIndex redisHistoryVectorIndex,
            TextEmbedder historyTextEmbedder) {
        TokenizerProfile profile = new TokenizerProfile(
                memoryProperties.getRecallWordWeight(),
                memoryProperties.getRecallBigramWeight(),
                memoryProperties.getRecallUnigramWeight(),
                new LinkedHashSet<>(memoryProperties.getRecallExtraStopWords()));
        boolean semantic = memoryProperties.isHistoryVectorEnabled();
        log.info("[会话检索] 键前缀 domainplane:conv:idx: / :terms:，语义路={}，分词权重 word/bigram/unigram={}/{}/{}，停用词 {} 个",
                semantic ? "开启（关键词 + 向量 RRF）" : "关闭（纯关键词）",
                profile.wordWeight(), profile.bigramWeight(), profile.unigramWeight(),
                profile.extraStopWords().size());
        return new RedisHistorySearch(stringRedisTemplate, redisConversationArchive, profile,
                redisHistoryVectorIndex, historyTextEmbedder, semantic);
    }

    /**
     * 历史轮次的向量索引 Bean（F04-b）。
     *
     * <p>与倒排索引同族前缀 {@code domainplane:conv:vec:}，同为派生数据（可重放归档重建），
     * 同一套 30 天 TTL 与删域级联。它只服务"字面不同但语义相同"的那类召回：
     * 关键词路照旧负责专有名词、编号、金额这些向量路不擅长的东西。</p>
     */
    @Bean
    public RedisHistoryVectorIndex redisHistoryVectorIndex(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate) {
        log.info("[会话检索] RedisHistoryVectorIndex 初始化：键前缀 domainplane:conv:vec:，"
                + "一会话一 Hash（field=turnId），每会话上限 {} 轮", HistoryVectorIndex.MAX_TURNS_PER_SESSION);
        return new RedisHistoryVectorIndex(stringRedisTemplate);
    }

    /**
     * 历史检索用的嵌入器（F04-b）。
     *
     * <p>未配嵌入模型时返回 {@code null} 而不是抛：语义路是增强不是依赖，
     * "没配"应当静默退化成纯关键词，而不是给每轮对话刷一条警告。</p>
     */
    @Bean
    public TextEmbedder historyTextEmbedder(LlmModelHolder modelHolder) {
        return text -> {
            if (!modelHolder.isEmbeddingConfigured()) {
                return null;
            }
            return modelHolder.embeddingModel().embed(text).content().vector();
        };
    }

    /**
     * 装配清单账本 Bean（F04-a）。
     *
     * <p>每会话一 List，只留最近 {@link AssemblyReportStore#KEEP_PER_SESSION} 条：
     * 它是排障用的账，不是档案，老账没有保留价值。</p>
     */
    @Bean
    public RedisAssemblyReportStore redisAssemblyReportStore(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate) {
        log.info("[装配清单] RedisAssemblyReportStore 初始化：键前缀 domainplane:conv:asm:，"
                + "每会话保留最近 {} 条", AssemblyReportStore.KEEP_PER_SESSION);
        return new RedisAssemblyReportStore(stringRedisTemplate);
    }

    /**
     * 会话体量盘点与导出 Bean（F04-c）。
     *
     * <p>只读：接口层没有任何删除动作。存储增长靠"看得见 + 搬得走"来应对，
     * 不靠清理——那与"原始记忆不主动删除"的硬约束正面冲突。</p>
     */
    @Bean
    public RedisSessionStorage redisSessionStorage(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            RedisConversationArchive redisConversationArchive,
            RedisAssemblyReportStore redisAssemblyReportStore,
            RedisHistoryVectorIndex redisHistoryVectorIndex) {
        log.info("[会话体量] 只读盘点 + 会话副本导出，不含任何清理动作");
        return new RedisSessionStorage(stringRedisTemplate, redisConversationArchive,
                redisAssemblyReportStore, redisHistoryVectorIndex);
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
        log.debug("[检查点] ObjectStreamStateSerializer 已注册 ChatMessage / ToolExecutionRequest / Content 序列化器");
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
