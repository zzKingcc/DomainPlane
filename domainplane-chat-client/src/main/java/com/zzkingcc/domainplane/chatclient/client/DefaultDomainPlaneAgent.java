package com.zzkingcc.domainplane.chatclient.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.clientcore.exception.ClientErrorCode;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException;
import com.zzkingcc.domainplane.clientcore.exception.ServerRejectedException;
import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.ApprovalRequiredException;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgent;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import com.zzkingcc.domainplane.api.model.MessageContent;
import com.zzkingcc.domainplane.api.model.ToolCall;
import lombok.ToString;
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
 * 三种方式的差别只在于怎么消费那条事件流，底层走的是同一个 {@link AgentService}
 * 远程通道 —— 没有第二套协议，也没有第二份状态。
 *
 * @author zzkingcc
 */
@ToString(onlyExplicitlyIncluded = true)
public class DefaultDomainPlaneAgent implements DomainPlaneAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultDomainPlaneAgent.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * {@code ask} 的默认上限。
     *
     * SSE 正常结束由终止事件（{@code DONE}）判定，不需要靠超时收尾；但服务端完全不响应
     * （线程池满、网关挂起、连接建立后不吐任何帧）时，无参 {@code block()} 会让调用线程
     * 永久阻塞。给一个有界的兜底：宽到能容下一次正常的长回答，窄到不会挂死业务线程。
     */
    public static final Duration DEFAULT_ANSWER_TIMEOUT = Duration.ofMinutes(30);

    private final AgentService transport;
    private final String domainId;
    private final Duration answerTimeout;

    /** 只出域：transport / 超时时长不进日志，避免把连接细节和内部超时带出去 */
    @ToString.Include(name = "domainId")
    private String domainForLog() {
        return domainId;
    }

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
    public String ask(String sessionId, java.util.List<MessageContent> contents) {
        List<AgentEvent> collected;
        try {
            collected = events(sessionId, contents).collectList().block(answerTimeout);
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
        // 此时手上的 answer 只是半截，绝不能当成功返回：那等于让业务方拿着残缺答案继续跑。
        if (!terminated) {
            throw truncated(sessionId, collected.size());
        }
        return answer.toString();
    }

    /**
     * 终止事件：流"正常结束"的唯一凭据。
     *
     * 没有它，HTTP 连接被中间层掐断与"这一轮答完了"在客户端看来一模一样。
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
        return new DomainPlaneException(ClientErrorCode.STREAM_TRUNCATED,
                "事件流在收到终止事件（DONE/STOPPED/ERROR）前结束，答案不完整（已收到 " + received
                        + " 个事件）；若本轮耗时较长，请调大 domainplane.client.read-timeout 或服务端"
                        + " spring.mvc.async.request-timeout");
    }

    /** 整轮等待超过上限：连截断信号都没等到，只能按超时收场 */
    private DomainPlaneException timedOut(String sessionId, Throwable cause) {
        log.warn("[DomainPlaneAgent] 会话[{}] 等待答案超过上限（{}）", sessionId, answerTimeout, cause);
        return new DomainPlaneException(ClientErrorCode.TIMEOUT,
                "等待答案超过上限（" + answerTimeout + "），本轮未收到任何终止事件。"
                        + "请确认服务端仍在运行，或调大 domainplane.client.answer-timeout");
    }

    @Override
    public Flux<String> stream(String sessionId, java.util.List<MessageContent> contents) {
        // 截断检测：记录是否见过终止事件，流"正常结束"时若没见过就补一个错误，
        // 没有终止事件就没有"答完了"的凭据，半截答案绝不能按完整答案交给业务方。
        var terminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        return events(sessionId, contents)
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

    /**
     * 拿完整事件流（可带图）。
     *
     * <p>{@code contents} 原样填进 {@link AgentRequest}：合并 TEXT、保留图片块都由
     * {@code AgentRequest} 自己负责，这里不做任何裁剪：裁剪过一次，就再也说不清
     * 哪一层丢的。</p>
     */
    @Override
    public Flux<AgentEvent> events(String sessionId, java.util.List<MessageContent> contents) {
        return transport.chat(AgentRequest.builder()
                .sessionId(sessionId)
                .message(DomainPlaneAgent.mergedText(contents))
                .contents(contents)
                .domain(domainId)
                .build());
    }

    @Override
    public Flux<AgentEvent> resume(String sessionId, boolean approved) {
        return transport.resume(sessionId, approved, domainId);
    }

    @Override
    public boolean stop(String sessionId) {
        // 会话状态按 (域, sessionId) 隔离：stop 必须带上本门面绑定的域，否则停不到这个域里的会话
        return transport.stop(sessionId, domainId);
    }

    // ==================== 事件 → 异常 ====================

    private ApprovalRequiredException approvalRequired(String sessionId, AgentEvent event) {
        return new ApprovalRequiredException(sessionId, domainId,
                parseToolCalls(sessionId, event.getPayload()), event.getTraceId());
    }

    /**
     * 解析中断 payload（{@code {"tools":[{"name":..,"arguments":{..},"requireApproval":true}]}}）。
     *
     * 解析失败即失败，不当成"没有待授权工具"往下走：
     * 空清单会被宿主渲染成一张空的确认框，宿主照样能点批准 → 危险动作执行，
     * 而没人看到过批准内容。这与"丢掉需要审批这件事"一样糟，只是更隐蔽：
     * 后者宿主知道出事了，前者宿主以为自己批的是空操作。
     *
     * 抛出的异常不含原始 payload（避免把工具参数带进异常消息落到日志里）。
     */
    private List<ToolCall> parseToolCalls(String sessionId, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new DomainPlaneException(ClientErrorCode.RESPONSE_UNREADABLE,
                    "审批中断事件未携带待授权清单，无法在本轮继续；请查看服务端日志排查 payload 构造。"
                            + "为安全起见本轮不会放行任何工具，请重试该会话");
        }
        List<ToolCall> calls = new ArrayList<>();
        try {
            for (JsonNode tool : MAPPER.readTree(payload).path("tools")) {
                String name = tool.path("name").asText("");
                if (name.isBlank()) {
                    // 清单里有无名条目 = 宿主渲染出来是一行空白，用户不知道自己在批什么
                    throw new DomainPlaneException(ClientErrorCode.RESPONSE_UNREADABLE,
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
            throw new DomainPlaneException(ClientErrorCode.RESPONSE_UNREADABLE,
                    "审批清单解析失败，本轮不会放行任何工具，请查看服务端日志排查 payload 构造");
        }
        if (calls.isEmpty()) {
            // 服务端发了"有审批中断"但清单为空：状态不一致，绝不能当成"无需审批"
            log.warn("[DomainPlaneAgent] 会话[{}] 收到空的审批清单，已阻断本轮", sessionId);
            throw new DomainPlaneException(ClientErrorCode.RESPONSE_UNREADABLE,
                    "审批清单为空，无法确认待授权动作，本轮不会放行任何工具，请重试该会话");
        }
        return List.copyOf(calls);
    }

    /**
     * ERROR 事件 → {@link ServerRejectedException}（原样透传）。
     *
     * <p>事件的 code / codeName / content / traceId 一个字不改地交出去。<b>不把服务端码
     * 翻译成本地枚举</b>：翻译要求 SDK 与服务端共用一份枚举，服务端加一个新码之后，
     * 老 SDK 只能把它兜底成"未预期错误"，宿主从此分不清"服务端说了什么"与
     * "我这边没认出来"。透传则没有这个问题。</p>
     */
    private static DomainPlaneException failure(AgentEvent event) {
        Integer serverCode = event.getCode();
        if (serverCode == null) {
            // 服务端报错却没给码：这不是一次"带码的业务拒绝"，按响应读不懂处理，
            // 但文案仍用服务端原文：它比我们编的任何一句都准。
            String content = event.getContent();
            String message = content == null || content.isBlank() ? "服务端报错但未携带状态码" : content;
            if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
                message = message + "（traceId=" + event.getTraceId() + "）";
            }
            return new DomainPlaneException(ClientErrorCode.RESPONSE_UNREADABLE, message);
        }
        // SSE 通道没有 HTTP 状态可言，置 -1；可重试性来自服务端随事件下发的 retryable，
        // 它没给就是 null（不是 false）："没说"和"说了不能重试"是两回事。
        return new ServerRejectedException(serverCode, event.getCodeName(), event.getContent(),
                null, event.getTraceId(), -1, event.getRetryable());
    }
}
