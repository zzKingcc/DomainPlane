package com.zzkingcc.domainplane.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #12：Redis 的 Jackson 默认类型校验从 {@code LaissezFaireSubTypeValidator} 收紧成白名单。
 *
 * <p>{@code activateDefaultTyping(NON_FINAL)} 会按报文里的 {@code @class} 还原类型。
 * LaissezFaire 的语义是"来者不拒"：共享 Redis 实例、口令泄漏、备份恢复，
 * 任何能往这个 keyspace 写内容的人都能让服务端实例化任意类：反序列化 RCE 面。</p>
 *
 * <p>本测试直接用真实 Bean 造出来的序列化器打两份报文：白名单类型必须能往返，
 * 白名单外的类型（哪怕是 JDK 自带的 {@link File}）必须被拒。</p>
 *
 * @author zzkingcc
 */
@DisplayName("RedisClientConfiguration：默认类型只放行白名单内的类")
class RedisClientPolymorphicTypeTest {

    /** 只造序列化器用，不该真的去连 Redis */
    private static final class StubConnectionFactory implements RedisConnectionFactory {
        @Override
        public RedisConnection getConnection() {
            throw new UnsupportedOperationException("本测试只取序列化器，不连 Redis");
        }

        @Override
        public boolean getConvertPipelineAndTxResults() {
            return true;
        }

        @Override
        public org.springframework.data.redis.connection.RedisClusterConnection getClusterConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.springframework.data.redis.connection.RedisSentinelConnection getSentinelConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.springframework.dao.DataAccessException translateExceptionIfPossible(RuntimeException ex) {
            throw new UnsupportedOperationException();
        }
    }

    private RedisSerializer<Object> valueSerializer() {
        RedisClientConfiguration config = new RedisClientConfiguration();
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> template =
                config.domainplaneRedisTemplate(new StubConnectionFactory());
        return (RedisSerializer<Object>) template.getValueSerializer();
    }

    @Test
    @DisplayName("白名单类型（JDK 集合）必须能正常往返")
    void whitelistedTypeRoundTrips() {
        RedisSerializer<Object> serializer = valueSerializer();
        List<String> original = new ArrayList<>(List.of("a", "b"));

        byte[] bytes = serializer.serialize(original);
        assertTrue(bytes != null && bytes.length > 0, "序列化结果不能为空");

        Object back = serializer.deserialize(bytes);
        assertEquals(original, back, "白名单类型必须能还原");
    }

    @Test
    @DisplayName("白名单外的类型必须被拒：@class 指向 java.io.File 也不放行")
    void nonWhitelistedTypeIsRejected() {
        RedisSerializer<Object> serializer = valueSerializer();

        /* 构造一份"别的写入方"塞进来的报文：类型指向 JDK 自带的 File。
           白名单只放行 com.zzkingcc.domainplane. / java.util. / java.time. / java.lang.，
           java.io.File 不在其中：若校验退化成 LaissezFaire，这里会被成功实例化。 */
        byte[] evil = ("{\"@class\":\"java.io.File\",\"path\":\"/etc/passwd\"}")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> serializer.deserialize(evil),
                "白名单外的类型必须被校验器拒绝，否则 Redis 内容可被用来实例化任意类");
    }

    @Test
    @DisplayName("反序列化目标类型也过白名单：直接点名一个外部类同样被拒")
    void arbitraryExternalTypeIsRejected() {
        RedisSerializer<Object> serializer = valueSerializer();

        byte[] evil = ("[\"com.zzkingcc.domainplane.NotARealClass\",{}]")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> serializer.deserialize(evil));
    }
}
