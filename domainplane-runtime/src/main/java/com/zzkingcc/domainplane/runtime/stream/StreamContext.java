package com.zzkingcc.domainplane.runtime.stream;

import com.zzkingcc.domainplane.api.agent.CallerContext;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.support.TraceId;

import java.util.Objects;

/**
 * 单个会话的流式输出上下文
 *
 * @author zzkingcc
 */
public final class StreamContext {

    private final String sessionId;
    private final reactor.core.publisher.FluxSink<AgentEvent> sink;
    private final CallerContext caller;
    /** 本轮排障标识：随 ERROR 事件下发，便于用 traceId 串起一次完整调用 */
    private final String traceId;
    private final StringBuilder partialOutput = new StringBuilder();

    public StreamContext(String sessionId, reactor.core.publisher.FluxSink<AgentEvent> sink,
                         CallerContext caller) {
        this(sessionId, sink, caller, TraceId.generate());
    }

    public StreamContext(String sessionId, reactor.core.publisher.FluxSink<AgentEvent> sink,
                         CallerContext caller, String traceId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.sink = Objects.requireNonNull(sink, "sink");
        // 域是必填字段：没有域就无法决定"哪些工具可见"，因此这里不允许回落到匿名身份
        this.caller = Objects.requireNonNull(caller, "caller（CallerContext 必须携带 profile）");
        this.traceId = (traceId == null || traceId.isBlank()) ? TraceId.generate() : traceId;
    }

    public String sessionId() {
        return sessionId;
    }

    /** 本轮 traceId */
    public String traceId() {
        return traceId;
    }

    /** 本轮调用方身份（域 / 租户 / 用户） */
    public CallerContext caller() {
        return caller;
    }

    /** 本轮所处的域（去空白）；未指定时为 {@code null} */
    public String domainId() {
        return caller.normalizedProfile();
    }

    /** 下发一个事件;流已被取消时 Reactor 会静默丢弃 */
    public void emit(AgentEvent event) {
        sink.next(event);
    }

    /** 累积模型增量文本 */
    public void appendPartial(String text) {
        if (text != null) {
            partialOutput.append(text);
        }
    }

    /** 本轮已产生的文本(停止时用于日志) */
    public String partialOutput() {
        return partialOutput.toString();
    }

    /** 结束事件流(不抛异常) */
    public void complete() {
        sink.complete();
    }
}
