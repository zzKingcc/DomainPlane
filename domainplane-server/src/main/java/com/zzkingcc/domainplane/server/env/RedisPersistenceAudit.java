package com.zzkingcc.domainplane.server.env;

import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Redis 持久化自检 —— 记忆是长期存储，Redis 怎么配决定它扛不扛得住重启与内存压力。
 *
 * 为什么值得单独查：这两种配置错了都不会报错，只会"某天重启后用户历史没了"或
 * "内存紧张时记忆被随机淘汰"。一期把 Redis 当会话记忆的唯一存储，所以 RDB/AOF 与淘汰策略
 * 从"运维偏好"变成了语义前提。
 *
 * <b>执行时机是"保存 Redis 配置时"（{@code POST /admin/infra}），不是启动</b>：
 * 启动期连 Redis 读 INFO 会把一次存储不可达变成启动耗时与告警噪音——而 Redis 不是
 * 启动依赖（守卫 fail-open、Lettuce 懒连接），自检结果对启动也毫无影响。保存配置
 * 意味着用户正在看这一页，此时的反馈最有行动价值。
 *
 * 只提醒、不阻断保存：客户环境各异，拦保存比丢历史更糟。检查项读不到时同样只告警
 * （受管 Redis 常禁用 CONFIG 命令）。
 *
 * <p>结论既写日志、也<b>作为返回值</b>回给管控台（{@code warnings}），否则用户保存完
 * 什么也看不到、只能去翻服务端日志。任何异常都只记录、绝不抛出。</p>
 *
 * @author zzkingcc
 */
@Component
public class RedisPersistenceAudit {

    private static final Logger log = LoggerFactory.getLogger(RedisPersistenceAudit.class);

    private final StringRedisTemplate redisTemplate;
    private final InfraSettingsHolder infraHolder;

    public RedisPersistenceAudit(StringRedisTemplate redisTemplate, InfraSettingsHolder infraHolder) {
        this.redisTemplate = redisTemplate;
        this.infraHolder = infraHolder;
    }

    /**
     * 执行自检，返回给人看的结论（空列表 = 都正常或读不到）。
     *
     * <p>不抛异常：自检失败只说明"查不出来"，不该让保存失败。</p>
     */
    public List<String> audit() {
        List<String> findings = new ArrayList<>();
        if (!infraHolder.isRedisConfigured()) {
            findings.add("Redis 尚未配置：会话记忆与图检查点均不可用（对话会报 90005），可到管控台「存储配置」补填");
            log.warn("[Redis 自检] {}", findings.get(0));
            return findings;
        }
        addIfPresent(findings, auditPersistence(), "Redis 未开持久化");
        addIfPresent(findings, auditEvictionPolicy(), "Redis 淘汰策略会丢记忆");
        return findings;
    }

    private static void addIfPresent(List<String> findings, String finding, String logPrefix) {
        if (finding != null) {
            findings.add(finding);
            log.warn("[Redis 自检] {}：{}", logPrefix, finding);
        }
    }

    /** AOF / RDB：记忆是长期存储，没有持久化就等于会话历史只活在内存里 */
    private String auditPersistence() {
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
                return "未开启持久化（既无 AOF、RDB 快照也不可用）：Redis 一重启用户对话历史就整体丢失，"
                        + "建议 appendonly yes + appendfsync everysec";
            }
            if (!aof) {
                log.info("[Redis 自检] 仅 RDB 快照、未开 AOF：记忆能跨重启，但只能恢复到上一次快照，"
                        + "两者之间写入的对话会丢。建议补上 appendonly yes");
                return "仅 RDB 快照、未开 AOF：只能恢复到上一次快照，两次快照之间的对话会丢";
            }
            return null;
        } catch (Exception e) {
            log.warn("[Redis 自检] 读取持久化状态失败（跳过该检查）: {}", e.getMessage());
            return null;
        }
    }

    /** 淘汰策略：必须是 noeviction，否则内存紧张时记忆 key 会被随机淘汰 = 随机丢用户历史 */
    private String auditEvictionPolicy() {
        try {
            Properties config = redisTemplate.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().getConfig("maxmemory-policy"));
            String policy = value(config, "maxmemory-policy");
            if (policy.isEmpty()) {
                return null;
            }
            if (!"noeviction".equalsIgnoreCase(policy)) {
                log.warn("""
                        ================ [Redis 自检] 淘汰策略会丢记忆 ================
                        当前 maxmemory-policy = {}（应为 noeviction）。
                        内存紧张时 Redis 会直接淘汰会话记忆 key，那不是"缓冲"，是随机丢用户历史。
                        注意：改成 noeviction 后写满会导致写入失败（对话报错），
                        所以请同时按容量配置 maxmemory（单会话上限约 60–120KB）并做用量告警。
                        ==========================================================""", policy);
                return "maxmemory-policy=" + policy + "（应为 noeviction）：内存紧张时会随机淘汰会话记忆；"
                        + "改 noeviction 的同时要配好 maxmemory 并做用量告警";
            }
            return null;
        } catch (Exception e) {
            log.warn("[Redis 自检] 读取 maxmemory-policy 失败（跳过该检查）: {}", e.getMessage());
            return null;
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
