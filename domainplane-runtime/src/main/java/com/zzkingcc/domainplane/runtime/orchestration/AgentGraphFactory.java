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
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
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
import java.util.function.Consumer;
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
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 编排图的构建与它的三个节点 —— 图怎么搭、agent / tools 节点各做什么、
 * 什么条件下从 agent 走向 tools 或走向中断，以及"等模型把话说完"的那段阻塞。
 *
 * <p>这是 F07 四步拆分里最后、也是最粘的一组：节点要拿到本轮的流式上下文、
 * 停止标志、按域过滤后的工具集、"域 → 模型"的解析，以及等模型时的超时。
 * 它们此前全部直接读编排服务的私有字段，所以搬出来的代价是这些依赖必须显式声明
 * —— 这恰恰是这一步的收益：<b>每个节点到底动了什么，写在了构造器上</b>，
 * 不再散落在 2,500 行的中间。</p>
 *
 * <p>两个刻意留在编排服务的回调：工具入参的展示脱敏（{@code displayArguments}）
 * 与本轮真实用量的回填（{@code usageCapture}）。前者属"失败矩阵"那一半的文案口径，
 * 后者属用量统计，两者都不是图结构的一部分 —— 把它们塞进图工厂，
 * 下一次改文案就要动图。</p>
 *
 * @author zzkingcc
 */
@Slf4j
final class AgentGraphFactory {

    /**
     * 条件边的分支键。
     *
     * <p>键在 {@code AgentOrchestrationService} 侧也要用到（读图快照判断"这一轮是不是
     * 停在审批点"），所以放在图工厂里作为唯一的字面量出处，服务按
     * {@code AgentGraphFactory.BRANCH_REVIEW} 引用 —— 图改分支名时只有一处要动。</p>
     */
    static final String BRANCH_EXIT = "exit";
    static final String BRANCH_AUTO = "auto";
    static final String BRANCH_REVIEW = "review";

    /**
     * metadata 里携带本轮流式上下文的键名。
     *
     * 本轮的 {@link StreamContext} 必须随图执行一路传下去，绝不能在节点里按
     * threadId 回查全局注册表：注册表里存的是"当前那一轮"，而节点跑的是"发起它的那一轮"。
     * 两者在两个请求重叠时会不一致，此时 A 的工具调用会按 B 的域过滤、
     * A 的模型 token 会流进 B 的 SSE 流。这是跨域串号，不是显示问题。
     */
    static final String CTX_METADATA_KEY = "domainplane.streamContext";

    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final ToolRouter toolRouter;
    private final ToolCallEchoResolver toolCallEchoResolver;
    private final ModelResolver modelResolver;
    private final CancellationRegistry cancellationRegistry;
    private final StreamSinkRegistry streamSinks;
    private final Timeouts timeouts;
    private final BiFunction<String, String, String> displayArguments;
    private final Consumer<ChatResponse> usageCapture;

    AgentGraphFactory(StreamingChatModel streamingChatModel, ChatMemoryProvider chatMemoryProvider,
                      ToolRouter toolRouter, ToolCallEchoResolver toolCallEchoResolver,
                      ModelResolver modelResolver, CancellationRegistry cancellationRegistry,
                      StreamSinkRegistry streamSinks, Timeouts timeouts,
                      BiFunction<String, String, String> displayArguments,
                      Consumer<ChatResponse> usageCapture) {
        this.streamingChatModel = streamingChatModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.toolRouter = toolRouter;
        this.toolCallEchoResolver = toolCallEchoResolver;
        this.modelResolver = modelResolver;
        this.cancellationRegistry = cancellationRegistry;
        this.streamSinks = streamSinks;
        this.timeouts = timeouts;
        this.displayArguments = displayArguments;
        this.usageCapture = usageCapture;
    }

    /**
     * 构建 StateGraph 并编译
     */
    CompiledGraph<MessagesState<ChatMessage>> build(BaseCheckpointSaver checkpointSaver,
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
    static Map<String, Channel<?>> messagesSchema() {
        Map<String, Channel<?>> schema = new HashMap<>(MessagesState.SCHEMA);
        schema.put(MessagesState.MESSAGES_STATE,
                Channels.<ChatMessage>appenderWithDuplicate(ArrayList::new));
        return schema;
    }

    /**
     * 同步节点包装器:在调用线程上执行节点逻辑,不切换线程
     */
    AsyncNodeActionWithConfig<MessagesState<ChatMessage>> syncNode(
            BiFunction<MessagesState<ChatMessage>, RunnableConfig, Map<String, Object>> action) {
        return (state, config) -> {
            try {
                return CompletableFuture.completedFuture(action.apply(state, config));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }

    String stateKeyOf(RunnableConfig config) {
        return config.threadId().orElseThrow(() -> new IllegalStateException("RunnableConfig 缺少 threadId"));
    }

    String sessionIdOf(RunnableConfig config) {
        return SessionKeys.sessionIdOf(stateKeyOf(config));
    }

    String sessionIdOfKey(String key) {
        return SessionKeys.sessionIdOf(key);
    }

    StreamContext contextOf(RunnableConfig config) {
        Optional<Object> carried = config.metadata(CTX_METADATA_KEY);
        if (carried.isPresent() && carried.get() instanceof StreamContext ctx) {
            return ctx;
        }
        // 兜底：仅用于图在编排器之外被直接驱动的情形（如测试）。
        // 这里刻意告警而不是静默：按 key 回查在两个请求重叠时会拿到别的轮次的上下文，
        // 那是跨域串号，必须让这种驱动方式在日志里显形。
        StreamContext fallback = streamSinks.get(stateKeyOf(config));
        log.warn("[Agent编排] 会话[{}] 图配置未携带本轮上下文，回退到按 key 查询注册表"
                + "（若存在同一会话的重叠请求，可能取到别的轮次的上下文）", SessionKeys.sessionIdOf(stateKeyOf(config)));
        return fallback;
    }

    /**
     * agent 节点:调用流式模型,推送 TOKEN 事件,返回 AiMessage 到状态
     */
    Map<String, Object> agentNode(MessagesState<ChatMessage> state, RunnableConfig config) {
        String key = stateKeyOf(config);
        String sessionId = SessionKeys.sessionIdOf(key);
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
                    usageCapture.accept(completeResponse);
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
    AiMessage awaitAiMessage(CompletableFuture<AiMessage> future, String key) {
        long limitMillis = timeouts.modelWaitMs();
        long sliceMillis = timeouts.modelWaitSliceMs();
        long deadline = System.nanoTime() + limitMillis * 1_000_000L;
        while (true) {
            try {
                return future.get(sliceMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (cancellationRegistry.isCancelled(key)) {
                    future.completeExceptionally(new CancellationException("用户主动停止"));
                    throw new CancellationException("用户主动停止");
                }
                if (System.nanoTime() >= deadline) {
                    future.completeExceptionally(new TimeoutException("模型响应超时"));
                    throw new CompletionException(new TimeoutException(
                            "等待模型响应超过 " + (limitMillis / 1000) + " 秒，已放弃本轮"));
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

    /**
     * 解析本轮使用的对话模型。
     *
     * 没有解析器（未装配模型档案的部署、单元测试）时退回构造期注入的模型，行为与"全局一个模型"一致。
     * 一旦装配了解析器，解析不到 / 解析异常都不回落任何内置默认模型：未绑定 = 无可调用，
     * 如实体上报 {@link NotConfiguredException}，让调用方拿到明确原因。
     */
    StreamingChatModel resolveModel(String domainId) {
        if (modelResolver == null) {
            return streamingChatModel;
        }
        // 解析器存在时，未配置 / 解析失败都如实向上抛，不静默回落默认模型
        return modelResolver.streamingChat(domainId);
    }

    /**
     * tools 节点:执行 LLM 请求的工具调用,返回 ToolExecutionResultMessage 列表到状态
     */
    Map<String, Object> toolsNode(MessagesState<ChatMessage> state, RunnableConfig config) {
        String key = stateKeyOf(config);
        String sessionId = SessionKeys.sessionIdOf(key);
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
                            displayArguments.apply(request.name(), request.arguments())));
                    context.emit(AgentEvent.toolResult(sessionId, request.name(), denied));
                }
                results.add(ToolExecutionResultMessage.from(request, denied));
                continue;
            }

            if (context != null && echoToolCalls) {
                context.emit(AgentEvent.toolCall(sessionId, request.name(),
                        displayArguments.apply(request.name(), request.arguments())));
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
    AsyncCommandAction<MessagesState<ChatMessage>> routeAfterAgent() {
        return (state, config) -> {
            var lastMessage = state.lastMessage()
                    .orElseThrow(() -> new IllegalStateException("消息列表为空"));

            String key = stateKeyOf(config);
            String sessionId = SessionKeys.sessionIdOf(key);
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
}
