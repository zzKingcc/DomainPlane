package com.zzkingcc.domainplane.api.agent;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * DomainPlane 对外的 Agent。
 * @author zzkingcc
 */
public interface DomainPlaneAgent {

    /**
     * 本实例绑定的域标识，永不为空（未指定时是 {@link Domains#DEFAULT}）。
     */
    String domainId();

    /**
     * 问一句，同步拿最终答案。
     *
     * 内部会消费完整条事件流并把 {@code TOKEN} 增量拼成整段文本。
     *
     * @param sessionId 会话 ID，需由调用方保持稳定（记忆与检查点的唯一键）
     * @param question  用户输入
     * @return 模型的最终回答
     * @throws ApprovalRequiredException 本轮被人工审批挂起（宿主应展示待审批工具，再调 {@link #resume}）
     * @throws RuntimeException          服务端返回错误或传输层失败 —— 由 SDK 抛出携带 {@code ErrorCode} 的
     *                                   {@code DomainPlaneException}（`90001`/`90002`/`10002` 这类可分支的码）
     */
    String ask(String sessionId, String question);

    /**
     * 问一句，逐字输出（只含模型文本增量）。
     */
    Flux<String> stream(String sessionId, String question);

    /**
     * 问一句，拿完整事件流 —— 需要看工具调用、审批中断、错误码时用它。
     *
     * 返回的是冷流，订阅后才开始执行；调用方可自行做超时与背压。
     */
    Flux<AgentEvent> events(String sessionId, String question);

    /**
     * 恢复被 {@link ApprovalRequiredException} 挂起的会话。
     *
     * @param approved {@code true}=批准执行工具；{@code false}=拒绝，由模型重新决策
     */
    Flux<AgentEvent> resume(String sessionId, boolean approved);

    /**
     * 请求停止会话任务（不可恢复，幂等）。
     *
     * @return {@code true}=本次设置成功；{@code false}=已在停止状态或被服务端拒绝
     */
    boolean stop(String sessionId);
}
