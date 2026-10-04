package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.runtime.tool.InstanceLifecycle;
import com.zzkingcc.domainplane.runtime.tool.InstanceRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.tool.RemoteToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 工具实例配置：在线实例表 + 生命周期（判死摘除 / 强制下线）。
 * @author zzkingcc
 */
@Configuration
@EnableScheduling
public class InstanceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InstanceConfiguration.class);

    /** 在线实例表（内存态，不落盘：重启后表清空，靠实例 SDK 自动重连恢复） */
    @Bean
    @ConditionalOnMissingBean
    public InstanceRegistry instanceRegistry() {
        return new InstanceRegistry();
    }

    /**
     * 实例生命周期：超时摘除 + 强制下线。
     *
     * @param timeoutSeconds               超时窗（秒），默认 35 = 心跳周期 5s × 7
     * @param forceOfflineRetentionSeconds 强制下线拒绝标记的保留时长（秒），默认 1h
     */
    @Bean
    @ConditionalOnMissingBean
    public InstanceLifecycle instanceLifecycle(
            InstanceRegistry instanceRegistry,
            ToolRegistry toolRegistry,
            @Value("${domainplane.instance.timeout-seconds:35}") long timeoutSeconds,
            @Value("${domainplane.instance.force-offline-retention-seconds:3600}") long forceOfflineRetentionSeconds) {
        log.info("[工具实例] 判死超时窗 {}s，强制下线拒绝标记保留 {}s，扫描间隔见 domainplane.instance.scan-interval-ms",
                timeoutSeconds, forceOfflineRetentionSeconds);
        return new InstanceLifecycle(instanceRegistry, toolRegistry,
                timeoutSeconds * 1000L, forceOfflineRetentionSeconds * 1000L);
    }

    /**
     * 远程工具执行器：同名工具的多个副本随机选一个调用，传输层失败换副本重试。
     *
     * @param invokeTimeoutMillis 单次调用超时（毫秒），默认 30s
     * @param invokeMaxAttempts   单次调用最多尝试几个不同副本，默认 2
     */
    @Bean
    @ConditionalOnMissingBean
    public RemoteToolExecutor remoteToolExecutor(
            ToolRegistry toolRegistry,
            @Value("${domainplane.instance.invoke-timeout-ms:30000}") int invokeTimeoutMillis,
            @Value("${domainplane.instance.invoke-max-attempts:2}") int invokeMaxAttempts) {
        log.info("[工具实例] 远程工具调用超时 {}ms，单次调用最多尝试 {} 个副本",
                invokeTimeoutMillis, invokeMaxAttempts);
        return new RemoteToolExecutor(toolRegistry, invokeTimeoutMillis, invokeMaxAttempts);
    }
}
