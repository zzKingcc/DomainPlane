package com.zzkingcc.domainplane.api.agent;

import com.zzkingcc.domainplane.api.model.ToolCall;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author zzkingcc
 */
@Getter
public class ApprovalRequiredException extends RuntimeException {

    /** 会话 ID */
    private final String sessionId;
    /** 挂起时所处的域 */
    private final String domainId;
    /** 待审批的工具调用清单 */
    private final List<ToolCall> tools;
    /** 排障标识；服务端未下发时为 {@code null} */
    private final String traceId;

    public ApprovalRequiredException(String sessionId, String domainId, List<ToolCall> tools, String traceId) {
        super(describe(sessionId, tools));
        this.sessionId = sessionId;
        this.domainId = domainId;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.traceId = traceId;
    }

    private static String describe(String sessionId, List<ToolCall> tools) {
        // 正常路径的清单已保证非空（空清单在 SDK 层就被拦下并抛 DomainPlaneException）：
        // 空清单会被宿主渲染成一张空的确认框，用户点下"批准"却不知道批准了什么
        String names = tools.stream().map(ToolCall::getName).collect(Collectors.joining("、"));
        return "会话 " + sessionId + " 已因人工审批挂起，待授权工具：" + names;
    }
}
