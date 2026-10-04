package com.zzkingcc.domainplane.server.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
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

        // ObjectMapper 保留类型信息，反序列化时能还原原始对象
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);

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
