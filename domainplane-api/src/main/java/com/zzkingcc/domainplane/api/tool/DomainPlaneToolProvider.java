package com.zzkingcc.domainplane.api.tool;

/**
 * 工具提供者标记接口
 * 当工具方法所在的类被 AOP 代理、注解没留在代理方法上时，
 * 扫描器可能从实例上读不到注解。实现本接口可以确保该类被扫到。
 *
 * @author zzkingcc
 */
public interface DomainPlaneToolProvider {
}
