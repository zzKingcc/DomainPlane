package com.zzkingcc.domainplane.runtime.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.model.ToolCall;
import com.zzkingcc.domainplane.api.model.ToolCallPayload;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.domain.memory.DualConstraintChatMemory;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.domainplane.runtime.stream.StreamContext;
import com.zzkingcc.domainplane.runtime.stream.StreamSinkRegistry;
import com.zzkingcc.domainplane.runtime.tool.SensitiveMasker;
import com.zzkingcc.domainplane.runtime.tool.ToolCallEchoResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.runtime.usage.TokenUsageRecorder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
     * 语义是"用户拒绝了那次审批"。补一条回答是为了让挂起的那一轮成为完整轮次——
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
                    // langgraph4j 1.8.17 的 AsyncEdgeAction 只吃一个参数，得用 AsyncCommandAction ——
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
     * 两者在两个请求重叠时会不一致 —— 此时 A 的工具调用会按 B 的域过滤、
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
                 *   • 不并入答案 —— 思考不是答案的一部分，混进 {@code TOKEN} 流之后
                 *       下游就再也分不开了（只能靠猜文本规律）；
                 *   • 不进 {@code appendPartial} —— 那段是"本轮已产出的答案"，停止回滚
                 *       与最终回答都用它；混进思考会让用户点了停止后拿到的半截回答里带着推理过程；
                 *   • 不解析内容 —— 展示成折叠块、灰色小字、还是干脆不显示，是消费方的决定，
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
                    // 用户点了停止这一轮照旧跑完 —— 现象就是"停止偶尔不灵"。
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
                    // 里检查，取消就完全不生效——用户点了停止，这一轮照旧跑完并写入记忆。
                    if (cancellationRegistry.isCancelled(key)) {
                        future.completeExceptionally(new CancellationException("用户主动停止"));
                        return;
                    }
                    future.complete(completeResponse.aiMessage());
                }

                @Override
                public void onError(Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            AiMessage aiMessage = future.join();
            return Map.of("messages", aiMessage);
        } finally {
            // 失败路径也要统计：模型已经开始产 token 就产生了用量，只记成功路径会漏
            TokenUsageRecorder.addLlmOutputTokens(llmOutputTokens.get());
        }
    }

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
        // 调用方只收到最终答复。审批挂起（INTERRUPT，含待授权工具清单）不归它管 ——
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
            // 同样被拦下——否则动态下线对新会话不生效,模型会一直"看得见"已消失的工具。
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

            // 通过 ToolRouter 统一路由执行(内含 Token 用量统计与异常兜底)
            // 远程工具要把 traceId 带给工具实例,而 execute(request, memoryId) 的签名里
            // 没有它,故在此绑定线程上下文、执行完立即解除。工具执行是同步阻塞调用,
            // 绑定与解除都在本线程同一次调用内,不会泄漏到同线程的下一轮。
            ToolInvocationContext.bind(context == null ? null : context.traceId());
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
        return orchestrate(request.getSessionId(), request.getMessage(), domain);
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
     * @param message   用户问题
     * @param domain    本轮所处的域；决定本轮可见工具集
     * @return 事件流,挂起时下发 {@code INTERRUPT} 事件,正常结束下发 {@code DONE}
     */
    public Flux<AgentEvent> orchestrate(String sessionId, String message, String domain) {
        String key = stateKeyOf(domain, sessionId);
        // 清上一轮遗留的停止标志 —— 必须在这里，**提交进线程池之前**。
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
                executor.execute(() -> runOrchestrate(sessionId, key, message, context));
            } catch (RejectedExecutionException e) {
                log.error("[Agent编排] 会话[{}] 提交失败,编排线程池已满", sessionId);
                streamSinks.unregister(key, context);
                sink.next(AgentEvent.error(sessionId, ErrorCode.SYSTEM_BUSY, context.traceId()));
                sink.complete();
            }
        });
    }

    private void runOrchestrate(String sessionId, String key, String message, StreamContext context) {
        // 同会话串行：本会话已有一轮在执行则直接拒绝（最细粒度的并发控制）。
        // 停止标志的清理不在这里，而在提交进线程池之前（见 orchestrate 的 prepareNewRound）。
        // 若在这里清：本方法是在任务出队后才执行的，而用户可以在"排队中"点停止——
        // 那次停止会被这一次清理抹掉，任务照跑到底，界面上"停止中"却什么都没发生。
        // 不报错、不留痕，只表现为"停止偶尔不灵"。
        if (!cancellationRegistry.tryMarkRunning(key)) {
            log.warn("[Agent编排] 会话[{}] 正在执行中，拒绝并发请求（如需重开请先 stop 并等其结束）", sessionId);
            context.emit(AgentEvent.error(sessionId, ErrorCode.SESSION_BUSY, TraceId.currentOrNew()));
            // 顺序是硬要求：先注销再 complete。complete() 会同步触发 sink.onDispose，
            // 而 onDispose 里要靠 isCurrent 判断"这是不是我这一轮"——注销在前，它就判 false，
            // 不会把"停止标志"打到别人身上。反过来（先 complete）的话，被拒绝的这一轮
            // 会亲手把正在正常执行的那一轮置为停止：它的回答被回滚、事件被丢弃，
            // 而被拒绝的请求只是拿到一个本该给别人的 SESSION_BUSY。
            streamSinks.unregister(key, context);
            context.complete();
            return;
        }

        // 本轮排障标识：贯穿日志与 ERROR 事件，便于用 traceId 串起一次完整调用
        TraceId.begin(context == null ? null : context.traceId());
        // 提问前的记忆快照（停止时回滚用）；声明在 try 之外，停止分支也要用。
        // 哨兵必须是 null 而不是空列表：null = "还没拍过快照"（本轮还没往记忆里写过东西），
        // 空列表 = "快照就是空的"（记忆本来就空）。两者混用会让"异常早于快照"被误判成"清空记忆"。
        List<ChatMessage> memoryBefore = null;
        try {
            ChatMemory memory = chatMemoryProvider.get(key);

            // ① 先读断点：这一轮是否要额外补一条占位回答取决于有没有待审批断点，
            //    而封顶判定必须按真实写入量算（3 条 vs 2 条），所以顺序不能反。
            //    读不出来 ≠ 没有待审批：此刻绝不能走到入口清理——那恰好会把待审批的断点删掉，
            //    正是本轮要防的那件事。直接报"检查点读写失败"并返回，由调用方退避后重试。
            boolean pending;
            try {
                pending = pendingApproval(key);
            } catch (IllegalStateException e) {
                log.error("[Agent编排] 会话[{}] 读取检查点失败，无法判断是否存在待审批中断点", sessionId, e);
                context.emit(AgentEvent.error(sessionId, ErrorCode.CHECKPOINT_ERROR,
                        TraceId.currentOrNew()));
                context.complete();
                return;
            }

            // ② 封顶判定：记忆只增不淘汰，到上限后这一轮直接拒绝，且刻意做成**零副作用** ——
            //    不写记忆、不清断点、不调模型（用户不会白烧一次调用），换 sessionId 才有出路。
            //    判定是粘性的：只要记忆还满着，之后每次都用同一个码拒绝，不需要"已封顶"标记位。
            //
            //    预留条数必须告诉它 pending：那一轮会多补一条占位回答（见 ③），
            //    按 2 条预留会在 maxMessages-2 时放行、写完 maxMessages+1 —— 记忆只增不淘汰，
            //    破限之后**永久**拒绝，这个会话再也开不了新轮。
            if (memory instanceof DualConstraintChatMemory dual) {
                DualConstraintChatMemory.Capacity capacity = dual.capacityFor(message, pending);
                if (!capacity.canAccept()) {
                    log.warn("[Agent编排] 会话[{}] 已达记忆上限，拒绝新一轮：{}", sessionId, capacity.detail());
                    context.emit(AgentEvent.error(sessionId, capacity.detail(),
                            ErrorCode.SESSION_MEMORY_FULL, TraceId.currentOrNew()));
                    context.complete();
                    return;
                }
            }

            // ③ 上一轮停在审批点、用户没回复审批而是直接开了新对话 → **视为用户拒绝了那次审批**。
            //    既不能把新对话挡回去（那会让会话在断点 TTL 到期前整个不可用），
            //    也不能把断点留着（否则事后任何一次 resume 都能把那个待授权动作执行掉）。
            //    所以：补一条占位回答让那一轮成为完整轮次（不留"有问无答"），再取消断点。
            if (pending) {
                log.info("[Agent编排] 会话[{}] 存在待审批断点且用户发起了新一轮对话 → 视为拒绝：取消该动作",
                        sessionId);
                memory.add(AiMessage.from(APPROVAL_REJECTED_PLACEHOLDER));
            }
            releaseCheckpointQuietly(key, pending ? "视为拒绝" : "入口清理");

            TokenUsageRecorder.begin();

            /* 读一次历史，既拍快照又组装请求体 —— 原先这里是两次 messages()：
               一次为快照（List.copyOf）、一次为拼请求体（addAll），两次都是
               "Redis 拉全量 → 逐条反序列化"，在长会话上是纯粹的白跑一趟。

               顺序上仍严格是"先拍快照、后写提问"：快照必须落在写入之前，
               否则"没有快照"就不再等于"本轮没写过记忆"，停止时回滚会多回滚一条。 */
            List<ChatMessage> historyBefore = memory.messages();
            memoryBefore = List.copyOf(historyBefore);
            memory.add(UserMessage.from(message));

            List<ChatMessage> messages = new ArrayList<>(historyBefore.size() + 2);
            // 系统提示词按本轮所处的域解析：公共基线 + 域差异。
            // 执行单元内冻结、单元之间用最新 —— resume 不重跑本方法（它从检查点恢复），
            // 因此这里读到的就是本次执行单元开始时的提示词，天然满足该语义。
            String systemMessage = promptResolver.resolve(context == null ? null : context.domainId());
            if (systemMessage != null && !systemMessage.isBlank()) {
                messages.add(SystemMessage.from(systemMessage));
            }
            // 刚写入的那条提问要补进请求体：历史快照里还没有它，
            // 而 memory.add() 走的是增量落盘、不再回写这份本地列表。
            messages.addAll(historyBefore);
            messages.add(UserMessage.from(message));

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
                rememberFinalAnswer(memory, lastState);
                // 耗时是排查"为什么慢"的第一线索：LLM 慢还是工具慢，靠它定位
                log.info("[Agent编排] 会话[{}] traceId={} 图编排完成,耗时={}ms",
                        sessionId, TraceId.current(), System.currentTimeMillis() - startedAt);
                context.emit(AgentEvent.done(sessionId));
            }
            context.complete();
        } catch (Exception e) {
            if (isCancellationException(e)) {
                handleStop(sessionId, key, context, "orchestrate", memoryBefore);
            } else if (!handleNotConfigured(sessionId, key, context, e, memoryBefore)) {
                log.error("[Agent编排] 会话[{}] traceId={} 执行失败", sessionId, TraceId.current(), e);
                // 异常中断时图没走到 END，releaseThread 不会触发，checkpoint 会残留。
                // 残留状态里可能有"悬空的 AiMessage（带工具调用却没有结果）"，
                // 下一轮带着它继续跑会反复失败——必须在这里主动清掉，避免一次异常拖垮后续所有提问。
                releaseCheckpointQuietly(key, "执行异常");
                // 记忆必须与 checkpoint 一起回滚：本轮开头写进去的 UserMessage 已经没有对应的
                // 回答（回答是在 rememberFinalAnswer 里写的，那一步没走到）。不回滚就留下
                // 一条永久"有问无答"的记录 —— 下一轮模型看到上次的提问没有回应，
                // 会顺着答非所问，而且这个偏差会一直累积，用户从界面上看不出出了什么事。
                // stop 路径早就守住了这个不变式，异常路径此前没守住。
                restoreMemory(key, memoryBefore);
                context.emit(AgentEvent.error(sessionId, ErrorCode.ORCHESTRATION_FAILED,
                        TraceId.currentOrNew()));
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
                rememberFinalAnswer(chatMemoryProvider.get(key), lastState);
                log.info("[Agent编排-resume] 会话[{}] resume 完成", sessionId);
                context.emit(AgentEvent.done(sessionId));
            }
            context.complete();
        } catch (Exception e) {
            if (isCancellationException(e)) {
                // resume 不写记忆，没有需要回滚的提问
                handleStop(sessionId, key, context, "resume", null);
            } else if (!handleNotConfigured(sessionId, key, context, e)) {
                log.error("[Agent编排-resume] 会话[{}] traceId={} 执行失败",
                        sessionId, TraceId.current(), e);
                // 同 orchestrate：异常中断不会触发 releaseThread，需主动清残留 checkpoint
                releaseCheckpointQuietly(key, "resume 异常");
                context.emit(AgentEvent.error(sessionId, ErrorCode.ORCHESTRATION_FAILED,
                        TraceId.currentOrNew()));
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
     * 把本轮最终回复写入会话记忆
     */
    private void rememberFinalAnswer(ChatMemory memory, MessagesState<ChatMessage> lastState) {
        if (memory == null || lastState == null) {
            log.warn("[Agent编排] 无可用状态,跳过记忆写入");
            return;
        }
        var finalMessages = lastState.messages();
        for (int i = finalMessages.size() - 1; i >= 0; i--) {
            if (finalMessages.get(i) instanceof AiMessage ai && !ai.hasToolExecutionRequests()) {
                memory.add(ai);
                return;
            }
        }
        log.warn("[Agent编排] 未找到可写入记忆的最终回复");
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
     * 只改展示文本 —— 真正执行与回喂模型的参数仍是原文，否则工具会拿到 {@code ***} 而失效。
     */
    private String displayArguments(String toolName, String arguments) {
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
     * @param memoryBefore 提问前的记忆快照；不为 {@code null} 时一并回滚，
     *                     理由同通用异常路径（提问已写入而回答未写入）
     * @return true 表示已按"未配置"处理并结束流，调用方不要再走通用兜底
     */
    private boolean handleNotConfigured(String sessionId, String key, StreamContext context, Throwable e,
                                        List<ChatMessage> memoryBefore) {
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
        restoreMemory(key, memoryBefore);
        // 用 4 参重载，把"去管控台哪一页填"的具体指引带进事件体（枚举默认文案只有一句概括）
        context.emit(AgentEvent.error(sessionId, notConfigured.getMessage(),
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
     * @param memoryBefore 本轮提问前的记忆快照；{@code null} = 本次不涉及记忆回滚
     */
    private void handleStop(String sessionId, String key, StreamContext context, String source,
                            List<ChatMessage> memoryBefore) {
        log.info("[Agent编排] 会话[{}] 任务被用户停止,source={}, 半截文本长度={}",
                sessionId, source, context == null ? 0 : context.partialOutput().length());

        // 1. 回滚记忆(仅 orchestrate 场景,resume 未写记忆)
        if ("orchestrate".equals(source)) {
            restoreMemory(key, memoryBefore);
        }

        // 2. 清 checkpoint(不可恢复)
        releaseCheckpointQuietly(key, "用户停止");

        // 3. 推送停止事件给调用方
        if (context != null) {
            context.emit(AgentEvent.stopped(sessionId));
            context.complete();
        }
    }

    /**
     * 把会话记忆恢复到"本轮提问之前"的那一刻。
     *
     * @param memoryBefore 提问前的快照；{@code null} 表示异常发生在拍快照之前
     */
    private void restoreMemory(String key, List<ChatMessage> memoryBefore) {
        String sessionId = SessionKeys.sessionIdOf(key);
        try {
            ChatMemory memory = chatMemoryProvider.get(key);
            if (!(memory instanceof DualConstraintChatMemory dual)) {
                log.warn("[Agent编排] 会话[{}] memory 非 DualConstraintChatMemory,无法回滚记忆", sessionId);
                return;
            }
            if (memoryBefore == null) {
                // 快照是在写提问之前拍的，所以"没有快照"= 本轮还没往记忆里写过任何东西 = 无需回滚。
                // 这里绝不能"删最后一条"或"清空"：那会把上一轮的对话内容一起抹掉。
                log.info("[Agent编排] 会话[{}] 无提问前快照（本轮未写入记忆），跳过记忆回滚", sessionId);
                return;
            }
            dual.restore(memoryBefore);
            log.info("[Agent编排] 会话[{}] 记忆已回滚到本轮提问前（{} 条）", sessionId, memoryBefore.size());
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
