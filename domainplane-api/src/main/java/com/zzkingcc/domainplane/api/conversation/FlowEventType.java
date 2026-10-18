package com.zzkingcc.domainplane.api.conversation;

/**
 * 轮记录里一个流程事件类型。
 *
 * <p>对应「单轮不只存问题和回复，把全流程都放进去」里的"全流程"：
 * 模型思考、工具调用、工具结果、中间文本、错误、审批，按序全存。</p>
 *
 * @author zzkingcc
 */
public enum FlowEventType {

    /** 模型思考（中间推理文本） */
    THINKING,

    /** 一次工具调用；{@code text} 为参数 JSON，{@code toolName} 为工具名 */
    TOOL_CALL,

    /** 一次工具结果；{@code text} 为结果文本（可能被截断，见 {@link TurnFlags#truncated()}） */
    TOOL_RESULT,

    /** 模型中间输出的一段文本（非最终回答） */
    ASSISTANT_DELTA,

    /** 一轮内的错误：工具失败、调用被拦、平台错误 */
    ERROR,

    /** 审批事件：中断待授权 / 批准 / 拒绝 */
    APPROVAL
}
