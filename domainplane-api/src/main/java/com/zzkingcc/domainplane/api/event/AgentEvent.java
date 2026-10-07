package com.zzkingcc.domainplane.api.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * Agent 流式事件
 *
 * @author zzkingcc
 */
@Getter
@ToString
public final class AgentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件类型 */
    private final AgentEventType type;
    /** 会话 ID */
    private final String sessionId;
    /** 事件类型对应的中文描述 */
    private final String content;
    /** 事件类型给程序解读的结构化 Json */
    private final String payload;
    /** 状态码 */
    private final Integer code;
    /** 状态枚举名 */
    private final String codeName;
    /** 一次完整调用的追踪 ID */
    private final String traceId;
    /** 事件产生时间 */
    private final long timestamp;

    /**
     * Jackson 反序列化入口：跨进程（服务端 SSE → 客户端）消费事件流时由 Jackson 调用。
     */
    @JsonCreator
    public AgentEvent(@JsonProperty("type") AgentEventType type,
                      @JsonProperty("sessionId") String sessionId,
                      @JsonProperty("content") String content,
                      @JsonProperty("payload") String payload,
                      @JsonProperty("code") Integer code,
                      @JsonProperty("codeName") String codeName,
                      @JsonProperty("traceId") String traceId,
                      @JsonProperty("timestamp") Long timestamp) {
        this.type = type;
        this.sessionId = sessionId;
        this.content = content;
        this.payload = payload;
        this.code = code;
        this.codeName = codeName;
        this.traceId = traceId;
        this.timestamp = timestamp == null ? System.currentTimeMillis() : timestamp;
    }

    private AgentEvent(AgentEventType type, String sessionId, String content, String payload) {
        this(type, sessionId, content, payload, null, null, null, System.currentTimeMillis());
    }

    private AgentEvent(AgentEventType type, String sessionId, String content,
                       Integer code, String codeName, String traceId) {
        this(type, sessionId, content, null, code, codeName, traceId, System.currentTimeMillis());
    }

    /** 模型增量输出 */
    public static AgentEvent token(String sessionId, String content) {
        return new AgentEvent(AgentEventType.TOKEN, sessionId, content, null);
    }

    /** 模型思考增量输出 */
    public static AgentEvent thinking(String sessionId, String content) {
        return new AgentEvent(AgentEventType.THINKING, sessionId, content, null);
    }

    /** 工具调用请求 */
    public static AgentEvent toolCall(String sessionId, String toolName, String argumentsJson) {
        return new AgentEvent(AgentEventType.TOOL_CALL, sessionId, toolName, argumentsJson);
    }

    /** 工具执行结果 */
    public static AgentEvent toolResult(String sessionId, String toolName, String result) {
        return new AgentEvent(AgentEventType.TOOL_RESULT, sessionId, toolName, result);
    }

    /** 挂起等待人工授权 */
    public static AgentEvent interrupt(String sessionId, String toolsJson) {
        return new AgentEvent(AgentEventType.INTERRUPT, sessionId, null, toolsJson);
    }

    /** 任务被主动停止 */
    public static AgentEvent stopped(String sessionId) {
        return new AgentEvent(AgentEventType.STOPPED, sessionId, null, null);
    }

    /** 执行异常 */
    public static AgentEvent error(String sessionId, String message) {
        return new AgentEvent(AgentEventType.ERROR, sessionId, message, null);
    }

    /**
     * 执行异常
     *
     * @param errorCode 状态码，决定前端该采取的行动
     * @param traceId   一次完整调用的追踪 ID
     */
    public static AgentEvent error(String sessionId, String message,
                                   com.zzkingcc.domainplane.api.code.ErrorCode errorCode, String traceId) {
        return new AgentEvent(AgentEventType.ERROR, sessionId,
                message == null ? errorCode.getMessage() : message,
                errorCode.getCode(), errorCode.name(), traceId);
    }

    /** 执行异常 */
    public static AgentEvent error(String sessionId,
                                   com.zzkingcc.domainplane.api.code.ErrorCode errorCode, String traceId) {
        return error(sessionId, errorCode.getMessage(), errorCode, traceId);
    }

    /** 本轮执行正常结束 */
    public static AgentEvent done(String sessionId) {
        return new AgentEvent(AgentEventType.DONE, sessionId, null, null);
    }
}
