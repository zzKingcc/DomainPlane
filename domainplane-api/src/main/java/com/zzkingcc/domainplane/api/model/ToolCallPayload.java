package com.zzkingcc.domainplane.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 审批工具列表，用于回写给用户。
 *
 * @author zzkingcc
 */
@Getter
@ToString(onlyExplicitlyIncluded = true)
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

    /** 只出个数：清单里带着工具参数，整份打进日志属泄露，也没人读 */
    @ToString.Include(name = "tools")
    private int toolCount() {
        return tools.size();
    }
}
