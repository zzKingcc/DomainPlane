package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 轮记录里的一个流程事件（按序）。
 *
 * <p>{@code text} 的语义随 {@link #type()} 变：TOOL_CALL 是参数 JSON、
 * TOOL_RESULT 是结果文本、ASSISTANT_DELTA / THINKING / ERROR / APPROVAL 是正文。
 * {@code toolName} 只对 TOOL_CALL / TOOL_RESULT 有意义。</p>
 *
 * @author zzkingcc
 */
public record FlowEvent(
        @JsonProperty("type") FlowEventType type,
        @JsonProperty("text") String text,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("at") Instant at) {

    @JsonCreator
    public FlowEvent(@JsonProperty("type") FlowEventType type,
                     @JsonProperty("text") String text,
                     @JsonProperty("toolName") String toolName,
                     @JsonProperty("at") Instant at) {
        this.type = type;
        this.text = text;
        this.toolName = toolName;
        this.at = at;
    }

    /** 无工具名的文本事件 */
    public static FlowEvent of(FlowEventType type, String text) {
        return new FlowEvent(type, text, null, Instant.now());
    }

    /** 工具调用 / 工具结果事件 */
    public static FlowEvent ofTool(FlowEventType type, String toolName, String text) {
        return new FlowEvent(type, text, toolName, Instant.now());
    }
}
