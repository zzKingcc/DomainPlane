package com.zzkingcc.domainplane.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

/**
 * 解析 LLM 生成的审批工具调用描述的json，用于回写给用户。
 *
 * @author zzkingcc
 */
@Getter
@ToString
public final class ToolCall {

    private final String name;
    /** LLM 生成的原始参数 JSON;为 null 时序列化为 JSON null */
    private final String arguments;
    /** 该工具是否被 {@code @RequireApproval} 标记 */
    private final boolean requireApproval;

    @JsonCreator
    public ToolCall(@JsonProperty("name") String name,
                    @JsonProperty("arguments") String arguments,
                    @JsonProperty("requireApproval") boolean requireApproval) {
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = arguments;
        this.requireApproval = requireApproval;
    }

    public static ToolCall of(String name, String arguments, boolean requireApproval) {
        return new ToolCall(name, arguments, requireApproval);
    }

    /**
     * LLM 生成的原始参数 JSON;为 null 时序列化为 JSON null。
     */
    @JsonRawValue
    public String getArguments() {
        return arguments == null ? "null" : arguments;
    }
}
