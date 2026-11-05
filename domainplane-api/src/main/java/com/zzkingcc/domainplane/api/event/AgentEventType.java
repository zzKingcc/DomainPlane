package com.zzkingcc.domainplane.api.event;

import lombok.Getter;

/**
 * Agent 流式事件类型
 *
 * @author zzkingcc
 */
@Getter
public enum AgentEventType {

    TOKEN("模型增量输出"),
    THINKING("模型思考增量输出"),
    PLAN("本轮执行计划"),
    TOOL_CALL("工具调用请求"),
    TOOL_RESULT("工具执行结果"),
    /**
     * 能力工具产出的媒体原样下发。
     *
     * <p>只存在于 SSE 事件流：{@code payload} 里带 base64（或服务商地址），
     * 由调用方即时渲染。<b>绝不进会话归档、记忆或工具结果文本</b> ——
     * 那些持久记录里只留 {@code img_xxx} 这样的引用名（索引）。</p>
     */
    MEDIA("媒体内容"),
    INTERRUPT("挂起等待人工授权"),
    STOPPED("任务被主动停止"),
    ERROR("执行异常"),
    DONE("本轮执行正常结束");

    /** 事件的中文说明 */
    private final String description;

    AgentEventType(String description) {
        this.description = description;
    }
}
