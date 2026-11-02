package com.zzkingcc.domainplane.runtime.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例四态状态机（ONLINE / MUTED / DRAINING / FORCE_OFFLINE）与心跳快慢路径。
 *
 * <p>此前只有 {@code InstanceMuteTest} 覆盖了 MUTED 一态，其余三态与"慢路径到底
 * 什么条件下触发"没有断言 —— 而慢路径是唯一会重建副本的路径，它错了的表现是
 * "工具无故消失"或"manifest 变了但副本不更新"，两者都很难从现象反推到这里。</p>
 */
class InstanceStateMachineTest {

    private static final Set<String> TOOLS = Set.of("query_order");
    private static final String ENDPOINT = "http://10.0.0.5:8081/invoke";

    private final InstanceRegistry registry = new InstanceRegistry();

    /** syncs 记录"重新同步 manifest"被触发的次数 —— 它就是慢路径的可观测副作用 */
    private final AtomicInteger syncs = new AtomicInteger();

    private boolean heartbeat(String instanceId, String digest) {
        return registry.onHeartbeat(instanceId, ENDPOINT, digest, TOOLS, syncs::incrementAndGet);
    }

    private boolean heartbeat(String instanceId, String digest, String endpoint) {
        return registry.onHeartbeat(instanceId, endpoint, digest, TOOLS, syncs::incrementAndGet);
    }

    @Test
    @DisplayName("快路径：manifest 与回流地址都没变时不重建副本")
    void fastPathSkipsResync() {
        assertTrue(heartbeat("s1", "d1"));
        int afterFirst = syncs.get();

        assertTrue(heartbeat("s1", "d1"));
        assertEquals(afterFirst, syncs.get(),
                "同一 manifest 与地址的第二次心跳必须走快路径：每跳一次都重建副本等于把注册表按心跳频率重写");
    }

    @Test
    @DisplayName("慢路径：manifest 变化 / 地址变化 / 首次登记，三种都要重建副本")
    void slowPathTriggersOnAnyChange() {
        assertTrue(heartbeat("s2", "d1"));
        int base = syncs.get();

        heartbeat("s2", "d2");
        assertEquals(base + 1, syncs.get(), "manifest 变了必须重建");

        heartbeat("s2", "d2", "http://10.0.0.9:8081/invoke");
        assertEquals(base + 2, syncs.get(), "回流地址变了必须重建");

        heartbeat("s3", "d9");
        assertEquals(base + 3, syncs.get(), "首次登记也是慢路径");
    }

    @Test
    @DisplayName("DRAINING：判死后清表，条目移除且不可被再次判死")
    void drainRemovesTheEntry() {
        assertTrue(heartbeat("s4", "d1"));
        AtomicInteger cleanups = new AtomicInteger();

        // deadline 取未来时刻：lastSeen 必然小于它，即"已安静超时"
        assertTrue(registry.drain("s4", System.currentTimeMillis() + 1, cleanups::incrementAndGet));
        assertEquals(1, cleanups.get());
        assertTrue(registry.find("s4").isEmpty(), "判死完成后条目必须移表，否则扫描器每轮重复摘除");

        assertFalse(registry.drain("s4", System.currentTimeMillis() + 1, cleanups::incrementAndGet),
                "已移表的实例不该再被判死一次");
    }

    @Test
    @DisplayName("判死失败要回滚成 ONLINE：冻在 DRAINING 里等于这次清理被永久放弃")
    void drainRollsBackWhenCleanupFails() {
        assertTrue(heartbeat("s5", "d1"));

        assertFalse(registry.drain("s5", System.currentTimeMillis() + 1, () -> {
            throw new IllegalStateException("副本摘除失败");
        }));
        assertEquals(InstanceState.ONLINE, registry.find("s5").orElseThrow().state(),
                "清理失败必须回到 ONLINE：扫描器只认 ONLINE，冻在 DRAINING 就再也不会重试");
    }

    @Test
    @DisplayName("FORCE_OFFLINE：不可恢复，标记置位后重连心跳持续被拒")
    void forceOfflineIsTerminal() {
        assertTrue(heartbeat("s6", "d1"));
        assertTrue(registry.forceOffline("s6", () -> { }));

        assertFalse(heartbeat("s6", "d1"), "强制下线后的心跳必须持续被拒（410）");
        assertEquals(InstanceState.FORCE_OFFLINE, registry.find("s6").orElseThrow().state());
        assertFalse(registry.mute("s6", () -> { }), "已强制下线的实例不能转入熔断：那是绕开终态的复活路径");

        registry.expireForceOffline("s6");
        assertTrue(registry.find("s6").isEmpty(), "拒绝标记过期后表项移除，实例重连即回 ONLINE");
        assertTrue(heartbeat("s6", "d1"), "清表之后同一实例可以重新登记");
    }

    @Test
    @DisplayName("MUTED 是可恢复的：解除后靠摘要哨兵迫使下次心跳重建副本")
    void muteIsRecoverable() {
        assertTrue(heartbeat("s7", "d1"));
        assertTrue(registry.mute("s7", () -> { }));
        assertEquals(InstanceState.MUTED, registry.find("s7").orElseThrow().state());

        assertTrue(registry.restore("s7"));
        assertEquals(InstanceState.ONLINE, registry.find("s7").orElseThrow().state());

        int before = syncs.get();
        assertTrue(heartbeat("s7", "d1"));
        assertEquals(before + 1, syncs.get(), "解除后第一次心跳必须走慢路径重建副本");
    }

    @Test
    @DisplayName("熔断实例心跳中断后由 drop 清表，不留僵尸条目")
    void dropRemovesMutedEntry() {
        assertTrue(heartbeat("s8", "d1"));
        assertTrue(registry.mute("s8", () -> { }));

        assertFalse(registry.drop("s9"), "不在表里的实例不能 drop");
        assertTrue(registry.drop("s8"));
        assertTrue(registry.find("s8").isEmpty());

        assertFalse(registry.drop("s8"), "已移除的条目重复 drop 应明确失败");
    }
}
