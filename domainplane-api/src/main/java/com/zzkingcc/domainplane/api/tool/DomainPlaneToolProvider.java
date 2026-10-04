package com.zzkingcc.domainplane.api.tool;

/**
 * 工具提供者标记接口（<b>可选</b>）
 *
 * <p>方法上标注 {@code @Tool} 即会被扫描注册，<b>不要求实现本接口</b>——
 * 服务端进程内与工具实例 SDK 两侧规则一致。</p>
 *
 * <p>保留它的唯一用途：当工具方法所在的类被 AOP 代理、注解没留在代理方法上时，
 * 扫描器可能从实例上读不到注解。实现本接口可以确保该类被扫到。</p>
 *
 * @author zzkingcc
 */
public interface DomainPlaneToolProvider {
}
