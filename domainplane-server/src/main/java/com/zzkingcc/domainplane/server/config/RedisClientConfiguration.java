package com.zzkingcc.domainplane.server.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类（内置适配redis）。
 * @author zzkingcc
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisClientConfiguration {

    /**
     * 平台私有 Redis 连接工厂
     */
    @Bean(name = "domainplaneRedisConnectionFactory")
    public RedisConnectionFactory domainplaneRedisConnectionFactory(InfraSettingsHolder infraHolder,
                                                                 RedisProperties props) {
        return new SwappableRedisConnectionFactory(infraHolder, props);
    }

    /**
     * 平台私有通用 RedisTemplate，Value 以 JSON 序列化存储。
     */
    @Bean(name = "domainplaneRedisTemplate")
    public RedisTemplate<String, Object> domainplaneRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("domainplaneRedisConnectionFactory")
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        /* 保留类型信息以便还原对象，但必须限定可反序列化的类型范围：
           LaissezFaire 会按报文里的 @class 无条件实例化任意类：Redis 内容一旦被第三方
           写入（共享实例、口令泄漏、备份恢复），即构成反序列化 RCE 面。
           白名单只放行本项目类与 JDK 基础类型/集合，语义上足够。 */
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.zzkingcc.domainplane.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .build();
        objectMapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL);

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // Key 用 String 序列化，可读性好
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 用 JSON 序列化，保留对象类型
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 平台私有 StringRedisTemplate，专用于纯字符串操作（会话记忆 / 检查点 / 计数器）。
     */
    @Bean(name = "domainplaneStringRedisTemplate")
    public StringRedisTemplate domainplaneStringRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("domainplaneRedisConnectionFactory")
            RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
