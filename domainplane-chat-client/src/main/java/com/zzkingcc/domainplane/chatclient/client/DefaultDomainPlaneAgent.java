package com.zzkingcc.domainplane.chatclient.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException;
import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.ApprovalRequiredException;
import com.zzkingcc.domainplane.api.agent.CallerContext;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgent;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import com.zzkingcc.domainplane.api.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link DomainPlaneAgent} 的实现：把"答案 / 逐字 / 事件"三种消费方式收在同一个已绑定域的门面上。
 *
 * <p>三种方式的差别只在于<b>怎么消费那条事件流</b>，底层走的是同一个 {@link AgentService}
 * 远程通道 —— 没有第二套协议，也没有第二份状态。</p>
 *
 * @author zzkingcc
 */
public class DefaultDomainPlaneAgent implements DomainPlaneAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultDomainPlaneAgent.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * {@code ask} 的默认上限。
     *
     * <p>SSE 正常结束由终止事件（{@code DONE}）判定，不需要靠超时收尾；但服务端完全不响应
     * （线程池满、网关挂起、连接建立后不吐任何帧）时，无参 {@code block()} 会让调用线程
     * <b>永久阻塞</b>。给一个有界的兜底：宽到能容下一次正常的长回答，窄到不会挂死业务线程。</p>
     */
    public static final Duration DEFAULT_ANSWER_TIMEOUT = Duration.ofMinutes(30);

    private final AgentService transport;
    private final String domainId;
    private final Duration answerTimeout;

    public DefaultDomainPlaneAgent(AgentService transport, String domainId) {
        this(transport, domainId, DEFAULT_ANSWER_TIMEOUT);
    }

    public DefaultDomainPlaneAgent(AgentService transport, String domainId, Duration answerTimeout) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.domainId = domainId;
        this.answerTimeout = answerTimeout == null || answerTimeout.isNegative() || answerTimeout.isZero()
                ? DEFAULT_ANSWER_TIMEOUT
                : answerTimeout;
    }

    @Override
    public String domainId() {
        return domainId;
    }

    @Override
    public String ask(String sessionId, String question) {
        return ask(sessionId, question, null, null);
    }

    @Override
    public String ask(String sessionId, String question, String tenantId, String userId) {
        List<AgentEvent> collected;
        try {
            collected = events(sessionId, question, tenantId, userId).collectList().block(answerTimeout);
        } catch (IllegalStateException e) {
            // reactor 的 block(Duration) 超时抛这个，消息通常不含业务信息
            throw timedOut(sessionId, e);
        }
        if (collected == null) {
            return "";
        }
        StringBuilder answer = new StringBuilder();
        boolean terminated = false;
        for (AgentEvent event : collected) {
            switch (event.getType()) {
                case TOKEN -> answer.append(event.getContent() == null ? "" : event.getContent());
                // 审批挂起：宿主必须介入，因此不返回答案而是抛出去
                case INTERRUPT -> throw approvalRequired(sessionId, event);
                case ERROR -> throw failure(event);
                // 已产出的部分就是此刻能给出的全部；被停止不是错误
                case STOPPED -> {
                    return answer.toString();
                }
                case DONE -> terminated = true;
                // TOOL_CALL / TOOL_RESULT：ask 只关心最终文本
                default -> {
                }
            }
        }
        // 没收到任何终止事件就走到这里 = 流被中途掐断（容器异步超时、网关截断、连接重置…）。
        // 此时手上的 answer 只是半截，绝不能当成功返回 —— 那等于让业务方拿着残缺答案继续跑。
        if (!terminated) {
            throw truncated(sessionId, collected.size());
        }
        return answer.toString();
    }

    /**
     * 终止事件：流"正常结束"的唯一凭据。
     *
     * <p>没有它，HTTP 连接被中间层掐断与"这一轮答完了"在客户端看来一模一样。</p>
     */
    private static boolean isTerminal(AgentEventType type) {
        return type == AgentEventType.DONE
                || type == AgentEventType.STOPPED
                || type == AgentEventType.ERROR
                || type == AgentEventType.INTERRUPT;
    }

    /** 流在收到终止事件前结束 */
    private static DomainPlaneException truncated(String sessionId, int received) {
        log.warn("[DomainPlaneAgent] 会话[{}] 事件流未收到终止事件即结束，已收到 {} 个事件，判定为流被截断",
                sessionId, received);
        return new DomainPlaneException(ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                "事件流在收到终止事件（DONE/STOPPED/ERROR）前结束，答案不完整（已收到 " + received
                        + " 个事件）；若本轮耗时较长，请调大 domainplane.client.read-timeout 或服务端"
                        + " spring.mvc.async.request-timeout");
    }

    /** 整轮等待超过上限：连截断信号都没等到，只能按超时收场 */
    private DomainPlaneException timedOut(String sessionId, Throwable cause) {
        log.warn("[DomainPlaneAgent] 会话[{}] 等待答案超过上限（{}）", sessionId, answerTimeout, cause);
        return new DomainPlaneException(ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                "等待答案超过上限（" + answerTimeout + "），本轮未收到任何终止事件。"
                        + "请确认服务端仍在运行，或调大 domainplane.client.answer-timeout");
    }

    @Override
    public Flux<String> stream(String sessionId, String question) {
        return stream(sessionId, question, null, null);
    }

    @Override
    public Flux<String> stream(String sessionId, String question, String tenantId, String userId) {
        // 截断检测：记录是否见过终止事件，流"正常结束"时若没见过就补一个错误 ——
        // 没有终止事件就没有"答完了"的凭据，半截答案绝不能按完整答案交给业务方。
        var terminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        return events(sessionId, question, tenantId, userId)
                .concatMap(event -> {
                    counter.incrementAndGet();
                    if (isTerminal(event.getType())) {
                        terminated.set(true);
                    }
                    return switch (event.getType()) {
                        case TOKEN -> Flux.just(event.getContent() == null ? "" : event.getContent());
                        case INTERRUPT -> Flux.error(approvalRequired(sessionId, event));
                        case ERROR -> Flux.error(failure(event));
                        default -> Flux.empty();
                    };
                })
                .concatWith(Flux.defer(() -> terminated.get()
                        ? Flux.empty()
                        : Flux.error(truncated(sessionId, counter.get()))));
    }

    @Override
    public Flux<AgentEvent> events(String sessionId, String question) {
        return events(sessionId, question, null, null);
    }

    @Override
    public Flux<AgentEvent> events(String sessionId, String question, String tenantId, String userId) {
        return transport.chat(AgentRequest.builder()
                .sessionId(sessionId)
                .message(question)
                .profile(domainId)
                .tenantId(tenantId)
                .userId(userId)
                .build());
    }

    @Override
    public Flux<AgentEvent> resume(String sessionId, boolean approved) {
        return transport.resume(sessionId, approved, CallerContext.of(domainId));
    }

    @Override
    public boolean stop(String sessionId) {
        // 会话状态按 (域, sessionId) 隔离：stop 必须带上本门面绑定的域，否则停不到这个域里的会话
        return transport.stop(sessionId, CallerContext.of(domainId));
    }

    // ==================== 事件 → 异常 ====================

    private ApprovalRequiredException approvalRequired(String sessionId, AgentEvent event) {
        return new ApprovalRequiredException(sessionId, domainId,
                parseToolCalls(sessionId, event.getPayload()), event.getTraceId());
    }

    /**
     * 解析中断 payload（{@code {"tools":[{"name":..,"arguments":{..},"requireApproval":true}]}}）。
     *
     * <p><b>解析失败即失败</b>，不当成"没有待授权工具"往下走：
     * 空清单会被宿主渲染成一张空的确认框，宿主照样能点批准 → 危险动作执行，
     * 而没人看到过批准内容。这与"丢掉需要审批这件事"一样糟，只是更隐蔽 ——
     * 后者宿主知道出事了，前者宿主以为自己批的是空操作。</p>
     *
     * <p>抛出的异常不含原始 payload（避免把工具参数带进异常消息落到日志里）。</p>
     */
    private List<ToolCall> parseToolCalls(String sessionId, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new DomainPlaneException(ErrorCode.UNEXPECTED_ERROR,
                    "审批中断事件未携带待授权清单，无法在本轮继续；请查看服务端日志排查 payload 构造。"
                            + "为安全起见本轮不会放行任何工具，请重试该会话");
        }
        List<ToolCall> calls = new ArrayList<>();
        try {
            for (JsonNode tool : MAPPER.readTree(payload).path("tools")) {
                String name = tool.path("name").asText("");
                if (name.isBlank()) {
                    // 清单里有无名条目 = 宿主渲染出来是一行空白，用户不知道自己在批什么
                    throw new DomainPlaneException(ErrorCode.UNEXPECTED_ERROR,
                            "审批清单中存在无名称条目，无法确认待授权动作，已阻断本轮（请重试该会话）");
                }
                JsonNode arguments = tool.path("arguments");
                calls.add(ToolCall.of(
                        name,
                        arguments.isMissingNode() || arguments.isNull() ? null : arguments.toString(),
                        tool.path("requireApproval").asBoolean(false)));
            }
        } catch (DomainPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[DomainPlaneAgent] 会话[{}] 中断 payload 解析失败（已阻断本轮，不按空清单放行）",
                    sessionId, e);
            throw new DomainPlaneException(ErrorCode.UNEXPECTED_ERROR,
                    "审批清单解析失败，本轮不会放行任何工具，请查看服务端日志排查 payload 构造");
        }
        if (calls.isEmpty()) {
            // 服务端发了"有审批中断"但清单为空：状态不一致，绝不能当成"无需审批"
            log.warn("[DomainPlaneAgent] 会话[{}] 收到空的审批清单，已阻断本轮", sessionId);
            throw new DomainPlaneException(ErrorCode.UNEXPECTED_ERROR,
                    "审批清单为空，无法确认待授权动作，本轮不会放行任何工具，请重试该会话");
        }
        return List.copyOf(calls);
    }

    /** ERROR 事件 → 带码异常；traceId 并进文案，方便一键排障 */
    private static DomainPlaneException failure(AgentEvent event) {
        ErrorCode code = event.getCode() == null ? null : ErrorCode.of(event.getCode());
        if (code == null) {
            // 服务端给了未在本版本枚举里的码：兜底成可重试的服务错误，但保留原文案
            code = ErrorCode.UNEXPECTED_ERROR;
        }
        String message = event.getContent() == null || event.getContent().isBlank()
                ? code.getMessage() : event.getContent();
        if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
            message = message + "（traceId=" + event.getTraceId() + "）";
        }
        return new DomainPlaneException(code, message);
    }

    @Override
    public String toString() {
        return "DomainPlaneAgent{domainId='" + domainId + "'}";
    }
}
