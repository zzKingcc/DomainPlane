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

    /** 编排执行器是否使用虚拟线程（默认开）；关掉则回落到下面的平台线程池参数 */
    private boolean virtualThreads = true;

    /**
     * 同时执行的编排轮数上限（并发闸门）。
     *
     * <p>虚拟线程下它是<b>唯一</b>的背压来源：执行器没有队列上限，不加闸门
     * 过载时会一直起线程直到 OOM，而不是快速失败。取不到许可时按"系统繁忙"拒绝本轮。</p>
     */
    private int concurrencyLimit = 200;

    /**
     * 平台线程池核心线程数。
     *
     * <p>{@code virtualThreads=true} 时它<b>不再是并发上限</b>，只作为回落到平台池时的兜底参数。</p>
     */
    private int corePoolSize = 8;

    /** 平台线程池最大线程数（回落时使用） */
    private int maxPoolSize = 32;

    /** 平台线程池等待队列容量（回落时使用） */
    private int queueCapacity = 200;

    /** 非核心线程空闲存活时间(秒) */
    private long keepAliveSeconds = 60;
}
