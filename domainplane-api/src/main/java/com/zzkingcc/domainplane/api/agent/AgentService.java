package com.zzkingcc.domainplane.api.agent;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 服务对外契约
 *
 * @author zzkingcc
 */
public interface AgentService {

    /**
     * 发起一轮对话
     *
     * @param request 调用请求,sessionId 同时作为记忆与检查点 key
     * @return 事件流,订阅后开始执行
     */
    Flux<AgentEvent> chat(AgentRequest request);

    /**
     * 恢复被 {@code INTERRUPT} 事件挂起的会话
     *
     * @param sessionId 会话 ID,需与 chat 时一致
     * @param approved  true=批准执行工具; false=拒绝,内核注入拒绝反馈让模型重新决策
     * @param caller    调用方身份（域 / 租户 / 用户），不得为 {@code null}
     * @return 事件流,resume 后的输出继续推送
     */
    Flux<AgentEvent> resume(String sessionId, boolean approved, CallerContext caller);

    /**
     * 请求停止指定会话的任务(不可恢复)
     *
     * @param sessionId 会话 ID
     * @param caller    调用方身份（域 / 租户 / 用户）。会话状态按 <b>(域, sessionId)</b> 隔离，
     *                  停止必须携带与发起对话时<b>同一个域</b>，否则停不到那个域里的会话
     * @return true=本次设置成功; false=该会话已处于停止状态(幂等)
     */
    boolean stop(String sessionId, CallerContext caller);
}
