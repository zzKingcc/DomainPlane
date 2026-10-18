package com.zzkingcc.domainplane.common.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标契约：{@code snapshot()} 的键名是对外承诺（{@code docs/接口文档.md} 的 {@code /admin/metrics}），
 * 改名等于破坏接入方已经接好的看板。
 *
 * <p>断言一律用<b>增量</b>而不是绝对值：计数器是进程内静态状态，多个测试共享同一个 JVM。</p>
 *
 * @author zzkingcc
 */
class MetricsRegistryTest {

    /** 全部对外键名（改名即破坏契约） */
    private static final Set<String> AGREED_KEYS = Set.of(
            "chatRequests", "resumeRequests", "stopRequests", "toolCalls", "toolFailures",
            "recallSearches", "recallHits", "recallMisses", "recallFallbacks",
            "recallToolCalls", "recallBudgetBlocked",
            "indexedTurns", "indexedTerms", "indexWriteFailures");

    @Test
    @DisplayName("快照包含约定的键")
    void snapshotHasAgreedKeys() {
        Map<String, Object> snapshot = MetricsRegistry.snapshot();

        assertNotNull(snapshot.get("uptimeSeconds"));
        assertTrue(countersOf(snapshot).keySet().containsAll(AGREED_KEYS),
                "缺键：" + countersOf(snapshot).keySet());
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

    @Test
    @DisplayName("检索：命中计 hits，空手计 misses，两者都进 searches；兜底另计")
    void recallSearchCounts() {
        long searches = counter("recallSearches");
        long hits = counter("recallHits");
        long misses = counter("recallMisses");
        long fallbacks = counter("recallFallbacks");

        MetricsRegistry.recallSearch(true, false);
        MetricsRegistry.recallSearch(false, true);

        assertEquals(searches + 2, counter("recallSearches"));
        assertEquals(hits + 1, counter("recallHits"));
        assertEquals(misses + 1, counter("recallMisses"));
        assertEquals(fallbacks + 1, counter("recallFallbacks"));
    }

    @Test
    @DisplayName("工具层：实际检索与预算拦截分开计（拦截不该算一次检索）")
    void recallToolCounts() {
        long toolCalls = counter("recallToolCalls");
        long blocked = counter("recallBudgetBlocked");
        long searches = counter("recallSearches");

        MetricsRegistry.recallToolCall();
        MetricsRegistry.recallBudgetBlocked();

        assertEquals(toolCalls + 1, counter("recallToolCalls"));
        assertEquals(blocked + 1, counter("recallBudgetBlocked"));
        assertEquals(searches, counter("recallSearches"), "拦截本身不是一次检索");
    }

    @Test
    @DisplayName("索引：成功累计轮数与词根数，失败单独计")
    void indexCounts() {
        long turns = counter("indexedTurns");
        long terms = counter("indexedTerms");
        long failures = counter("indexWriteFailures");

        MetricsRegistry.indexed(7);
        assertEquals(turns + 1, counter("indexedTurns"));
        assertEquals(terms + 7, counter("indexedTerms"));

        MetricsRegistry.indexed(0);
        assertEquals(turns + 2, counter("indexedTurns"), "没有词根也算尝试过一轮");
        assertEquals(terms + 7, counter("indexedTerms"), "0 词根不该改动词根数");

        MetricsRegistry.indexWriteFailure();
        assertEquals(failures + 1, counter("indexWriteFailures"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> countersOf(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("counters");
    }

    private static long counter(String key) {
        return ((Number) countersOf(MetricsRegistry.snapshot()).get(key)).longValue();
    }
}
