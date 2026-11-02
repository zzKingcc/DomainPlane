package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例心跳的可观测性：心跳此前只进状态快照，趋势无从判断。
 *
 * <p>这里钉的是"分母与分子同时存在"这条口径：只有失败数的话，10 次失败在
 * 100 次心跳里与在 10,000 次里是两回事，看到数字也无法判断实例是不是真的在退出。</p>
 */
class InstanceHeartbeatMetricsTest {

    private static final Set<String> TOOLS = Set.of("query_order");

    private final InstanceRegistry registry = new InstanceRegistry();

    @SuppressWarnings("unchecked")
    private static long counter(String key) {
        return ((Number) ((java.util.Map<String, Object>)
                MetricsRegistry.snapshot().get("counters")).get(key)).longValue();
    }

    @Test
    @DisplayName("受理与被拒都计数：被拒是分子、受理是分母")
    void heartbeatCountsBothAcceptedAndRejected() {
        long acceptedBefore = counter("instanceHeartbeats");
        long rejectedBefore = counter("instanceHeartbeatFailures");

        AtomicInteger syncs = new AtomicInteger();
        assertTrue(registry.onHeartbeat("m1", "http://10.0.0.5:8081/invoke", "d1", TOOLS, syncs::incrementAndGet));
        registry.onHeartbeat("m1", "http://10.0.0.5:8081/invoke", "d1", TOOLS, syncs::incrementAndGet);

        assertEquals(acceptedBefore + 2, counter("instanceHeartbeats"), "两次心跳都要进分母");
        assertEquals(rejectedBefore, counter("instanceHeartbeatFailures"), "没被拒就不该有失败数");

        // 强制下线后继续心跳：实例不理会 410 是常态，这正是要观测的"退不掉"
        assertTrue(registry.forceOffline("m1", () -> { }));
        assertFalse(registry.onHeartbeat("m1", "http://10.0.0.5:8081/invoke", "d1", TOOLS, syncs::incrementAndGet));

        assertEquals(acceptedBefore + 3, counter("instanceHeartbeats"),
                "被拒的那次也要进分母：否则失败率会超过 100%");
        assertEquals(rejectedBefore + 1, counter("instanceHeartbeatFailures"));
    }

    @Test
    @DisplayName("参数非法时抛异常且不计数：那不是一次心跳，是调用方的 bug")
    void blankInstanceIdIsNotAHeartbeat() {
        long acceptedBefore = counter("instanceHeartbeats");
        try {
            registry.onHeartbeat("  ", "http://10.0.0.5:8081/invoke", "d1", TOOLS, () -> { });
            throw new AssertionError("空 instanceId 应当被拒");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        assertEquals(acceptedBefore, counter("instanceHeartbeats"),
                "参数校验失败发生在计数之前：把这种调用算进心跳数会稀释失败率");
    }
}
