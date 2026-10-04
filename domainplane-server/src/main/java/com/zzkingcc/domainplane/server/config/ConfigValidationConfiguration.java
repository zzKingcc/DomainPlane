package com.zzkingcc.domainplane.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务端启动期配置校验。
 * @author zzkingcc
 */
@Configuration
@EnableConfigurationProperties({ElasticsearchProperties.class, RedisProperties.class})
public class ConfigValidationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidationConfiguration.class);

    @Bean
    public String domainplaneConfigValidator(ElasticsearchProperties es, RedisProperties redis) {
        // 启动期刻意不报"未配置"：见类注释。
        warnIfUnresolved("[配置错误] domainplane.elasticsearch.host", es.getHost());
        warnIfUnresolved("[配置错误] domainplane.redis.host", redis.getHost());
        return "domainplane-config-validated";
    }

    private static void warnIfUnresolved(String key, String value) {
        if (value != null && value.trim().startsWith("${")) {
            log.warn("{} 的值为 {}，占位符未被解析（对应的环境变量是否缺失？）。"
                            + "本次启动按【未配置】处理；可在管控台直接填写并保存，"
                            + "或补齐环境变量后重启。", key, value);
        }
    }
}
