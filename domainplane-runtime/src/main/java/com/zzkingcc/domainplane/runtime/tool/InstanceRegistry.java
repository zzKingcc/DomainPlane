package com.zzkingcc.domainplane.runtime.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线实例表
 *
 * @author zzkingcc
 */
public class InstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);

    /** instanceId → 在线表条目（ONLINE / DRAINING / FORCE_OFFLINE 都在表里；OFFLINE = 已移除） */
    private final Map<String, InstanceSession> sessions = new ConcurrentHashMap<>();

    /**
     * 受理一次整包心跳。
     *
     * @param instanceId     实例标识
     * @param endpoint       调用回流地址
     * @param manifestDigest 本次 manifest 摘要（调用方算好，本类不碰 manifest 内容）
     * @param toolNames      本次声明的工具名
     * @param syncManifest   digest 或回流地址变化时的同步动作（由接入层提供，内部走
     *                       {@link ToolRegistry#replaceInstanceTools}）；<b>快路径下不会被调用</b>
     * @return {@code true} = 已受理（接入层回 200）；{@code false} = 该实例已被强制下线（回 410 Gone，
     * 实例应停止心跳并退出）
     */
    public boolean onHeartbeat(String instanceId,
                               String endpoint,
                               String manifestDigest,
                               Set<String> toolNames,
                               Runnable syncManifest) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        String digest = manifestDigest == null ? "" : manifestDigest;

        synchronized (StripedLocks.of(instanceId)) {
            InstanceSession existing = sessions.get(instanceId);

            // 强制下线不可恢复：即便实例不理会 410 继续发心跳，也持续被拒，副本不会复活
            if (existing != null && existing.state().isRejecting()) {
                log.warn("[实例心跳] {} 已被强制下线，拒绝本次心跳（410 Gone）", instanceId);
                return false;
            }

            // 熔断中：心跳照常受理、照常刷 lastSeen，但不把副本写回注册表——
            // 否则下一次心跳就自行解除熔断了。恢复只能由控制台或实例重启触发。
            if (existing != null && existing.state() == InstanceState.MUTED) {
                log.debug("[实例心跳] {} 处于熔断状态，仅刷新 lastSeen，副本保持摘除", instanceId);
                sessions.put(instanceId, new InstanceSession(instanceId, endpoint,
                        System.currentTimeMillis(), toolNames, digest, InstanceState.MUTED));
                return true;
            }

            boolean digestChanged = existing != null && !digest.equals(existing.manifestDigest());
            boolean endpointChanged = existing != null && !endpointEquals(existing.endpoint(), endpoint);
            boolean fastPath = existing != null
                    && existing.state() == InstanceState.ONLINE
                    && !digestChanged
                    && !endpointChanged;

            if (fastPath) {
                // 多数心跳走这里：manifest 与地址都没变，只刷时间戳，注册表一个字节都不动
                log.debug("[实例心跳] {} 快路径（manifest 与回流地址均未变），仅刷新 lastSeen", instanceId);
            } else {
                log.info("[实例心跳] {} 慢路径（{}），重新同步 manifest：{} 个工具",
                        instanceId, slowPathReason(existing, digestChanged, endpointChanged),
                        toolNames == null ? 0 : toolNames.size());
                if (syncManifest != null) {
                    syncManifest.run();
                }
            }

            sessions.put(instanceId, new InstanceSession(instanceId, endpoint,
                    System.currentTimeMillis(), toolNames, digest, InstanceState.ONLINE));
            return true;
        }
    }

    private static String slowPathReason(InstanceSession existing, boolean digestChanged, boolean endpointChanged) {
        if (existing == null) {
            return "首次登记";
        }
        if (existing.state() != InstanceState.ONLINE) {
            return "上次未正常受理";
        }
        if (digestChanged) {
            return "manifest 变化";
        }
        return "回流地址变化";
    }

    /** 回流地址是否与上次一致（{@code null} 与空串视为同一个"未提供"） */
    private static boolean endpointEquals(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /**
     * 判死摘除：在实例锁内完成"重新确认超时 → 置 DRAINING → 执行清理 → 移表"。
     *
     * @param deadlineMillis 超时判据（{@code lastSeen < deadline} 即超时）
     * @param cleanup        摘除该实例副本的动作（幂等）
     * @return {@code true} = 本次确实完成了摘除；{@code false} = 不满足判死条件，或清理失败已回滚
     */
    public boolean drain(String instanceId, long deadlineMillis, Runnable cleanup) {
        if (instanceId == null) {
            return false;
        }
        synchronized (StripedLocks.of(instanceId)) {
            InstanceSession current = sessions.get(instanceId);
            if (current == null
                    || current.state() != InstanceState.ONLINE
                    || current.lastSeen() >= deadlineMillis) {
                return false;
            }
            sessions.put(instanceId, current.withState(InstanceState.DRAINING));
            log.warn("[实例判死] {} 已安静 {}ms（阈值 {}），摘除其副本",
                    instanceId, current.silentMillis(System.currentTimeMillis()), deadlineMillis);

            try {
                cleanup.run();
            } catch (RuntimeException e) {
                // 回滚成 ONLINE：冻在 DRAINING 等于这次清理被静默放弃——扫描器只看 ONLINE，不会再来一次
                sessions.put(instanceId, current);
                log.error("[实例判死] {} 摘除副本时异常，已回滚状态待下轮重试: {}",
                        instanceId, e.getMessage(), e);
                return false;
            }

            // 无条件的移表是对的：整段都在锁内，期间不可能有心跳把表项替换成 ONLINE
            sessions.remove(instanceId);
            return true;
        }
    }

    /**
     * 强制下线：置 {@code FORCE_OFFLINE}，并立即摘除其副本。
     *
     * @return {@code true} = 已标记且副本已摘除；{@code false} = 表里没有该实例，或标记成功但摘副本失败
     */
    public boolean forceOffline(String instanceId, Runnable cleanup) {
        if (instanceId == null) {
            return false;
        }
        synchronized (StripedLocks.of(instanceId)) {
            InstanceSession current = sessions.get(instanceId);
            if (current == null) {
                return false;
            }
            sessions.put(instanceId, current.withState(InstanceState.FORCE_OFFLINE));
            log.warn("[实例强制下线] {} 已标记 FORCE_OFFLINE，其副本立即摘除，重连心跳返 410", instanceId);

            try {
                cleanup.run();
            } catch (RuntimeException e) {
                log.error("[实例强制下线] {} 副本摘除失败，标记已置位，扫描器将每轮重试: {}",
                        instanceId, e.getMessage(), e);
                return false;
            }
            return true;
        }
    }

    /**
     * 熔断：置 {@code MUTED}，心跳继续受理，只摘除副本。
     *
     * @return {@code true} = 已熔断且副本已摘除；{@code false} = 表里没有该实例，或摘除失败（标记已置位）
     */
    public boolean mute(String instanceId, Runnable cleanup) {
        if (instanceId == null) {
            return false;
        }
        synchronized (StripedLocks.of(instanceId)) {
            InstanceSession current = sessions.get(instanceId);
            if (current == null || current.state().isRejecting()) {
                return false;
            }
            sessions.put(instanceId, current.withState(InstanceState.MUTED));
            log.warn("[实例熔断] {} 已标记 MUTED，其副本立即摘除，心跳仍受理", instanceId);

            try {
                cleanup.run();
            } catch (RuntimeException e) {
                log.error("[实例熔断] {} 副本摘除失败，标记已置位，扫描器将每轮重试: {}",
                        instanceId, e.getMessage(), e);
                return false;
            }
            return true;
        }
    }

    /**
     * 解除熔断：置回 {@code ONLINE}，并把 manifest 摘要换成哨兵值——
     * 下一次心跳必然判定"摘要变了"从而走慢路径，用实例自己带来的 manifest 重建副本。
     * 服务端因此不必缓存 manifest。
     *
     * @return {@code true} = 已置回等待重建；{@code false} = 表里没有该实例，或它不是熔断状态
     */
    public boolean restore(String instanceId) {
        if (instanceId == null) {
            return false;
        }
        synchronized (StripedLocks.of(instanceId)) {
            InstanceSession current = sessions.get(instanceId);
            if (current == null || current.state() != InstanceState.MUTED) {
                return false;
            }
            sessions.put(instanceId, new InstanceSession(current.instanceId(), current.endpoint(),
                    current.lastSeen(), current.toolNames(),
                    RESYNC_DIGEST_PREFIX + System.nanoTime(), InstanceState.ONLINE));
            log.info("[实例熔断] {} 已解除，等待下一次心跳重建副本", instanceId);
            return true;
        }
    }

    /** 熔断实例超时後的清表：它没有副本可摘，直接移表，避免留下心跳已停却永不回收的僵尸条目 */
    public boolean drop(String instanceId) {
        if (instanceId == null) {
            return false;
        }
        synchronized (StripedLocks.of(instanceId)) {
            InstanceSession current = sessions.get(instanceId);
            if (current == null || current.state() != InstanceState.MUTED) {
                return false;
            }
            sessions.remove(instanceId);
            log.info("[实例熔断] {} 心跳已中断超过超时窗，在线表条目移除", instanceId);
            return true;
        }
    }

    /** 解除熔断后写在 session 上的摘要哨兵：与任何真实摘要都不相等，迫使下次心跳走慢路径 */
    private static final String RESYNC_DIGEST_PREFIX = "resync:";
    public void expireForceOffline(String instanceId) {
        if (instanceId == null) {
            return;
        }
        synchronized (StripedLocks.of(instanceId)) {
            sessions.computeIfPresent(instanceId, (key, current) ->
                    current.state().isRejecting() ? null : current);
            log.info("[实例强制下线] {} 的拒绝标记已过期，表项移除", instanceId);
        }
    }

    /** 查在线表条目 */
    public Optional<InstanceSession> find(String instanceId) {
        return Optional.ofNullable(sessions.get(instanceId));
    }

    /** 在线表快照（管控台展示 / 超时扫描共用） */
    public List<InstanceSession> all() {
        return List.copyOf(sessions.values());
    }

    /** 在线表条目数（含 DRAINING 与未过期的 FORCE_OFFLINE） */
    public int size() {
        return sessions.size();
    }
}
