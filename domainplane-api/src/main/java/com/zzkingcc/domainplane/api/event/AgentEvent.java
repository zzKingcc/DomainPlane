package com.zzkingcc.domainplane.api.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Agent 流式事件
 *
 * @author zzkingcc
 */
public final class AgentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final AgentEventType type;
    private final String sessionId;
    private final String content;
    private final String payload;
    /** 状态码。*/
    private final Integer code;
    /** 状态枚举名。 */
    private final String codeName;
    /** 排障标识。 */
    private final String traceId;
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

    /** 工具调用请求 */
    public static AgentEvent toolCall(String sessionId, String toolName, String argumentsJson) {
        return new AgentEvent(AgentEventType.TOOL_CALL, sessionId, toolName, argumentsJson);
    }

    /** 工具执行结果 */
    public static AgentEvent toolResult(String sessionId, String toolName, String result) {
        return new AgentEvent(AgentEventType.TOOL_RESULT, sessionId, toolName, result);
    }

    /** 挂起等待人工授权;payload 为 {@link com.zzkingcc.domainplane.api.model.ToolCallPayload} 的 JSON */
    public static AgentEvent interrupt(String sessionId, String toolsJson) {
        return new AgentEvent(AgentEventType.INTERRUPT, sessionId, null, toolsJson);
    }

    /** 任务被主动停止 */
    public static AgentEvent stopped(String sessionId) {
        return new AgentEvent(AgentEventType.STOPPED, sessionId, null, null);
    }

    /** 执行异常（无状态码，不推荐：前端无法区分该重试还是该提示） */
    public static AgentEvent error(String sessionId, String message) {
        return new AgentEvent(AgentEventType.ERROR, sessionId, message, null);
    }

    /**
     * 执行异常
     *
     * @param errorCode 状态码，决定前端该采取的行动
     * @param traceId   排障标识
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

    public AgentEventType getType() {
        return type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getContent() {
        return content;
    }

    public String getPayload() {
        return payload;
    }

    /**
     * 状态码。
     */
    public Integer getCode() {
        return code;
    }

    /** 状态枚举名，前端应以此定义常量而非硬编码数字 */
    public String getCodeName() {
        return codeName;
    }

    /** 排障标识 */
    public String getTraceId() {
        return traceId;
    }

    /** 事件产生时间 */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "AgentEvent{type=" + type
                + ", sessionId='" + sessionId + '\''
                + ", content=" + (content == null ? "null" : "'" + content + "'")
                + ", payload=" + payload
                + ", code=" + code
                + ", codeName=" + codeName
                + ", traceId=" + traceId
                + ", timestamp=" + timestamp + '}';
    }
}
