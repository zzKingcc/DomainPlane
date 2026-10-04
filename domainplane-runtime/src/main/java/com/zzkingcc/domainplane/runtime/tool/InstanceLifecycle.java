package com.zzkingcc.domainplane.runtime.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 实例生命周期 —— 判死摘除与强制下线
 *
 * @author zzkingcc
 */
public class InstanceLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InstanceLifecycle.class);

    private final InstanceRegistry instances;
    private final ToolRegistry tools;

    /** 超时窗（毫秒）：超过它没收到心跳即判死。默认 35s = 心跳周期 5s × 7 */
    private final long timeoutMillis;

    /** 强制下线标记的保留时长（毫秒）：过期后移除表项，实例可重新注册（默认 1h） */
    private final long forceOfflineRetentionMillis;

    public InstanceLifecycle(InstanceRegistry instances,
                             ToolRegistry tools,
                             long timeoutMillis,
                             long forceOfflineRetentionMillis) {
        this.instances = instances;
        this.tools = tools;
        this.timeoutMillis = timeoutMillis;
        this.forceOfflineRetentionMillis = forceOfflineRetentionMillis;
    }

    /**
     * 定时扫描在线表：超时判死 + 强制下线副本兜底回收 + 过期标记清理。
     */
    @Scheduled(fixedDelayString = "${domainplane.instance.scan-interval-ms:5000}")
    public void scan() {
        long now = System.currentTimeMillis();
        long deadline = now - timeoutMillis;

        for (InstanceSession session : instances.all()) {
            String instanceId = session.instanceId();

            if (session.state() == InstanceState.MUTED) {
                // 熔断中：副本每轮重试摘除（幂等），心跳停了就从在线表移除，避免留僵尸条目
                tools.removeInstance(instanceId);
                if (session.lastSeen() < deadline) {
                    instances.drop(instanceId);
                }
                continue;
            }

            if (session.state() == InstanceState.FORCE_OFFLINE) {
                // 兜底回收：强制下线时若摘副本失败，这里每轮重试。
                // 不这么做就会留下"状态被判死、副本仍在承接调用"的孤儿副本，而扫描器只看 ONLINE，
                // 永远不会再来处理它。removeInstance 幂等，已清空时是空操作。
                tools.removeInstance(instanceId);
                if (session.silentMillis(now) > forceOfflineRetentionMillis) {
                    instances.expireForceOffline(instanceId);
                }
                continue;
            }

            if (session.state() != InstanceState.ONLINE || session.lastSeen() >= deadline) {
                continue;
            }

            // drain 内部会重新确认超时，并在同一把实例锁内完成"置 DRAINING → 摘副本 → 移表"
            if (instances.drain(instanceId, deadline, () -> tools.removeInstance(instanceId))) {
                log.info("[实例判死] {} 已摘除（安静超过 {}ms），会话不受影响，仅其工具副本消失",
                        instanceId, timeoutMillis);
            }
        }
    }

    /**
     * 强制下线（管控台按钮触发）。
     *
     * @return {@code false} = 在线表里没有这个实例（可能已自然下线），或副本摘除失败
     */
    public boolean forceOffline(String instanceId) {
        boolean applied = instances.forceOffline(instanceId, () -> tools.removeInstance(instanceId));
        if (!applied && instances.find(instanceId).isEmpty()) {
            log.warn("[实例强制下线] {} 不在在线表中，无从下线", instanceId);
        }
        return applied;
    }

    /**
     * 熔断（管控台按钮触发）：不断心跳，只让它的工具不可调用。
     *
     * @return {@code false} = 在线表里没有这个实例，或它已被强制下线，或副本摘除失败
     */
    public boolean mute(String instanceId) {
        boolean applied = instances.mute(instanceId, () -> tools.removeInstance(instanceId));
        if (!applied && instances.find(instanceId).isEmpty()) {
            log.warn("[实例熔断] {} 不在在线表中，无从熔断", instanceId);
        }
        return applied;
    }

    /**
     * 解除熔断：置回等待重建，副本在下一次心跳到来时恢复。
     */
    public boolean restore(String instanceId) {
        return instances.restore(instanceId);
    }

    /** 超时窗（毫秒），供管控台展示与排障 */
    public long timeoutMillis() {
        return timeoutMillis;
    }
}
