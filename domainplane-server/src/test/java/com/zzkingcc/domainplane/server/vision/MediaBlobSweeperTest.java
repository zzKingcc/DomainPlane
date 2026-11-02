package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 孤儿 blob 回收的四条口径：无主才删、活着的留、在写的不碰（竞态护栏）、
 * 失败不扩散。再加一条：别的用途前缀（如后续的文档原文）不在扫描范围内。
 *
 * @author zzkingcc
 */
@DisplayName("孤儿 blob 回收：无主才删 / 竞态护栏 / 失败不扩散")
class MediaBlobSweeperTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @TempDir
    Path tempDir;

    /** 内存版 StringRedisTemplate：只桩 opsForValue / expire / hasKey */
    private static class StubRedis extends StringRedisTemplate {

        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, Duration> ttls = new LinkedHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    StubRedis.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
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
            if (!values.containsKey(key)) {
                return Boolean.FALSE;
            }
            ttls.put(key, timeout);
            return Boolean.TRUE;
        }

        @Override
        public Boolean hasKey(String key) {
            return values.containsKey(key);
        }
    }

    /** 内存版 BlobStore：时间可被测试改写，用来造"老的孤儿"与"刚写的在写对象" */
    private static class InMemoryBlobStore implements BlobStore {

        private final Map<String, byte[]> data = new LinkedHashMap<>();
        private final Map<String, Instant> times = new LinkedHashMap<>();
        private final boolean failDelete;
        private final boolean failList;

        InMemoryBlobStore() {
            this(false, false);
        }

        InMemoryBlobStore(boolean failDelete, boolean failList) {
            this.failDelete = failDelete;
            this.failList = failList;
        }

        @Override
        public void put(String key, InputStream data) {
            try {
                this.data.put(key, data.readAllBytes());
                this.times.put(key, Instant.now());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Optional<InputStream> open(String key) {
            return data.containsKey(key)
                    ? Optional.of(new ByteArrayInputStream(data.get(key)))
                    : Optional.empty();
        }

        @Override
        public void delete(String key) {
            if (failDelete) {
                throw new RuntimeException("删不动");
            }
            data.remove(key);
            times.remove(key);
        }

        @Override
        public boolean exists(String key) {
            return data.containsKey(key);
        }

        @Override
        public List<String> listKeys(String prefix) {
            if (failList) {
                throw new RuntimeException("列举失败");
            }
            return data.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
        }

        @Override
        public Optional<Instant> lastModified(String key) {
            return Optional.ofNullable(times.get(key));
        }
    }

    private StubRedis redis;
    private InMemoryBlobStore blobStore;
    private BlobMediaAttachmentStore store;
    private MediaBlobSweeper sweeper;

    @BeforeEach
    void setUp() {
        redis = new StubRedis();
        blobStore = new InMemoryBlobStore();
        store = new BlobMediaAttachmentStore(redis, TTL, blobStore);
        sweeper = new MediaBlobSweeper(store, blobStore, 5);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private String liveAttachment() {
        return store.put(Kind.IMAGE, "image/png", "QUJD", null);
    }

    /** 造一个"登记表已过期"的老 blob（无主，且年龄远超护栏线） */
    private String orphanBlob(String attachmentId) {
        String blobKey = MediaIds.blobKeyOf(attachmentId);
        blobStore.put(blobKey, stream("orphan"));
        blobStore.times.put(blobKey, Instant.now().minus(Duration.ofHours(1)));
        return blobKey;
    }

    @Test
    @DisplayName("无主的删、活着的留；报告计数对得上")
    void reclaimsOrphanKeepsLive() {
        String liveId = liveAttachment();
        String orphanKey = orphanBlob("img_0000000000000000");

        MediaBlobSweeper.Report report = sweeper.sweepOrphanBlobs();

        assertEquals(2, report.scanned());
        assertEquals(1, report.reclaimed());
        assertEquals(0, report.failed());
        assertEquals(0, report.skippedYoung());
        assertFalse(blobStore.exists(orphanKey), "无主 blob 必须回收");
        assertTrue(blobStore.exists(MediaIds.blobKeyOf(liveId)), "活着的附件一个都不能碰");
    }

    @Test
    @DisplayName("竞态护栏：刚写的无主对象（先落字节后登记）本轮不删")
    void skipsYoungBlobs() {
        // 刚 put 完就把登记表删掉：模拟扫描撞进"字节已落、登记未写"的窗口
        String blobKey = MediaIds.blobKeyOf("img_0000000000000001");
        blobStore.put(blobKey, stream("in-flight"));

        MediaBlobSweeper.Report report = sweeper.sweepOrphanBlobs();

        assertEquals(1, report.skippedYoung());
        assertEquals(0, report.reclaimed());
        assertTrue(blobStore.exists(blobKey), "年龄小于护栏线的对象不许删");
    }

    @Test
    @DisplayName("护栏关掉（min-age=0）时年轻的无主对象也回收：配置显式要求就不猜")
    void minAgeZeroDisablesGuard() {
        MediaBlobSweeper noGuard = new MediaBlobSweeper(store, blobStore, 0);
        String blobKey = MediaIds.blobKeyOf("img_0000000000000002");
        blobStore.put(blobKey, stream("in-flight"));

        MediaBlobSweeper.Report report = noGuard.sweepOrphanBlobs();

        assertEquals(1, report.reclaimed());
        assertEquals(0, report.skippedYoung());
        assertFalse(blobStore.exists(blobKey));
    }

    @Test
    @DisplayName("删不动的只计数不抛：一个坏对象不该挡住后面所有孤儿")
    void deleteFailureCountedNotThrown() {
        InMemoryBlobStore flaky = new InMemoryBlobStore(true, false);
        BlobMediaAttachmentStore flakyStore = new BlobMediaAttachmentStore(redis, TTL, flaky);
        MediaBlobSweeper flakySweeper = new MediaBlobSweeper(flakyStore, flaky, 5);

        String liveId = flakyStore.put(Kind.IMAGE, "image/png", "QUJD", null);
        String orphanKey = MediaIds.blobKeyOf("img_0000000000000003");
        flaky.put(orphanKey, stream("orphan"));
        flaky.times.put(orphanKey, Instant.now().minus(Duration.ofHours(1)));

        MediaBlobSweeper.Report report = flakySweeper.sweepOrphanBlobs();

        assertEquals(1, report.failed());
        assertEquals(0, report.reclaimed());
        assertTrue(flaky.exists(orphanKey), "删不掉的下轮重试，本轮不动它");
        assertTrue(flaky.exists(MediaIds.blobKeyOf(liveId)));
    }

    @Test
    @DisplayName("列举失败：本轮整体跳过，不当成零个对象 silently 成功")
    void listFailureDoesNotThrow() {
        InMemoryBlobStore broken = new InMemoryBlobStore(false, true);
        MediaBlobSweeper brokenSweeper =
                new MediaBlobSweeper(new BlobMediaAttachmentStore(redis, TTL, broken), broken, 5);

        MediaBlobSweeper.Report report = brokenSweeper.sweepOrphanBlobs();

        assertEquals(0, report.scanned());
        assertEquals(1, report.failed(), "列举失败要计数，否则静默跳轮没人发现");
    }

    @Test
    @DisplayName("只扫 attachments/ 一族：别的用途前缀（后续文档原文）不在范围内")
    void ignoresForeignPrefixes() {
        blobStore.put("docs/doc_1", stream("document-original"));
        blobStore.times.put("docs/doc_1", Instant.now().minus(Duration.ofHours(1)));

        MediaBlobSweeper.Report report = sweeper.sweepOrphanBlobs();

        assertEquals(0, report.scanned(), "前缀之外的键连检查都不做");
        assertTrue(blobStore.exists("docs/doc_1"), "别人的东西不能碰");
    }

    @Test
    @DisplayName("有主的对象哪怕年龄再老也不动（登记表是唯一判据）")
    void liveObjectsNeverTouchedRegardlessOfAge() {
        String liveId = liveAttachment();
        // 把活附件的年龄改老：年龄只服务竞态护栏，不是判据
        blobStore.times.put(MediaIds.blobKeyOf(liveId), Instant.now().minus(Duration.ofHours(5)));

        MediaBlobSweeper.Report report = sweeper.sweepOrphanBlobs();

        assertEquals(1, report.scanned());
        assertEquals(0, report.reclaimed());
        assertTrue(blobStore.exists(MediaIds.blobKeyOf(liveId)));
    }

    @Test
    @DisplayName("真本地存储 + 真 Redis 桩的端到端：回收后磁盘上确实没有了")
    void endToEndWithRealLocalBlobStore() throws IOException {
        LocalBlobStore real = new LocalBlobStore(tempDir.resolve("blobs"));
        BlobMediaAttachmentStore realStore = new BlobMediaAttachmentStore(redis, TTL, real);
        MediaBlobSweeper realSweeper = new MediaBlobSweeper(realStore, real, 5);

        String liveId = realStore.put(Kind.IMAGE, "image/png", "QUJD", null);
        String orphanKey = MediaIds.blobKeyOf("img_0000000000000005");
        real.put(orphanKey, stream("orphan"));
        // 把年龄改老：本地实现的护栏读的是文件 mtime
        Files.setLastModifiedTime(
                tempDir.resolve("blobs").resolve(orphanKey),
                FileTime.from(Instant.now().minus(Duration.ofHours(1))));

        MediaBlobSweeper.Report report = realSweeper.sweepOrphanBlobs();

        assertEquals(1, report.reclaimed());
        assertTrue(Files.exists(tempDir.resolve("blobs").resolve("attachments").resolve(liveId)));
        assertFalse(Files.exists(tempDir.resolve("blobs").resolve(orphanKey)));
    }
}
