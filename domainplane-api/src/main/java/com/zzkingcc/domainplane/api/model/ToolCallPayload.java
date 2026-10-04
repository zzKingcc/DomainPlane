package com.zzkingcc.domainplane.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 中断事件的 payload 结构
 *
 * @author zzkingcc
 */
public final class ToolCallPayload {

    private final List<ToolCall> tools;

    @JsonCreator
    public ToolCallPayload(@JsonProperty("tools") List<ToolCall> tools) {
        this.tools = tools == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public static ToolCallPayload of(List<ToolCall> tools) {
        return new ToolCallPayload(tools);
    }

    public static ToolCallPayload empty() {
        return new ToolCallPayload(Collections.emptyList());
    }

    public List<ToolCall> getTools() {
        return tools;
    }

    @Override
    public String toString() {
        return "ToolCallPayload{tools=" + Objects.requireNonNull(tools).size() + '}';
    }
}
