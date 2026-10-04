package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行时配置
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.agent")
public class AgentProperties {

    /** 编排线程池核心线程数,即稳定并发会话数 */
    private int corePoolSize = 8;

    /** 编排线程池最大线程数 */
    private int maxPoolSize = 32;

    /** 等待队列容量;超过后新请求直接拒绝并返回"系统繁忙" */
    private int queueCapacity = 200;

    /** 非核心线程空闲存活时间(秒) */
    private long keepAliveSeconds = 60;
}
