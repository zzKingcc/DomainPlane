package com.zzkingcc.domainplane.runtime.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标契约：{@code snapshot()} 的键名是对外承诺（{@code docs/API.md} 的 {@code /admin/metrics}），
 * 改名等于破坏接入方已经接好的看板。
 *
 * <p>断言一律用<b>增量</b>而不是绝对值——计数器是进程内静态状态，多个测试共享同一个 JVM。</p>
 * @author zzkingcc
 */
class MetricsRegistryTest {

    @Test
    @DisplayName("快照包含约定的键")
    void snapshotHasAgreedKeys() {
        Map<String, Object> snapshot = MetricsRegistry.snapshot();

        assertNotNull(snapshot.get("uptimeSeconds"));
        Map<String, Object> counters = countersOf(snapshot);
        assertTrue(counters.keySet().containsAll(Set.of(
                "chatRequests", "resumeRequests", "stopRequests", "toolCalls", "toolFailures")));
    }

    @Test
    @DisplayName("工具调用：成功只计总数，失败同时计失败数")
    void toolCallCounts() {
        long calls = counter("toolCalls");
        long failures = counter("toolFailures");

        MetricsRegistry.toolCall(true);
        assertEquals(calls + 1, counter("toolCalls"));
        assertEquals(failures, counter("toolFailures"), "成功不应计入失败");

        MetricsRegistry.toolCall(false);
        assertEquals(calls + 2, counter("toolCalls"));
        assertEquals(failures + 1, counter("toolFailures"));
    }

    @Test
    @DisplayName("只有 chat / resume / stop 计入会话类指标，其他路径不污染")
    void requestRouting() {
        long chat = counter("chatRequests");
        long resume = counter("resumeRequests");
        long stop = counter("stopRequests");

        MetricsRegistry.request("/api/agent/chat");
        MetricsRegistry.request("/api/agent/resume");
        MetricsRegistry.request("/api/agent/stop/s-1");
        MetricsRegistry.request("/api/agent/health");
        MetricsRegistry.request("/admin/settings");

        assertEquals(chat + 1, counter("chatRequests"));
        assertEquals(resume + 1, counter("resumeRequests"));
        assertEquals(stop + 1, counter("stopRequests"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> countersOf(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("counters");
    }

    private static long counter(String key) {
        return ((Number) countersOf(MetricsRegistry.snapshot()).get(key)).longValue();
    }
}
