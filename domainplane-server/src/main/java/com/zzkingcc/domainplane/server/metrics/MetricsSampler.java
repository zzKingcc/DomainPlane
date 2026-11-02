package com.zzkingcc.domainplane.server.metrics;

import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指标采样器：把 {@link MetricsRegistry} 的累计计数器按固定间隔抄进历史环形缓冲，
 * 供 {@code GET /admin/metrics/history} 画趋势。
 *
 * <p>为什么放在运行层而不让公共层自己定时：common 不引 Spring，定时是容器能力；
 * 且"阈值多少、多久采一次"属于部署决策，放在 server 层才能被 yaml 覆盖
 * （{@code domainplane.metrics.sample-ms}）。</p>
 *
 * @author zzkingcc
 */
@Component
public class MetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(MetricsSampler.class);

    /**
     * 采样间隔默认 10 秒：趋势窗口 = 60 点 × 10s = 10 分钟。
     * 再密没有信息量（管控台的异常以分钟计），再疏曲线会锯齿。
     */
    public static final long DEFAULT_INTERVAL_MS = 10_000L;

    /** 实际生效的间隔（与 history 口径无关，仅用于启动日志与排障） */
    private final long intervalMs;

    public MetricsSampler(
            @org.springframework.beans.factory.annotation.Value("${domainplane.metrics.sample-ms:10000}") long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** 启动即采一点：服务刚起来就有人看趋势时，不必等一个间隔才有第一个点 */
    @PostConstruct
    public void init() {
        MetricsRegistry.sample();
        log.info("[指标] 采样器就绪：每 {} ms 一点，窗口 {} 点",
                intervalMs, MetricsRegistry.historyCapacity());
    }

    @Scheduled(fixedDelayString = "${domainplane.metrics.sample-ms:10000}")
    public void sample() {
        MetricsRegistry.sample();
    }
}
