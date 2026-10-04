package com.zzkingcc.domainplane.api.agent;

/**
 * 取得已绑定域的 {@link DomainPlaneAgent} —— 消费侧<b>唯一的域绑定入口</b>。
 *
 * <p>典型用法（绑定一次，反复调用）：</p>
 * <pre>{@code
 * @Service
 * public class OrderService {
 *     private final DomainPlaneAgent agent;
 *
 *     public OrderService(DomainPlaneAgentFactory factory) {
 *         this.agent = factory.forDomain("default.customer");   // null / 空白 → 根域 default
 *     }
 *
 *     public String ask(String sessionId, String question) {
 *         return agent.ask(sessionId, question);         // 不带域参数，漏不掉
 *     }
 * }
 * }</pre>
 *
 * @author zzkingcc
 */
public interface DomainPlaneAgentFactory {

    /**
     * 取得绑定指定域的门面。
     *
     * @param domainId 域标识（完整路径）；{@code null} / 空白 → 根域 {@link Domains#DEFAULT}
     * @return 绑定该域的门面，<b>可重复使用</b>（无状态、线程安全）；同一域返回同一实例
     */
    DomainPlaneAgent forDomain(String domainId);
}
