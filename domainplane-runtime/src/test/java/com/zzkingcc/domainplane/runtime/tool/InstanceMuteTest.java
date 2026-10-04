package com.zzkingcc.domainplane.runtime.tool;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例熔断的回归测试：熔断只掐工具、不掐心跳，副本不因心跳而复活。
 */
class InstanceMuteTest {

    private static final Set<String> TOOLS = Set.of("query_order");

    private final InstanceRegistry registry = new InstanceRegistry();

    /** 同一份 manifest 的心跳；syncs 记录"重新同步"被触发的次数 */
    private boolean heartbeat(String digest, AtomicInteger syncs) {
        return registry.onHeartbeat("i1", "http://10.0.0.5:8081/invoke", digest, TOOLS, syncs::incrementAndGet);
    }

    /** 熔断点前的心跳不算：这里只统计熔断建立之后的同步次数，好断言"没有偷偷复活" */
    @Test
    void mute_keepsHeartbeatAcceptedButBlocksResync() {
        AtomicInteger syncs = new AtomicInteger();
        assertTrue(heartbeat("d1", syncs));

        assertTrue(registry.mute("i1", () -> { }));
        assertEquals(InstanceState.MUTED, registry.find("i1").orElseThrow().state());
        int before = syncs.get();

        assertTrue(heartbeat("d1", syncs), "熔断期间心跳仍应受理");
        assertEquals(InstanceState.MUTED, registry.find("i1").orElseThrow().state());
        assertEquals(before, syncs.get(), "熔断期间心跳不得重建副本");
    }

    /** 恢复靠"摘要哨兵"迫使下次心跳走慢路径：服务端因此不必缓存 manifest */
    @Test
    void restore_forcesSlowPathOnNextHeartbeat() {
        AtomicInteger syncs = new AtomicInteger();
        heartbeat("d1", syncs);
        registry.mute("i1", () -> { });

        assertTrue(registry.restore("i1"));
        String armed = registry.find("i1").orElseThrow().manifestDigest();
        assertNotEquals("d1", armed, "解除后摘要必须与原值不同，否则下次心跳会走快路径");

        int before = syncs.get();
        heartbeat("d1", syncs);
        assertEquals(before + 1, syncs.get(), "解除后的第一次心跳必须重建副本");
        assertEquals(InstanceState.ONLINE, registry.find("i1").orElseThrow().state());
    }

    /** 对不在表里的实例、或已吊销的实例，熔断与恢复都应明确失败而不是静默造出条目 */
    @Test
    void muteAndRestore_rejectUnknownOrRejectedInstances() {
        AtomicInteger syncs = new AtomicInteger();
        assertFalse(registry.mute("nobody", () -> { }));
        assertFalse(registry.restore("nobody"));

        heartbeat("d1", syncs);
        assertTrue(registry.forceOffline("i1", () -> { }));
        assertFalse(registry.mute("i1", () -> { }));
    }
}
