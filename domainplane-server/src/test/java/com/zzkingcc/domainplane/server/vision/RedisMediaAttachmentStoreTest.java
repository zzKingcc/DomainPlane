package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.StoredAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体暂存区（Redis 实现）的四条口径：引用名、媒体类型、TTL、续期。
 *
 * <p>Redis 侧用内存桩，但<b>键前缀</b>与<b>payload 里存 kind</b> 这两件事必须按真实行为验：
 * 前者决定排障时能不能按命名空间找到东西；后者是"这份内容能不能进视觉模型"的唯一判据，
 * 靠引用名前缀反推等于把一处判据写成两处（写侧拼前缀、读侧切前缀），任一处改动都会静默错位。</p>
 *
 * @author zzkingcc
 */
@DisplayName("媒体暂存区：引用名 / 媒体类型 / TTL / 续期")
class RedisMediaAttachmentStoreTest {

    private static final String KEY_PREFIX = "domainplane:media:attachment:";
    private static final Duration TTL = Duration.ofMinutes(30);

    /** 内存版 StringRedisTemplate：只桩 opsForValue 与 expire */
    private static class StubRedis extends StringRedisTemplate {

        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, Duration> ttls = new LinkedHashMap<>();
        boolean failWrite;
        boolean failExpire;

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    StubRedis.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            if (failWrite) {
                                throw new IllegalStateException("连接被重置");
                            }
                            values.put((String) args[0], (String) args[1]);
                            ttls.put((String) args[0], (Duration) args[2]);
                            yield null;
                        }
                        case "get" -> values.get((String) args[0]);
                        case "toString" -> "stub-value-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 ValueOperations 方法：" + method.getName());
                    });
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            if (failExpire) {
                throw new IllegalStateException("连接被重置");
            }
            if (!values.containsKey(key)) {
                return Boolean.FALSE;
            }
            ttls.put(key, timeout);
            return Boolean.TRUE;
        }
    }

    private static RedisMediaAttachmentStore store(StubRedis redis) {
        return new RedisMediaAttachmentStore(redis, TTL);
    }

    // ==================== 引用名 ====================

    @Test
    @DisplayName("同一份内容重复暂存只有一个键（引用名按内容哈希派生）")
    void putIsContentAddressedAndDeduped() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);

        String first = store.put(Kind.IMAGE, "image/png", "AAAA", null);
        String second = store.put(Kind.IMAGE, "image/png", "AAAA", null);

        assertEquals(first, second, "同一份内容必须得到同一个引用名");
        assertEquals(1, redis.values.size(), "重复上行不该多占一个键");
    }

    @Test
    @DisplayName("引用名前缀按媒体类型分：img_ / aud_ / vid_")
    void idPrefixCarriesKind() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);

        assertTrue(store.put(Kind.IMAGE, "image/png", "AAAA", null).startsWith("img_"));
        assertTrue(store.put(Kind.AUDIO, "audio/wav", "BBBB", null).startsWith("aud_"));
        assertTrue(store.put(Kind.VIDEO, null, null, "https://example.com/v.mp4").startsWith("vid_"),
                "视频允许 URL 形态（音频 URL 在入口就被拒）");
    }

    @Test
    @DisplayName("键落在 media 命名空间下：排障时按前缀就能找到这一族")
    void keysLiveUnderMediaNamespace() {
        StubRedis redis = new StubRedis();
        String id = store(redis).put(Kind.AUDIO, "audio/wav", "BBBB", null);

        assertEquals(List.of(KEY_PREFIX + id), List.copyOf(redis.values.keySet()));
    }

    // ==================== 媒体类型往返 ====================

    @Test
    @DisplayName("音视频的类型能原样取回（取回侧要靠它判「能不能进视觉模型」）")
    void kindRoundTrips() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);

        String audioId = store.put(Kind.AUDIO, "audio/wav", "BBBB", null);
        StoredAttachment audio = store.get(audioId).orElseThrow();
        assertEquals(Kind.AUDIO, audio.kind());
        assertEquals("audio/wav", audio.mimeType());
        assertEquals("BBBB", audio.base64());
        assertFalse(audio.isImage(), "音频绝不能进 seeImage");

        String videoId = store.put(Kind.VIDEO, null, null, "https://example.com/v.mp4");
        StoredAttachment video = store.get(videoId).orElseThrow();
        assertEquals(Kind.VIDEO, video.kind());
        assertTrue(video.hasUrl());
        assertFalse(video.isImage());
    }

    @Test
    @DisplayName("payload 里没记 kind 的老数据按图片处理（不把一份正常图片拒之门外）")
    void missingKindFallsBackToImage() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);
        String id = store.put(Kind.IMAGE, "image/png", "AAAA", null);
        // 模拟更早的写入方：payload 里只有 mime 与字节
        redis.values.put(KEY_PREFIX + id, "{\"mime\":\"image/png\",\"b64\":\"AAAA\"}");

        assertTrue(store.get(id).orElseThrow().isImage());
    }

    // ==================== TTL ====================

    @Test
    @DisplayName("暂存即带完整 TTL；过期与不存在都归「取不到」一种处置")
    void putAppliesTtlAndExpiryMeansGone() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);
        String id = store.put(Kind.IMAGE, "image/png", "AAAA", null);

        assertEquals(TTL, redis.ttls.get(KEY_PREFIX + id), "TTL 是清理机制本身，不能省");

        redis.values.remove(KEY_PREFIX + id);
        assertTrue(store.get(id).isEmpty());
        assertTrue(store.get(null).isEmpty());
        assertTrue(store.get("  ").isEmpty());
    }

    @Test
    @DisplayName("既没字节也没地址 / 没类型：入参错误，直接拒")
    void putRejectsEmptyPayload() {
        RedisMediaAttachmentStore store = store(new StubRedis());

        assertThrows(IllegalArgumentException.class,
                () -> store.put(Kind.IMAGE, "image/png", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.put(null, "image/png", "AAAA", null));
    }

    @Test
    @DisplayName("写失败必须抛：不能让模型拿到一个永远取不到的引用名")
    void putFailurePropagates() {
        StubRedis redis = new StubRedis();
        redis.failWrite = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store(redis).put(Kind.AUDIO, "audio/wav", "BBBB", null));
        assertTrue(e.getMessage().contains("音频"), "文案要说清是哪一类媒体：" + e.getMessage());
    }

    // ==================== 续期 ====================

    @Test
    @DisplayName("续期：把还在的键重置为完整 TTL")
    void touchRenewsExistingKeys() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);
        String imageId = store.put(Kind.IMAGE, "image/png", "AAAA", null);
        String audioId = store.put(Kind.AUDIO, "audio/wav", "BBBB", null);
        redis.ttls.clear();

        store.touch(List.of(imageId, audioId));

        assertEquals(TTL, redis.ttls.get(KEY_PREFIX + imageId));
        assertEquals(TTL, redis.ttls.get(KEY_PREFIX + audioId), "音视频与图片同一条续期口径");
    }

    @Test
    @DisplayName("续期：键已不存在不是错误，含空串 / null 也不炸")
    void touchIgnoresMissingKeysAndBlankInput() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);
        String id = store.put(Kind.IMAGE, "image/png", "AAAA", null);

        store.touch(Arrays.asList("img_never_existed", id, "", null));
        store.touch(null);
        store.touch(List.of());

        assertEquals(TTL, redis.ttls.get(KEY_PREFIX + id), "混着一个不存在的键也要把在的那个续上");
    }

    @Test
    @DisplayName("续期失败只告警、不抛：它不该拖垮一轮正常对话")
    void touchNeverThrows() {
        StubRedis redis = new StubRedis();
        RedisMediaAttachmentStore store = store(redis);
        String id = store.put(Kind.IMAGE, "image/png", "AAAA", null);
        redis.failExpire = true;

        store.touch(List.of(id));
    }
}
