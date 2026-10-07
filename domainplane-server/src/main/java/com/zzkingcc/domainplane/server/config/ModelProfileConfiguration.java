package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.server.model.DefaultModelResolver;
import com.zzkingcc.domainplane.server.model.ModelClientFactory;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型档案装配：档案注册表 → 客户端工厂 → 解析器。
 *
 * 解析器以 {@link ModelResolver} 契约暴露给内核（{@code domainplane-runtime}），
 * 内核不感知档案、绑定这些概念。
 *
 * @author zzkingcc
 */
@Configuration
public class ModelProfileConfiguration {

    /**
     * 档案注册表：档案与「域 → 档案」绑定的唯一真相。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelProfileRegistry modelProfileRegistry(ModelProfileStore store) {
        return new ModelProfileRegistry(store);
    }

    /**
     * 客户端工厂：按档案指纹构建并缓存模型客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelClientFactory modelClientFactory() {
        return new ModelClientFactory();
    }

    /**
     * 模型解析器：对话模型只来自用户自建的模型档案，没有内置 default。
     */
    @Bean
    @ConditionalOnMissingBean(ModelResolver.class)
    public ModelResolver modelResolver(ModelProfileRegistry registry,
                                       ModelClientFactory factory) {
        return new DefaultModelResolver(registry, factory);
    }
}
