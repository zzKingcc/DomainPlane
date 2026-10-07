package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSwappable;
import io.lettuce.core.api.StatefulConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 可热替换的 Redis 连接工厂 —— {@link RedisConnectionFactory} 的委派代理。
 * @author zzkingcc
 */
@Slf4j
public class SwappableRedisConnectionFactory implements RedisConnectionFactory, InfraSwappable, DisposableBean {

    /** 旧实现延迟关闭的宽限期（秒） */
    private static final long GRACE_SECONDS = 30;

    private final InfraSettingsHolder holder;

    /** 连接池等性能参数仍以 yaml 为准，管控台只管"连哪儿" */
    private final RedisProperties yamlProps;

    private volatile LettuceConnectionFactory delegate;

    public SwappableRedisConnectionFactory(InfraSettingsHolder holder, RedisProperties yamlProps) {
        this.holder = holder;
        this.yamlProps = yamlProps;
        this.delegate = build(holder.current().getRedis(), yamlProps);
        log.info("[存储配置] Redis 连接工厂就绪：{}", holder.current().getRedis().describe());
        holder.register(this);
    }

    /**
     * 用一份候选配置构建 Lettuce 连接工厂。
     */
    public static LettuceConnectionFactory build(InfraSettings.Redis cfg, RedisProperties yaml) {
        String host = cfg != null && cfg.isUsable() ? cfg.getHost() : "localhost";
        int port = cfg == null ? 6379 : cfg.effectivePort();
        int database = cfg == null ? 0 : cfg.effectiveDatabase();
        String password = cfg == null ? null : cfg.getPassword();

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
        standalone.setHostName(host);
        standalone.setPort(port);
        standalone.setDatabase(database);
        if (password != null && !password.isBlank()) {
            standalone.setPassword(RedisPassword.of(password));
        }

        RedisProperties.Pool pool = yaml.getPool();
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(pool.getMaxActive());
        poolConfig.setMaxIdle(pool.getMaxIdle());
        poolConfig.setMinIdle(pool.getMinIdle());
        poolConfig.setMaxWait(pool.getMaxWait());

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(yaml.getTimeout())
                .shutdownTimeout(Duration.ofMillis(100))
                .poolConfig(poolConfig)
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    @Override
    public synchronized void swap() {
        InfraSettings.Redis cfg = holder.current().getRedis();
        LettuceConnectionFactory fresh = build(cfg, yamlProps);
        LettuceConnectionFactory old = this.delegate;
        this.delegate = fresh;
        log.info("[存储配置] Redis 连接工厂已切换：{}", cfg.describe());
        scheduleDestroy(old);
    }

    private LettuceConnectionFactory current() {
        LettuceConnectionFactory d = this.delegate;
        if (d == null) {
            synchronized (this) {
                if (this.delegate == null) {
                    this.delegate = build(holder.current().getRedis(), yamlProps);
                }
                return this.delegate;
            }
        }
        return d;
    }

    /**
     * 未配置守卫 —— 把"压根没配"和"配了但连不上"在取连接点就分开。
     */
    private void requireConfigured() {
        if (!holder.isRedisConfigured()) {
            throw new NotConfiguredException("Redis 尚未配置，请先在管控台"
                    + "「存储配置」页填写地址并保存（保存后立即生效，无需重启）");
        }
    }

    // ===== RedisConnectionFactory 接口（含继承自 PersistenceExceptionTranslator 的一个） =====

    @Override
    public boolean getConvertPipelineAndTxResults() {
        return current().getConvertPipelineAndTxResults();
    }

    @Override
    public RedisConnection getConnection() {
        requireConfigured();
        return current().getConnection();
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
        requireConfigured();
        return current().getClusterConnection();
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
        requireConfigured();
        return current().getSentinelConnection();
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
        return current().translateExceptionIfPossible(ex);
    }

    /** 容器关闭时销毁当前在用的连接工厂（实现 {@link DisposableBean}，由 Spring 自动回调） */
    @Override
    public void destroy() {
        LettuceConnectionFactory d = this.delegate;
        if (d != null) {
            d.destroy();
        }
    }

    /**
     * 延迟销毁专用调度器：固定单线程 + 守护线程。
     */
    private static final java.util.concurrent.ScheduledExecutorService DELAYED_DESTROY =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "domainplane-conn-destroy");
                t.setDaemon(true);
                return t;
            });

    /** 延迟销毁旧实现：切换瞬间可能仍有在途命令握着旧连接池 */
    private static void scheduleDestroy(LettuceConnectionFactory old) {
        if (old == null) {
            return;
        }
        DELAYED_DESTROY.schedule(() -> {
            try {
                old.destroy();
            } catch (Exception e) {
                log.debug("[存储配置] 销毁旧的 Redis 连接工厂时出错（可忽略）: {}", e.getMessage());
            }
        }, GRACE_SECONDS, TimeUnit.SECONDS);
    }
}
