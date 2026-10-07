package com.zzkingcc.domainplane.api.agent;

/**
 * @author zzkingcc
 */
public interface DomainPlaneAgentFactory {

    /**
     * 取得绑定指定域的门面。
     *
     * @param domainId 域标识（完整路径）；{@code null} / 空白 → 根域 {@link Domains#DEFAULT}
     * @return 绑定该域的门面，可重复使用（无状态、线程安全）；同一域返回同一实例
     */
    DomainPlaneAgent forDomain(String domainId);
}
