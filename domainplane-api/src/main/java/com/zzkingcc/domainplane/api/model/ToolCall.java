package com.zzkingcc.domainplane.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.Objects;

/**
 * 待授权的工具调用描述
 *
 * @author zzkingcc
 */
public final class ToolCall {

    private final String name;
    private final String arguments;
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

    public String getName() {
        return name;
    }

    /** LLM 生成的原始参数 JSON;为 null 时序列化为 JSON null */
    @JsonRawValue
    public String getArguments() {
        return arguments == null ? "null" : arguments;
    }

    /** 该工具是否被 {@code @RequireApproval} 标记 */
    public boolean isRequireApproval() {
        return requireApproval;
    }

    @Override
    public String toString() {
        return "ToolCall{name='" + name + "', arguments=" + arguments
                + ", requireApproval=" + requireApproval + '}';
    }
}
