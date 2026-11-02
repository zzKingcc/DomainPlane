package com.zzkingcc.domainplane.runtime.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.config.Timeouts;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.runtime.usage.TokenUsageRecorder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 规划前置 —— 进图前用主模型做的一次独立规划调用。
 *
 * <p>它回答一个问题：这一轮值不值得先排个计划。输出是<b>二态 JSON</b>：
 * {@code {"mode":"direct"}}（简单请求，直进 loop）或
 * {@code {"mode":"plan","goal":...,"steps":[...],"risks":...}}（多步请求，先给计划）。
 * 路由判断不另设分类器：direct / plan 由模型在同一次调用里自判，行为只有一份。</p>
 *
 * <p>三条硬边界：</p>
 * <ul>
 *   <li><b>不 emit 任何事件</b>：规划过程对调用方不可见（要不要把<b>结果</b>发出去是
 *       {@link PlanDisplayResolver} 的事），流式接口只用来收口终态；</li>
 *   <li><b>失败即降级</b>：超时 / 解析失败 / 上游报错都只告警并返回"无计划"，
 *       主流程照常进 loop —— 规划是增强，不是请求链的一环，它坏了不能比不用它更糟；</li>
 *   <li><b>停止必须生效</b>：切片等待与 {@code agentNode} 同口径，规划中点停止
 *       最迟一个切片就抛 {@link CancellationException}，由编排模板统一收尾。</li>
 * </ul>
 *
 * <p>产出只进<b>本轮请求体</b>（调用方自行决定以何种形态注入），不进会话记忆 ——
 * 计划的生命周期是一轮，不是一段会话。</p>
 *
 * @author zzkingcc
 */
@Slf4j
final class TurnPlanner {

    /** 规划调用的产出：有计划（含原样 JSON 与注入用渲染文本）或无计划（降级直进 loop） */
    record Outcome(boolean planned, String planJson, String rendered) {

        static Outcome none() {
            return new Outcome(false, null, null);
        }

        static Outcome of(String planJson, String rendered) {
            return new Outcome(true, planJson, rendered);
        }

        boolean isPlan() {
            return planned;
        }

        /** 计划注入本轮请求体用的系统消息（草稿口径：允许按工具结果修订，不死磕原计划） */
        SystemMessage asSystemMessage() {
            return SystemMessage.from(rendered);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelResolver modelResolver;
    private final StreamingChatModel streamingChatModel;
    private final ToolRouter toolRouter;
    private final CancellationRegistry cancellationRegistry;
    private final Timeouts timeouts;
    private final Consumer<ChatResponse> usageCapture;

    TurnPlanner(ModelResolver modelResolver, StreamingChatModel streamingChatModel,
                ToolRouter toolRouter, CancellationRegistry cancellationRegistry,
                Timeouts timeouts, Consumer<ChatResponse> usageCapture) {
        this.modelResolver = modelResolver;
        this.streamingChatModel = streamingChatModel;
        this.toolRouter = toolRouter;
        this.cancellationRegistry = cancellationRegistry;
        this.timeouts = timeouts;
        this.usageCapture = usageCapture;
    }

    /**
     * 做一次规划调用。除取消外的任何失败都降级为"无计划"，绝不向上抛。
     *
     * @param domainId  本轮所处的域（解析模型与可见工具用）
     * @param messages  本轮已装配好的请求消息（系统提示 + 摘要/卡片 + 窗口 + 本轮提问）
     * @param key       图状态键（停止标志的定位）
     * @param sessionId 会话 ID（仅日志用）
     */
    Outcome plan(String domainId, List<ChatMessage> messages, String key, String sessionId) {
        try {
            return doPlan(domainId, messages, key, sessionId);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Agent编排-规划] 会话[{}] traceId={} 规划调用失败，降级直进 loop：{}",
                    sessionId, TraceId.currentOrNew(), e.toString());
            return Outcome.none();
        }
    }

    private Outcome doPlan(String domainId, List<ChatMessage> messages, String key, String sessionId) {
        StreamingChatModel model = resolveModel(domainId);

        /* 规划指令与域的系统提示合并成**一条** SystemMessage：
           并列两条 system 在 OpenAI 兼容实现里通常没问题，但 Anthropic 那类
           "system 单独成字段"的协议只认一条，第二条会被丢弃或直接报错 ——
           而协议是按域档案分派的，这里不能赌。域提示在前、规划指令在后：
           前者是"你是谁"，后者是"这一趟先做什么"，顺序反了模型会先执行完规划器
           人格再回到角色设定。 */
        String instruction = plannerPrompt(domainId);
        List<ChatMessage> plannerMessages = new ArrayList<>();
        int firstNonSystem = 0;
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage domainSystem
                && domainSystem.text() != null && !domainSystem.text().isBlank()) {
            plannerMessages.add(SystemMessage.from(domainSystem.text() + "\n\n" + instruction));
            firstNonSystem = 1;
        } else {
            plannerMessages.add(SystemMessage.from(instruction));
        }
        plannerMessages.addAll(messages.subList(firstNonSystem, messages.size()));

        var request = ChatRequest.builder().messages(plannerMessages).build();
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicInteger outputTokens = new AtomicInteger(0);

        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (cancellationRegistry.isCancelled(key)) {
                    future.completeExceptionally(new CancellationException("用户主动停止"));
                    return;
                }
                // 不 emit：规划过程对调用方不可见。增量只用于用量估算。
                outputTokens.addAndGet(TokenUsageRecorder.estimateTokens(partialResponse));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (cancellationRegistry.isCancelled(key)) {
                    future.completeExceptionally(new CancellationException("用户主动停止"));
                    return;
                }
                usageCapture.accept(completeResponse);
                AiMessage aiMessage = completeResponse.aiMessage();
                future.complete(aiMessage == null ? null : aiMessage.text());
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        try {
            String text = awaitPlannedText(future, key, sessionId);
            return parse(text, sessionId);
        } finally {
            /* 规划也是模型调用，用量必须回填：只记成功路径会漏掉"规划跑完但解析失败"
               那一类（它们同样消耗了 token），口径与 agentNode 的 finally 一致。 */
            TokenUsageRecorder.addLlmOutputTokens(outputTokens.get());
        }
    }

    /**
     * 切片等待规划终态 —— 与 {@code AgentGraphFactory.awaitAiMessage} 同口径：
     * 每片醒来先看停止标志，用户点停止最迟一个切片生效；总上限独立
     * （{@code planner-wait-ms}，比等模型答话短得多：规划卡住不值得让它拖住整轮）。
     */
    private String awaitPlannedText(CompletableFuture<String> future, String key, String sessionId) {
        long limitMillis = timeouts.plannerWaitMs();
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
                    future.completeExceptionally(new TimeoutException("规划调用超时"));
                    log.warn("[Agent编排-规划] 会话[{}] 规划超过 {} 秒，降级直进 loop",
                            sessionId, limitMillis / 1000);
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new CompletionException(cause);
            }
        }
    }

    /**
     * 解析规划产出。只认"干净的二态 JSON"：mode=plan 且 goal 与 steps 齐备才算有计划，
     * 其余一切形态（direct、缺字段、空步骤、裹了说明文字解析不出来）都按"无计划"降级。
     */
    private Outcome parse(String text, String sessionId) {
        if (text == null || text.isBlank()) {
            return Outcome.none();
        }
        try {
            JsonNode root = MAPPER.readTree(extractJson(text));
            String mode = root.path("mode").asText("");
            if (!"plan".equalsIgnoreCase(mode)) {
                return Outcome.none();
            }
            String goal = root.path("goal").asText("").trim();
            JsonNode steps = root.path("steps");
            if (goal.isEmpty() || !steps.isArray() || steps.isEmpty()) {
                log.warn("[Agent编排-规划] 会话[{}] 计划缺 goal 或 steps，降级直进 loop", sessionId);
                return Outcome.none();
            }
            String risks = root.path("risks").asText("").trim();
            String rendered = render(goal, steps, risks);
            return Outcome.of(MAPPER.writeValueAsString(root), rendered);
        } catch (Exception e) {
            log.warn("[Agent编排-规划] 会话[{}] 计划 JSON 解析失败，降级直进 loop：{}",
                    sessionId, e.toString());
            return Outcome.none();
        }
    }

    /** 剥掉可能的 markdown 围栏与前后说明文字，只留最外层 JSON 对象 */
    private static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("产出中找不到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    /** 计划渲染成注入用文本：只写"做什么"，工具与依赖作参考，明确草稿口径 */
    private static String render(String goal, JsonNode steps, String risks) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮执行计划】（草稿：工具结果与计划冲突时按实际情况修订执行，不必死磕原计划）\n");
        sb.append("目标：").append(goal).append('\n');
        sb.append("步骤：\n");
        int i = 1;
        for (JsonNode step : steps) {
            sb.append(i++).append(". ").append(step.path("what").asText("").trim());
            List<String> tools = toStringList(step.path("tools"));
            if (!tools.isEmpty()) {
                sb.append("（可用工具：").append(String.join("、", tools)).append("）");
            }
            List<String> deps = toStringList(step.path("dependsOn"));
            if (!deps.isEmpty()) {
                sb.append("（依赖步骤 ").append(String.join("、", deps)).append("）");
            }
            sb.append('\n');
        }
        if (!risks.isEmpty()) {
            sb.append("风险与注意：").append(risks).append('\n');
        }
        return sb.toString();
    }

    private static List<String> toStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return values;
    }

    private String plannerPrompt(String domainId) {
        String tools;
        try {
            List<String> names = toolRouter.toolSpecifications(domainId).stream()
                    .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                    .toList();
            tools = names.isEmpty() ? "（本轮无可用工具）" : String.join(", ", names);
        } catch (Exception e) {
            tools = "（本轮无可用工具）";
        }
        return """
                你是任务规划器。基于后续对话上下文，判断本轮请求是否需要先规划再执行，只输出一个 JSON 对象，不要输出其他任何内容。

                判据：
                - 简单问答、闲聊、改写、单步即可完成的请求：输出 {"mode":"direct"}
                - 需要多个步骤或多次工具调用才能完成的请求：输出规划，格式：
                  {"mode":"plan","goal":"本轮要达成的目标（一句话）","steps":[{"what":"这一步做什么","tools":["工具名"],"dependsOn":[依赖的步骤序号，从1开始]}],"risks":"执行中需要注意的风险，可为空字符串"}

                要求：
                - 步骤以 3~7 个为宜，只写"做什么"，不写具体调用参数；
                - tools 只能从下面的可用工具清单里选，没有合适的工具就不要列；dependsOn 没有依赖就给空数组；
                - 拿不准时按 direct 处理：规划本身有成本，宁可漏规划，不要给简单请求加延迟。

                可用工具：%s""".formatted(tools);
    }

    private StreamingChatModel resolveModel(String domainId) {
        if (modelResolver == null) {
            return streamingChatModel;
        }
        return modelResolver.streamingChat(domainId);
    }
}
