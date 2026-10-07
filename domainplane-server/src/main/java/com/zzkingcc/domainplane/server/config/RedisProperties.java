package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis 存储配置（私有，统一前缀 {@code domainplane.redis}）。
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.redis")
public class RedisProperties {

    /** 服务端主机地址（IP 或域名）。为空 = 未配置（可到管控台补填） */
    private String host;

    /** 服务端端口 */
    private int port = 6379;

    /** 密码，未设置则为 null（无密码模式） */
    private String password;

    /**
     * 库号（database index）。
     */
    private int database = 0;

    /**
     * 连接 / 读取超时。
     *
     * 默认值要跟 {@code application.yaml} 里的 {@code domainplane.redis.timeout} 一致：
     * 配公网/跨机房 Redis 时 2s 太紧，一次抖动就会变成 70004 CHECKPOINT_ERROR。
     */
    private Duration timeout = Duration.ofSeconds(5);

    /** Lettuce 连接池配置 */
    private Pool pool = new Pool();

    @Data
    public static class Pool {
        /** 最大活跃连接数 */
        private int maxActive = 8;
        /** 最大空闲连接数 */
        private int maxIdle = 8;
        /** 最小空闲连接数 */
        private int minIdle = 0;
        /** 获取连接最大等待时间（-1 表示阻塞直至可用） */
        private Duration maxWait = Duration.ofMillis(-1);
    }
}
