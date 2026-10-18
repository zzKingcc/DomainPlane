package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.server.capability.CapabilityCallExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 能力调用（生图 / 语音 / 视频）的隔离层装配。
 *
 * <p>与 {@code VisionConfiguration} 里那个看图池分开两个 bean：上限与池都不同（理由见
 * {@link CapabilityCallExecutor}）。都是"部署决策"，所以放服务端装配。</p>
 *
 * @author zzkingcc
 */
@Configuration
public class CapabilityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CapabilityConfiguration.class);

    /**
     * 能力调用专用隔离池。
     *
     * <p>上限默认 120s：生图 / 语音合成通常几十秒内返回，比本地工具的 30s 宽裕得多；
     * 生视频的"边提交边等"预算由 {@code domainplane.capability.video-wait-ms} 单独控制，
     * 且必须小于本上限，否则等待还没结束就被隔离层掐断。</p>
     *
     * <p>{@code destroyMethod = "close"}：Spring 关闭时 {@code shutdownNow}，
     * 不留非守护线程挂着进程。</p>
     */
    @Bean(destroyMethod = "close")
    public CapabilityCallExecutor capabilityCallExecutor(
            @Value("${domainplane.capability.pool-size:8}") int poolSize,
            @Value("${domainplane.capability.timeout-ms:120000}") long timeoutMs) {
        CapabilityCallExecutor executor = new CapabilityCallExecutor(poolSize, timeoutMs);
        log.info("[能力调用] 专用隔离池 {} 线程（零队列，满即拒），单次调用上限 {}ms",
                Math.max(1, poolSize), executor.timeoutMs());
        return executor;
    }
}
