package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行指标快照 {@code GET /admin/metrics} 与趋势 {@code GET /admin/metrics/history}。
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin")
public class AdminMetricsController {

    private final ToolRegistry toolRegistry;
    private final CancellationRegistry cancellationRegistry;

    public AdminMetricsController(ToolRegistry toolRegistry, CancellationRegistry cancellationRegistry) {
        this.toolRegistry = toolRegistry;
        this.cancellationRegistry = cancellationRegistry;
    }

    /**
     * 指标快照。
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.putAll(MetricsRegistry.snapshot());
        body.put("runningSessions", cancellationRegistry.runningCount());
        body.put("registeredTools", toolRegistry.size());
        Runtime runtime = Runtime.getRuntime();
        body.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        body.put("heapMaxBytes", runtime.maxMemory());
        return body;
    }

    /**
     * 指标趋势：历史采样点（旧 → 新，末尾带实时点）。
     *
     * <p>点是累计值不是增量：读端按相邻两点差 ÷ 时间差算区间速率，丢点不产生假零。</p>
     *
     * @param points 要多少点，最多 {@value MetricsRegistry#historyCapacity()}（环形缓冲容量）
     */
    @GetMapping("/metrics/history")
    public Map<String, Object> metricsHistory(@RequestParam(defaultValue = "60") int points) {
        int wanted = Math.min(Math.max(points, 1), MetricsRegistry.historyCapacity());
        List<Map<String, Object>> all = MetricsRegistry.history();
        // 只截尾部：窗口是"最近 wanted 点"，头部截掉不影响读数
        List<Map<String, Object>> tail = wanted >= all.size()
                ? all
                : new ArrayList<>(all.subList(all.size() - wanted, all.size()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("capacity", MetricsRegistry.historyCapacity());
        body.put("points", tail);
        return body;
    }
}
