package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.server.env.SingleInstanceGuard;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 单实例守卫的装配。
 *
 * 与 {@link StartupSelfCheckConfiguration} 里的自检分开：那些是只提醒不阻断的
 * （"配置不对但还能跑"），而本守卫是会拦启动的：它挡的不是配置不当，
 * 而是"多实例共用 Redis 会静默产生错误结果"这种正确性问题。因此它不是普通自检，
 * 也不该跟着 {@code domainplane.single-instance-guard} 之外的自检项一起被误当成提醒。
 *
 * @author zzkingcc
 */
@Configuration
public class SingleInstanceGuardConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuardConfiguration.class);

    /**
     * 租约有效期。
     *
     * 取 90s 是权衡：短到"实例被 kill -9 之后，运维不必等太久就能重启接管"，
     * 长到"一次正常的优雅停机释放失败，也不会立刻把下一次启动挡在门外"。
     */
    private static final Duration LEASE_TTL = Duration.ofSeconds(90);

    @Bean
    public SingleInstanceGuard domainplaneSingleInstanceGuard(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            InfraSettingsHolder infraSettingsHolder,
            @org.springframework.beans.factory.annotation.Value("${domainplane.single-instance-guard:true}")
            boolean guardEnabled) {
        // 开关此前没有任何绑定代码：文档写着可以关，实际只看"Redis 配没配"，
        // 关不掉。这里按文档语义接上，默认开启。
        if (!guardEnabled) {
            log.warn("[单实例守卫] 已按配置 domainplane.single-instance-guard=false 关闭。"
                    + "请先确认 Redis 上真的只有一个 DomainPlane 在跑");
            return new SingleInstanceGuard(stringRedisTemplate, LEASE_TTL, false);
        }
        // Redis 未配置时不存在"两个进程共享会话状态"这个场景，守卫无意义
        if (!infraSettingsHolder.isRedisConfigured()) {
            log.info("[单实例守卫] Redis 未配置，会话能力本就不可用，跳过单实例检查");
            return new SingleInstanceGuard(stringRedisTemplate, LEASE_TTL, false);
        }
        return new SingleInstanceGuard(stringRedisTemplate, LEASE_TTL, true);
    }

    /**
     * 启动时抢占运行权。
     *
     * 必须是 {@link InitializingBean}（启动期回调 {@code afterPropertiesSet}）。
     * 写成 {@code DisposableBean} 会把它变成停机动作：启动期从不抢占（守卫形同不存在，
     * 两个实例共用 Redis 照常起来），而停机时反而去 {@code SET NX} 抢一个没人释放的租约，
     * 把紧随其后的重启挡在门外 TTL 那么久：方向正好相反。
     *
     * 失败会抛异常并中断启动：这是刻意的：多实例的失败方式是不报错的错误结果，
     * 宁可起不来，也不要让两个实例在同一份会话状态上互相覆盖。
     */
    @Bean
    public InitializingBean domainplaneSingleInstanceGuardStartup(SingleInstanceGuard guard) {
        return guard::acquire;
    }
}