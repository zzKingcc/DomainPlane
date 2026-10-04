package com.zzkingcc.domainplane.api.agent;

import com.zzkingcc.domainplane.api.model.ToolCall;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 本轮被<b>人工审批</b>挂起 —— {@link DomainPlaneAgent#ask} 与 {@link DomainPlaneAgent#stream} 遇到
 * 审批中断时抛出它。
 *
 * <p>它表示的不是"失败"，而是一个<b>需要宿主介入的中间状态</b>：拿到它 → 展示待审批的工具
 * → 用户确认后调 {@link DomainPlaneAgent#resume(String, boolean)} 继续。
 * 若要自己处理事件流（而非挨异常），用 {@link DomainPlaneAgent#events}。</p>
 *
 * <p><b>清单不会为空</b>：拿不到可解析的待授权清单时，SDK 抛 {@code DomainPlaneException}
 * 而不是把这个异常的 {@link #getTools()} 留空 —— 空清单会被宿主渲染成一张空的确认框，
 * 用户点下「批准」却不知道自己批准了什么。宁可让这一轮明确失败。</p>
 *
 * <p>注意 {@link #getDomainId()}：`resume` 必须带同一个域，而门面已经绑好了，所以调用方不用管。</p>
 *
 * @author zzkingcc
 */
public class ApprovalRequiredException extends RuntimeException {

    private final String sessionId;
    private final String domainId;
    private final List<ToolCall> tools;
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

    /** 哪个会话被挂起 */
    public String getSessionId() {
        return sessionId;
    }

    /** 挂起时所处的域（`resume` 必须带同一个；门面已绑定，调用方无需关心） */
    public String getDomainId() {
        return domainId;
    }

    /** 待审批的工具调用清单（含参数 JSON 与是否确需审批），可直接渲染成确认框 */
    public List<ToolCall> getTools() {
        return tools;
    }

    /** 排障标识；服务端未下发时为 {@code null} */
    public String getTraceId() {
        return traceId;
    }
}
