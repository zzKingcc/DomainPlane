package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.tool.ToolExecutor;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.ChatMesssageSerializer;
import org.bsc.langgraph4j.langchain4j.serializer.std.ToolExecutionRequestSerializer;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.Executor;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

/**
 * 编排测试台 —— 在纯 JVM 内存里把 {@code AgentOrchestrationService} 完整跑起来。
 *
 * <p><b>它能成立的前提</b>（2026-10-26 逐条核过代码，设计文档 7.13 那条"只能端到端验"已经过时）：</p>
 * <ul>
 *   <li>构造器只强制 {@code promptResolver / executor / stateSerializer} 三项非空，其余可为 null；</li>
 *   <li>{@code executor} 传 {@code Runnable::run} → 同步执行，不含线程切换；</li>
 *   <li>langgraph4j-core 自带 {@link MemorySaver} → {@code interruptBefore} 与 {@code resume} 能完整验证；</li>
 *   <li>归档 / 摘要 / 检索 / 媒体暂存 / 装配清单 / 模型解析全部判空 → 传 null 退化为"不摘要、不归档、不检索"。</li>
 * </ul>
 *
 * <p><b>为什么这是 F07 拆分的第一步而不是"先补单测"</b>：编排缺的从来不是可测性，而是桩件。
 * 没有这套桩，任何结构改动都只能靠"眼看像不像"来判断有没有改坏。</p>
 */
final class OrchestrationHarness {

    static final String DOMAIN = "default.harness";
    static final String SESSION = "s-1";

    private final ScriptedStreamingChatModel model = new ScriptedStreamingChatModel();
    private final MapChatMemoryStore memories = new MapChatMemoryStore();
    private final CancellationRegistry cancellation = new CancellationRegistry();
    private final DomainRegistry domainRegistry = new DomainRegistry();
    private final ToolRegistry toolRegistry = new ToolRegistry(domainRegistry);
    private final BaseCheckpointSaver checkpoints;
    private final Executor executor;
    private final AgentOrchestrationService service;

    OrchestrationHarness() {
        this(Runnable::run);
    }

    /**
     * @param executor 编排执行器；默认 {@code Runnable::run}（同步，保证断言不依赖线程调度）
     */
    OrchestrationHarness(Executor executor) {
        this(executor, new MemorySaver());
    }

    /**
     * @param checkpoints 检查点存档；换成会抛异常的存档即可复现"读断点失败"的两条分支
     */
    OrchestrationHarness(Executor executor, BaseCheckpointSaver checkpoints) {
        this.executor = executor;
        this.checkpoints = checkpoints;
        this.service = buildService(null, null);
    }

    /**
     * 带规划前置装配的测试台：{@code planningOptions} 开 = 每轮进图前先做一次规划调用
     * （消耗脚本的第一条模型应答）；{@code planDisplayResolver} 决定 PLAN 事件发不发。
     */
    OrchestrationHarness(Executor executor, BaseCheckpointSaver checkpoints,
                         PlanningOptions planningOptions, PlanDisplayResolver planDisplayResolver) {
        this.executor = executor;
        this.checkpoints = checkpoints;
        this.service = buildService(planningOptions, planDisplayResolver);
    }

    private AgentOrchestrationService buildService(PlanningOptions planningOptions,
                                                   PlanDisplayResolver planDisplayResolver) {
        domainRegistry.create(DOMAIN);
        ToolRouter router = new ToolRouter(toolRegistry, domainRegistry);
        return new AgentOrchestrationService(new OrchestrationDeps(
                model, memories, router, domainId -> "", checkpoints,
                graphStateSerializer(), cancellation, executor,
                null, null, null, null, null, null, null, null, null,
                planningOptions, planDisplayResolver));
    }

    /**
     * 图状态序列化器 —— 必须与生产同 configured 口径，否则测试台验的是另一套行为。
     *
     * <p>langchain4j 的消息类与内容类都不是 {@code Serializable}。图执行时的
     * {@code cloneState} 深拷贝会走这条序列化器，缺任何一种 registrations 都会
     * 以 {@code NotSerializableException} 炸掉整轮 —— 而炸的位置在图内部，
     * 编排只能把它归类成 {@code ORCHESTRATION_FAILED}，看起来像"编排写坏了"。
     * 生产侧同一份装配在 {@code MemoryConfiguration.graphStateSerializer()}。</p>
     */
    private static ObjectStreamStateSerializer<MessagesState<ChatMessage>> graphStateSerializer() {
        var serializer = new ObjectStreamStateSerializer<MessagesState<ChatMessage>>(MessagesState::new);
        serializer.mapper()
                .register(ToolExecutionRequest.class, new ToolExecutionRequestSerializer())
                .register(ChatMessage.class, new ChatMesssageSerializer())
                .register(Content.class, new com.zzkingcc.domainplane.runtime.message.JsonContentSerializer());
        return serializer;
    }

    // ---------- 装配 ----------

    ScriptedStreamingChatModel model() {
        return model;
    }

    CancellationRegistry cancellation() {
        return cancellation;
    }

    ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    AgentOrchestrationService service() {
        return service;
    }

    /**
     * 注册一个本地工具。
     *
     * @param requiresApproval true = 声明需人工确认（走 review 中断分支）
     */
    OrchestrationHarness tool(String name, String result, boolean requiresApproval) {
        return toolAction(name, () -> result, requiresApproval);
    }

    /**
     * 注册一个执行体带副作用的本地工具。
     *
     * 用它才能在"工具正在跑"的时刻插入外部事件（例如停止那个 ownRound 的标志）。
     */
    OrchestrationHarness toolAction(String name, java.util.function.Supplier<String> result,
                                    boolean requiresApproval) {
        ToolDescriptor.Approval approval = requiresApproval
                ? new ToolDescriptor.Approval("MANUAL", "", "高危", List.of(), 0)
                : ToolDescriptor.Approval.none();
        ToolDescriptor descriptor = new ToolDescriptor(name, "desc", "cat", "1",
                null, true, true, List.of(), List.of(DOMAIN), approval, "harness");
        ToolSpecification spec = ToolSpecification.builder().name(name).description("desc").build();
        ToolExecutor body = (request, context) -> result.get();
        toolRegistry.register(new ToolRegistry.Registered(descriptor, spec, body, List.of()));
        return this;
    }

    // ---------- 驱动 ----------

    String key() {
        return SessionKeys.of(DOMAIN, SESSION);
    }

    List<AgentEvent> chat(String message) {
        AgentRequest request = AgentRequest.builder()
                .sessionId(SESSION).message(message).domain(DOMAIN).build();
        return collect(service.chat(request));
    }

    List<AgentEvent> resume(boolean approved) {
        return collect(service.resume(SESSION, approved, DOMAIN));
    }

    boolean stop() {
        return cancellation.requestStop(key());
    }

    /** 让下一次"取会话记忆"抛异常（复现"异常早于长度测量"） */
    void failNextMemoryLookup() {
        memories.failNextGets(1);
    }

    /** 会话记忆当前内容 */
    List<ChatMessage> memory() {
        return memories.raw(key());
    }

    static List<AgentEvent> collect(Flux<AgentEvent> flux) {
        return flux.collectList().block();
    }

    // ---------- 断言辅助 ----------

    static AgentEvent only(List<AgentEvent> events) {
        if (events.size() != 1) {
            throw new AssertionError("期望只有 1 个事件，实际 " + events.size() + "：" + events);
        }
        return events.get(0);
    }

    static String text(List<AgentEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent event : events) {
            if (event == null || event.getContent() == null) {
                continue;
            }
            sb.append(event.getContent());
        }
        return sb.toString();
    }

    static boolean hasCode(List<AgentEvent> events, int code) {
        return events.stream().anyMatch(e -> e != null && e.getCode() == code);
    }
}
