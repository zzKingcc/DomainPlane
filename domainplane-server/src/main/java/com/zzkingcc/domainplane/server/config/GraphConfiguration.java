package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.conversation.ContextAssembler;
import com.zzkingcc.domainplane.runtime.conversation.RollingSummaryService;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.orchestration.AgentOrchestrationService;
import com.zzkingcc.domainplane.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolCallEchoResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationArchive;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationSummary;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisHistorySearch;
import com.zzkingcc.domainplane.server.conversation.ChatModelSummarizer;
import com.zzkingcc.domainplane.server.prompt.DomainSystemPromptResolver;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.tool.DomainToolCallEchoResolver;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 编排配置
 * @author zzkingcc
 */
@Configuration
@EnableConfigurationProperties({PromptProperties.class, AgentProperties.class})
public class GraphConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GraphConfiguration.class);

    /**
     * 图编排专用线程池
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService agentExecutor(AgentProperties agentProperties) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "domainplane-agent-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                agentProperties.getCorePoolSize(),
                agentProperties.getMaxPoolSize(),
                agentProperties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(agentProperties.getQueueCapacity()),
                factory);

        log.info("[Agent编排配置] 编排线程池 core={}, max={}, queue={}",
                agentProperties.getCorePoolSize(), agentProperties.getMaxPoolSize(),
                agentProperties.getQueueCapacity());
        return executor;
    }

    /**
     * 对外只暴露 {@link AgentService} 契约,接入方不感知具体编排实现。
     */
    @Bean
    @Lazy
    public AgentService agentService(
            @Qualifier("openAiStreamingChatModel") StreamingChatModel streamingChatModel,
            @Qualifier("chatMemoryProvider") ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            RedisCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> graphStateSerializer,
            CancellationRegistry cancellationRegistry,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver,
            MediaAttachmentStore mediaAttachmentStore,
            RedisConversationArchive conversationArchive,
            MemoryProperties memoryProperties,
            RedisHistorySearch historySearch,
            RollingSummaryService rollingSummaryService) {

        log.info("[Agent编排配置] 创建 AgentOrchestrationService，默认模型={}，会话记忆=Redis持久化，"
                        + "检查点=Redis(支持 interrupt/resume)，系统提示词解析器={}，模型解析器={}，"
                        + "工具执行信息回写解析器={}，媒体暂存区={}，会话归档={}，倒排检索={}，"
                        + "装配预算={}(窗口 {}%/{} 轮、摘要 {}、卡片 {} 张)，滚动摘要={}",
                streamingChatModel == null ? "unknown" : streamingChatModel.getClass().getSimpleName(),
                promptResolver.getClass().getSimpleName(),
                modelResolver.getClass().getSimpleName(),
                toolCallEchoResolver.getClass().getSimpleName(),
                mediaAttachmentStore.getClass().getSimpleName(),
                conversationArchive.getClass().getSimpleName(),
                historySearch == null ? "未装配（卡片退化为最近老轮次）"
                        : historySearch.getClass().getSimpleName(),
                memoryProperties.getAssemblyBudget(),
                (int) (memoryProperties.getAssemblyWindowShare() * 100),
                memoryProperties.getAssemblyWindowTurns(),
                memoryProperties.getAssemblySummaryBudget(),
                memoryProperties.getAssemblyMaxCards(),
                rollingSummaryService == null ? "未装配（摘要退化为 digest 拼接）"
                        : (memoryProperties.isSummaryEnabled() ? "开启" : "关闭（digest 拼接）"));

        ContextAssembler assembler = new ContextAssembler(
                memoryProperties.getAssemblyBudget(),
                (int) Math.round(memoryProperties.getAssemblyBudget() * memoryProperties.getAssemblyWindowShare()),
                memoryProperties.getAssemblySummaryBudget(),
                memoryProperties.getAssemblyWindowTurns(),
                memoryProperties.getAssemblyMaxCards());

        return new AgentOrchestrationService(streamingChatModel, chatMemoryProvider, toolRouter,
                promptResolver, checkpointSaver, graphStateSerializer, cancellationRegistry, agentExecutor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, conversationArchive, assembler,
                historySearch, rollingSummaryService);
    }

    /**
     * 滚动摘要调度器（M4-R1）。
     *
     * <p>只在 {@code domainplane.memory.summary.enabled=true} 时真正生成摘要；即便开启，
     * 未配到模型也会静默降级为 digest 拼接（内核不依赖具体模型）。模型走非流式
     * {@code openAiChatModel} 且不加任何工具：摘要调用必须无副作用。</p>
     */
    @Bean(destroyMethod = "close")
    public RollingSummaryService rollingSummaryService(
            RedisConversationSummary conversationSummary,
            RedisConversationArchive conversationArchive,
            MemoryProperties memoryProperties,
            @Qualifier("openAiChatModel") ChatModel chatModel) {
        ChatModelSummarizer summarizer = new ChatModelSummarizer(
                chatModel, memoryProperties.getSummaryMaxOutputTokens());
        RollingSummaryService.Options options = new RollingSummaryService.Options(
                memoryProperties.isSummaryEnabled(),
                memoryProperties.getSummaryMinNewTurns(),
                memoryProperties.getSummaryMaxMaterialTurns());
        log.info("[会话摘要] RollingSummaryService 初始化：enabled={}，阈值 {} 轮，单次素材上限 {} 轮，"
                        + "输出上限 {} tokens，工作线程 {}",
                options.enabled(), options.minNewTurns(), options.maxMaterialTurns(),
                memoryProperties.getSummaryMaxOutputTokens(), memoryProperties.getSummaryWorkers());
        return new RollingSummaryService(conversationSummary, conversationArchive, summarizer, options,
                memoryProperties.getSummaryWorkers());
    }

    /**
     * 系统提示词解析器
     */
    @Bean
    @ConditionalOnMissingBean(SystemPromptResolver.class)
    public SystemPromptResolver systemPromptResolver(DomainSettingsStore domainSettingsStore,
                                                     PromptProperties promptProperties) {
        return new DomainSystemPromptResolver(domainSettingsStore, promptProperties);
    }

    /**
     * 工具执行信息回写开关解析器（域级，默认关）
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallEchoResolver.class)
    public ToolCallEchoResolver toolCallEchoResolver(DomainSettingsStore domainSettingsStore) {
        return new DomainToolCallEchoResolver(domainSettingsStore);
    }
}
