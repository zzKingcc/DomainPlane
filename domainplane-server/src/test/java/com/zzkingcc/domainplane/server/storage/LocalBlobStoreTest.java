package com.zzkingcc.domainplane.server.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 blob 存储的五条口径：读写往返、键的合法性（目录穿越）、列举前缀、
 * 临时文件清理、修改时间。
 *
 * <p>穿越防护按真实攻击面验：键是接口入参，调用方（引用名由内容哈希派生）本无恶意，
 * 但接口是通用的——漏一层校验，将来某个新调用方就是事故。</p>
 *
 * @author zzkingcc
 */
@DisplayName("本地 blob 存储：读写 / 键合法性 / 列举 / 临时文件")
class LocalBlobStoreTest {

    @TempDir
    Path tempDir;

    private LocalBlobStore store;

    @BeforeEach
    void setUp() {
        // 根目录故意不存在：写入路径要能自己建
        store = new LocalBlobStore(tempDir.resolve("blobs"));
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写入后原样读回；不存在时读/删都不炸")
    void putOpenRoundTrip() throws IOException {
        store.put("attachments/img_abc", stream("hello-blob"));

        assertTrue(store.exists("attachments/img_abc"));
        try (InputStream in = store.open("attachments/img_abc").orElseThrow()) {
            assertEquals("hello-blob", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertTrue(store.open("attachments/none").isEmpty());
        assertFalse(store.exists("attachments/none"));
        store.delete("attachments/none");
    }

    @Test
    @DisplayName("同键重复写是覆盖语义（幂等）")
    void overwriteReplaces() throws IOException {
        store.put("attachments/img_abc", stream("first"));
        store.put("attachments/img_abc", stream("second"));

        try (InputStream in = store.open("attachments/img_abc").orElseThrow()) {
            assertEquals("second", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(1, store.listKeys("attachments/").size(), "覆盖不该多留一份");
    }

    @Test
    @DisplayName("删除幂等：删过的不再存在，删不存在的键不报错")
    void deleteIsIdempotent() {
        store.put("attachments/img_abc", stream("x"));
        store.delete("attachments/img_abc");

        assertFalse(store.exists("attachments/img_abc"));
        store.delete("attachments/img_abc");
        store.delete("attachments/never-existed");
    }

    @Test
    @DisplayName("写入自动建多级目录")
    void putCreatesNestedDirs() {
        store.put("attachments/img/deep/img_abc", stream("x"));
        assertTrue(store.exists("attachments/img/deep/img_abc"));
    }

    @Test
    @DisplayName("非法键一律拒：.. / 绝对路径 / 空段 / 空串 / null，且不留下任何文件")
    void rejectsIllegalKeys() {
        List<Runnable> puts = List.of(
                () -> store.put("../escape", stream("x")),
                () -> store.put("a/../../escape", stream("x")),
                () -> store.put("/abs/path", stream("x")),
                () -> store.put("attachments//img_abc", stream("x")),
                () -> store.put("attachments/./img_abc", stream("x")),
                () -> store.put("", stream("x")),
                () -> store.put("   ", stream("x")),
                () -> store.put(null, stream("x")));
        for (Runnable put : puts) {
            assertThrows(IllegalArgumentException.class, put::run);
        }
        // 读侧、删侧、列举侧同一条防线
        assertThrows(IllegalArgumentException.class, () -> store.open("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.exists("..\\escape"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.listKeys("../"));
        assertThrows(IllegalArgumentException.class, () -> store.lastModified("../escape"));

        assertEquals(List.of(), store.listKeys(""), "非法写入不该在磁盘上留任何东西");
    }

    @Test
    @DisplayName("列举按前缀过滤且有序；空前缀=全部；根目录不存在时返回空")
    void listKeysFiltersByPrefix() {
        store.put("attachments/img_2", stream("x"));
        store.put("attachments/img_1", stream("x"));
        store.put("docs/doc_1", stream("x"));

        assertEquals(List.of("attachments/img_1", "attachments/img_2"), store.listKeys("attachments/"));
        assertEquals(List.of("docs/doc_1"), store.listKeys("docs/"));
        assertEquals(List.of("attachments/img_1", "attachments/img_2", "docs/doc_1"), store.listKeys(""));
        assertEquals(List.of(), store.listKeys("attachments/img_1/"));

        LocalBlobStore missing = new LocalBlobStore(tempDir.resolve("no-such-dir"));
        assertEquals(List.of(), missing.listKeys("attachments/"));
    }

    @Test
    @DisplayName("写失败时临时文件被清掉：半截文件不该留在目录里冒充对象")
    void tmpFileCleanedUpWhenWriteFails() throws IOException {
        // 流一读就炸：临时文件已创建、内容没写全，清理逻辑必须把它删掉
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("磁盘满了");
            }
        };

        assertThrows(UncheckedIOException.class,
                () -> store.put("attachments/img_abc", failing));

        try (Stream<Path> walk = Files.walk(tempDir.resolve("blobs"))) {
            List<String> leftovers = walk
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.contains(".tmp-"))
                    .toList();
            assertEquals(List.of(), leftovers, "失败的写入不该留下临时文件");
        }
    }

    @Test
    @DisplayName("修改时间：写过的能取到，没写过的取空")
    void lastModified() {
        store.put("attachments/img_abc", stream("x"));
        assertTrue(store.lastModified("attachments/img_abc").isPresent());
        assertTrue(store.lastModified("attachments/none").isEmpty());
    }
}
