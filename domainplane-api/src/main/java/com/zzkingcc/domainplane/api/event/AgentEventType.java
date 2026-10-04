package com.zzkingcc.domainplane.api.event;

/**
 * Agent 流式事件类型
 *
 * @author zzkingcc
 */
public enum AgentEventType {

    TOKEN("模型增量输出"),
    TOOL_CALL("工具调用请求"),
    TOOL_RESULT("工具执行结果"),
    INTERRUPT("挂起等待人工授权"),
    STOPPED("任务被主动停止"),
    ERROR("执行异常"),
    DONE("本轮执行正常结束");

    private final String description;

    AgentEventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
