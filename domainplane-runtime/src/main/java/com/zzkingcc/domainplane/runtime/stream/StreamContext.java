package com.zzkingcc.domainplane.runtime.stream;

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
    /** 本轮所处的域（入口已归一化） */
    private final String domain;
    /** 本轮排障标识：随 ERROR 事件下发，便于用 traceId 串起一次完整调用 */
    private final String traceId;
    private final StringBuilder partialOutput = new StringBuilder();

    public StreamContext(String sessionId, reactor.core.publisher.FluxSink<AgentEvent> sink,
                         String domain) {
        this(sessionId, sink, domain, TraceId.generate());
    }

    public StreamContext(String sessionId, reactor.core.publisher.FluxSink<AgentEvent> sink,
                         String domain, String traceId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.sink = Objects.requireNonNull(sink, "sink");
        // 域是必填字段：没有域就无法决定"哪些工具可见"，不能回落到匿名身份
        this.domain = Objects.requireNonNull(domain, "domain（本轮所处的域必填）");
        this.traceId = (traceId == null || traceId.isBlank()) ? TraceId.generate() : traceId;
    }

    public String sessionId() {
        return sessionId;
    }

    /** 本轮 traceId */
    public String traceId() {
        return traceId;
    }

    /** 本轮所处的域 */
    public String domainId() {
        return domain;
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
