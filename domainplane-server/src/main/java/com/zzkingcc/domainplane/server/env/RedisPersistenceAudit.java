package com.zzkingcc.domainplane.server.env;

import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Properties;

/**
 * Redis 持久化自检 —— 记忆是长期存储，Redis 怎么配决定它扛不扛得住重启与内存压力。
 *
 * 为什么值得单独查：这两种配置错了都不会报错，只会"某天重启后用户历史没了"或
 * "内存紧张时记忆被随机淘汰"。一期把 Redis 当会话记忆的唯一存储，所以 RDB/AOF 与淘汰策略
 * 从"运维偏好"变成了语义前提。
 *
 * 只提醒、不阻断启动：客户环境各异，拦启动比丢历史更糟。检查项读不到时同样只告警
 * （受管 Redis 常禁用 CONFIG 命令）。
 *
 * @author zzkingcc
 */
public class RedisPersistenceAudit {

    private static final Logger log = LoggerFactory.getLogger(RedisPersistenceAudit.class);

    private final StringRedisTemplate redisTemplate;
    private final InfraSettingsHolder infraHolder;

    public RedisPersistenceAudit(StringRedisTemplate redisTemplate, InfraSettingsHolder infraHolder) {
        this.redisTemplate = redisTemplate;
        this.infraHolder = infraHolder;
    }

    /** 执行自检；任何异常都只告警，绝不抛出（启动期不允许因为自检失败而失败） */
    public void audit() {
        if (!infraHolder.isRedisConfigured()) {
            log.warn("[Redis 自检] Redis 尚未配置：会话记忆与图检查点均不可用（对话会报 90005），"
                    + "可到管控台「存储配置」补填");
            return;
        }
        auditPersistence();
        auditEvictionPolicy();
    }

    /** AOF / RDB：记忆是长期存储，没有持久化就等于会话历史只活在内存里 */
    private void auditPersistence() {
        try {
            Properties info = redisTemplate.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("persistence"));
            boolean aof = "1".equals(value(info, "aof_enabled"));
            boolean rdb = "ok".equalsIgnoreCase(value(info, "rdb_last_bgsave_status"));

            if (!aof && !rdb) {
                log.warn("""
                        ================ [Redis 自检] 未开启持久化 ================
                        会话记忆是长期存储，当前 Redis 既没开 AOF、RDB 快照也不可用：
                        Redis 一重启（或崩溃恢复）用户对话历史就整体丢失。
                        建议：appendonly yes + appendfsync everysec（每秒落盘，最坏丢 1 秒）
                        =======================================================""");
            } else if (!aof) {
                log.info("[Redis 自检] 仅 RDB 快照、未开 AOF：记忆能跨重启，但只能恢复到上一次快照，"
                        + "两者之间写入的对话会丢。建议补上 appendonly yes");
            }
        } catch (Exception e) {
            log.warn("[Redis 自检] 读取持久化状态失败（跳过该检查，不影响启动）: {}", e.getMessage());
        }
    }

    /** 淘汰策略：必须是 noeviction，否则内存紧张时记忆 key 会被随机淘汰 = 随机丢用户历史 */
    private void auditEvictionPolicy() {
        try {
            Properties config = redisTemplate.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().getConfig("maxmemory-policy"));
            String policy = value(config, "maxmemory-policy");
            if (policy.isEmpty()) {
                return;
            }
            if (!"noeviction".equalsIgnoreCase(policy)) {
                log.warn("""
                        ================ [Redis 自检] 淘汰策略会丢记忆 ================
                        当前 maxmemory-policy = {}（应为 noeviction）。
                        内存紧张时 Redis 会直接淘汰会话记忆 key，那不是"缓冲"，是随机丢用户历史。
                        注意：改成 noeviction 后写满会导致写入失败（对话报错），
                        所以请同时按容量配置 maxmemory（单会话上限约 60–120KB）并做用量告警。
                        ==========================================================""", policy);
            }
        } catch (Exception e) {
            log.warn("[Redis 自检] 读取 maxmemory-policy 失败（跳过该检查，不影响启动）: {}", e.getMessage());
        }
    }

    private static String value(Properties properties, String key) {
        if (properties == null) {
            return "";
        }
        Object raw = properties.get(key);
        return raw == null ? "" : raw.toString().trim();
    }
}
