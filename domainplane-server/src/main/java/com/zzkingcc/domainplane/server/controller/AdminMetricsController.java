package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行指标快照 {@code GET /admin/metrics}。
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
}
