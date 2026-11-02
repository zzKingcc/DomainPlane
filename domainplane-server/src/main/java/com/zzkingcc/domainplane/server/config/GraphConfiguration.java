package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.config.Timeouts;
import com.zzkingcc.domainplane.runtime.conversation.ContextAssembler;
import com.zzkingcc.domainplane.runtime.conversation.RollingSummaryService;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.orchestration.AgentOrchestrationService;
import com.zzkingcc.domainplane.runtime.orchestration.ConcurrencyGate;
import com.zzkingcc.domainplane.runtime.orchestration.OrchestrationDeps;
import com.zzkingcc.domainplane.runtime.orchestration.PlanDisplayResolver;
import com.zzkingcc.domainplane.runtime.orchestration.PlanningOptions;
import com.zzkingcc.domainplane.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolCallEchoResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisAssemblyReportStore;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationArchive;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationSummary;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisHistorySearch;
import com.zzkingcc.domainplane.server.conversation.ChatModelSummarizer;
import com.zzkingcc.domainplane.server.prompt.DomainSystemPromptResolver;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.tool.DomainPlanDisplayResolver;
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
import java.util.concurrent.Executors;
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
@EnableConfigurationProperties({PromptProperties.class, AgentProperties.class, TimeoutsProperties.class,
        PlanningProperties.class})
public class GraphConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GraphConfiguration.class);

    /**
     * 超时总表的唯一出口 bean。
     *
     * <p>编排 / 本地工具 / 看图 / 能力调用四处都从这里取值，于是"等多久算太长"
     * 在平台里只可能有一份答案。绑定与校验分别在 {@link TimeoutsProperties} 与
     * {@link Timeouts} 里，这里只搬运。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public Timeouts timeouts(TimeoutsProperties properties) {
        Timeouts timeouts = properties.timeouts();
        log.info("[超时总表] 等模型 {}ms（分片 {}ms）、规划 {}ms、本地工具 {}ms、看图 {}ms、能力调用 {}ms",
                timeouts.modelWaitMs(), timeouts.modelWaitSliceMs(), timeouts.plannerWaitMs(),
                timeouts.toolInvokeMs(), timeouts.visionCallMs(), timeouts.capabilityCallMs());
        return timeouts;
    }

    /**
     * 图编排执行器。
     *
     * <p>默认每轮一个<b>虚拟线程</b>：编排的主耗时是"等模型"，虚拟线程在阻塞时会被卸载，
     * 不占载体线程，于是不再需要"开多少个线程才够"这种靠猜的参数。
     * {@code domainplane.agent.virtual-threads=false} 时回落到平台线程池（旧参数仍在，
     * 仅作兜底开关）。</p>
     *
     * <p><b>并发上限不在这里</b>：虚拟线程执行器没有队列上限，背压转由
     * {@link ConcurrencyGate} 显式给出 —— 那是"服务过载"从快速失败变成 OOM 的分水岭。</p>
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService agentExecutor(AgentProperties agentProperties) {
        if (agentProperties.isVirtualThreads()) {
            ThreadFactory factory = Thread.ofVirtual().name("domainplane-agent-", 0).factory();
            log.info("[Agent编排配置] 编排执行器=虚拟线程（每轮一个），并发上限由闸门控制={}",
                    agentProperties.getConcurrencyLimit());
            return Executors.newThreadPerTaskExecutor(factory);
        }
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

        log.info("[Agent编排配置] 编排执行器=平台线程池（回落模式） core={}, max={}, queue={}",
                agentProperties.getCorePoolSize(), agentProperties.getMaxPoolSize(),
                agentProperties.getQueueCapacity());
        return executor;
    }

    /**
     * 并发闸门。
     *
     * <p>虚拟线程下它是唯一的过载保护：编排一轮要等模型几十秒，池/闸门满了必须快速失败，
     * 让调用方退避重试，而不是让请求在内存里堆着。</p>
     */
    @Bean
    public ConcurrencyGate orchestrationConcurrencyGate(AgentProperties agentProperties) {
        return new ConcurrencyGate(agentProperties.getConcurrencyLimit());
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
            RollingSummaryService rollingSummaryService,
            RedisAssemblyReportStore assemblyReportStore,
            ConcurrencyGate concurrencyGate,
            Timeouts timeouts,
            PlanningProperties planningProperties,
            PlanDisplayResolver planDisplayResolver) {

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

        return new AgentOrchestrationService(
                new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter,
                        promptResolver, checkpointSaver, graphStateSerializer, cancellationRegistry,
                        agentExecutor, modelResolver, toolCallEchoResolver, mediaAttachmentStore,
                        conversationArchive, assembler, historySearch, rollingSummaryService,
                        assemblyReportStore, timeouts,
                        new PlanningOptions(planningProperties.isEnabled()),
                        planDisplayResolver),
                concurrencyGate);
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

    /**
     * 域级「执行计划展示」开关解析器（默认关）：
     * 规划是否<b>做</b>由 {@code domainplane.planning.enabled} 管，计划是否<b>给前端看</b>由这里管。
     */
    @Bean
    @ConditionalOnMissingBean(PlanDisplayResolver.class)
    public PlanDisplayResolver planDisplayResolver(DomainSettingsStore domainSettingsStore) {
        return new DomainPlanDisplayResolver(domainSettingsStore);
    }
}
