package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.server.env.RedisPersistenceAudit;
import com.zzkingcc.domainplane.server.prompt.PromptToolConsistencyAudit;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 启动期自检：专门挑"声明与事实不一致、但不会报错"的那类问题。
 *
 * 为什么单独一个配置类：这类检查的共同点是只提醒、不阻断，且要在所有单例就绪后
 * （{@link SmartInitializingSingleton}）才能看到全貌：工具扫描完了、落盘设置读到了，
 * 才能判断"提示词说的"和"注册表里的事实"是否一致。后续再加自检项（如 skill 声明的工具是否存在）
 * 都挂在这里，不往业务配置里塞。
 *
 * @author zzkingcc
 */
@Configuration
public class StartupSelfCheckConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StartupSelfCheckConfiguration.class);

    /**
     * 提示词 ↔ 工具可见性一致性自检。
     *
     * 挡的是"提示词点名了某工具、但它在当前域不可见"：模型被告知有能力却调不到，
     * 且这类问题不抛异常、HTTP 仍 200，人工很难发现。
     */
    @Bean
    public SmartInitializingSingleton domainplanePromptToolConsistencyAudit(ToolRouter toolRouter,
                                                                        DomainSettingsStore domainSettingsStore,
                                                                        PromptProperties promptProperties) {
        log.debug("[启动自检] 已注册：提示词 ↔ 工具可见性一致性检查");
        return () -> new PromptToolConsistencyAudit(toolRouter, domainSettingsStore, promptProperties).audit();
    }

    /**
     * Redis 持久化 ↔ 淘汰策略自检。
     *
     * 挡的是"会话记忆是长期存储、但 Redis 根本没开持久化或用了会淘汰 key 的策略"：
     * 这两种情况都不报错，只会在某天重启或内存紧张时静默丢用户历史。
     */
    @Bean
    public SmartInitializingSingleton domainplaneRedisPersistenceAudit(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            InfraSettingsHolder infraSettingsHolder) {
        log.debug("[启动自检] 已注册：Redis 持久化 ↔ 淘汰策略检查");
        return () -> new RedisPersistenceAudit(stringRedisTemplate, infraSettingsHolder).audit();
    }
}
