package com.zzkingcc.domainplane.runtime.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.api.conversation.MediaRef;
import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.model.ContentType;
import com.zzkingcc.domainplane.api.model.MessageContent;
import com.zzkingcc.domainplane.api.model.ToolCall;
import com.zzkingcc.domainplane.api.model.ToolCallPayload;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.common.exception.CheckpointCorruptedException;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.common.exception.UpstreamLlmFailure;
import com.zzkingcc.domainplane.domain.memory.DualConstraintChatMemory;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.config.Timeouts;
import com.zzkingcc.domainplane.runtime.conversation.ContextAssembler;
import com.zzkingcc.domainplane.runtime.conversation.ContextBudget;
import com.zzkingcc.domainplane.runtime.conversation.RollingSummaryService;
import com.zzkingcc.domainplane.runtime.conversation.TurnRecorder;
import com.zzkingcc.domainplane.runtime.model.ModelLimits;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.model.VisionTarget;
import com.zzkingcc.domainplane.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.domainplane.runtime.stream.StreamContext;
import com.zzkingcc.domainplane.runtime.stream.StreamSinkRegistry;
import com.zzkingcc.domainplane.runtime.tool.SensitiveMasker;
import com.zzkingcc.domainplane.runtime.tool.ToolCallEchoResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.runtime.usage.TokenUsageRecorder;
import com.zzkingcc.domainplane.runtime.vision.ImageAlphaFlattener;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 基于 LangGraph4j 的 Agent 图节点状态机编排
 *
 * @author zzkingcc
 */
@Slf4j
public class AgentOrchestrationService implements AgentService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    /**
     * 「挂起未批 + 用户直接开新对话」时补的那条占位回答。
     *
     * 语义是"用户拒绝了那次审批"。补一条回答是为了让挂起的那一轮成为完整轮次：
     * 否则记忆里会留下一条永远没人回答的提问，下一轮模型会看到"用户问过、却没人答"。
     */
    private static final String APPROVAL_REJECTED_PLACEHOLDER = "（用户未确认，该次需要审批的操作已取消）";

    private final CompiledGraph<MessagesState<ChatMessage>> compiledGraph;
    private final ChatMemoryProvider chatMemoryProvider;
    private final StreamingChatModel streamingChatModel;
    /**
     * 系统提示词解析器
     */
    private final SystemPromptResolver promptResolver;
    private final CancellationRegistry cancellationRegistry;
    private final BaseCheckpointSaver checkpointSaver;
    /** 工具路由器,Agent 调用能力的唯一入口 */
    private final ToolRouter toolRouter;
    /** 会话状态键（域 + sessionId）→ 流式上下文；键里带域，同名会话在不同域下不会互相顶掉 */
    private final StreamSinkRegistry streamSinks;
    /** 执行图编排的专用线程池(阻塞式流式调用,不占用公共 ForkJoinPool) */
    private final Executor executor;

    /**
     * 按域解析对话模型；为 {@code null} 时全部使用构造期注入的那个模型（行为与"全局一个模型"一致）。
     */
    private final ModelResolver modelResolver;

    /**
     * 按域解析「工具执行信息回写」开关；为 {@code null} 时按不回写处理（行为与"全局关"一致）。
     */
    private final ToolCallEchoResolver toolCallEchoResolver;

    /**
     * 图片暂存区：主模型是纯文本模型时，本轮图片存这里、只把引用名交给模型。
     *
     * <p>为 {@code null} 时（未装配的部署、单元测试）带图请求只能走"图直接进消息"那条路：
     * 那时若主模型不支持读图，失败会在上游以参数错误的形式暴露，
     * 而不是被这里静默吞掉（静默吞图比报错更糟：模型会对着不存在的图编答案）。</p>
     */
    private final MediaAttachmentStore mediaAttachmentStore;

    /**
     * 会话归档：按轮存全流程（提问 / 工具现场 / 回答 / 用量），无淘汰无封顶。
     *
     * <p>为 {@code null} 时（未装配的部署、单元测试）不归档，其余行为分毫不变：
     * 归档是档案，不是请求链上的一环：写失败只告警，不让一轮对话失败。</p>
     */
    private final ConversationArchive conversationArchive;

    /**
     * 召回装配器：决定"这轮给模型看多少"。
     *
     * <p>M2 起它是请求体构造的唯一入口（取代原先"全量历史 + 本轮提问"的拼法），
     * 也是封顶拒绝语义的替代品：装不下逐级降级，永不拒绝开轮。
     * 永不为 {@code null}，未显式注入时用默认预算构造，行为可预期。</p>
     */
    private final ContextAssembler contextAssembler;

    /**
     * 会话历史的倒排检索：闭轮时写索引，装配层与 {@code recall_history} 查索引。
     *
     * <p>为 {@code null} 时（未装配的部署、单元测试）不写也不查索引，
     * 卡片段退化为"窗口之前最近的老轮次"，其余行为分毫不变。
     * 索引是<b>派生数据</b>：写失败只告警（重放归档即可重建），
     * 查不到就走子串扫描兜底。</p>
     */
    private final HistorySearch historySearch;

    /**
     * 滚动摘要（M4-R1）：闭轮后异步把滑出窗口的老轮次压成一份随会话推进的摘要。
     *
     * <p>为 {@code null} 时（未装配的部署、单元测试）整段关闭：装配侧读不到摘要就
     * 退化为 digest 拼接（M2 行为），历史一条都不丢。摘要是<b>有损增强</b>：
     * 生成失败、写失败都只告警，绝不影响本轮对话。</p>
     */
    private final RollingSummaryService rollingSummaryService;

    /**
     * 装配清单落账（F04-a）：每轮把"各段给了多少 / 砍了多少"记一条。
     *
     * <p>为 {@code null} 时（未装配的部署、单元测试）不落账，装配行为分毫不变 ——
     * 清单是纯观测产物，它反过来影响装配结果会让"观测"变成"干扰"。
     * 写失败只告警：账本不是对话的一环。</p>
     */
    private final AssemblyReportStore assemblyReportStore;

    /**
     * 并发闸门：为 {@code null} 时不限制并发（单元测试、最小装配）。
     *
     * <p>它必须挂在编排层而不是执行器上：执行器换成虚拟线程之后不再有队列上限，
     * 背压只能由这里显式给出，见 {@link ConcurrencyGate}。</p>
     */
    private final ConcurrencyGate concurrencyGate;

    /** 本轮入参的装配（多模态内容块 → UserMessage 与图片路由） */
    private final TurnInputFactory turnInput;

    /** 本轮上下文的装配胶水（系统提示 / 近况窗口 / 摘要段 / 卡片段 / 预算收窄） */
    private final TurnContextFactory turnContext;

    /** 一轮跑完（或跑砸）之后的收尾与持久化 */
    private final TurnLifecycle turnLifecycle;

    private final TurnFailureHandler failureHandler;

    /** 编排图：构建、三个节点、条件边、等模型与模型解析 */
    private final AgentGraphFactory graph;

    /**
     * 超时总表：等模型终态的总上限与分片。
     *
     * <p>不再用两个 {@code static final} 常量：它俩此前是"编排的私有知识"，
     * 而同一个问题（"等多久算太长"）在工具 / 看图 / 能力调用 / 检索里各有一份答案，
     * 改一处漏一处。取值的唯一出口是 {@link OrchestrationDeps#timeoutsOrDefaults()}。</p>
     */
    private final Timeouts timeouts;

    /**
     * 规划前置：进图前用主模型做的一次独立规划调用（可选能力）。
     *
     * <p>为 {@code null} = 部署未启用规划（{@code PlanningOptions.disabled()}），
     * 编排行为与该能力不存在时分毫不变。启用后每轮进图前多一次调用：
     * direct / plan 由模型自判，计划只进本轮请求体（草稿口径），失败降级直进 loop。</p>
     */
    private final TurnPlanner planner;

    /**
     * 域级「执行计划展示」开关解析器：决定规划结果发不发调用方（{@code PLAN} 事件）。
     *
     * <p>为 {@code null}（未装配的部署、单元测试）一律不下发，规划照做 ——
     * 与 {@code toolCallEchoResolver} 同一条"观测不干扰主流程"的边界。</p>
     */
    private final PlanDisplayResolver planDisplayResolver;

    /**
     * 主构造器：装配由 {@link OrchestrationDeps} 整体给出。
     */
    public AgentOrchestrationService(OrchestrationDeps deps) {
        this(deps, null);
    }

    /**
     * 完整构造器。
     *
     * @param deps            装配依赖
     * @param concurrencyGate 并发闸门；{@code null} = 不限制并发
     */
    public AgentOrchestrationService(OrchestrationDeps deps, ConcurrencyGate concurrencyGate) {
        this.concurrencyGate = concurrencyGate;
        this.streamingChatModel = deps.streamingChatModel();
        this.chatMemoryProvider = deps.chatMemoryProvider();
        this.promptResolver = Objects.requireNonNull(deps.promptResolver(), "promptResolver");
        this.checkpointSaver = deps.checkpointSaver();
        this.cancellationRegistry = deps.cancellationRegistry();
        this.toolRouter = deps.toolRouter();
        this.streamSinks = new StreamSinkRegistry();
        this.executor = Objects.requireNonNull(deps.executor(), "executor");
        this.modelResolver = deps.modelResolver();
        this.toolCallEchoResolver = deps.toolCallEchoResolver();
        this.mediaAttachmentStore = deps.mediaAttachmentStore();
        this.conversationArchive = deps.conversationArchive();
        this.contextAssembler = deps.contextAssembler() == null
                ? new ContextAssembler() : deps.contextAssembler();
        this.historySearch = deps.historySearch();
        this.rollingSummaryService = deps.rollingSummaryService();
        this.assemblyReportStore = deps.assemblyReportStore();
        this.timeouts = deps.timeoutsOrDefaults();
        this.planDisplayResolver = deps.planDisplayResolver();
        this.planner = deps.planningOptions() != null && deps.planningOptions().enabled()
                ? new TurnPlanner(modelResolver, streamingChatModel, toolRouter, cancellationRegistry,
                        timeouts, AgentOrchestrationService::captureRealUsage)
                : null;

        // 工具规格不在构造期固化：域是按请求变化的，工具集必须在 agent 节点运行时
        // 用 toolRouter.toolSpecifications(domainId) 按本轮所处的域过滤。
        int totalTools = toolRouter.toolSpecifications().size();
        int approvalTools = toolRouter.toolsRequiringApproval().size();

        this.graph = new AgentGraphFactory(streamingChatModel, chatMemoryProvider, toolRouter,
                toolCallEchoResolver, modelResolver, cancellationRegistry, streamSinks, timeouts,
                this::displayArguments, AgentOrchestrationService::captureRealUsage);
        this.turnInput = new TurnInputFactory(modelResolver, mediaAttachmentStore, graph::resolveModel);
        /* 一律传**已经归一化过的字段**而不是 {@code deps.x()}：
           {@code contextAssembler} 在装配里是"可空、空则用缺省单例"，这里若直接把
           deps 里的 null 递进去，协作者就会拿到一个空装配器并在取窗口大小时炸掉，
           而编排服务自己那份却是好的 —— 两处对同一个"缺省"的理解分叉，
           症状是每一轮都失败、且失败原因与配置无关，极难往这里想。 */
        this.turnContext = new TurnContextFactory(promptResolver, contextAssembler, modelResolver,
                conversationArchive, rollingSummaryService, historySearch);
        this.turnLifecycle = new TurnLifecycle(chatMemoryProvider, conversationArchive,
                rollingSummaryService, assemblyReportStore, mediaAttachmentStore,
                checkpointSaver, contextAssembler, historySearch,
                AgentOrchestrationService::usageOf, TurnFailureHandler::rootMessage);
        this.failureHandler = new TurnFailureHandler(turnLifecycle);
        this.compiledGraph = graph.build(deps.checkpointSaver(), deps.stateSerializer());

        log.info("[Agent编排-LangGraph] 初始化完成,注册表内共 {} 个工具(其中 {} 个需授权);"
                        + "已出现的域: {};实际可见工具集与系统提示词都按每次请求的域动态解析;"
                        + "图结构: agent→(auto|review)→tools→agent(条件循环)",
                totalTools, approvalTools, toolRouter.knownDomains());
    }

    /**
     * 单模型构造：不传 {@link ModelResolver}，所有域共用构造期注入的那个模型。
     *
     * @deprecated 装配统一走 {@link #AgentOrchestrationService(OrchestrationDeps)}；
     *             保留为本轮引用迁移期间的兼容入口，逐个核完调用方后删除
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor) {
        this(OrchestrationDeps.minimal(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor));
    }

    /**
     * 不带图片暂存区的构造：模型解析器齐备但不需要委派兜底。
     *
     * @deprecated 见 {@link #AgentOrchestrationService(OrchestrationDeps)}
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver) {
        this(new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, null, null, null, null, null, null, null));
    }

    /**
     * @deprecated 见 {@link #AgentOrchestrationService(OrchestrationDeps)}
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver,
            MediaAttachmentStore mediaAttachmentStore) {
        this(new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, null, null, null, null, null, null));
    }

    /**
     * @deprecated 见 {@link #AgentOrchestrationService(OrchestrationDeps)}
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver,
            MediaAttachmentStore mediaAttachmentStore,
            ConversationArchive conversationArchive) {
        this(new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, conversationArchive,
                null, null, null, null, null));
    }

    /**
     * @deprecated 见 {@link #AgentOrchestrationService(OrchestrationDeps)}
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver,
            MediaAttachmentStore mediaAttachmentStore,
            ConversationArchive conversationArchive,
            ContextAssembler contextAssembler) {
        this(new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, conversationArchive,
                contextAssembler, null, null, null, null));
    }

    /**
     * @deprecated 见 {@link #AgentOrchestrationService(OrchestrationDeps)}
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver,
            MediaAttachmentStore mediaAttachmentStore,
            ConversationArchive conversationArchive,
            ContextAssembler contextAssembler,
            HistorySearch historySearch) {
        this(new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, conversationArchive,
                contextAssembler, historySearch, null, null, null));
    }

    /**
     * @deprecated 见 {@link #AgentOrchestrationService(OrchestrationDeps)}
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver,
            MediaAttachmentStore mediaAttachmentStore,
            ConversationArchive conversationArchive,
            ContextAssembler contextAssembler,
            HistorySearch historySearch,
            RollingSummaryService rollingSummaryService) {
        this(new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, conversationArchive,
                contextAssembler, historySearch, rollingSummaryService, null, null));
    }

    /**
     * 完整装配（含滚动摘要 + 装配清单落账）。
     *
     * @deprecated 见 {@link #AgentOrchestrationService(OrchestrationDeps)}
     */
    @Deprecated
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor,
            ModelResolver modelResolver,
            ToolCallEchoResolver toolCallEchoResolver,
            MediaAttachmentStore mediaAttachmentStore,
            ConversationArchive conversationArchive,
            ContextAssembler contextAssembler,
            HistorySearch historySearch,
            RollingSummaryService rollingSummaryService,
            AssemblyReportStore assemblyReportStore) {
        this(new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, conversationArchive,
                contextAssembler, historySearch, rollingSummaryService, assemblyReportStore, null));
    }


    /** 会话状态键（threadId）：域 + sessionId，一切按会话索引的状态都用它 */
    /** 从 RunnableConfig 取调用方声明的 sessionId（状态键里分隔符之后的部分，用于事件与日志） */
    /**

    /** 从图中取回本轮上下文；节点内只允许用这个方法取，禁止按 key 回查注册表 */
    /**
     * 超时总表里属于编排的那两项（等模型终态的总上限与分片）。
     *
     * <p>取自 {@link OrchestrationDeps#timeoutsOrDefaults()}：未配置时是代码默认值，
     * 行为与改造前的两个 {@code static final} 常量完全一致。保留"分片等待读停止标志"语义 ——
     * 配置化改的只是这两个数的来源，不是等待方式。</p>
     */

    /**
     * 对外契约入口:发起一轮对话
     */
    @Override
    public Flux<AgentEvent> chat(AgentRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String domain = request.getDomain();
        log.info("[Agent编排] 会话[{}] 收到调用请求, domain={}", request.getSessionId(), domain);

        DomainCheck check = checkDomain(domain);
        if (check != DomainCheck.OK) {
            log.warn("[Agent编排] 会话[{}] 域校验未通过: {}", request.getSessionId(), check.detail());
            // 本方法跑在 HTTP 线程上，那时还没 begin 过 traceId：用 currentOrNew 保证事件里的
            // traceId 一定非空，否则调用方拿到的是一串 null，上报也无从查起
            return Flux.just(AgentEvent.error(request.getSessionId(), check.detail(), check.code(),
                    TraceId.currentOrNew()));
        }
        // 软删宽限期闸门（F03 软删三阶段·阶段一）：只拦新开轮，resume 不挂这道闸。
        // 与 checkDomain 的分工：那个管"结构上能不能当入口"（已登记 ∧ 可调用 ∧ 叶子），
        // 这个管"生命周期上还接不接新客"。软删域完整留在注册表内、结构判据一个字不改——
        // 否则宽限期内 resume 收尾会被入口闸门自己拦死（误报 DOMAIN_NOT_CALLABLE），
        // 审批悬案将永远无法落地。resume 侧由既有闸门兜底：域出注册表才 DOMAIN_NOT_FOUND。
        String gateDomain = Domains.normalize(domain);
        if (toolRouter.isPendingDelete(gateDomain)) {
            log.warn("[Agent编排] 会话[{}] 域 {} 处于软删宽限期，拒绝新开轮",
                    request.getSessionId(), gateDomain);
            return Flux.just(AgentEvent.error(request.getSessionId(),
                    "域 " + gateDomain + " 已进入删除宽限期，不再接受新会话；"
                            + "该域下进行中的会话可继续收尾，或请在管控台撤销删除",
                    ErrorCode.DOMAIN_PENDING_DELETE, TraceId.currentOrNew()));
        }
        String sessionIdReason = SessionKeys.validateSessionId(request.getSessionId());
        if (sessionIdReason != null) {
            log.warn("[Agent编排] 会话标识不合法: {}", sessionIdReason);
            return Flux.just(AgentEvent.error(request.getSessionId(), sessionIdReason,
                    ErrorCode.INVALID_PARAMETER, TraceId.currentOrNew()));
        }
        String contentProblem = turnInput.validateContents(request.getContents());
        if (contentProblem != null) {
            log.warn("[Agent编排] 会话[{}] 内容块校验未通过: {}", request.getSessionId(), contentProblem);
            return Flux.just(AgentEvent.error(request.getSessionId(), contentProblem,
                    ErrorCode.INVALID_PARAMETER, TraceId.currentOrNew()));
        }
        return orchestrate(request.getSessionId(), request.getMessage(), request.getContents(), domain);
    }

    /**
     * 校验本轮域：域为空归一化为根域；域不存在或不是可调用单元都拒绝。
     *
     * 可调用性是显式声明的（见 {@code DomainRegistry}）：父域可以只做装配
     * （供后代继承工具 / 提示词 / 模型绑定 / 知识），由叶子当入口。
     */
    private DomainCheck checkDomain(String domain) {
        // 域为空 → 归一化为根域 default（根域不可删、恒存在且恒为可调用单元），不当作入参错误。
        String domainId = Domains.normalize(domain);
        if (!toolRouter.knownDomains().contains(domainId)) {
            return DomainCheck.fail(ErrorCode.DOMAIN_NOT_FOUND,
                    "域不存在: " + domainId + "（已登记的域: " + toolRouter.knownDomains() + "）");
        }
        if (!toolRouter.isCallableDomain(domainId)) {
            return DomainCheck.fail(ErrorCode.DOMAIN_NOT_CALLABLE,
                    "域 " + domainId + " 不是可调用单元：它只做装配（供后代继承工具 / 提示词 / 模型 / 知识），"
                            + "不能直接作为入口。可调用的域: " + toolRouter.callableDomains()
                            + "；如需让它可调用，请在管控台「域空间」把它标记为可调用");
        }
        return DomainCheck.OK;
    }

    /**
     * 身份 / 域校验结果
     *
     * @param code   失败时的错误码；通过时为 {@code null}
     * @param detail 面向调用方的说明（含具体缺什么、当前有哪些域可选）
     */
    private record DomainCheck(ErrorCode code, String detail) {

        static final DomainCheck OK = new DomainCheck(null, null);

        static DomainCheck fail(ErrorCode code, String detail) {
            return new DomainCheck(code, detail);
        }
    }

    /**
     * 对外契约入口:请求停止任务
     *
     * 必须带上与发起对话时同一个域：会话状态按 (域, sessionId) 隔离，
     * 只给 sessionId 停不到另一个域里的同名会话。客户端断连触发的隐式停止同理。
     */
    @Override
    public boolean stop(String sessionId, String domain) {
        return cancellationRegistry.requestStop(stateKeyOf(domain, sessionId));
    }

    /** (域, sessionId) → 会话状态键；域缺失时按根域算 */
    private static String stateKeyOf(String domain, String sessionId) {
        return SessionKeys.of(domain, sessionId);
    }

    /**
     * Agent 对话入口(流式)
     *
     * 流程:
     *
     *   • 清理上一轮遗留的停止标志与 checkpoint
     *   • 加载会话记忆,添加 UserMessage
     *   • 构建初始状态: [SystemMessage] + 历史消息
     *   • stream 消费 graph 执行,实时推送事件
     *   • 检测到中断(review 前):推送 INTERRUPT 事件,挂起等待 resume
     *   • 正常完成:将最终 AiMessage 存入会话记忆
     *
     *
     * @param sessionId 会话 ID（域内唯一：不同域可以用同一个 sessionId）
     * @param message   用户问题的纯文本部分（多模态时是各 TEXT 块的合并结果）
     * @param contents  多模态内容块（可空）；空 = 纯文本请求，走旧路径
     * @param domain    本轮所处的域；决定本轮可见工具集
     * @return 事件流,挂起时下发 {@code INTERRUPT} 事件,正常结束下发 {@code DONE}
     */
    public Flux<AgentEvent> orchestrate(String sessionId, String message, List<MessageContent> contents,
                                        String domain) {
        String key = stateKeyOf(domain, sessionId);
        // 清上一轮遗留的停止标志，必须在这里，**提交进线程池之前**。
        // 编排任务会先排队，用户可以在排队期间点停止；清理若发生在任务出队后执行，
        // 那次停止就被抹掉了（任务照跑到底，界面显示"停止中"却什么都没发生）。
        // 这就是 R-03 的根因：清理的时机决定了"排队期间的停止"能否生效。
        cancellationRegistry.prepareNewRound(key);
        return startRound(sessionId, key, domain,
                context -> runOrchestrate(sessionId, key, message, contents, context));
    }

    /**
     * 提交一个新轮：注册流上下文 → 过并发闸门 → 交给执行器。
     *
     * <p>{@code orchestrate} 与 {@code resume} 的这段提交逻辑逐行相同（只是主体不同），
     * 与执行模板 {@link #runTurn} 是同一条理由：改一处漏一处的代价，是"停止偶尔不灵"
     * 或"过载时静默 OOM"这类不报错的故障。</p>
     *
     * @param round 本轮主体；参数里的 {@code context} 是本轮刚注册的流上下文
     */
    private Flux<AgentEvent> startRound(String sessionId, String key, String domain, RoundStarter round) {
        return Flux.create(sink -> {
            StreamContext context = streamSinks.register(key, sink, domain);
            // 流被取消(客户端断连)或终止时,通知执行线程退出并清理上下文。
            // 只有"自己仍是当前上下文"才算客户端断连：同一会话换了一轮（中断后紧接 resume、
            // 客户端重试）时，上一轮的收尾也会跑到这里，若照旧置停止标志，会把刚启动的新一轮一起取消。
            sink.onDispose(() -> {
                if (streamSinks.isCurrent(key, context)) {
                    cancellationRegistry.requestStop(key);
                }
                streamSinks.unregister(key, context);
            });

            // 闸门先于提交：取不到许可就不进执行器。反过来（先提交再判）会让被拒的轮
            // 也占掉一个执行槽位，虚拟线程下那就是不断地起线程再立刻放弃。
            if (concurrencyGate != null && !concurrencyGate.tryAcquire()) {
                log.error("[Agent编排] 会话[{}] 并发闸门已满（上限 {}，在飞 {}），拒绝本轮",
                        sessionId, concurrencyGate.limit(), concurrencyGate.inFlight());
                streamSinks.unregister(key, context);
                sink.next(AgentEvent.error(sessionId, ErrorCode.SYSTEM_BUSY, context.traceId()));
                sink.complete();
                return;
            }

            try {
                executor.execute(() -> {
                    try {
                        round.start(context);
                    } finally {
                        // 许可的归还必须与 tryAcquire 成对：挂在执行线程里而不是提交处，
                        // 才能保证"许可持有期 = 这一轮真正在跑的时长"
                        if (concurrencyGate != null) {
                            concurrencyGate.release();
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                if (concurrencyGate != null) {
                    concurrencyGate.release();
                }
                log.error("[Agent编排] 会话[{}] 提交失败,编排线程池已满", sessionId);
                streamSinks.unregister(key, context);
                sink.next(AgentEvent.error(sessionId, ErrorCode.SYSTEM_BUSY, context.traceId()));
                sink.complete();
            }
        });
    }

    /** 一轮的启动动作；拿到本轮的流上下文后自行进入编排 */
    @FunctionalInterface
    private interface RoundStarter {
        void start(StreamContext context);
    }

    private void runOrchestrate(String sessionId, String key, String message,
                                List<MessageContent> contents, StreamContext context) {
        TurnState state = new TurnState();
        runTurn(sessionId, key, context, "orchestrate", state,
                e -> turnLifecycle.finalizeTurnOnFailure(key, state.turnId, state.graphStarted, e, context),
                () -> runOrchestrateBody(sessionId, key, message, contents, context, state));
    }


    /**
     * 一轮编排的公共骨架：串行闸门 → trace → 主体 → 失败分派 → finally 三件套。
     *
     * <p>{@code orchestrate} 与 {@code resume} 的这五件事<b>逐条相同</b>，差异只在主体：
     * 在两处各写一份，改一处漏一处的风险由语法上的距离决定，而这里是两块相隔 300 行、
     * 长得几乎一模一样的代码 —— 抽成模板后只有主体各留一份。</p>
     *
     * @param source 标记本轮来源（{@code orchestrate} / {@code resume}），
     *               只影响两处日志与"要不要回滚记忆"这一个判据
     * @param state  本轮的收尾状态（记忆长度 / 归档轮号 / 图是否已起），
     *               必须在 try 之外，catch 要靠它们收尾
     * @param archive 失败时的归档处置：两种轮的处置不同（前者判"删还是中止"，后者只有中止）
     * @param step    本轮主体
     */
    private void runTurn(String sessionId, String key, StreamContext context, String source,
                         TurnState state, FailureArchive archive, TurnStep step) {
        // 同会话串行：本会话已有一轮在执行则直接拒绝（最细粒度的并发控制）。
        // 停止标志的清理不在这里，而在提交进线程池之前（见 orchestrate 的 prepareNewRound）。
        if (!cancellationRegistry.tryMarkRunning(key)) {
            log.warn("[Agent编排] 会话[{}] 正在执行中，拒绝并发请求（如需重开请先 stop 并等其结束）", sessionId);
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_BUSY, TraceId.currentOrNew()));
            // 顺序是硬要求：先注销再 complete。complete() 会同步触发 sink.onDispose，
            // 而 onDispose 里要靠 isCurrent 判断"这是不是我这一轮"，注销在前，它就判 false，
            // 不会把"停止标志"打到别人身上。反过来（先 complete）的话，被拒绝的这一轮
            // 会亲手把正在正常执行的那一轮置为停止。
            streamSinks.unregister(key, context);
            context.complete();
            return;
        }

        TraceId.begin(context == null ? null : context.traceId());
        try {
            step.run();
        } catch (Exception e) {
            /* 归档收尾先于错误处理：删（图没起来）或中止（跑起来了）。
               失败只告警，归档写不进去不该影响记忆回滚与错误事件的收尾。 */
            archive.accept(e);
            if (TurnFailureHandler.isCancellationException(e)) {
                failureHandler.handleStop(sessionId, key, context, source, state.memorySizeBefore);
            } else if (!failureHandler.handleNotConfigured(sessionId, key, context, e, state.memorySizeBefore)) {
                log.error("{} 会话[{}] traceId={} 执行失败",
                        tagOf(source), sessionId, TraceId.current(), e);
                // 异常中断时图没走到 END，releaseThread 不会触发，checkpoint 会残留。
                // 残留状态里可能有"悬空的 AiMessage（带工具调用却没有结果）"，必须主动清掉。
                turnLifecycle.releaseCheckpointQuietly(key, releaseReasonOf(source));
                // resume 不写记忆，没有可回滚的提问；orchestrate 必须回滚，否则留下永久"有问无答"
                if (state.memorySizeBefore != null) {
                    turnLifecycle.restoreMemory(key, state.memorySizeBefore);
                }
                failureHandler.emitOrchestrationFailure(sessionId, context, e);
                context.complete();
            }
        } finally {
            TokenUsageRecorder.finishAndLog();
            cancellationRegistry.clear(key);
            // 释放"执行中"标记：必须与 tryMarkRunning 成对，否则该会话会被永久锁死
            cancellationRegistry.unmarkRunning(key);
            // 只注销自己这一轮的上下文：期间若已换了新的一轮（客户端重试），不能把新的一起删掉
            streamSinks.unregister(key, context);
            // 线程池会复用线程，必须清理 ThreadLocal，否则下一个任务会继承到本次 traceId
            TraceId.end();
        }
    }

    /** resume 侧的日志与 checkpoint 清理原因口径不同，由来源统一派生，避免两处手工对齐 */
    private static String tagOf(String source) {
        return "resume".equals(source) ? "[Agent编排-resume]" : "[Agent编排]";
    }

    private static String releaseReasonOf(String source) {
        return "resume".equals(source) ? "resume 异常" : "执行异常";
    }

    /**
     * 本轮主体。允许抛异常：所有出口由 {@link #runTurn} 统一收尾。
     */
    @FunctionalInterface
    private interface TurnStep {
        void run() throws Exception;
    }

    /** 失败时的归档处置；失败一律只告警，不抛 */
    @FunctionalInterface
    private interface FailureArchive {
        void accept(Throwable failure);
    }

    /**
     * 一轮之内要跨 {@code try} 边界传给收尾逻辑的量。
     *
     * <p>它们原先是声明在 try 之外的局部变量 —— 那是 Java 的一条硬约束：try 自身就是一个块作用域，
     * 里面声明的量对 catch 不可见。抽成模板后 body 移进了另一个方法，局部变量到不了 catch，
     * 这份载体就是它们的落脚处。字段命名与原先一致，替换是逐处对齐过的。</p>
     */
    private static final class TurnState {

        /**
         * 本轮写入记忆之前的<b>条数</b>（停止/异常时回滚用）。
         *
         * <p>哨兵必须是 {@code null} 而不是 0：null = "还没量过"（本轮还没往记忆里写过东西），
         * 0 = "量到的是空的"（记忆本来就是空）。两者混用会让"异常早于测量"被误判成"清空记忆"，
         * 而那次清空会把上一轮的内容一起抹掉。</p>
         */
        Integer memorySizeBefore = null;

        /** 本轮归档轮号；{@code <= 0} = 本轮没有开过轮 */
        long turnId = -1;

        /** 图是否已真正开始执行：决定失败时归档是"删"还是"中止" */
        boolean graphStarted = false;
    }

    /**
     * 一轮编排的主体（含"本轮看多少"的装配与图执行）。
     *
     * <p>所有失败一律向上抛：记忆回滚、断点清理、错误事件都由 {@link #runTurn} 统一收尾，
     * 这里只管"这一轮怎么跑"。标记详见 {@link TurnState#graphStarted}。</p>
     */
    private void runOrchestrateBody(String sessionId, String key, String message,
                                    List<MessageContent> contents, StreamContext context,
                                    TurnState state) {
        ChatMemory memory = chatMemoryProvider.get(key);

        // ① 先读断点：这一轮是否要额外补一条占位回答取决于有没有待审批断点，
        //    而封顶判定必须按真实写入量算（3 条 vs 2 条），所以顺序不能反。
        //    读不出来 ≠ 没有待审批：此刻绝不能走到入口清理，那恰好会把待审批的断点删掉，
        //    正是本轮要防的那件事。直接报"检查点读写失败"并返回，由调用方退避后重试。
        boolean pending;
        try {
            pending = pendingApproval(key);
        } catch (CheckpointCorruptedException e) {
            /* 检查点数据损坏：键读得出来、内容解不开。重试没有意义（同一个坏键），
               而这个分支若走"整轮拒绝"，这个会话就永久废掉了，用户只能换 sessionId。

               清键让它自愈：删掉之后下一轮读到的是空检查点，会话立刻可用。
               安全侧没问题，断点没了，那个"待审批"的动作永远不会被执行，
               等价于审批被拒（真正的危险是反过来：读不出来却当成"没有待审批"，
               于是本该停下等授权的一轮以"完成"收尾）。

               清键失败也不放过：把错误照样抛出去，绝不带着一个已知损坏的键继续跑。 */
            log.error("[Agent编排] 会话[{}] 检查点数据损坏，清键后按'无待审批'继续（本轮自愈）",
                    sessionId, e);
            turnLifecycle.releaseCheckpointQuietly(key, "检查点损坏自愈");
            pending = false;
        } catch (IllegalStateException e) {
            // 瞬时故障（Redis 抖动 / 连不上 / 超时）：重试有可能成功，整轮拒绝并标记可重试。
            log.error("[Agent编排] 会话[{}] 读取检查点失败，无法判断是否存在待审批中断点", sessionId, e);
            context.emit(AgentEvent.error(sessionId, ErrorCode.CHECKPOINT_ERROR,
                    TraceId.currentOrNew()));
            context.complete();
            return;
        }

        /* ② 封顶判定已移除（M2）：原语义是"记忆到上限即拒绝新一轮、会话作废"，
           而封顶只会约束"会话有多少"，约束不了"这轮给模型看多少"。
           后者由 ContextAssembler 的预算与降级链负责（方案 §5.2 / §5.3 / §8），
           它保证装不下就逐级降级，永不拒绝开轮。
           DualConstraintChatMemory.capacityFor 仍保留（内部自检 + 回归测试），
           只是不再作为拒绝轮次的闸门。 */

        // ③ 上一轮停在审批点、用户没回复审批而是直接开了新对话 → **视为用户拒绝了那次审批**。
        //    既不能把新对话挡回去（那会让会话在断点 TTL 到期前整个不可用），
        //    也不能把断点留着（否则事后任何一次 resume 都能把那个待授权动作执行掉）。
        //    所以：补一条占位回答让那一轮成为完整轮次（不留"有问无答"），再取消断点。
        if (pending) {
            log.info("[Agent编排] 会话[{}] 存在待审批断点且用户发起了新一轮对话 → 视为拒绝：取消该动作",
                    sessionId);
            memory.add(AiMessage.from(APPROVAL_REJECTED_PLACEHOLDER));
            // 归档侧的同一语义：那一轮停在审批点、再没等到授权，以"审批被拒"闭合。
            // 现场留在档里，它问过什么、卡在哪，本身就是档案
            turnLifecycle.rejectOpenTurnQuietly(key);
        } else {
            /* 崩溃恢复：上一条仍是未闭合开放轮（进程崩在闭轮之前、或断点已过期），
               先标 aborted 再开新轮，归档里不允许出现两条开放轮。

               必须先读 pending 才能走这条分支：停在审批点的那一轮是"合法地开着"
               （断点还在、用户随时可能 resume），此刻误标中止，那一轮的最终回答
               就再也落不进档；反过来 pending 为假还开着，就只能是崩溃留下的残骸。 */
            turnLifecycle.recoverStaleOpenTurnQuietly(key);
        }
        turnLifecycle.releaseCheckpointQuietly(key, pending ? "视为拒绝" : "入口清理");

        /* ④ 本轮带图时先定"图走哪条路"，定不下来就在写记忆前拒绝。
           图片是这一轮能不能跑的前置条件，不是跑到模型那一步才知道的结果：
           把它留到后面，被拒的那一轮已经在记忆里留下一条永远没有回答的提问，
           下一轮模型会顺着它答非所问，而用户看不出发生过什么。 */
        String domainId = context == null ? null : context.domainId();
        List<MessageContent> effectiveContents = contents;
        String notice = null;
        // 本轮全部媒体（图 / 音 / 视）暂存后的引用名：按媒体块顺序，失败处为 null，无媒体轮为空。
        // 声明在分支之外：归档开轮要用它把"可按名取回"写进 UserInput.media（M4-R4）
        List<String> attachmentIds = List.of();
        int imageTotal = turnInput.imageCount(contents);
        /* ① 先定"图走哪条路"。只以"这一轮带图"为前提：
           音视频不参与视觉路由（没有 seeImage 那样的承接方），但它们同样要进暂存区，见 ②。 */
        TurnInputFactory.VisionRoute route = imageTotal > 0 ? turnInput.resolveVisionRoute(domainId) : null;
        if (route == TurnInputFactory.VisionRoute.UNAVAILABLE) {
            log.warn("[Agent编排] 会话[{}] 域 {} 本轮带 {} 张图，但域链上没有可承接的视觉模型，拒绝本轮",
                    sessionId, domainId, imageTotal);
            // 文案走码的对外文案，不在这里拼运维指引：AgentEvent.error 的四参工厂是
            // "传入文案优先"，拼什么就原样发给终端用户，上一版把「去管控台绑模型」
            // 拼进去，用户够不着管控台，看到只剩茫然。排障信息上面的 warn 日志已有
            // （域、图数），码的 action 字段也写着完整处置步骤，三者不缺。
            context.emit(AgentEvent.error(sessionId,
                    ErrorCode.MODEL_NO_VISION, TraceId.currentOrNew()));
            context.complete();
            return;
        }

        /* ② 把本轮全部媒体存进暂存区。无图轮也走这一步：音视频的暂存与视觉路由无关，
           它服务的是归档侧的引用，不留引用，档里只剩一句"曾经有过一段音频"，
           字节永远拿不回来。失败处置见 storeAttachments。 */
        TurnInputFactory.StoredAttachments stored = turnInput.storeAttachments(contents);
        attachmentIds = stored.ids();

        if (route == TurnInputFactory.VisionRoute.INLINE) {
            /* 主模型自己就能看图（effectiveContents 原样保留，图随消息进上下文），
               但图**同时**要进暂存区，否则 seeImage 在"域里只有一个视觉模型"这种最常见
               配置下永远取不到图，模型调它只会得到"找不到图片"，用户看到的是
               "图片引用无效或已过期"（2026-10-16 实测复现）。

               与委派路径的关键差别：这里存图是**额外能力**（自主委派 + 历史回看），
               不是"本轮能不能跑"的前提，所以失败只告警、绝不阻断，主模型本来
               就能自己看图，不该因为一次 Redis 抖动把一轮正常对话拖垮。

               失败时通知一律不写：通知里的"本轮附带 N 张图片"是按成功暂存的那几张报的数，
               而模型看到的图比这多，两个数对不上会让它以为还有图没给它。不写没有损失：
               图本来就在它的消息里。 */
            if (stored.imageFailure() != null) {
                log.warn("[Agent编排] 会话[{}] 域 {} 本轮 {} 张图暂存失败（不影响本轮，"
                        + "主模型直接看图即可）：{}", sessionId, domainId, imageTotal,
                        stored.imageFailure());
            } else if (!stored.imageIds().isEmpty()) {
                notice = turnInput.optionalDelegateNotice(stored.imageIds());
                log.info("[Agent编排] 会话[{}] 域 {} 主模型自带视觉，本轮 {} 张图既直接入消息"
                        + "也已暂存为附件：{}", sessionId, domainId, imageTotal, stored.imageIds());
            }
        }
        if (route == TurnInputFactory.VisionRoute.DELEGATE) {
            /* 存不进去要让本轮失败：图已被剥出消息，主模型手上只剩引用名清单，
               少一个名字就是少一张图，而它无从知道原本有几张。 */
            if (stored.imageFailure() != null) {
                throw new IllegalStateException("本轮图片暂存失败，无法交由 seeImage 委派："
                        + stored.imageFailure());
            }
            effectiveContents = turnInput.withoutImages(contents);
            notice = turnInput.attachmentNotice(stored.imageIds());
            log.info("[Agent编排] 会话[{}] 域 {} 主模型非视觉，本轮 {} 张图转为附件交由 seeImage 委派：{}",
                    sessionId, domainId, stored.imageIds().size(), stored.imageIds());
        }

        TokenUsageRecorder.begin();

        /* 读尾部窗口 + 量一个长度 —— M4-R5 起不再读全量。

           原先这里是 `memory.messages()` 把整段历史拉回来：一次给窗口取尾（只用到最后
           十几个元素），一次 `List.copyOf` 存成回滚快照。两次都是 O(全量)，会话越长越亏。
           现在两件事各走一条与历史长度无关的路：
             • 窗口 = 尾部 LRANGE（只取窗口要用的条数）；
             • 回滚 = 记住"写入前的条数"（Redis 侧 LLEN 是 O(1)），失败时截回该长度。

           顺序上仍严格是"先量长度与读窗口、后写提问"：长度必须落在写入之前，
           否则"没有长度"就不再等于"本轮没写过记忆"，回滚会少截一条。 */
        state.memorySizeBefore = TurnContextFactory.memorySize(memory);
        List<ChatMessage> window = turnContext.windowOf(memory);
        /* 本轮的提问消息只构造一次，记忆与请求体共用同一份。
           多模态下这不再是"省一次构造"：分两次构造就有两条 UserMessage，
           一条进记忆、一条进模型，两份内容块列表是否一致全凭调用方自觉：
           一旦不一致，模型看到的是另一张图，而排查时两边都"看起来正常"。 */
        UserMessage userMessage = turnInput.buildUserMessage(message, effectiveContents, notice);
        memory.add(userMessage);

        /* 归档开轮：用户输入即刻落盘，回答慢慢跑（RPUSH 的返回值即 turnId）。
           放在写记忆之后、跑图之前：一是提问必须先落档（崩溃了也知道他问过什么），
           二是此时"这一轮能不能跑"的前置判定（封顶、视觉路由）都已通过，
           不会给注定被拒的轮留一条永远没人回答的开放轮。

           媒体引用取<b>原始</b> contents 而不是 effectiveContents：委派路径下后者
           已把图片剥掉，若用它开轮，归档里就再也看不出"这一轮带过图"（M1 的旧口径）。
           引用名来自刚刚暂存的 attachmentIds，与<b>媒体块</b>按序对齐（M4-R4）：
           图 / 音 / 视都占号，三种媒体的引用名都能落进档案。

           失败只告警：归档是档案不是请求链的一环，Redis 挂了不该让对话失败。 */
        if (conversationArchive != null) {
            try {
                state.turnId = conversationArchive.openTurn(key,
                        UserInput.of(message, TurnRecorder.mediaRefsOf(contents, attachmentIds)));
            } catch (Exception e) {
                log.error("[Agent编排] 会话[{}] 归档开轮失败，本轮不归档：{}", sessionId, e.getMessage());
            }
        }
        // 开轮即续期（M4-R4）：刚开的这一轮已在归档里，续期口径与闭轮完全一致。
        // 与暂存时写入的 TTL 一致，此处真正的价值是把"续期"这件事绑在"会话又在动"
        // 这个事实上，会话沉寂后不再有轮次经过，附件按原 TTL 自然过期
        turnLifecycle.renewActiveAttachments(key);

        /* ⑤ 请求装配：系统提示 + 早期摘要 + 相关轮卡片 + 近况窗口 + 本轮提问。
           窗口只取最近若干轮的原文（消息块原样带，图片因此不会丢），
           更老的轮次以摘要/卡片形态出现，装配与降级全部在 ContextAssembler 里。
           窗口在上面的"读尾部窗口"一步就已备好（不含本轮提问，提问单列）。 */
        String summary = turnContext.buildSummarySegment(key, contextAssembler.windowTurns());
        List<HistoryCard> cards = turnContext.buildCardsSegment(key, contextAssembler.windowTurns(),
                message, state.turnId);
        ContextAssembler.Assembled assembled = turnContext.assemblerFor(sessionId, domainId)
                .assembleWithReport(key, state.turnId, turnContext.systemMessageOf(domainId), window, summary,
                        cards, userMessage);
        List<ChatMessage> messages = assembled.messages();
        turnLifecycle.recordAssemblyQuietly(key, assembled.report());

        /* ⑤.5 规划前置（可选能力，planner 为 null = 未启用，行为分毫不变）。
           进图前用主模型做一次独立规划调用：direct（简单请求直进 loop）或 plan（多步请求先给计划）。
           计划以"草稿"口径追加进<b>本轮请求体</b> —— 只进 messages，不进记忆：
           计划的生命周期是一轮，混进记忆会跟着会话活到下轮，变成第二份会话历史。
           规划失败（超时 / 解析失败 / 上游报错）在 planner 内部降级为"无计划"，绝不阻塞主流程；
           唯一向上抛的是用户停止（CancellationException），由 runTurn 模板统一收尾。
           是否把计划下发给调用方由域级展示开关决定：关 = 规划照做、前端不可见。 */
        if (planner != null) {
            TurnPlanner.Outcome plan = planner.plan(domainId, messages, key, sessionId);
            if (plan.isPlan()) {
                messages = new ArrayList<>(messages);
                messages.add(planInjectionIndex(messages), plan.asSystemMessage());
                boolean display = planDisplayResolver != null && planDisplayResolver.displayOn(domainId);
                if (display) {
                    // PLAN 恒先于本轮第一个 TOKEN：此时模型还没开始答
                    context.emit(AgentEvent.plan(sessionId, plan.planJson()));
                }
                log.info("[Agent编排-规划] 会话[{}] traceId={} 本轮按计划执行（计划下发前端={}）",
                        sessionId, TraceId.current(), display);
            } else {
                log.debug("[Agent编排-规划] 会话[{}] 本轮无需计划（direct 或降级），直进 loop", sessionId);
            }
        }

        RunnableConfig config = RunnableConfig.builder()
                .threadId(key)
                .addMetadata(AgentGraphFactory.CTX_METADATA_KEY, context)
                .build();

        log.info("[Agent编排] 会话[{}] traceId={} 开始图编排,消息数={}",
                sessionId, TraceId.current(), messages.size());
        long startedAt = System.currentTimeMillis();

        // stream 消费,中断时迭代自然停止;lastState 用于绕过 releaseThread 后 stateOf 失效的问题
        boolean interrupted = false;
        MessagesState<ChatMessage> lastState = null;

        /* 这个点之前抛出的异常 = 图编排压根没起来（请求体还没构造完），
           那种情况下开放轮等于"没发生过"，归档侧删掉它；
           之后抛的 = 图跑了一半，开放轮要保住现场（它问过什么、跑到哪一步）。
           两种处置的差别在档案的诚实度：留一条"没发生过"的轮，
           会让 read_history 读出一个用户根本没问过的问题。 */
        state.graphStarted = true;

        for (var output : compiledGraph.stream(GraphInput.args(Map.of("messages", messages)), config)) {
            lastState = output.state();
            log.debug("[Agent编排] 会话[{}] 节点完成: {}", sessionId, output.node());

            if ("agent".equals(output.node()) && isInterruptedBeforeReview(config)) {
                // 域一致性由状态键本身保证：断点就存在键 (域, sessionId) 下，
                // 用别的域 resume 找不到断点，天然拦在门外。
                context.emit(AgentEvent.interrupt(sessionId,
                        buildInterruptPayload(lastState, context.domainId())));
                interrupted = true;
                log.info("[Agent编排] 会话[{}] 中断于 review,等待人工授权,耗时={}ms",
                        sessionId, System.currentTimeMillis() - startedAt);
                break;
            }
        }

        if (!interrupted) {
            turnLifecycle.rememberFinalAnswer(memory, lastState, key, state.turnId);
            // 耗时是排查"为什么慢"的第一线索：LLM 慢还是工具慢，靠它定位
            log.info("[Agent编排] 会话[{}] traceId={} 图编排完成,耗时={}ms",
                    sessionId, TraceId.current(), System.currentTimeMillis() - startedAt);
            context.emit(AgentEvent.done(sessionId));
        }
        context.complete();    }

    /**
     * 计划消息的注入下标：本轮提问（最后一条 UserMessage）之前。
     *
     * <p>计划是"这一轮怎么做"的上下文，跟着提问走而不是挂在消息序列末尾 ——
     * 放到提问之后，部分兼容协议会拒绝非首位的 system 消息，且模型读到"提问 → 计划"
     * 的顺序也更容易把计划当成上一轮的遗留。找不到提问（理论上不会发生：装配产物
     * 恒以本轮提问收尾）就退化为追加到末尾。</p>
     */
    private static int planInjectionIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof dev.langchain4j.data.message.UserMessage) {
                return i;
            }
        }
        return messages.size();
    }


    /**
     * 恢复被中断的会话
     *
     * @param sessionId 会话 ID,需与 orchestrate 时一致
     * @param approved  true=批准执行工具; false=拒绝,注入拒绝反馈让 agent 重新决策
     * @param domain    会话所属的域，必须与发起对话时同一个域
     * @return 事件流,resume 后的输出继续推送
     */
    @Override
    public Flux<AgentEvent> resume(String sessionId, boolean approved, String domain) {
        DomainCheck check = checkDomain(domain);
        if (check != DomainCheck.OK) {
            log.warn("[Agent编排] 会话[{}] resume 域校验未通过: {}", sessionId, check.detail());
            return Flux.just(AgentEvent.error(sessionId, check.detail(), check.code(),
                    TraceId.currentOrNew()));
        }
        String key = stateKeyOf(domain, sessionId);
        // 同 orchestrate：清上一轮遗留的停止标志必须发生在提交进线程池之前，
        // 否则用户在排队期间点的 stop 会被任务出队后的清理抹掉
        cancellationRegistry.prepareNewRound(key);
        return startRound(sessionId, key, domain, context -> runResume(sessionId, key, approved, context));
    }

    private void runResume(String sessionId, String key, boolean approved, StreamContext context) {
        TurnState state = new TurnState();
        runTurn(sessionId, key, context, "resume", state,
                // resume 不开轮，所以这里只有"中止"一种处置：被审批中断的那一轮
                // 在开轮时就落了档，续跑失败同样要把现场保住
                e -> turnLifecycle.abortOpenTurnQuietly(key, "resume 执行失败：" + TurnFailureHandler.rootMessage(e),
                        context == null ? null : context.partialOutput()),
                () -> runResumeBody(sessionId, key, approved, context));
    }

    /**
     * 续跑的主体。
     *
     * <p>失败一律向上抛：resume 不写记忆，所以没有需要回滚的提问，
     * 其余出口与 {@code orchestrate} 共用 {@link #runTurn} 的收尾。</p>
     */
    private void runResumeBody(String sessionId, String key, boolean approved, StreamContext context)
            throws Exception {
        TokenUsageRecorder.begin();

        RunnableConfig config = RunnableConfig.builder()
                .threadId(key)
                // 同 runOrchestrate：resume 恢复出的图执行同样必须绑定"发起它的那一轮"
                .addMetadata(AgentGraphFactory.CTX_METADATA_KEY, context)
                .build();

        var snapshot = compiledGraph.stateOf(config);
        if (snapshot.isEmpty()) {
            // 会话无检查点：可能是已结束/已过期/从未创建。这是明确的业务状态，
            // 用 SESSION_NOT_FOUND 而不是笼统的执行失败，前端才能正确提示"请新建会话"
            log.warn("[Agent编排] 会话[{}] 无检查点,无法 resume", sessionId);
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_NOT_FOUND,
                    TraceId.currentOrNew()));
            context.complete();
            return;
        }

        log.info("[Agent编排] 会话[{}] resume,approved={}, 当前节点={}, next={}",
                sessionId, approved, snapshot.get().node(), snapshot.get().next());

        if (!AgentGraphFactory.BRANCH_REVIEW.equals(snapshot.get().next())) {
            // resume 的语义是"授权某一次待执行的工具调用"。断点不停在审批点时（例如上一轮异常
            // 残留的断点），这个 approved 会被套到不该套的状态上，等于让图从任意位置继续。
            log.warn("[Agent编排] 会话[{}] 的断点不停在审批点(当前 next={})，拒绝 resume",
                    sessionId, snapshot.get().next());
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_STATE_INVALID,
                    TraceId.currentOrNew()));
            context.complete();
            return;
        }

        // 域一致性由状态键本身保证：断点存在键 (域, sessionId) 下，用另一个域 resume
        // 根本找不到断点（SESSION_NOT_FOUND），不会出现"旧域提示词 + 新域工具集"的组合。

        // 续跑即续期（M4-R4）：审批等待动辄长于附件 TTL，续跑这一刻是"这一轮又活了"
        // 的最早时点。若等待已超过 TTL，附件其实已经没了，这里续不到它，
        // seeImage 会如实回喂"附件已过期"，而不是拿一张取不到的图去问模型
        turnLifecycle.renewActiveAttachments(key);

        if (!approved) {
            // 拒绝:只为这批待执行的工具调用追加"被拒绝"的结果,由模型据此重新生成不带工具调用的回复。
            List<ChatMessage> rejections = new ArrayList<>();
            var history = snapshot.get().state().messages();
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        rejections.add(ToolExecutionResultMessage.from(req,
                                "用户拒绝了此工具调用,请直接回复或换一种方式回答"));
                    }
                    break;
                }
            }
            // asNode 必须是 "tools"，不是 "review"：
            // 它表示"假装 tools 节点已产出本次更新"，图随即沿 tools → agent 这条出边进入
            // agent，由模型基于"用户已拒绝"的结果重新生成回复。
            // 若写成 "review"：review 的出边是 tools，会直接进 toolsNode，而此刻最后一条
            // 消息是 ToolExecutionResultMessage（非 AiMessage），必撞 toolsNode 的
            // "最后一条消息不是 AiMessage" 校验，整轮被兜底成 ORCHESTRATION_FAILED。
            compiledGraph.updateState(config, Map.of("messages", rejections), "tools");
        }

        boolean interrupted = false;
        MessagesState<ChatMessage> lastState = null;

        for (var output : compiledGraph.stream(GraphInput.resume(), config)) {
            lastState = output.state();
            log.debug("[Agent编排-resume] 会话[{}] 节点完成: {}", sessionId, output.node());

            if ("agent".equals(output.node()) && isInterruptedBeforeReview(config)) {
                context.emit(AgentEvent.interrupt(sessionId,
                        buildInterruptPayload(lastState, context.domainId())));
                interrupted = true;
                log.info("[Agent编排-resume] 会话[{}] 再次中断于 review", sessionId);
                break;
            }
        }

        if (!interrupted) {
            // resume 不开轮：闭合的是"被审批中断的那一轮"在开轮时留下的那条开放轮
            turnLifecycle.rememberFinalAnswer(chatMemoryProvider.get(key), lastState, key, -1);
            log.info("[Agent编排-resume] 会话[{}] resume 完成", sessionId);
            context.emit(AgentEvent.done(sessionId));
        }
        context.complete();    }


    /** 把一轮用户输入里带引用名的附件名收集进 sink */
    /**
     * 本轮 token 用量快照，落进归档的 {@code TurnRecord.usage}。
     *
     * <p>折算口径（实报优先、缺失回落估算，以及两条口径下 {@code totalTokens} 的差别）
     * 全在 {@link TokenUsageRecorder#toUsage()}，这里只取快照。</p>
     */
    private static TokenUsage usageOf() {
        return TokenUsageRecorder.toUsage();
    }

    /**
     * 把服务商上报的真实用量记进本轮统计（M4 回填）。
     *
     * <p>用 {@code var} 接住 {@code response.tokenUsage()}：那是 langchain4j 的
     * {@code dev.langchain4j.model.output.TokenUsage}，与本文件已导入的平台
     * {@code api.conversation.TokenUsage} 同名，写全名会撞。</p>
     */
    private static void captureRealUsage(ChatResponse response) {
        if (response == null || response.tokenUsage() == null) {
            return;
        }
        var usage = response.tokenUsage();
        TokenUsageRecorder.recordRealUsage(usage.inputTokenCount(), usage.outputTokenCount());
    }

    /** 停止 / 异常 / 崩溃：把开放轮标记 aborted 后闭合，现场（问了什么、半截文本）留在档里 */
    /**
     * 会话是否停在审批点（存在待授权的中断点）。
     */
    private boolean pendingApproval(String key) {
        RunnableConfig config = RunnableConfig.builder().threadId(key).build();
        try {
            return compiledGraph.stateOf(config)
                    .map(snapshot -> AgentGraphFactory.BRANCH_REVIEW.equals(snapshot.next()))
                    .orElse(false);
        } catch (CheckpointCorruptedException e) {
            // 数据损坏：不归到下面的"读失败整轮拒绝"，交给调用方清键自愈，
            // 那是唯一能让这个会话重新可用的办法，而整轮拒绝对坏键永远无效。
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "读取会话检查点失败，无法判断是否存在待审批中断点: " + e.getMessage(), e);
        }
    }

    /**
     * 判断图是否已中断在 review 节点之前
     */
    private boolean isInterruptedBeforeReview(RunnableConfig config) {
        try {
            var snapshot = compiledGraph.stateOf(config).orElse(null);
            return snapshot != null && AgentGraphFactory.BRANCH_REVIEW.equals(snapshot.next());
        } catch (Exception e) {
            // 读不出来 ≠ 没中断。两者混为一谈会让"本该停下等审批"的一轮以"完成"收尾：
            // 中间态被写进记忆、DONE 照发，而审批入口在界面上永远不出现。
            // 读不到就明确失败，让调用方看到错误，而不是拿到一个错的"完成"。
            throw new IllegalStateException(
                    "读取图中断状态失败，无法判断是否停在审批点: " + e.getMessage(), e);
        }
    }

    /**
     * 给人看的工具参数：声明的敏感项（{@code @ToolAdvanced.sensitive}）在
     * 工具调用事件与审批 payload 里只显示掩码。
     *
     * 只改展示文本，真正执行与回喂模型的参数仍是原文，否则工具会拿到 {@code ***} 而失效。
     */
    private String displayArguments(String toolName, String arguments) {
        if (!toolRouter.exists(toolName)) {
            // 工具已不在注册表：敏感参数声明随条目一起消失，此刻没有依据判断哪个参数敏感。
            // 按空集处理等于把参数原样透出，工具刚下线、模型仍在调用的这一刻最该闭嘴。
            return SensitiveMasker.maskAll(arguments);
        }
        return SensitiveMasker.mask(arguments, toolRouter.sensitiveParams(toolName));
    }

    /**
     * 构建中断事件 payload:待授权工具调用列表(JSON)
     *
     * 失败即失败：序列化异常时向上抛，绝不降级成空清单。
     * 空清单会被客户端渲染成"没有待授权动作"，用户据此点了批准 → 危险动作照常执行，
     * 而他从头到尾没看到自己在批准什么。这比整轮失败危险得多。
     * 抛出后由上层 catch 收尾：清断点（不能留下可被 resume 的待授权断点）+ ERROR 事件。
     */
    private String buildInterruptPayload(MessagesState<ChatMessage> state, String domainId) {
        Set<String> approvalTools = toolRouter.toolsRequiringApproval(domainId);
        List<ToolCall> calls = new ArrayList<>();
        if (state != null) {
            var messages = state.messages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        calls.add(ToolCall.of(req.name(),
                                displayArguments(req.name(), req.arguments()),
                                approvalTools.contains(req.name())));
                    }
                    break;
                }
            }
        }
        // 走到 review 分支就说明图已经拦下了待授权的工具请求，"清单却是空的"只能是状态不一致
        // （state 丢失、消息被换掉）。发一个空清单等于告诉调用方"没有待授权动作"，
        // 调用方据此放行 → 审批形同虚设。同样按失败收尾。
        if (calls.isEmpty()) {
            log.error("[Agent编排] 会话中断于审批点但待授权清单为空，断点状态与图状态不一致；"
                    + "本轮按失败收尾（不发送空清单：那会让调用方在无审批内容的情况下放行工具）");
            throw new IllegalStateException("审批清单为空但图已拦下待授权动作，断点状态不一致，已阻断本轮");
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(ToolCallPayload.of(calls));
        } catch (Exception e) {
            log.error("[Agent编排] 中断 payload 序列化失败，待授权动作={} 个，本轮按失败收尾"
                    + "（不降级为空清单：用户会看不到批准内容却按下批准）", calls.size(), e);
            throw new IllegalStateException("构建审批清单失败，已阻断本轮（不会在用户未看清内容的情况下放行工具执行）: "
                    + e.getMessage(), e);
        }
    }

    /** 静默清理 checkpoint:失败只记日志,不阻断主流程 */
    /** 当前注册的流上下文数量(观测用) */
    public int activeStreamCount() {
        return streamSinks.size();
    }
}
