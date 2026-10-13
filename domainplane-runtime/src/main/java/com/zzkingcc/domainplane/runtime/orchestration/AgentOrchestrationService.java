package com.zzkingcc.domainplane.runtime.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
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
     * 条件边的分支键。
     */
    private static final String BRANCH_EXIT = "exit";
    private static final String BRANCH_AUTO = "auto";
    private static final String BRANCH_REVIEW = "review";

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
     * 单模型构造：不传 {@link ModelResolver}，所有域共用构造期注入的那个模型。
     */
    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ToolRouter toolRouter,
            SystemPromptResolver promptResolver,
            BaseCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
            CancellationRegistry cancellationRegistry,
            Executor executor) {
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver, checkpointSaver,
                stateSerializer, cancellationRegistry, executor, null, null);
    }

    /**
     * 不带图片暂存区的构造：模型解析器齐备但不需要委派兜底。
     */
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
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver, checkpointSaver,
                stateSerializer, cancellationRegistry, executor, modelResolver, toolCallEchoResolver, null);
    }

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
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver, checkpointSaver,
                stateSerializer, cancellationRegistry, executor, modelResolver, toolCallEchoResolver,
                mediaAttachmentStore, null);
    }

    /**
     * 完整装配。
     *
     * @param conversationArchive 会话归档；{@code null} = 不归档（其余行为与改造前一致）
     * @param contextAssembler    召回装配器；{@code null} = 用默认预算装配
     */
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
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver, checkpointSaver,
                stateSerializer, cancellationRegistry, executor, modelResolver, toolCallEchoResolver,
                mediaAttachmentStore, conversationArchive, null);
    }

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
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver, checkpointSaver,
                stateSerializer, cancellationRegistry, executor, modelResolver, toolCallEchoResolver,
                mediaAttachmentStore, conversationArchive, contextAssembler, null);
    }

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
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver, checkpointSaver,
                stateSerializer, cancellationRegistry, executor, modelResolver, toolCallEchoResolver,
                mediaAttachmentStore, conversationArchive, contextAssembler, historySearch, null);
    }

    /**
     * 完整装配（含滚动摘要）。
     *
     * @param rollingSummaryService 滚动摘要调度器；{@code null} = 不摘要，装配侧退化为 digest 拼接
     */
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

        this.streamingChatModel = streamingChatModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.promptResolver = Objects.requireNonNull(promptResolver, "promptResolver");
        this.checkpointSaver = checkpointSaver;
        this.cancellationRegistry = cancellationRegistry;
        this.toolRouter = toolRouter;
        this.streamSinks = new StreamSinkRegistry();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.modelResolver = modelResolver;
        this.toolCallEchoResolver = toolCallEchoResolver;
        this.mediaAttachmentStore = mediaAttachmentStore;
        this.conversationArchive = conversationArchive;
        this.contextAssembler = contextAssembler == null ? new ContextAssembler() : contextAssembler;
        this.historySearch = historySearch;
        this.rollingSummaryService = rollingSummaryService;

        // 工具规格不在构造期固化：域是按请求变化的，工具集必须在 agent 节点运行时
        // 用 toolRouter.toolSpecifications(domainId) 按本轮所处的域过滤。
        int totalTools = toolRouter.toolSpecifications().size();
        int approvalTools = toolRouter.toolsRequiringApproval().size();

        this.compiledGraph = buildGraph(checkpointSaver, stateSerializer);

        log.info("[Agent编排-LangGraph] 初始化完成,注册表内共 {} 个工具(其中 {} 个需授权);"
                        + "已出现的域: {};实际可见工具集与系统提示词都按每次请求的域动态解析;"
                        + "图结构: agent→(auto|review)→tools→agent(条件循环)",
                totalTools, approvalTools, toolRouter.knownDomains());
    }

    /**
     * 构建 StateGraph 并编译
     */
    private CompiledGraph<MessagesState<ChatMessage>> buildGraph(BaseCheckpointSaver checkpointSaver,
                                                                 ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer) {
        try {
            var workflow = new StateGraph<MessagesState<ChatMessage>>(messagesSchema(), stateSerializer)
                    .addNode("agent", syncNode(this::agentNode))
                    .addNode("review", syncNode((state, config) -> Map.<String, Object>of()))   // no-op 节点,仅作中断锚点
                    .addNode("tools", syncNode(this::toolsNode))
                    .addEdge(START, "agent")
                    // 条件边需要 RunnableConfig（要从中取 sessionId → 调用方权限）才能决定路由。
                    // langgraph4j 1.8.17 的 AsyncEdgeAction 只吃一个参数，得用 AsyncCommandAction：
                    // 它本质是 BiFunction<State, RunnableConfig, Command>，与 addConditionalEdges 兼容。
                    // 注意：Command 里放的是【分支键】，不是节点名（详见 routeAfterAgent 注释）。
                    .addConditionalEdges("agent", routeAfterAgent(),
                            Map.of(BRANCH_EXIT, END, BRANCH_AUTO, "tools", BRANCH_REVIEW, "review"))
                    .addEdge("review", "tools")   // approve 后执行工具
                    .addEdge("tools", "agent");

            var compileConfig = org.bsc.langgraph4j.CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .interruptBefore("review")    // 仅 review 节点前中断,tools 直连不暂停
                    .releaseThread(true)          // 正常结束后释放 checkpoint,避免无限堆积
                    .build();

            return workflow.compile(compileConfig);
        } catch (org.bsc.langgraph4j.GraphStateException e) {
            throw new IllegalStateException("StateGraph 构建失败", e);
        }
    }

    /**
     * 图状态的通道定义
     */
    private static Map<String, Channel<?>> messagesSchema() {
        Map<String, Channel<?>> schema = new HashMap<>(MessagesState.SCHEMA);
        schema.put(MessagesState.MESSAGES_STATE,
                Channels.<ChatMessage>appenderWithDuplicate(ArrayList::new));
        return schema;
    }

    /**
     * 同步节点包装器:在调用线程上执行节点逻辑,不切换线程
     */
    private AsyncNodeActionWithConfig<MessagesState<ChatMessage>> syncNode(
            BiFunction<MessagesState<ChatMessage>, RunnableConfig, Map<String, Object>> action) {
        return (state, config) -> {
            try {
                return CompletableFuture.completedFuture(action.apply(state, config));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }

    /** 会话状态键（threadId）：域 + sessionId，一切按会话索引的状态都用它 */
    private String stateKeyOf(RunnableConfig config) {
        return config.threadId().orElseThrow(() -> new IllegalStateException("RunnableConfig 缺少 threadId"));
    }

    /** 从 RunnableConfig 取调用方声明的 sessionId（状态键里分隔符之后的部分，用于事件与日志） */
    private String sessionIdOf(RunnableConfig config) {
        return SessionKeys.sessionIdOf(stateKeyOf(config));
    }

    private String sessionIdOfKey(String key) {
        return SessionKeys.sessionIdOf(key);
    }

    /**
     * metadata 里携带本轮流式上下文的键名。
     *
     * 本轮的 {@link StreamContext} 必须随图执行一路传下去，绝不能在节点里按
     * threadId 回查全局注册表：注册表里存的是"当前那一轮"，而节点跑的是"发起它的那一轮"。
     * 两者在两个请求重叠时会不一致，此时 A 的工具调用会按 B 的域过滤、
     * A 的模型 token 会流进 B 的 SSE 流。这是跨域串号，不是显示问题。
     */
    private static final String CTX_METADATA_KEY = "domainplane.streamContext";

    /** 从图中取回本轮上下文；节点内只允许用这个方法取，禁止按 key 回查注册表 */
    private StreamContext contextOf(RunnableConfig config) {
        Optional<Object> carried = config.metadata(CTX_METADATA_KEY);
        if (carried.isPresent() && carried.get() instanceof StreamContext ctx) {
            return ctx;
        }
        // 兜底：仅用于图在编排器之外被直接驱动的情形（如测试）。
        // 这里刻意告警而不是静默：按 key 回查在两个请求重叠时会拿到别的轮次的上下文，
        // 那是跨域串号，必须让这种驱动方式在日志里显形。
        StreamContext fallback = streamSinks.get(stateKeyOf(config));
        log.warn("[Agent编排] 会话[{}] 图配置未携带本轮上下文，回退到按 key 查询注册表"
                + "（若存在同一会话的重叠请求，可能取到别的轮次的上下文）", sessionIdOfKey(stateKeyOf(config)));
        return fallback;
    }

    /**
     * agent 节点:调用流式模型,推送 TOKEN 事件,返回 AiMessage 到状态
     */
    private Map<String, Object> agentNode(MessagesState<ChatMessage> state, RunnableConfig config) {
        String key = stateKeyOf(config);
        String sessionId = sessionIdOfKey(key);
        // 取本轮上下文，而不是按 key 回查注册表：见 contextOf 的说明
        StreamContext context = contextOf(config);
        AtomicInteger llmOutputTokens = new AtomicInteger(0);

        // 按本轮所处的域过滤工具集：只有声明了该域（或未声明任何域）的工具才会出现在模型视野里。
        // 域是"按请求"的，因此必须在此处（运行时）过滤，不能在构造函数里固化。
        String domainId = context == null ? null : context.domainId();
        List<dev.langchain4j.agent.tool.ToolSpecification> visibleTools =
                toolRouter.toolSpecifications(domainId);

        // 本轮使用哪个模型：按域解析（执行单元内取一次，与"域、提示词"同一条冻结规则）
        StreamingChatModel model = resolveModel(domainId);

        try {
            var parameters = ChatRequestParameters.builder()
                    .toolSpecifications(visibleTools)
                    .build();
            var request = ChatRequest.builder()
                    .messages(state.messages())
                    .parameters(parameters)
                    .build();

            CompletableFuture<AiMessage> future = new CompletableFuture<>();

            model.chat(request, new StreamingChatResponseHandler() {                @Override
                public void onPartialResponse(String partialResponse) {
                    // 停止检查:用户请求停止后,完成 future 抛 CancellationException,终止流
                    if (cancellationRegistry.isCancelled(key)) {
                        future.completeExceptionally(new CancellationException("用户主动停止"));
                        return;
                    }
                    if (context != null) {
                        llmOutputTokens.addAndGet(TokenUsageRecorder.estimateTokens(partialResponse));
                        context.appendPartial(partialResponse);
                        context.emit(AgentEvent.token(sessionId, partialResponse));
                    }
                }

                /**
                 * 思考增量：只打标记、原样透传。
                 *
                 * 三处"不"是刻意的：
                 *
                 *   • 不并入答案，思考不是答案的一部分，混进 {@code TOKEN} 流之后
                 *       下游就再也分不开了（只能靠猜文本规律）；
                 *   • 不进 {@code appendPartial}，那段是"本轮已产出的答案"，停止回滚
                 *       与最终回答都用它；混进思考会让用户点了停止后拿到的半截回答里带着推理过程；
                 *   • 不解析内容，展示成折叠块、灰色小字、还是干脆不显示，是消费方的决定，
                 *       平台只负责把两类帧分开。
                 *
                 *
                 * 只有档案里「输出思考」打开的模型才会走到这里（框架不返回思考时这条回调不会触发）；
                 * 关着的档案不产生这类帧，消费方不需要为此分支。
                 */
                @Override
                public void onPartialThinking(PartialThinking partialThinking,
                                              PartialThinkingContext partialThinkingContext) {
                    // 停止检查与 onPartialResponse 同口径：漏了这里，模型一直在"想"而没吐答案时，
                    // 用户点了停止这一轮照旧跑完，现象就是"停止偶尔不灵"。
                    if (cancellationRegistry.isCancelled(key)) {
                        future.completeExceptionally(new CancellationException("用户主动停止"));
                        return;
                    }
                    if (context == null || partialThinking == null) {
                        return;
                    }
                    String text = partialThinking.text();
                    if (text != null && !text.isEmpty()) {
                        context.emit(AgentEvent.thinking(sessionId, text));
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    // 这个回调同样要查停止标志：模型不分片、一次性返回时若只在 onPartialResponse
                    // 里检查，取消就完全不生效，用户点了停止，这一轮照旧跑完并写入记忆。
                    if (cancellationRegistry.isCancelled(key)) {
                        future.completeExceptionally(new CancellationException("用户主动停止"));
                        return;
                    }
                    captureRealUsage(completeResponse);
                    future.complete(completeResponse.aiMessage());
                }

                @Override
                public void onError(Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            // 不用 future.join()：它既没有上限也不看停止标志。上游连接半开
            // （既不吐 token、也不回调 onError）时，本会话的"执行中"标记会被永久占住，
            // stop() 设的标志再也没人读，该会话只能重启进程才能再用。
            AiMessage aiMessage = awaitAiMessage(future, key);
            return Map.of("messages", aiMessage);
        } finally {
            // 失败路径也要统计：模型已经开始产 token 就产生了用量，只记成功路径会漏
            TokenUsageRecorder.addLlmOutputTokens(llmOutputTokens.get());
        }
    }

    /**
     * 等待模型给出终态，带停止检查与总时长上限。
     *
     * <p>{@code future.join()} 不能用：它既没有上限也不看停止标志。上游连接半开
     * （既不吐 token、也不回调 {@code onError}）时，本会话的"执行中"标记会被永久占住，
     * {@code stop()} 设下的标志再也没人读，该会话此后只能重启进程才能再用。</p>
     *
     * <p>切片等待而不是一次性 wait：每片醒来先看停止标志，用户点停止最迟一个切片就生效。</p>
     */
    private AiMessage awaitAiMessage(CompletableFuture<AiMessage> future, String key) {
        long deadline = System.nanoTime() + MODEL_WAIT_LIMIT_MILLIS * 1_000_000L;
        while (true) {
            try {
                return future.get(MODEL_WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (cancellationRegistry.isCancelled(key)) {
                    future.completeExceptionally(new CancellationException("用户主动停止"));
                    throw new CancellationException("用户主动停止");
                }
                if (System.nanoTime() >= deadline) {
                    future.completeExceptionally(new TimeoutException("模型响应超时"));
                    throw new CompletionException(new TimeoutException(
                            "等待模型响应超过 " + (MODEL_WAIT_LIMIT_MILLIS / 60000) + " 分钟，已放弃本轮"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (ExecutionException e) {
                // 与 join() 口径一致：尽量抛原始异常，包装异常解一层
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new CompletionException(cause);
            }
        }
    }

    /** 等模型终态时的单次阻塞上限：醒来先看停止标志 */
    private static final long MODEL_WAIT_SLICE_MILLIS = 200L;

    /** 等模型终态的总上限：兜住"上游既不吐 token 也不报错"的半开连接 */
    private static final long MODEL_WAIT_LIMIT_MILLIS = 10 * 60 * 1000L;

    /**
     * 解析本轮使用的对话模型。
     *
     * 没有解析器（未装配模型档案的部署、单元测试）时退回构造期注入的模型，行为与"全局一个模型"一致。
     * 一旦装配了解析器，解析不到 / 解析异常都不回落任何内置默认模型：未绑定 = 无可调用，
     * 如实体上报 {@link NotConfiguredException}，让调用方拿到明确原因。
     */
    private StreamingChatModel resolveModel(String domainId) {
        if (modelResolver == null) {
            return streamingChatModel;
        }
        // 解析器存在时，未配置 / 解析失败都如实向上抛，不静默回落默认模型
        return modelResolver.streamingChat(domainId);
    }

    /**
     * 本轮真正用来装配上下文的 {@link ContextAssembler}。
     *
     * <p>装配实例是启动期按配置造好的单例（预算与模型无关），而模型是<b>按域、按请求</b>
     * 解析的，所以"这轮给模型看多少"必须在此刻重算：取
     * {@code min(配置预算, 模型声明的上下文窗口 - 输出预留 - 安全边际)}。具体算法见
     * {@link ContextBudget}（只封顶、不放大）。</p>
     *
     * <p>刻意不让上限解析失败影响本轮：它只是"能不能更保守"的锦上添花，而
     * "域没绑模型"这件事会在 {@code agentNode} 里由 {@code resolveModel} 如实抛出。
     * 在这里因为取不到上限而失败，会把一个本该成功的轮次变成一个原因可疑的失败。</p>
     *
     * @return 收窄后的装配器；未声明窗口 / 未装解析器 / 解析异常时返回原单例
     */
    private ContextAssembler assemblerFor(String sessionId, String domainId) {
        if (modelResolver == null) {
            return contextAssembler;
        }
        ModelLimits limits;
        try {
            limits = modelResolver.modelLimits(domainId).orElse(null);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 域 {} 读取模型上限失败，装配预算维持配置值 {}：{}",
                    sessionId, domainId, contextAssembler.totalBudget(), e.getMessage());
            return contextAssembler;
        }
        if (limits == null) {
            return contextAssembler;
        }
        int budget = ContextBudget.effective(contextAssembler.totalBudget(),
                limits.contextWindow(), limits.maxOutputTokens());
        if (budget >= contextAssembler.totalBudget()) {
            return contextAssembler;
        }
        log.info("[Agent编排] 会话[{}] 域 {} 模型声明的上下文窗口 {}（输出预留 {}）小于装配预算 {}，"
                        + "本轮收紧为 {}",
                sessionId, domainId, limits.contextWindow(), limits.maxOutputTokens(),
                contextAssembler.totalBudget(), budget);
        return contextAssembler.withTotalBudget(budget);
    }

    /**
     * tools 节点:执行 LLM 请求的工具调用,返回 ToolExecutionResultMessage 列表到状态
     */
    private Map<String, Object> toolsNode(MessagesState<ChatMessage> state, RunnableConfig config) {
        String key = stateKeyOf(config);
        String sessionId = sessionIdOfKey(key);
        StreamContext context = contextOf(config);

        var lastMessage = state.lastMessage()
                .orElseThrow(() -> new IllegalStateException("消息列表为空"));

        if (!(lastMessage instanceof AiMessage aiMessage)) {
            throw new IllegalStateException("最后一条消息不是 AiMessage");
        }

        List<ToolExecutionResultMessage> results = new ArrayList<>();
        // 域取自本轮 StreamContext：resume 时会用它重新校验待执行的工具调用，
        // 这是"执行单元内不换提示词"之下唯一能拦住陈旧上下文放行危险动作的机制。
        String domainId = context == null ? null : context.domainId();
        // 工具执行信息回写开关：域级、默认关。关掉后 TOOL_CALL / TOOL_RESULT 不下发，
        // 调用方只收到最终答复。审批挂起（INTERRUPT，含待授权工具清单）不归它管：
        // 那是功能骨架，缺了它调用方无法审批，会话会永久挂起。
        boolean echoToolCalls = toolCallEchoResolver != null && toolCallEchoResolver.echoOn(domainId);
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            // 停止检查:每个工具执行前检查标志,避免停止后继续执行后续工具
            if (cancellationRegistry.isCancelled(key)) {
                throw new CancellationException("用户主动停止");
            }

            // 兜底校验:正常链路下域外工具根本不会喂给模型,但模型可能凭历史上下文
            // 调用了上一轮可见、本轮已不可见的工具（resume 场景尤其如此）。此处拦住,
            // 避免绕过域过滤真实执行。
            // 复核是 fail-closed 的:工具已不在注册表（提供它的实例下线、条目整体移除）
            // 同样被拦下，否则动态下线对新会话不生效,模型会一直"看得见"已消失的工具。
            if (!toolRouter.isToolVisible(request.name(), domainId)) {
                // 用 ErrorCode 的枚举文案，而不是自造一句：文案统一由枚举提供，
                // 改文案时不用满项目找散落的字符串。
                // 两种拒绝原因分开:*域外*是权限问题,*不存在*是能力已消失(工具提供方下线),
                // 对模型是不同信号,后者告知用户比反复重试更有用。
                boolean exists = toolRouter.exists(request.name());
                String denied = exists
                        ? ErrorCode.TOOL_PERMISSION_DENIED.getMessage()
                        + "（" + request.name() + "）"
                        : ErrorCode.TOOL_NOT_FOUND.getMessage()
                        + "（" + request.name() + "，可能已随提供方下线）";
                log.warn("[Agent编排] 会话[{}] traceId={} 拦截{}的工具调用: {}（domain={}）",
                        sessionId, TraceId.current(), exists ? "域外" : "已下线",
                        request.name(), domainId);
                if (context != null && echoToolCalls) {
                    context.emit(AgentEvent.toolCall(sessionId, request.name(),
                            displayArguments(request.name(), request.arguments())));
                    context.emit(AgentEvent.toolResult(sessionId, request.name(), denied));
                }
                results.add(ToolExecutionResultMessage.from(request, denied));
                continue;
            }

            if (context != null && echoToolCalls) {
                context.emit(AgentEvent.toolCall(sessionId, request.name(),
                        displayArguments(request.name(), request.arguments())));
            }

            // 三样一起绑（同绑定点、同解除点，不会泄漏到同线程的下一轮）：
            //   • traceId  —— 工具侧日志要能对上这次调用；
            //   • sessionId —— 历史检索工具只能读"本会话"的档，工具签名里不带它，
            //                  给了模型就等于给它一个"指定别的会话"的口子；
            //   • searchBudget —— 一轮内多次历史检索扣同一个桶（本轮新建，跨 agent→tools 循环共享）。
            ToolInvocationContext.bind(context == null ? null : context.traceId(),
                    sessionId, context == null ? null : context.searchBudget());
            // 知识库检索要按本轮域过滤，而工具签名里没有域参数：在这里把域绑给检索层。
            // 与 ToolInvocationContext 同绑定点、同解除点，不会泄漏到同线程的下一轮。
            RetrievalScope.bind(domainId);
            String result;
            try {
                result = toolRouter.execute(request);
            } finally {
                RetrievalScope.clear();
                ToolInvocationContext.clear();
            }

            if (context != null && echoToolCalls) {
                context.emit(AgentEvent.toolResult(sessionId, request.name(), result));
            }
            results.add(ToolExecutionResultMessage.from(request, result));
        }

        return Map.of("messages", results);
    }

    /**
     * 条件边:agent 节点执行后,按工具调用类型三路分流
     * @return "exit" 无工具→END; "auto" 全自主工具→tools直连; "review" 含需授权工具→review节点
     */
    private AsyncCommandAction<MessagesState<ChatMessage>> routeAfterAgent() {
        return (state, config) -> {
            var lastMessage = state.lastMessage()
                    .orElseThrow(() -> new IllegalStateException("消息列表为空"));

            String key = stateKeyOf(config);
            String sessionId = sessionIdOfKey(key);
            StreamContext context = contextOf(config);
            // 与 agentNode 使用同一份域、同一份过滤条件，保证"模型可见工具集"与"路由判断"一致
            Set<String> approvalTools = toolRouter.toolsRequiringApproval(
                    context == null ? null : context.domainId());

            if (lastMessage instanceof AiMessage aiMessage) {
                if (aiMessage.hasToolExecutionRequests()) {
                    boolean needsApproval = aiMessage.toolExecutionRequests().stream()
                            .anyMatch(req -> approvalTools.contains(req.name()));
                    if (needsApproval) {
                        log.info("[Agent编排-条件边] 含需授权工具,路由到 review 中断点");
                        return CompletableFuture.completedFuture(new Command(BRANCH_REVIEW));
                    }
                    log.info("[Agent编排-条件边] 全自主工具,直连 tools");
                    return CompletableFuture.completedFuture(new Command(BRANCH_AUTO));
                }
            }
            return CompletableFuture.completedFuture(new Command(BRANCH_EXIT));
        };
    }

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
        String sessionIdReason = SessionKeys.validateSessionId(request.getSessionId());
        if (sessionIdReason != null) {
            log.warn("[Agent编排] 会话标识不合法: {}", sessionIdReason);
            return Flux.just(AgentEvent.error(request.getSessionId(), sessionIdReason,
                    ErrorCode.INVALID_PARAMETER, TraceId.currentOrNew()));
        }
        String contentProblem = validateContents(request.getContents());
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

            try {
                executor.execute(() -> runOrchestrate(sessionId, key, message, contents, context));
            } catch (RejectedExecutionException e) {
                log.error("[Agent编排] 会话[{}] 提交失败,编排线程池已满", sessionId);
                streamSinks.unregister(key, context);
                sink.next(AgentEvent.error(sessionId, ErrorCode.SYSTEM_BUSY, context.traceId()));
                sink.complete();
            }
        });
    }

    /**
     * 把用户上行的内容块拼成本轮的 {@link UserMessage}。
     *
     * <p>三个易错点，都实测踩过：</p>
     *
     * <ul>
     *   <li>{@code UserMessage.from(String, Content...)} 的第一个 {@code String} 是<b>用户名</b>，
     *       不是文本，拿它拼多模态，文字会静默跑进 {@code name} 字段，
     *       模型只收到图、看不到那句话，而两边都不报错。文本必须以
     *       {@link TextContent} 的形式进内容块。</li>
     *   <li>TEXT 块不能再拼一遍：{@code message} 本来就是各 TEXT 块的合并结果
     *       （见 {@code AgentRequest.getMessage()}），再拼一遍等于把用户的话说两遍。</li>
     *   <li>平台追加的 {@code notice}（附件清单）必须并进同一个文本块，而不是自成一个
     *       {@code TextContent}：多个文本块在部分服务商上会被拼接成一段，
     *       但在另一些上只会取第一块，附件清单被丢掉的话，模型看不到引用名，
     *       既不会去调 {@code seeImage}，也无从解释自己为什么看不到图。</li>
     * </ul>
     *
     * @param notice 平台追加的提示（本轮附件清单），可空；空 = 纯用户输入
     */
    private static UserMessage buildUserMessage(String message, List<MessageContent> contents, String notice) {
        String text = message == null ? "" : message;
        boolean hasNotice = notice != null && !notice.isBlank();
        if (!hasNotice && (contents == null || contents.isEmpty())) {
            return UserMessage.from(text);
        }
        List<Content> media = new ArrayList<>(contents == null ? 0 : contents.size());
        if (contents != null) {
            for (MessageContent block : contents) {
                // 文本已由 message 承载（合并结果），此处只搬非文本块
                if (block.type() == ContentType.TEXT) {
                    continue;
                }
                media.add(toContent(block));
            }
        }
        String head = combine(text, notice);
        if (media.isEmpty()) {
            // 本轮没有任何媒体块：仍是一条普通文本消息，只是可能多了平台提示
            return UserMessage.from(head);
        }
        if (head.isBlank()) {
            // 只发图不发字是合法输入。此时不能塞一个空 TextContent 占位，
            // 那会让模型收到一个空文本块，部分厂商直接判参数非法。
            return UserMessage.from(media);
        }
        List<Content> blocks = new ArrayList<>(media.size() + 1);
        blocks.add(TextContent.from(head));
        blocks.addAll(media);
        return UserMessage.from(blocks);
    }

    /** 用户原话与平台提示拼成一段文本；任一侧空白即取另一侧 */
    private static String combine(String text, String notice) {
        String head = text == null ? "" : text;
        String tail = notice == null ? "" : notice;
        if (head.isBlank()) {
            return tail;
        }
        if (tail.isBlank()) {
            return head;
        }
        return head + "\n\n" + tail;
    }

    /** 内容块 → langchain4j 内容对象 */
    private static Content toContent(MessageContent block) {
        return switch (block.type()) {
            case IMAGE_URL -> ImageContent.from(block.requireUrl(), detailOf(block.detail()));
            case IMAGE_BASE64 -> {
                // 透明底 PNG 先合成白底再推：部分服务商预处理丢 alpha，
                // 笔画全在 alpha 里的图（签名 / 截图 / 印章）会被看成纯黑。无 alpha 的图原样不动。
                ImageAlphaFlattener.Flattened flat = ImageAlphaFlattener.flatten(block.base64(), block.requireMime());
                yield ImageContent.from(flat.base64(), flat.mime(), detailOf(block.detail()));
            }
            // 音频 / 视频：langchain4j 的 OpenAI 适配层把 AudioContent 映射为 input_audio
            // 部件（format 取 mime 子类型，audio/wav → wav）、VideoContent 映射为
            // video_url 部件（base64 自动拼 data URL）。服务商是否支持由域上绑的模型决定，
            // 不支持时错误从模型层明确返回，不在平台侧预设黑白名单。
            case AUDIO_URL -> AudioContent.from(block.requireUrl());
            case AUDIO_BASE64 -> AudioContent.from(block.base64(), block.requireMime());
            case VIDEO_URL -> VideoContent.from(block.requireUrl());
            case VIDEO_BASE64 -> VideoContent.from(block.base64(), block.requireMime());
            default -> throw new IllegalArgumentException("不支持的内容块类型: " + block.type());
        };
    }

    /**
     * 视觉细节档位。空 / 无法识别一律给 {@code AUTO}：交回厂商默认比替它挑一档安全，
     * langchain4j 的 {@code ImageContent.from(...)} 默认给 {@code LOW}（先把图缩到 512²），
     * 用户发张截图上来问字，缩完就看不清了。
     */
    private static ImageContent.DetailLevel detailOf(String detail) {
        if (detail == null || detail.isBlank()) {
            return ImageContent.DetailLevel.AUTO;
        }
        try {
            return ImageContent.DetailLevel.valueOf(detail.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ImageContent.DetailLevel.AUTO;
        }
    }

    /** 本轮内容块里的图片张数（容量判定据此计图片开销） */
    private static int imageCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isImage);
    }

    /** 本轮内容块里的音频条数（容量判定据此计音频开销） */
    private static int audioCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isAudio);
    }

    /** 本轮内容块里的视频条数（容量判定据此计视频开销） */
    private static int videoCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isVideo);
    }

    private static int countOf(List<MessageContent> contents, java.util.function.Predicate<ContentType> pick) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        return (int) contents.stream().filter(block -> pick.test(block.type())).count();
    }

    /**
     * 入口校验内容块，返回拒绝原因；通过时返回 {@code null}。
     *
     * 能在这里判出来的问题就不要留到编排线程里去炸：到了那边只会变成一句
     * {@code ORCHESTRATION_FAILED}（内部故障），而"base64 图没给 mime"是调用方传错了参数，
     * 两件事对调用方的处置完全不同，前者要重试/报障，后者要改请求。
     *
     * 校验只分两种形态（URL 块 / base64 块），不按图、音、视分叉：
     * 三者对"必填字段"的要求完全一致，分叉写就意味着将来加第四种媒体时要改三个地方。
     */
    private static String validateContents(List<MessageContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        for (MessageContent block : contents) {
            // 音频 URL 块：langchain4j 的 OpenAI 适配层映射 AudioContent 时强制要求
            // base64Data（ensureNotBlank(audio.base64Data)），URL 形态的音频到了
            // 请求构造阶段必抛异常，那会以 ORCHESTRATION_FAILED（内部故障）的面目
            // 出现，而这是调用方传错了形态，该在入口就给明确指引。
            if (block.type() == ContentType.AUDIO_URL) {
                return "音频 URL 块暂不支持：兼容适配层只接受 base64 音频，请改用 base64 上传";
            }
            try {
                if (block.type().isMedia() && block.url() != null && !block.url().isBlank()) {
                    block.requireUrl();
                } else if (block.type().isMedia() && block.carriesBytes()) {
                    if (block.type().isImage() && block.base64() == null) {
                        throw new IllegalArgumentException("base64 图片块缺少 base64 数据");
                    }
                    block.requireMime();
                } else if (block.type().isMedia()) {
                    // 既没给 URL 也没给字节：媒体块两手空
                    throw new IllegalArgumentException("媒体内容块（" + block.type() + "）必须提供 url 或 base64");
                }
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    /**
     * 本轮图片的三条去路。
     *
     * <p>判定必须在写记忆之前完成：它是"这一轮能不能跑"的前置条件，
     * 而不是跑到模型那一步才知道的结果。定在写记忆之后，被拒的那一轮会在记忆里
     * 留下一条永远没有回答的提问，下一轮模型会顺着它答非所问。</p>
     */
    private enum VisionRoute {
        /** 主模型本身就是视觉模型：图片直接进它的消息上下文 */
        INLINE,
        /** 主模型是纯文本模型：图片暂存成附件，由 seeImage 委派给同域的视觉子模型 */
        DELEGATE,
        /** 没有可承接的视觉模型：带图请求无处可去，如实拒绝 */
        UNAVAILABLE
    }

    /**
     * 判定本轮图片走哪条路。
     *
     * <p>判据只有一条：<b>域链上第一个可用的视觉模型是不是主模型自己</b>。
     * 是 → 图直接进消息；不是 → 交给那个子模型看；一个都没有 → 拒绝。</p>
     *
     * <p>"拒绝"之前先试解析一次主模型，是为了把两种失败分开：</p>
     *
     * <ul>
     *   <li>域压根没绑模型，那是"依赖未配置"，{@link #resolveModel} 会抛出并被既有收尾接住，
     *       调用方拿到的是"去管控台绑模型"的指引；</li>
     *   <li>绑了模型但整条链都不是视觉的，那才是 {@code MODEL_NO_VISION}（"这个域看不了图"）。</li>
     * </ul>
     *
     * <p>若不分这两步而统一报后者，"域没配模型"的用户会收到"没有视觉模型"的提示，
     * 按提示去绑一个视觉模型，绑完发现还是不能用，因为真正缺的是任何一个模型。</p>
     */
    private VisionRoute resolveVisionRoute(String domainId) {
        if (modelResolver == null) {
            // 未装配模型档案的部署（单测、最小装配）：无从判断视觉能力，维持"图直接进消息"的老行为。
            // 判成"不支持"会让这些部署下所有带图请求直接失败，而它们本就不经过档案链。
            return VisionRoute.INLINE;
        }
        Optional<VisionTarget> target = modelResolver.visionModel(domainId);
        if (target.isPresent()) {
            if (target.get().primary()) {
                return VisionRoute.INLINE;
            }
            if (mediaAttachmentStore == null) {
                log.warn("[Agent编排] 域 {} 的视觉模型 {} 不是主模型，但未装配图片暂存区，无法走委派",
                        domainId, target.get().alias());
                return VisionRoute.UNAVAILABLE;
            }
            return VisionRoute.DELEGATE;
        }
        // 没有视觉模型：先确认域是不是真的配了主模型（未配则抛出，走"依赖未配置"那条收尾）
        resolveModel(domainId);
        return VisionRoute.UNAVAILABLE;
    }

    /**
     * 一次媒体暂存的结果。
     *
     * @param ids          与内容块里的<b>媒体块</b>按出现顺序一一对应（图 / 音 / 视各占一个号），
     *                     哪一块没存进去，哪个位置就是 {@code null}；长度恒等于媒体块数，
     *                     归档侧要靠同一序号对齐（见 {@link TurnRecorder#mediaRefsOf}）
     * @param imageIds     其中图片的那几个（只含暂存成功的，顺序不变）。附件清单与 {@code seeImage}
     *                     只认这一份：音视频的引用名给模型，等于教它去调一个注定被拒的工具
     * @param imageFailure 第一处图片暂存失败的原因；没有图片失败时为 {@code null}。
     *                     音视频失败不进这里，两者的致命性不同，见 {@link #storeAttachments}
     */
    private record StoredAttachments(List<String> ids, List<String> imageIds, String imageFailure) {
    }

    /**
     * 把本轮全部媒体存进暂存区。
     *
     * <p>三种媒体存的目的不同，但"归档里留下一个可取的引用"这一层是共用的：图片是为了
     * {@code seeImage} 能取到像素，音视频是为了归档不至于只剩一句"曾经有过一段音频"
     * （见 {@link MediaRef}）。</p>
     *
     * <p><b>失败处置按媒体分两种</b>：</p>
     * <ul>
     *   <li><b>图片</b>存不进去由调用方决定致命与否，所以这里只把原因带出去、不抛出。
     *       委派路径下它<b>必须</b>致命：图已被剥出消息，主模型手上只剩引用名清单，
     *       少一个名字就是少一张图，而它无从知道原本有几张，用户问"这两张图有什么区别"，
     *       模型只谈得上一张，还理直气壮。主模型自带视觉时则不必：它本来就能直接看图。</li>
     *   <li><b>音视频</b>存不进去只告警：它们的字节仍在消息里（委派路径只剥图片），
     *       不影响"这一轮能不能跑"，代价仅是归档少一个可取引用。</li>
     * </ul>
     *
     * <p>"跳过失败的那一块"不是一个选项：序号必须与内容块严格对齐，失败了也占号。</p>
     */
    private StoredAttachments storeAttachments(List<MessageContent> contents) {
        // 与本类其他内容块方法同一口径：null 视为"没有内容块"，不是异常
        if (contents == null || contents.isEmpty()) {
            return new StoredAttachments(List.of(), List.of(), null);
        }
        List<String> ids = new ArrayList<>();
        List<String> imageIds = new ArrayList<>();
        String imageFailure = null;
        for (MessageContent block : contents) {
            if (block.type() == null || !block.type().isMedia()) {
                continue;
            }
            Kind kind = Kind.of(block.type());
            String id = null;
            try {
                id = switch (block.type()) {
                    case IMAGE_URL, VIDEO_URL ->
                            mediaAttachmentStore.put(kind, null, null, block.requireUrl());
                    case IMAGE_BASE64, AUDIO_BASE64, VIDEO_BASE64 ->
                            mediaAttachmentStore.put(kind, block.requireMime(), block.base64(), null);
                    // AUDIO_URL：入口校验已拒（兼容适配层只收 base64 音频），这里不重复报错
                    default -> null;
                };
            } catch (Exception e) {
                String reason = kind.label() + "暂存失败："
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (kind == Kind.IMAGE) {
                    if (imageFailure == null) {
                        imageFailure = reason;
                    }
                } else {
                    log.warn("[Agent编排] 本轮{}暂存失败（不影响本轮，归档将少一个可取引用）：{}",
                            kind.label(), reason);
                }
            }
            ids.add(id);
            if (kind == Kind.IMAGE && id != null) {
                imageIds.add(id);
            }
        }
        // 不用 List.copyOf：它拒绝 null，而"失败占号"正是靠 null 表达
        return new StoredAttachments(Collections.unmodifiableList(ids),
                Collections.unmodifiableList(imageIds), imageFailure);
    }

    /** 去掉图片块后的内容块列表（委派路径下，图不再进主模型的消息） */
    private static List<MessageContent> withoutImages(List<MessageContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<MessageContent> out = new ArrayList<>(contents.size());
        for (MessageContent block : contents) {
            if (!block.type().isImage()) {
                out.add(block);
            }
        }
        return out;
    }

    /**
     * 附件清单 —— 主模型是纯文本模型时，这是它唯一能知道"有图可看"的途径。
     *
     * <p>必须显式写出引用名并点明"你看不到图片像素"：不写名字，模型会凭上下文猜一个
     * （猜出来的名字取不到图，工具回一句"找不到图片"，用户看到的是一轮无意义的往返）；
     * 不点明"看不到"，模型会以为自己已经看到了图，直接对着附件名编内容。</p>
     *
     * <p>只列图片：音视频同样有引用名（归档定位用），但它们没有取回通道，
     * 写进"必须先调 seeImage"的清单里就是教模型去调一个注定被拒的工具。</p>
     */
    private static String attachmentNotice(List<String> imageIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮附带 ").append(imageIds.size())
                .append(" 张图片。你读不到图片像素，图片已暂存为附件，引用名：");
        for (int i = 0; i < imageIds.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(imageIds.get(i));
        }
        sb.append("。回答涉及图片内容的问题前，必须先调用 seeImage 工具")
                .append("（image 参数填上面的引用名，question 参数填你要从图里获取什么），不要凭猜测作答。】");
        return sb.toString();
    }

    /**
     * 视觉主模型的附件清单 —— 与 {@link #attachmentNotice} 的差别只在两个词："必须"与"可以"。
     *
     * <p>主模型自己就能看图（图已在其消息里），所以这条通知的首要任务是**别把它引到工具上**：
     * 先说清"图已直接可见、可以直接回答"，再给出引用名供"想交给同域视觉子模型细看"时使用。
     * 顺序不能反，2026-10-16 实测：只要工具在场，模型就会优先调它，哪怕图就在眼前
     * （同一张图不带工具能准确描述，带上一个同名 seeImage 定义立刻改调工具、并自编引用名）。</p>
     *
     * <p>引用名在这里是**能力**而不是**前提**：给出去是为了让"自主分配任务"这条路走得通，
     * 也让归档记住"这一轮带过图"以便回头按名取回；取不到（TTL 已过）时模型仍能直接看图作答，
     * 不像委派路径那样无路可走。</p>
     */
    private static String optionalDelegateNotice(List<String> imageIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮附带 ").append(imageIds.size())
                .append(" 张图片，已直接呈现在你的消息里，你可以直接查看并据此回答，无需调用工具。");
        sb.append("仅当需要把图交给同域的视觉子模型细看时（例如逐字识别、更细的版面判断），")
                .append("才调用 seeImage，image 参数填引用名：");
        for (int i = 0; i < imageIds.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(imageIds.get(i));
        }
        sb.append("，question 参数填你要从图里获取什么。】");
        return sb.toString();
    }

    private void runOrchestrate(String sessionId, String key, String message,
                                List<MessageContent> contents, StreamContext context) {
        // 同会话串行：本会话已有一轮在执行则直接拒绝（最细粒度的并发控制）。
        // 停止标志的清理不在这里，而在提交进线程池之前（见 orchestrate 的 prepareNewRound）。
        // 若在这里清：本方法是在任务出队后才执行的，而用户可以在"排队中"点停止，
        // 那次停止会被这一次清理抹掉，任务照跑到底，界面上"停止中"却什么都没发生。
        // 不报错、不留痕，只表现为"停止偶尔不灵"。
        if (!cancellationRegistry.tryMarkRunning(key)) {
            log.warn("[Agent编排] 会话[{}] 正在执行中，拒绝并发请求（如需重开请先 stop 并等其结束）", sessionId);
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_BUSY, TraceId.currentOrNew()));
            // 顺序是硬要求：先注销再 complete。complete() 会同步触发 sink.onDispose，
            // 而 onDispose 里要靠 isCurrent 判断"这是不是我这一轮"，注销在前，它就判 false，
            // 不会把"停止标志"打到别人身上。反过来（先 complete）的话，被拒绝的这一轮
            // 会亲手把正在正常执行的那一轮置为停止：它的回答被回滚、事件被丢弃，
            // 而被拒绝的请求只是拿到一个本该给别人的 SESSION_BUSY。
            streamSinks.unregister(key, context);
            context.complete();
            return;
        }

        // 本轮排障标识：贯穿日志与 ERROR 事件，便于用 traceId 串起一次完整调用
        TraceId.begin(context == null ? null : context.traceId());
        /* 本轮写入记忆之前的<b>条数</b>（停止/异常时回滚用）；声明在 try 之外，
           停止分支也要用。回滚 = 把记忆截回这个长度（M4-R5），不再需要内容快照。

           哨兵必须是 null 而不是 0：null = "还没量过"（本轮还没往记忆里写过东西），
           0 = "量到的是空的"（记忆本来就是空）。两者混用会让"异常早于测量"
           被误判成"清空记忆"，而那次清空会把上一轮的内容一起抹掉。 */
        Integer memorySizeBefore = null;
        /* 归档用的两个量必须声明在 try 之外：catch 里要靠它们做归档收尾，
           而 try 块里声明的局部变量对 catch 是不可见的（try 自身就是一个块作用域）。
           与 memorySizeBefore 同款写法，它们都是"本轮收尾要用"的东西。 */
        long turnId = -1;
        boolean graphStarted = false;
        try {
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
                releaseCheckpointQuietly(key, "检查点损坏自愈");
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
                rejectOpenTurnQuietly(key);
            } else {
                /* 崩溃恢复：上一条仍是未闭合开放轮（进程崩在闭轮之前、或断点已过期），
                   先标 aborted 再开新轮，归档里不允许出现两条开放轮。

                   必须先读 pending 才能走这条分支：停在审批点的那一轮是"合法地开着"
                   （断点还在、用户随时可能 resume），此刻误标中止，那一轮的最终回答
                   就再也落不进档；反过来 pending 为假还开着，就只能是崩溃留下的残骸。 */
                recoverStaleOpenTurnQuietly(key);
            }
            releaseCheckpointQuietly(key, pending ? "视为拒绝" : "入口清理");

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
            int imageTotal = imageCount(contents);
            /* ① 先定"图走哪条路"。只以"这一轮带图"为前提：
               音视频不参与视觉路由（没有 seeImage 那样的承接方），但它们同样要进暂存区，见 ②。 */
            VisionRoute route = imageTotal > 0 ? resolveVisionRoute(domainId) : null;
            if (route == VisionRoute.UNAVAILABLE) {
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
            StoredAttachments stored = storeAttachments(contents);
            attachmentIds = stored.ids();

            if (route == VisionRoute.INLINE) {
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
                    notice = optionalDelegateNotice(stored.imageIds());
                    log.info("[Agent编排] 会话[{}] 域 {} 主模型自带视觉，本轮 {} 张图既直接入消息"
                            + "也已暂存为附件：{}", sessionId, domainId, imageTotal, stored.imageIds());
                }
            }
            if (route == VisionRoute.DELEGATE) {
                /* 存不进去要让本轮失败：图已被剥出消息，主模型手上只剩引用名清单，
                   少一个名字就是少一张图，而它无从知道原本有几张。 */
                if (stored.imageFailure() != null) {
                    throw new IllegalStateException("本轮图片暂存失败，无法交由 seeImage 委派："
                            + stored.imageFailure());
                }
                effectiveContents = withoutImages(contents);
                notice = attachmentNotice(stored.imageIds());
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
            memorySizeBefore = memorySize(memory);
            List<ChatMessage> window = windowOf(memory);
            /* 本轮的提问消息只构造一次，记忆与请求体共用同一份。
               多模态下这不再是"省一次构造"：分两次构造就有两条 UserMessage，
               一条进记忆、一条进模型，两份内容块列表是否一致全凭调用方自觉：
               一旦不一致，模型看到的是另一张图，而排查时两边都"看起来正常"。 */
            UserMessage userMessage = buildUserMessage(message, effectiveContents, notice);
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
                    turnId = conversationArchive.openTurn(key,
                            UserInput.of(message, TurnRecorder.mediaRefsOf(contents, attachmentIds)));
                } catch (Exception e) {
                    log.error("[Agent编排] 会话[{}] 归档开轮失败，本轮不归档：{}", sessionId, e.getMessage());
                }
            }
            // 开轮即续期（M4-R4）：刚开的这一轮已在归档里，续期口径与闭轮完全一致。
            // 与暂存时写入的 TTL 一致，此处真正的价值是把"续期"这件事绑在"会话又在动"
            // 这个事实上，会话沉寂后不再有轮次经过，附件按原 TTL 自然过期
            renewActiveAttachments(key);

            /* ⑤ 请求装配：系统提示 + 早期摘要 + 相关轮卡片 + 近况窗口 + 本轮提问。
               窗口只取最近若干轮的原文（消息块原样带，图片因此不会丢），
               更老的轮次以摘要/卡片形态出现，装配与降级全部在 ContextAssembler 里。
               窗口在上面的"读尾部窗口"一步就已备好（不含本轮提问，提问单列）。 */
            String summary = buildSummarySegment(key, contextAssembler.windowTurns());
            List<HistoryCard> cards = buildCardsSegment(key, contextAssembler.windowTurns(),
                    message, turnId);
            List<ChatMessage> messages = assemblerFor(sessionId, domainId).assemble(
                    systemMessageOf(domainId), window, summary, cards, userMessage);

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(key)
                    .addMetadata(CTX_METADATA_KEY, context)
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
            graphStarted = true;

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
                rememberFinalAnswer(memory, lastState, key, turnId);
                // 耗时是排查"为什么慢"的第一线索：LLM 慢还是工具慢，靠它定位
                log.info("[Agent编排] 会话[{}] traceId={} 图编排完成,耗时={}ms",
                        sessionId, TraceId.current(), System.currentTimeMillis() - startedAt);
                context.emit(AgentEvent.done(sessionId));
            }
            context.complete();
        } catch (Exception e) {
            /* 归档收尾先于错误处理：删（图没起来）或中止（跑起来了）。
               失败只告警，归档写不进去不该影响记忆回滚与错误事件的收尾，
               那些是"这一轮对用户的交代"，归档只是存档。 */
            finalizeTurnOnFailure(key, turnId, graphStarted, e, context);
            if (isCancellationException(e)) {
                handleStop(sessionId, key, context, "orchestrate", memorySizeBefore);
            } else if (!handleNotConfigured(sessionId, key, context, e, memorySizeBefore)) {
                log.error("[Agent编排] 会话[{}] traceId={} 执行失败", sessionId, TraceId.current(), e);
                // 异常中断时图没走到 END，releaseThread 不会触发，checkpoint 会残留。
                // 残留状态里可能有"悬空的 AiMessage（带工具调用却没有结果）"，
                // 下一轮带着它继续跑会反复失败，必须在这里主动清掉，避免一次异常拖垮后续所有提问。
                releaseCheckpointQuietly(key, "执行异常");
                // 记忆必须与 checkpoint 一起回滚：本轮开头写进去的 UserMessage 已经没有对应的
                // 回答（回答是在 rememberFinalAnswer 里写的，那一步没走到）。不回滚就留下
                // 一条永久"有问无答"的记录，下一轮模型看到上次的提问没有回应，
                // 会顺着答非所问，而且这个偏差会一直累积，用户从界面上看不出出了什么事。
                // stop 路径早就守住了这个不变式，异常路径此前没守住。
                restoreMemory(key, memorySizeBefore);
                emitOrchestrationFailure(sessionId, context, e);
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
        return Flux.create(sink -> {
            StreamContext context = streamSinks.register(key, sink, domain);
            // 同 orchestrate：只有"自己仍是当前上下文"才算客户端断连，
            // 否则上一轮的收尾会把这轮的停止标志置上
            sink.onDispose(() -> {
                if (streamSinks.isCurrent(key, context)) {
                    cancellationRegistry.requestStop(key);
                }
                streamSinks.unregister(key, context);
            });

            try {
                executor.execute(() -> runResume(sessionId, key, approved, context));
            } catch (RejectedExecutionException e) {
                log.error("[Agent编排] 会话[{}] resume 提交失败,编排线程池已满", sessionId);
                streamSinks.unregister(key, context);
                sink.next(AgentEvent.error(sessionId, ErrorCode.SYSTEM_BUSY, context.traceId()));
                sink.complete();
            }
        });
    }

    private void runResume(String sessionId, String key, boolean approved, StreamContext context) {
        // 同会话串行同样适用于 resume：本会话已有一轮在执行时拒绝，理由与 chat 一致。
        // 停止标志已在 resume() 提交前清过，此处不再清（见 orchestrate 里关于
        // "排队期间的 stop 会被出队后的清理抹掉"的说明）
        if (!cancellationRegistry.tryMarkRunning(key)) {
            log.warn("[Agent编排-resume] 会话[{}] 正在执行中，拒绝并发 resume", sessionId);
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_BUSY, TraceId.currentOrNew()));
            // 同 orchestrate 的 busy 分支：先注销再 complete，否则会误停正在跑的那一轮
            streamSinks.unregister(key, context);
            context.complete();
            return;
        }
        TraceId.begin(context == null ? null : context.traceId());

        try {
            TokenUsageRecorder.begin();

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(key)
                    // 同 runOrchestrate：resume 恢复出的图执行同样必须绑定"发起它的那一轮"
                    .addMetadata(CTX_METADATA_KEY, context)
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

            if (!BRANCH_REVIEW.equals(snapshot.get().next())) {
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
            renewActiveAttachments(key);

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
                rememberFinalAnswer(chatMemoryProvider.get(key), lastState, key, -1);
                log.info("[Agent编排-resume] 会话[{}] resume 完成", sessionId);
                context.emit(AgentEvent.done(sessionId));
            }
            context.complete();
        } catch (Exception e) {
            /* resume 不开轮，所以这里只有"中止"一种处置：被审批中断的那一轮
               在开轮时就落了档，续跑失败同样要把现场保住。 */
            abortOpenTurnQuietly(key, "resume 执行失败：" + rootMessage(e),
                    context == null ? null : context.partialOutput());
            if (isCancellationException(e)) {
                // resume 不写记忆，没有需要回滚的提问
                handleStop(sessionId, key, context, "resume", null);
            } else if (!handleNotConfigured(sessionId, key, context, e)) {
                log.error("[Agent编排-resume] 会话[{}] traceId={} 执行失败",
                        sessionId, TraceId.current(), e);
                // 同 orchestrate：异常中断不会触发 releaseThread，需主动清残留 checkpoint
                releaseCheckpointQuietly(key, "resume 异常");
                emitOrchestrationFailure(sessionId, context, e);
                context.complete();
            }
        } finally {
            TokenUsageRecorder.finishAndLog();
            cancellationRegistry.clear(key);
            // 释放"执行中"标记：必须与 tryMarkRunning 成对，否则该会话会被永久锁死
            cancellationRegistry.unmarkRunning(key);
            // 只注销自己这一轮的上下文（同一会话可能已有新一轮注册进来）
            streamSinks.unregister(key, context);
            TraceId.end();
        }
    }

    /**
     * 把本轮最终回复写入会话记忆，并闭合归档里的这一轮。
     *
     * @param sessionKey 会话状态键（域|sessionId）
     * @param turnId     本轮归档轮号；{@code <= 0} = 本轮没有开过轮
     *                   （resume 续跑的是"被审批中断的那一轮"，它的轮在那时就开了），
     *                   此时回档里找那条开放轮闭合
     */
    private void rememberFinalAnswer(ChatMemory memory, MessagesState<ChatMessage> lastState,
                                     String sessionKey, long turnId) {
        if (memory == null || lastState == null) {
            log.warn("[Agent编排] 无可用状态,跳过记忆写入");
            return;
        }
        var finalMessages = lastState.messages();
        /* 本轮提问 = 状态里最后一条 UserMessage：它之后的 AiMessage /
           ToolExecutionResultMessage 都是本轮的产物。
           orchestrate 与 resume 两条路径都成立，resume 从检查点恢复出的状态同样含完整历史，
           所以闭轮不依赖"本轮有没有开过轮"这件事。 */
        int userIdx = -1;
        for (int i = finalMessages.size() - 1; i >= 0; i--) {
            if (finalMessages.get(i) instanceof UserMessage) {
                userIdx = i;
                break;
            }
        }
        for (int i = finalMessages.size() - 1; i >= 0; i--) {
            if (finalMessages.get(i) instanceof AiMessage ai && !ai.hasToolExecutionRequests()) {
                memory.add(ai);
                closeTurnQuietly(sessionKey, turnId, finalMessages, userIdx, i, ai);
                return;
            }
        }
        log.warn("[Agent编排] 未找到可写入记忆的最终回复");
    }

    /**
     * 闭合归档里的这一轮：抽取全流程 → 生成摘要 → 原位写回。
     *
     * <p>失败只告警。归档是档案不是请求链的一环：写不进去不该让这一轮
     * （已经拿到回答、已经写进记忆）对调用方表现为失败。</p>
     */
    private void closeTurnQuietly(String sessionKey, long turnId, List<ChatMessage> messages,
                                  int userIdx, int finalIdx, AiMessage finalAnswer) {
        if (conversationArchive == null) {
            return;
        }
        String sessionId = sessionIdOfKey(sessionKey);
        try {
            long id = turnId;
            if (id <= 0) {
                var open = conversationArchive.findOpenTurn(sessionKey);
                if (open.isEmpty()) {
                    log.warn("[Agent编排] 会话[{}] 归档里没有待闭合的开放轮，跳过闭轮", sessionId);
                    return;
                }
                id = open.get().turnId();
            }
            if (userIdx < 0) {
                log.warn("[Agent编排] 会话[{}] 图状态里找不到本轮提问，跳过闭轮", sessionId);
                return;
            }
            // 先取回开放轮再补全：用户输入与开始时间都以开轮时落的那条为准
            // （那份 text 不含平台追加的附件清单提示，进索引与摘要更干净），
            // 顺便确认这一轮确实存在，不校验就 LSET 会在数据被外部改动时写坏别人的轮
            TurnRecord existing = conversationArchive.turnAt(sessionKey, id).orElse(null);
            if (existing == null) {
                log.warn("[Agent编排] 会话[{}] 归档里没有 turnId={} 这一轮，跳过闭轮", sessionId, id);
                return;
            }
            TurnRecorder.FlowSlice slice = TurnRecorder.extractFlow(messages, userIdx + 1, finalIdx);
            String answer = finalAnswer.text();
            TurnRecord closed = new TurnRecord(
                    id, sessionKey, existing.startedAt(), Instant.now(),
                    existing.user(), slice.events(), answer, usageOf(),
                    new TurnFlags(slice.truncated(), false, false, null),
                    TurnRecorder.digestOf(existing.user() == null ? "" : existing.user().text(), answer));
            conversationArchive.closeTurn(sessionKey, id, closed);
            indexClosedTurnQuietly(sessionKey, closed);
            // 闭轮即续期（M4-R4）：这一轮从头到尾可能跑了几分钟甚至更久，
            // 续到完整 TTL，让"回答里提到的图"在会后依然取得到
            renewActiveAttachments(sessionKey);
            // 闭轮后按需摘要（M4-R1）：只提交任务，判断与调用都在后台做，
            // 闭轮路径不因此多等一次归档读
            scheduleRollingSummaryQuietly(sessionKey);
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档闭轮失败：{}", sessionId, e.getMessage());
        }
    }

    /**
     * 闭轮后触发一次滚动摘要（M4-R1）。
     *
     * <p>只做提交，不做判断：是否达标、要不要调模型都在后台线程里决定
     * （见 {@link RollingSummaryService#schedule}）。提交失败、未装配、未开启都只当没发生：
     * 摘要是可选增强，装配侧读不到就走 digest 拼接。</p>
     */
    private void scheduleRollingSummaryQuietly(String sessionKey) {
        if (rollingSummaryService == null) {
            return;
        }
        try {
            rollingSummaryService.schedule(sessionKey, contextAssembler.windowTurns());
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 提交滚动摘要任务失败：{}",
                    sessionIdOfKey(sessionKey), e.getMessage());
        }
    }

    /**
     * 附件续期（M4-R4 活动续期）：把"会话当前仍在引用"的附件存活期重置为完整 TTL。
     *
     * <p><b>续期集合</b> = 近况窗口内各轮 {@code UserInput.media} 里带引用名的附件。
     * 窗口就是装配层每轮真正喂给模型的那批轮次，能被自动装配/被模型直接指到的附件，
     * 就保活；滑出窗口的老附件不再续期，会话沉寂后按 TTL 自然过期。
     * 于是"归档永久"与"附件 30 分钟"这两个时间尺度之间有了过渡：
     * 附件活得比它所属的那一轮久，且活得与会话的活跃窗口一样久。</p>
     *
     * <p><b>不做全会话扫描</b>：把整个会话的附件都续期需要一个"会话 → 附件名"的登记表，
     * 那是一份新的 Redis 键空间，得跟着 R5 的删域级联一起扩，收益（把滑出窗口三个月前的
     * 图也一直保活）与代价（存储不回收、级联多一处）不成比例。这里只续"还在活跃引用"的那批。</p>
     *
     * <p>尽力而为：读窗口失败、续期失败都只告警。附件是派生资源，
     * 最坏结果是它按原 TTL 过期，那等同于"会话没活跃过"，不该让一轮对话失败。</p>
     */
    private void renewActiveAttachments(String sessionKey) {
        if (mediaAttachmentStore == null || conversationArchive == null
                || sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        try {
            List<TurnRecord> recent =
                    conversationArchive.recentTurns(sessionKey, contextAssembler.windowTurns());
            for (TurnRecord t : recent) {
                collectAttachmentIds(t, ids);
            }
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 读近况窗口续期附件失败：{}",
                    sessionIdOfKey(sessionKey), e.getMessage());
            return;
        }
        if (!ids.isEmpty()) {
            mediaAttachmentStore.touch(ids);
        }
    }

    /** 把一轮用户输入里带引用名的附件名收集进 sink */
    private static void collectAttachmentIds(TurnRecord record, Set<String> sink) {
        if (record == null || record.user() == null || record.user().media() == null) {
            return;
        }
        for (MediaRef media : record.user().media()) {
            if (media.hasAttachment()) {
                sink.add(media.attachmentId());
            }
        }
    }

    /**
     * 闭合轮写入倒排索引（M3）。失败只告警：索引是派生数据，
     * 归档已经落盘，这一轮只是不能被倒排列中，靠子串扫描兜底。
     */
    private void indexClosedTurnQuietly(String sessionKey, TurnRecord closed) {
        if (historySearch == null) {
            return;
        }
        try {
            historySearch.indexTurn(sessionKey, closed);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] turnId={} 写检索索引失败，该轮靠子串扫描兜底：{}",
                    sessionIdOfKey(sessionKey), closed.turnId(), e.getMessage());
        }
    }

    /**
     * 系统提示词按本轮所处的域解析：公共基线 + 域差异。
     *
     * <p>执行单元内冻结、单元之间用最新，resume 不重跑本方法（它从检查点恢复），
     * 因此这里读到的就是本次执行单元开始时的提示词，天然满足该语义。</p>
     */
    private SystemMessage systemMessageOf(String domainId) {
        String text = promptResolver.resolve(domainId);
        return text == null || text.isBlank() ? null : SystemMessage.from(text);
    }

    /**
     * 近况窗口：取最近若干轮的<b>原文</b>。
     *
     * <p>取记忆而不是取归档：归档里媒体只剩引用（不存字节），而窗口里的图片
     * 必须是真内容块，否则模型看不到图。这也正是记忆继续存在的原因：
     * 它是"带内容块近况窗口"的来源，归档是"永久档案"。</p>
     *
     * <p>M4-R5 起只读尾部：窗口要的就是最后几条，让存储侧 {@code LRANGE key -N -1}
     * 一步到位，不必先把整段历史拉回来再 {@code subList}。存储不支持尾部读时
     * （单测的 Map 实现）{@link DualConstraintChatMemory#tail(int)} 内部退回全量取尾，
     * 行为不变。</p>
     */
    private List<ChatMessage> windowOf(ChatMemory memory) {
        int limit = MESSAGES_PER_WINDOW_TURN * contextAssembler.windowTurns();
        if (limit <= 0) {
            return List.of();
        }
        if (memory instanceof DualConstraintChatMemory dual) {
            return dual.tail(limit);
        }
        // 非双约束记忆（异常装配）：退回全量读后取尾，行为与改造前一致
        List<ChatMessage> all = memory.messages();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int keep = Math.min(all.size(), limit);
        return List.copyOf(all.subList(all.size() - keep, all.size()));
    }

    /**
     * 当前记忆条数（回滚要记的"写入前长度"）。
     *
     * <p>双约束记忆走存储侧的 O(1) 长度；其他实现只能读全量数一遍，
     * 那是异常装配的兜底路径，行为与改造前一致。</p>
     */
    private static int memorySize(ChatMemory memory) {
        if (memory instanceof DualConstraintChatMemory dual) {
            return dual.size();
        }
        List<ChatMessage> all = memory.messages();
        return all == null ? 0 : all.size();
    }

    /** 一轮在记忆里占几条消息（提问 + 回答） */
    private static final int MESSAGES_PER_WINDOW_TURN = 2;

    /**
     * 早期摘要段：滑出窗口的老轮次。
     *
     * <p>M4-R1 起两段合一：<b>滚动摘要（模型生成，覆盖到某轮号）+ 该轮号之后又滑出窗口的
     * 那几轮 digest</b>。滚动摘要是异步生成、过了阈值才更新的，天生滞后，光有它，
     * "摘要之后新滑出的那几轮"会既不在窗口里也不在摘要里，凭空消失。所以缺口用现成
     * digest 补齐。没有摘要时退化成纯 digest 拼接（M2 行为），两者都没有则返回 {@code null}。</p>
     *
     * <p>读档失败只告警并返回 {@code null}：摘要不是必需段，卡片和窗口都不依赖它。</p>
     */
    private String buildSummarySegment(String sessionKey, int windowTurns) {
        if (conversationArchive == null) {
            return null;
        }
        try {
            List<TurnRecord> tail = conversationArchive.recentTurns(sessionKey,
                    windowTurns + ContextAssembler.SUMMARY_MAX_TURNS);
            if (rollingSummaryService == null) {
                return ContextAssembler.summaryOf(tail, windowTurns);
            }
            var rolling = rollingSummaryService.load(sessionKey).orElse(null);
            String text = rolling == null ? null : rolling.text();
            long upTo = rolling == null ? 0L : rolling.upToTurnId();
            return ContextAssembler.summaryWithRolling(text, tail, windowTurns, upTo);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 读取归档用于摘要失败，本轮不带摘要段：{}",
                    sessionIdOfKey(sessionKey), e.getMessage());
            return null;
        }
    }

    /**
     * 相关轮卡片段：按本轮提问检索倒排索引（M3）。
     *
     * <p>检索不到（索引没建 / 老数据 / 查询太短）时退化成"窗口之前最近的老轮次"，
     * 与 M2 的行为一致，卡片段可以空，但不能因为检索不达就整段消失。</p>
     *
     * <p>与近况窗口去重：命中轮若在窗口内（turnId &gt; 当前轮 - W）不进卡片段，
     * 同一轮内容不在一轮请求里出现两次（方案 §5.5）。</p>
     */
    private List<HistoryCard> buildCardsSegment(String sessionKey, int windowTurns,
                                                String query, long currentTurnId) {
        int k = contextAssembler.maxCards();
        if (k <= 0) {
            return List.of();
        }
        long windowFloor = currentTurnId > 0 ? currentTurnId - windowTurns : 0;
        // 当前轮号未知（<=0）时不能走检索路径：判重条件是"轮号 <= windowFloor"，
        // 没有当前轮就无从算出窗口下界，写成 MAX_VALUE 会让条件恒真，卡片段与近况窗口
        // 重复输出同一轮内容。此时退化到下面的按位置切窗路径，它不依赖轮号。
        if (currentTurnId > 0 && historySearch != null && query != null && !query.isBlank()) {
            try {
                List<HistoryCard> cards = historySearch.search(sessionKey, query, k + windowTurns);
                List<HistoryCard> deduped = new ArrayList<>();
                for (HistoryCard card : cards) {
                    if (card.turnId() <= windowFloor && deduped.size() < k) {
                        deduped.add(card);
                    }
                }
                if (!deduped.isEmpty()) {
                    return deduped;
                }
            } catch (Exception e) {
                log.warn("[Agent编排] 会话[{}] 倒排检索失败，卡片段退化为最近老轮次：{}",
                        sessionIdOfKey(sessionKey), e.getMessage());
            }
        }
        if (conversationArchive == null) {
            return List.of();
        }
        try {
            List<TurnRecord> tail = conversationArchive.recentTurns(sessionKey,
                    windowTurns + k + 1);
            List<TurnRecord> older = new ArrayList<>(tail);
            int skip = Math.max(0, older.size() - windowTurns);
            List<TurnRecord> candidates = older.subList(0, skip);
            // 相关度从高到低：没有检索排序时，"越老越优先"会让最旧的一直占着卡片位，
            // 改成倒序取最近的老轮次，与"模型更可能追问刚才那件事"的直觉一致
            List<TurnRecord> ordered = new ArrayList<>();
            for (int i = candidates.size() - 1; i >= 0 && ordered.size() < k; i--) {
                ordered.add(candidates.get(i));
            }
            return ContextAssembler.cardsOf(ordered);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 读取归档用于相关轮卡片失败，本轮不带卡片段：{}",
                    sessionIdOfKey(sessionKey), e.getMessage());
            return List.of();
        }
    }

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

    /**
     * 审批被拒：闭合上一轮的开放轮（用户没回审批、直接开了新对话）。
     */
    private void rejectOpenTurnQuietly(String sessionKey) {
        if (conversationArchive == null) {
            return;
        }
        try {
            var open = conversationArchive.findOpenTurn(sessionKey);
            if (open.isEmpty()) {
                return;
            }
            conversationArchive.rejectTurn(sessionKey, open.get().turnId());
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档标记审批被拒失败：{}", sessionIdOfKey(sessionKey), e.getMessage());
        }
    }

    /**
     * 崩溃恢复：上一轮若仍是未闭合开放轮（进程崩在闭轮前 / 断��已过期），标 aborted 后闭合。
     */
    private void recoverStaleOpenTurnQuietly(String sessionKey) {
        if (conversationArchive == null) {
            return;
        }
        try {
            int recovered = conversationArchive.recoverStaleOpenTurn(sessionKey);
            if (recovered > 0) {
                log.warn("[Agent编排] 会话[{}] 发现上一轮未闭合（进程中断），已标记中止",
                        sessionIdOfKey(sessionKey));
            }
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档崩溃恢复失败：{}", sessionIdOfKey(sessionKey), e.getMessage());
        }
    }

    /** 停止 / 异常 / 崩溃：把开放轮标记 aborted 后闭合，现场（问了什么、半截文本）留在档里 */
    private void abortOpenTurnQuietly(String sessionKey, String reason, String partialAnswer) {
        if (conversationArchive == null) {
            return;
        }
        try {
            var open = conversationArchive.findOpenTurn(sessionKey);
            if (open.isEmpty()) {
                return;
            }
            conversationArchive.abortTurn(sessionKey, open.get().turnId(), reason, partialAnswer);
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档中止开放轮失败：{}", sessionIdOfKey(sessionKey), e.getMessage());
        }
    }

    /**
     * 失败路径的归档收尾：删（图压根没起来）还是中止（跑了一半）。
     *
     * <p>这一条必须落在错误处理<b>之前</b>：错误处理会回滚记忆，
     * 而归档要保留的正是被回滚掉的那一轮，先判断、先落档，再交回给既有收尾逻辑。</p>
     */
    private void finalizeTurnOnFailure(String sessionKey, long turnId, boolean graphStarted,
                                       Throwable e, StreamContext context) {
        if (conversationArchive == null) {
            return;
        }
        String partial = context == null ? null : context.partialOutput();
        if (!graphStarted && turnId > 0) {
            try {
                conversationArchive.deleteTurn(sessionKey, turnId);
                return;
            } catch (Exception ex) {
                log.error("[Agent编排] 会话[{}] 归档删除未启动轮失败：{}", sessionIdOfKey(sessionKey),
                        ex.getMessage());
            }
        }
        abortOpenTurnQuietly(sessionKey, "本轮执行失败：" + rootMessage(e), partial);
    }

    /** 异常链最内层的原因：包装异常（如 langchain4j 的客户端异常）真正有用的信息在 cause 上 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    /**
     * 会话是否停在审批点（存在待授权的中断点）。
     */
    private boolean pendingApproval(String key) {
        RunnableConfig config = RunnableConfig.builder().threadId(key).build();
        try {
            return compiledGraph.stateOf(config)
                    .map(snapshot -> BRANCH_REVIEW.equals(snapshot.next()))
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
            return snapshot != null && BRANCH_REVIEW.equals(snapshot.next());
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

    /**
     * 判断异常链中是否包含 CancellationException
     */
    private boolean isCancellationException(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof CancellationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 通用异常兜底的"出口"—— 决定这一轮该以哪个错误码回报。
     *
     * <p>此前这里一律发 {@link ErrorCode#ORCHESTRATION_FAILED}（可重试）。问题是上游模型
     * 明确拒绝的几类（欠费 / 凭证无效 / 请求被拒）重试也不会好，发可重试码等于让前端
     * 一直重发同一个必然失败的请求，而用户永远看不到真因（如 DeepSeek 的 Insufficient Balance）。</p>
     *
     * <p>定性交给 {@link UpstreamLlmFailure}：认出来就发对应的码，并把上游原文一并带出；
     * 认不出来（不是上游模型异常）维持原兜底，不乱归类。</p>
     */
    private void emitOrchestrationFailure(String sessionId, StreamContext context, Throwable e) {
        UpstreamLlmFailure.Diagnosis diagnosis = UpstreamLlmFailure.diagnose(e);
        if (diagnosis == null) {
            context.emit(AgentEvent.error(sessionId, ErrorCode.ORCHESTRATION_FAILED, TraceId.currentOrNew()));
            return;
        }
        log.warn("[Agent编排] 会话[{}] traceId={} 上游模型失败，按 {} 回报：{}",
                sessionId, TraceId.current(), diagnosis.code().name(), diagnosis.reason());
        String reason = diagnosis.reason();
        /* 不可重试的几类（欠费 / 鉴权 / 被拒）才把上游原文带进事件体：用户与运维都靠它定位；
           可重试的几类（限流 / 超时 / 上游 5xx）上游原文多是英文网络措辞，不如码的对外文案好懂；
           上游连原因都没给（reason 为 null）时同样退回码的对外文案。 */
        String message = (diagnosis.code().isRetryable() || reason == null)
                ? null
                : diagnosis.code().getUserMessage() + "（上游：" + reason + "）";
        context.emit(AgentEvent.error(sessionId, message, diagnosis.code(), TraceId.currentOrNew()));
    }

    /**
     * "依赖未配置"的专用收尾 —— 把它从通用执行失败里择出来。
     *
     * @return true 表示已按"未配置"处理并结束流，调用方不要再走通用兜底
     */
    private boolean handleNotConfigured(String sessionId, String key, StreamContext context, Throwable e) {
        return handleNotConfigured(sessionId, key, context, e, null);
    }

    /**
     * "依赖未配置"的专用收尾 —— 把它从通用执行失败里择出来。
     *
     * @param memorySizeBefore 提问前的记忆条数；不为 {@code null} 时一并回滚，
     *                         理由同通用异常路径（提问已写入而回答未写入）
     * @return true 表示已按"未配置"处理并结束流，调用方不要再走通用兜底
     */
    private boolean handleNotConfigured(String sessionId, String key, StreamContext context, Throwable e,
                                        Integer memorySizeBefore) {
        NotConfiguredException notConfigured = findNotConfigured(e);
        if (notConfigured == null) {
            return false;
        }
        log.warn("[Agent编排] 会话[{}] traceId={} 依赖未配置，按 {} 回报：{}",
                sessionId, TraceId.current(),
                notConfigured.getCodeName(), notConfigured.getMessage());
        releaseCheckpointQuietly(key, "依赖未配置");
        // 同样要回滚记忆：这一轮是以 ERROR 收尾的，没有回答，
        // 留着孤零零一条提问就是永久"有问无答"
        restoreMemory(key, memorySizeBefore);
        // 走 3 参工厂，事件体取码的对外文案。曾经用 4 参把 notConfigured.getMessage()
        // 原样带出去，而各抛出点的 message 写的是「请先在管控台××页填写」：
        // 那是给管理员的路标，终端用户够不着，看到只剩茫然。具体缺什么、
        // 去哪一页配，上面的 warn 日志与码的 action 字段都有，信息不丢。
        context.emit(AgentEvent.error(sessionId,
                notConfigured.getErrorCode(), TraceId.currentOrNew()));
        context.complete();
        return true;
    }

    /**
     * 在异常链上查找 {@link NotConfiguredException}。
     */
    private static NotConfiguredException findNotConfigured(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur instanceof NotConfiguredException nce) {
                return nce;
            }
            Throwable cause = cur.getCause();
            if (cause == cur) {
                break;
            }
            cur = cause;
        }
        return null;
    }

    /**
     * 处理用户主动停止:回滚记忆、清 checkpoint、推送停止事件
     *
     * @param memorySizeBefore 本轮提问前的记忆条数；{@code null} = 本次不涉及记忆回滚
     */
    private void handleStop(String sessionId, String key, StreamContext context, String source,
                            Integer memorySizeBefore) {
        log.info("[Agent编排] 会话[{}] 任务被用户停止,source={}, 半截文本长度={}",
                sessionId, source, context == null ? 0 : context.partialOutput().length());

        // 1. 回滚记忆(仅 orchestrate 场景,resume 未写记忆)
        if ("orchestrate".equals(source)) {
            restoreMemory(key, memorySizeBefore);
        }

        // 1.5 归档：记忆回滚掉的是"这一轮问过了但没答"，而现场（问了什么、答到哪儿）
        //      本身就是档案，标记 aborted 后闭合，不删
        abortOpenTurnQuietly(key, "用户主动停止", context == null ? null : context.partialOutput());

        // 2. 清 checkpoint(不可恢复)
        releaseCheckpointQuietly(key, "用户停止");

        // 3. 推送停止事件给调用方
        if (context != null) {
            context.emit(AgentEvent.stopped(sessionId));
            context.complete();
        }
    }

    /**
     * 把会话记忆回滚到"本轮提问之前"的那一刻。
     *
     * <p>M4-R5：不再持有内容快照，而是按"本轮写入前的条数"截掉本轮追加的那几条，
     * 原先要"读全量快照 + 失败时整体回写"，读写都是 O(全量)；现在一读长度、一剪尾部，
     * 与历史长度无关。语义上等价：记忆只增不减、且本轮只会往尾部追加，
     * 所以"截回原长度"与"还原成原内容"是同一件事。</p>
     *
     * @param memorySizeBefore 本轮写入前的条数；{@code null} 表示写入发生在测量之前（不可能），
     *                         或本次调用方本就不涉及回滚
     */
    private void restoreMemory(String key, Integer memorySizeBefore) {
        String sessionId = SessionKeys.sessionIdOf(key);
        try {
            ChatMemory memory = chatMemoryProvider.get(key);
            if (!(memory instanceof DualConstraintChatMemory dual)) {
                log.warn("[Agent编排] 会话[{}] memory 非 DualConstraintChatMemory,无法回滚记忆", sessionId);
                return;
            }
            if (memorySizeBefore == null) {
                // 长度是在写提问之前量的，所以"没量过"= 本轮还没往记忆里写过任何东西 = 无需回滚。
                // 这里绝不能"删最后一条"或"清空"：那会把上一轮的对话内容一起抹掉。
                log.info("[Agent编排] 会话[{}] 无提问前长度（本轮未写入记忆），跳过记忆回滚", sessionId);
                return;
            }
            dual.truncateTo(memorySizeBefore);
            log.info("[Agent编排] 会话[{}] 记忆已回滚到本轮提问前（{} 条）", sessionId, memorySizeBefore);
        } catch (Exception ex) {
            log.error("[Agent编排] 会话[{}] 回滚记忆失败", sessionId, ex);
        }
    }

    /** 静默清理 checkpoint:失败只记日志,不阻断主流程 */
    private void releaseCheckpointQuietly(String key, String reason) {
        String sessionId = SessionKeys.sessionIdOf(key);
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(key).build();
            checkpointSaver.release(config);
            log.debug("[Agent编排] 会话[{}] checkpoint 已清除({})", sessionId, reason);
        } catch (Exception ex) {
            log.warn("[Agent编排] 会话[{}] 清 checkpoint 失败({}): {}", sessionId, reason, ex.getMessage());
        }
    }

    /** 当前注册的流上下文数量(观测用) */
    public int activeStreamCount() {
        return streamSinks.size();
    }
}
