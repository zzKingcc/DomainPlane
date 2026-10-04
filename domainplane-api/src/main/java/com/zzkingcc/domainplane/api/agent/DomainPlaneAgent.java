package com.zzkingcc.domainplane.api.agent;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * DomainPlane 对外的<b>唯一入口</b>：一个已绑定域的 Agent。
 *
 * <p>为什么只有它：调用方要的从来不是"三个接口 + 一个请求对象 + 一个身份对象"，
 * 而是"问一句、拿答案"。这里按<b>使用频度</b>分三层，常用的一条直线，复杂的才展开：</p>
 * <ol>
 *   <li>{@link #ask} —— 只要最终答案（大多数场景）；</li>
 *   <li>{@link #stream} —— 要逐字输出；</li>
 *   <li>{@link #events} —— 要完整事件（工具调用、审批、错误码、耗时）。</li>
 * </ol>
 *
 * <p>域通过 {@link DomainPlaneAgentFactory#forDomain(String)} 在取得实例时绑定，
 * 因此<b>下面所有方法都没有域参数</b> —— "忘记传域"在编译期就写不出来。</p>
 *
 * <p>实例<b>无状态、线程安全</b>，按域缓存后可长期复用（见 {@code DefaultDomainPlaneAgentFactory}）。</p>
 *
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
     * <p>内部会消费完整条事件流并把 {@code TOKEN} 增量拼成整段文本。</p>
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
     * 同 {@link #ask}，并声明调用方归属（多租户计量与审计）。
     */
    String ask(String sessionId, String question, String tenantId, String userId);

    /**
     * 问一句，逐字输出（只含模型文本增量）。
     */
    Flux<String> stream(String sessionId, String question);

    /**
     * 同 {@link #stream}，并声明调用方归属。
     */
    Flux<String> stream(String sessionId, String question, String tenantId, String userId);

    /**
     * 问一句，拿完整事件流 —— 需要看工具调用、审批中断、错误码时用它。
     *
     * <p>返回的是冷流，订阅后才开始执行；调用方可自行做超时与背压。</p>
     */
    Flux<AgentEvent> events(String sessionId, String question);

    /**
     * 同 {@link #events}，并声明调用方归属。
     */
    Flux<AgentEvent> events(String sessionId, String question, String tenantId, String userId);

    /**
     * 恢复被 {@link ApprovalRequiredException} 挂起的会话。
     *
     * <p>域的绑定关系在这里发挥作用：resume 会自动带上中断时的同一个域，
     * 不会出现"换域恢复"（服务端会以 {@code 30002} 拒绝）。</p>
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
