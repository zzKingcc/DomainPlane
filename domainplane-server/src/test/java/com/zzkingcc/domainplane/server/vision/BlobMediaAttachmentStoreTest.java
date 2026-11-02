package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.StoredAttachment;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记表 + 字节分离后的媒体暂存区：新格式（blob 键在登记表、字节在 blob 存储）、
 * 老格式存量回退读、续期口径不变、失败序（先字节后登记）。
 *
 * <p>Redis 侧用内存桩，但<b>键前缀</b>、<b>payload 里记 blob 键不记字节</b>、
 * <b>老格式结构</b>这三件事必须按真实行为验——前者决定排障时能不能按命名空间
 * 找到东西，中间是"字节确实搬出了 Redis"的唯一证据，后者决定升级后存量还能不能读。</p>
 *
 * @author zzkingcc
 */
@DisplayName("媒体暂存区：登记表 + blob 字节 / 存量回退 / 续期 / 失败序")
class BlobMediaAttachmentStoreTest {

    private static final String KEY_PREFIX = "domainplane:media:attachment:";
    private static final Duration TTL = Duration.ofMinutes(30);

    @TempDir
    Path tempDir;

    /** 内存版 StringRedisTemplate：桩 opsForValue / expire / hasKey */
    private static class StubRedis extends StringRedisTemplate {

        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, Duration> ttls = new LinkedHashMap<>();
        boolean failWrite;
        boolean failExpire;
        boolean failHasKey;

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

        @Override
        public Boolean hasKey(String key) {
            if (failHasKey) {
                throw new IllegalStateException("连接被重置");
            }
            return values.containsKey(key);
        }
    }

    private StubRedis redis;
    private LocalBlobStore blobStore;
    private BlobMediaAttachmentStore store;

    @BeforeEach
    void setUp() {
        redis = new StubRedis();
        blobStore = new LocalBlobStore(tempDir.resolve("blobs"));
        store = new BlobMediaAttachmentStore(redis, TTL, blobStore);
    }

    private static String keyOf(String attachmentId) {
        return KEY_PREFIX + attachmentId;
    }

    // ==================== 新格式：字节在 blob，登记表只记键 ====================

    @Test
    @DisplayName("暂存：字节进 blob 存储，登记表只记 blob 键（Redis 里不再有大 value）")
    void putSplitsRegistryAndBytes() {
        String id = store.put(Kind.IMAGE, "image/png", "QUJD", null);

        assertTrue(id.startsWith("img_"));
        String raw = redis.values.get(keyOf(id));
        assertTrue(raw.contains("\"blob\":\"attachments/" + id + "\""), "登记表要记 blob 键：" + raw);
        assertFalse(raw.contains("QUJD"), "字节不该进登记表：" + raw);
        assertTrue(blobStore.exists("attachments/" + id), "字节要落在 blob 存储里");
    }

    @Test
    @DisplayName("取回：从 blob 读字节、编码回 base64，类型与 mime 原样往返")
    void getReadsBytesBackFromBlob() {
        String id = store.put(Kind.IMAGE, "image/png", "QUJD", null);

        StoredAttachment got = store.get(id).orElseThrow();
        assertEquals("QUJD", got.base64());
        assertEquals(Kind.IMAGE, got.kind());
        assertEquals("image/png", got.mimeType());
        assertTrue(got.isImage());
    }

    @Test
    @DisplayName("URL 引用不落字节：登记表记地址，取回给地址")
    void urlReferenceNeedsNoBlob() {
        String id = store.put(Kind.VIDEO, "video/mp4", null, "https://example.com/v.mp4");

        assertEquals(List.of(), blobStore.listKeys("attachments/"), "URL 引用不该落字节");
        StoredAttachment got = store.get(id).orElseThrow();
        assertTrue(got.hasUrl());
        assertEquals("https://example.com/v.mp4", got.url());
        assertEquals(Kind.VIDEO, got.kind());
        assertFalse(got.isImage());
    }

    @Test
    @DisplayName("同一份内容重复暂存：同一个引用名、一个 blob")
    void sameContentIsOneBlob() {
        String first = store.put(Kind.IMAGE, "image/png", "QUJD", null);
        String second = store.put(Kind.IMAGE, "image/png", "QUJD", null);

        assertEquals(first, second, "同一份内容必须得到同一个引用名");
        assertEquals(1, blobStore.listKeys("attachments/").size());
        assertEquals(1, redis.values.size(), "登记表也只有一个键");
    }

    // ==================== 老格式存量回退 ====================

    @Test
    @DisplayName("升级前存量（字节内联在登记表里）照常能读")
    void legacyInlineEntryStillReadable() {
        String id = "img_0000000000000000";
        redis.values.put(keyOf(id), "{\"kind\":\"audio\",\"mime\":\"audio/wav\",\"b64\":\"QUJD\"}");

        StoredAttachment got = store.get(id).orElseThrow();
        assertEquals(Kind.AUDIO, got.kind());
        assertEquals("audio/wav", got.mimeType());
        assertEquals("QUJD", got.base64());
    }

    @Test
    @DisplayName("存量里没记 kind 的老数据按图片处理（不把一份正常图片拒之门外）")
    void legacyEntryWithoutKindFallsBackToImage() {
        String id = "img_0000000000000001";
        redis.values.put(keyOf(id), "{\"mime\":\"image/png\",\"b64\":\"QUJD\"}");

        assertTrue(store.get(id).orElseThrow().isImage());
    }

    @Test
    @DisplayName("老格式存量也在续期口径内（键空间没变，EXPIRE 一视同仁）")
    void touchAlsoRenewsLegacyEntries() {
        String id = "img_0000000000000002";
        redis.values.put(keyOf(id), "{\"kind\":\"image\",\"mime\":\"image/png\",\"b64\":\"QUJD\"}");
        redis.ttls.clear();

        store.touch(List.of(id));

        assertEquals(TTL, redis.ttls.get(keyOf(id)));
    }

    // ==================== 取不到的各种面貌 ====================

    @Test
    @DisplayName("没存过 / 已过期 / 空入参：都归「取不到」一种处置")
    void getMissesReturnEmpty() {
        assertTrue(store.get("img_never_existed").isEmpty());
        assertTrue(store.get(null).isEmpty());
        assertTrue(store.get("  ").isEmpty());

        String id = store.put(Kind.IMAGE, "image/png", "QUJD", null);
        redis.values.remove(keyOf(id));   // 模拟登记表过期
        assertTrue(store.get(id).isEmpty());
    }

    @Test
    @DisplayName("登记表活着但字节没了：按取不到处置并告警，不抛（字节已被误清，对话不该陪葬）")
    void registryAliveButBlobGoneReturnsEmpty() {
        String id = store.put(Kind.IMAGE, "image/png", "QUJD", null);
        blobStore.delete("attachments/" + id);

        assertTrue(store.get(id).isEmpty());
    }

    @Test
    @DisplayName("登记表内容损坏：如实抛——平台的问题与「用户重发」要能区分")
    void corruptPayloadThrows() {
        String id = "img_0000000000000003";
        redis.values.put(keyOf(id), "not-json-at-all");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.get(id));
        assertTrue(e.getMessage().contains("损坏"), e.getMessage());
    }

    // ==================== 写入失败序 ====================

    @Test
    @DisplayName("入参错误直接拒：没类型 / 没字节也没地址")
    void putRejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> store.put(Kind.IMAGE, "image/png", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.put(null, "image/png", "QUJD", null));
    }

    @Test
    @DisplayName("字节写失败：登记表不许有——否则模型拿到永远取不到的引用名")
    void blobWriteFailureLeavesNoRegistry() {
        BlobStore broken = new BlobStore() {
            @Override
            public void put(String key, InputStream data) {
                throw new RuntimeException("磁盘满了");
            }

            @Override
            public Optional<InputStream> open(String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean exists(String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> listKeys(String prefix) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Instant> lastModified(String key) {
                throw new UnsupportedOperationException();
            }
        };
        BlobMediaAttachmentStore brokenStore = new BlobMediaAttachmentStore(redis, TTL, broken);

        assertThrows(IllegalStateException.class,
                () -> brokenStore.put(Kind.IMAGE, "image/png", "QUJD", null));
        assertTrue(redis.values.isEmpty(), "字节没落上，登记表就不该有");
    }

    @Test
    @DisplayName("登记表写失败：抛（字节已成无主 blob，回收扫描的职责）")
    void registryWriteFailureThrows() {
        redis.failWrite = true;

        assertThrows(IllegalStateException.class,
                () -> store.put(Kind.IMAGE, "image/png", "QUJD", null));
        assertTrue(blobStore.exists("attachments/" + MediaIds.derive(Kind.IMAGE, "QUJD")),
                "字节已落盘成为无主 blob——由回收扫描清掉，不静默吞异常");
    }

    // ==================== 续期 ====================

    @Test
    @DisplayName("续期：新格式键重置为完整 TTL")
    void touchRenewsRegistryTtl() {
        String imageId = store.put(Kind.IMAGE, "image/png", "QUJD", null);
        String audioId = store.put(Kind.AUDIO, "audio/wav", "QkFE", null);
        redis.ttls.clear();

        store.touch(List.of(imageId, audioId));

        assertEquals(TTL, redis.ttls.get(keyOf(imageId)));
        assertEquals(TTL, redis.ttls.get(keyOf(audioId)), "音视频与图片同一条续期口径");
    }

    @Test
    @DisplayName("续期：键不存在不是错误，空串 / null 混在里面也不炸")
    void touchIgnoresMissingKeysAndBlankInput() {
        String id = store.put(Kind.IMAGE, "image/png", "QUJD", null);

        store.touch(Arrays.asList("img_never_existed", id, "", null));
        store.touch(null);
        store.touch(List.of());

        assertEquals(TTL, redis.ttls.get(keyOf(id)), "混着一个不存在的键也要把在的那个续上");
    }

    @Test
    @DisplayName("续期失败只告警不抛：它不该拖垮一轮正常对话")
    void touchNeverThrows() {
        store.put(Kind.IMAGE, "image/png", "QUJD", null);
        redis.failExpire = true;

        store.touch(List.of(MediaIds.derive(Kind.IMAGE, "QUJD")));
    }

    // ==================== 登记表查询（回收对账用） ====================

    @Test
    @DisplayName("registered：活着 true，不在 false；Redis 抖动时保守答 true（本轮不删）")
    void registeredIsConservativeOnFailure() {
        String id = store.put(Kind.IMAGE, "image/png", "QUJD", null);

        assertTrue(store.registered(id));
        assertFalse(store.registered("img_never_existed"));
        assertFalse(store.registered(null));
        assertFalse(store.registered("  "));

        redis.failHasKey = true;
        assertTrue(store.registered("img_never_existed"), "查不动时按活着处理：误删救不回来，晚一轮回收可以");
    }

    @Test
    @DisplayName("类型前缀按媒体分：img_ / aud_ / vid_")
    void idPrefixCarriesKind() {
        assertTrue(store.put(Kind.IMAGE, "image/png", "AAAA", null).startsWith("img_"));
        assertTrue(store.put(Kind.AUDIO, "audio/wav", "BBBB", null).startsWith("aud_"));
        assertTrue(store.put(Kind.VIDEO, null, null, "https://example.com/v.mp4").startsWith("vid_"));
    }

    @Test
    @DisplayName("字节内容与 base64 文本逐字节一致（不做编解码往返，少一处能错的地方）")
    void blobBytesAreVerbatimBase64() throws Exception {
        String id = store.put(Kind.IMAGE, "image/png", "QUJD", null);
        try (InputStream in = blobStore.open("attachments/" + id).orElseThrow()) {
            assertEquals("QUJD", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
