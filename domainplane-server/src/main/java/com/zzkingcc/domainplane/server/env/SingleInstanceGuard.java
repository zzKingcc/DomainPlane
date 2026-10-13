package com.zzkingcc.domainplane.server.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 单实例守卫 —— 把"DomainPlane 是单体"从部署假设变成启动期会强制的事实。
 *
 * 为什么需要它：会话协调状态（会话占用、停止标志、流注册表、工具注册表）全部在进程内，
 * 没有跨实例协调。因此两个 DomainPlane 共用一个 Redis 时的失败方式格外糟糕，
 * 不报错，只是结果悄悄错了：
 *
 *   • 同一会话打到两个实例：两边的"占用"闸门都通过 → 并发执行 → 记忆丢更新、断点互相覆盖；
 *   • {@code stop} 落到没有在执行的那个实例：标志设在它那儿，在跑的那轮永远看不到，
 *       任务跑完并写进记忆，而接口返回 {@code stopRequested: true}，用户以为停了。
 *
 * 这类问题在日志里几乎留不下痕迹，等发现时用户数据已经串了。既然当前明确只支持单体，
 * 与其留一个静默的正确性陷阱，不如在启动时直接拦下来。
 *
 * 实现：启动时用 {@code SET key value NX EX ttl} 抢占一个带 TTL 的租约。
 * 抢不到 = 另一个实例的租约仍在有效期内 = 拒绝启动。进程优雅停机时释放租约；
 * 非正常退出则等 TTL 自然过期（因此 TTL 取值要短于"运维发现并处理"的间隔，
 * 但也要长于一次正常停机释放失败的窗口）。
 *
 * Redis 未配置时本守卫不生效：那时会话能力本身不可用（对话报 90005），
 * 也就不存在"两个进程共享会话状态"这个场景。
 *
 * @author zzkingcc
 */
public class SingleInstanceGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    /** 租约键：与具体部署无关，因此任何实例抢的都是同一个键 */
    private static final String LEASE_KEY = "domainplane:runtime:owner";

    /** 租约读写动作，抽成接口以便在没有 Redis 的情况下测判断逻辑 */
    interface LeaseStore {

        /**
         * @return true = 抢到了；false = 已有他人持有
         * @throws RuntimeException Redis 不可用（调用方据此放行而不是拦停）
         */
        boolean tryAcquire(String holderId, Duration ttl);

        /** 当前持有者，仅用于报错信息 */
        String currentHolder();

        /** 比较并删除：只有租约仍属于 holderId 时才删 */
        void releaseIfMine(String holderId);
    }

    private final LeaseStore leases;
    private final String holderId = UUID.randomUUID().toString();
    private final Duration ttl;
    private final boolean enabled;

    private volatile boolean acquired;

    public SingleInstanceGuard(StringRedisTemplate redisTemplate, Duration ttl, boolean enabled) {
        this(new RedisLeaseStore(redisTemplate), ttl, enabled);
    }

    /**
     * @param ttl     租约有效期。需短于"另一个实例崩溃后运维介入"的间隔，
     *                又要足够长以免正常停机释放失败时锁死太久
     * @param enabled 关闭守卫（{@code domainplane.single-instance-guard=false}）后本守卫退化为空操作
     */
    public SingleInstanceGuard(LeaseStore leases, Duration ttl, boolean enabled) {
        this.leases = leases;
        this.ttl = ttl;
        this.enabled = enabled;
    }

    /**
     * 抢占租约。
     *
     * @throws IllegalStateException 已有另一个实例持有租约
     */
    public void acquire() {
        if (!enabled) {
            log.info("[单实例守卫] 已通过配置关闭：多实例共用 Redis 会导致会话占用、stop、流与工具注册表"
                    + "全部错乱（且不报错），请确保确实只有一个 DomainPlane 在运行");
            return;
        }
        boolean ok;
        String current;
        try {
            ok = leases.tryAcquire(holderId, ttl);
            current = ok ? null : leases.currentHolder();
        } catch (Exception e) {
            // Redis 连不上不该由这个守卫拦住启动（那是连接问题，由别处报）
            log.warn("[单实例守卫] 抢占租约失败（{}），跳过单实例检查", e.getMessage());
            return;
        }
        if (!ok) {
            throw new IllegalStateException(
                    "检测到另一个 DomainPlane 实例正在使用同一个 Redis（持有者 " + current + "），拒绝启动。"
                            + "会话占用、停止标志、流注册表与工具注册表全部只存在于进程内，"
                            + "多实例共用 Redis 会静默产生错误结果（同一会话丢更新、stop 停不掉真正在跑的任务等）。"
                            + "当前形态为单体部署；请只保留一个实例，或为每个实例使用独立的 Redis。"
                            + "若确认要临时绕过，可设置 domainplane.single-instance-guard=false。");
        }
        acquired = true;
        log.info("[单实例守卫] 已持有运行权（租约 {}，TTL {}s），本次实例标识 {}",
                LEASE_KEY, ttl.toSeconds(), holderId);
    }

    /**
     * 比较并删除：只有租约当前的值仍是自己的标识时才删。
     *
     * 用 Lua 是因为"判断"与"删除"必须原子。若本进程的租约已过期并被另一个实例抢走，
     * 朴素的无条件 {@code DEL} 会把对方的租约删掉，等于凭空放跑第三个实例。
     */
    private static final String RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    /** 基于 Redis 的租约实现 */
    private static final class RedisLeaseStore implements LeaseStore {

        private final StringRedisTemplate redisTemplate;

        RedisLeaseStore(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        @Override
        public boolean tryAcquire(String holderId, Duration ttl) {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(LEASE_KEY, holderId, ttl));
        }

        @Override
        public String currentHolder() {
            return redisTemplate.opsForValue().get(LEASE_KEY);
        }

        @Override
        public void releaseIfMine(String holderId) {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
            Long deleted = redisTemplate.execute(script, List.of(LEASE_KEY), holderId);
            if (deleted != null && deleted > 0) {
                log.info("[单实例守卫] 已释放运行权");
            } else {
                log.info("[单实例守卫] 租约已不在本实例名下（可能已过期并被接管），不删除");
            }
        }
    }

    /** 优雅停机时释放租约，让重启后的实例能立刻拿到（不必等 TTL 过期） */
    @Override
    public void close() {
        if (!acquired) {
            return;
        }
        try {
            leases.releaseIfMine(holderId);
        } catch (Exception e) {
            log.warn("[单实例守卫] 释放租约失败，将由 TTL（{}s）自动过期：{}", ttl.toSeconds(), e.getMessage());
        }
        acquired = false;
    }
}