package com.zzkingcc.domainplane.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原文档案：索引是它重建出来的派生数据，所以它的读写口径就是"换后端/换模型"的底气。
 *
 * <p>钉的行为：① 写入剥离向量（原文权威源不存注定作废的复印件）；② 读不全立即抛
 * （对账与重灌都靠它，"读一半往下走"等于静默丢文档）；③ 键布局
 * {@code kb/chunks/{index}/{docId}.json} 是删域整域清除与 inventory 解析的共同契约；
 * ④ 写失败只告警不抛（上传主流程已成功，缺档有对账兜底）——但必须留痕。</p>
 *
 * @author zzkingcc
 */
@DisplayName("原文档案：剥离向量 / 读不全即抛 / 键布局 / 写失败不背摔上传")
class ChunkArchiveTest {

    /** 与生产同源的索引名（v2：索引由知识体唯一名派生，创建时冻结） */
    private static final String INDEX =
            com.zzkingcc.domainplane.api.support.KbIndexes.nameOfBody("sales", "kb-sales");

    @TempDir
    Path tmp;

    LocalBlobStore blob;
    ChunkArchive archive;

    @BeforeEach
    void setUp() {
        blob = new LocalBlobStore(tmp);
        archive = new ChunkArchive(blob, new ObjectMapper());
    }

    private static KbChunk chunk(String id, String text, String docId, float[] vector) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.DOC_ID, docId);
        meta.put(ChunkMetadataKeys.FILE_NAME, "a.pdf");
        meta.put(ChunkMetadataKeys.CHUNK_SEQ, "1");
        return new KbChunk(id, text, meta, vector);
    }

    // ==================== 写入：剥离向量 ====================

    @Test
    @DisplayName("write：向量被剥离，正文与元数据原样归档")
    void write_stripsVectors() {
        archive.write(INDEX, "d1", List.of(chunk("c1", "正文一", "d1", new float[]{0.1f, 0.2f})));

        Optional<List<KbChunk>> read = archive.read(INDEX, "d1");
        assertTrue(read.isPresent());
        KbChunk back = read.get().get(0);
        assertEquals("正文一", back.text());
        assertEquals("d1", back.metadata().get(ChunkMetadataKeys.DOC_ID));
        assertNull(back.vector(), "archive 是原文权威源，向量是派生数据，不带复印件");
    }

    @Test
    @DisplayName("write：空列表 / null 不建键（无原文可归档）")
    void write_emptyOrNull_writesNothing() {
        archive.write(INDEX, "d1", List.of());
        archive.write(INDEX, "d2", null);

        assertTrue(archive.allKeys().isEmpty(), archive.allKeys().toString());
        assertTrue(archive.read(INDEX, "d1").isEmpty());
    }

    @Test
    @DisplayName("write：存储故障只告警不抛（上传主流程已成功，缺档有对账兜底）")
    void write_failure_swallowed() {
        BlobStore failing = new BlobStore() {
            @Override
            public void put(String key, InputStream data) {
                throw new RuntimeException("磁盘满了");
            }

            @Override
            public Optional<InputStream> open(String key) {
                return Optional.empty();
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public boolean exists(String key) {
                return false;
            }

            @Override
            public List<String> listKeys(String prefix) {
                return List.of();
            }

            @Override
            public Optional<Instant> lastModified(String key) {
                return Optional.empty();
            }
        };
        ChunkArchive fragile = new ChunkArchive(failing, new ObjectMapper());

        fragile.write(INDEX, "d1", List.of(chunk("c1", "正文", "d1", null)));

        assertTrue(fragile.read(INDEX, "d1").isEmpty(), "写失败不应留下半成品");
    }

    // ==================== 读取：读不全立即抛 ====================

    @Test
    @DisplayName("read：档案不存在返回空（不是异常——从没归档过是合法状态）")
    void read_missing_returnsEmpty() {
        assertTrue(archive.read(INDEX, "nope").isEmpty());
    }

    @Test
    @DisplayName("read：JSON 损坏立即抛（对账与重灌都不能半途而废）")
    void read_corruptJson_throws() {
        blob.put(ChunkArchive.keyOf(INDEX, "bad"),
                new ByteArrayInputStream("{这不是JSON".getBytes(StandardCharsets.UTF_8)));

        assertThrows(IllegalStateException.class, () -> archive.read(INDEX, "bad"));
    }

    @Test
    @DisplayName("read：存储故障立即抛（不猜、不吞）")
    void read_storageFailure_throws() {
        BlobStore broken = new BlobStore() {
            @Override
            public void put(String key, InputStream data) {
            }

            @Override
            public Optional<InputStream> open(String key) {
                throw new RuntimeException("连接被重置");
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public boolean exists(String key) {
                return false;
            }

            @Override
            public List<String> listKeys(String prefix) {
                return List.of();
            }

            @Override
            public Optional<Instant> lastModified(String key) {
                return Optional.empty();
            }
        };
        ChunkArchive fragile = new ChunkArchive(broken, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> fragile.read(INDEX, "d1"));
    }

    // ==================== 删除：幂等 + 整域清除 ====================

    @Test
    @DisplayName("delete：幂等，删不存在的文档不抛")
    void delete_isIdempotent() {
        archive.write(INDEX, "d1", List.of(chunk("c1", "正文", "d1", null)));

        archive.delete(INDEX, "d1");
        archive.delete(INDEX, "d1");
        archive.delete(INDEX, "never-existed");

        assertTrue(archive.read(INDEX, "d1").isEmpty());
    }

    @Test
    @DisplayName("deleteCollection：按索引整域清除，返回删除条数")
    void deleteCollection_clearsWholeIndex() {
        archive.write(INDEX, "d1", List.of(chunk("c1", "正文", "d1", null)));
        archive.write(INDEX, "d2", List.of(chunk("c2", "正文", "d2", null)));
        archive.write("kb_other", "d9", List.of(chunk("c9", "正文", "d9", null)));

        int deleted = archive.deleteCollection(INDEX);

        assertEquals(2, deleted);
        assertEquals(Set.of("kb_other"), archive.inventory().keySet());
        assertEquals(Set.of("d9"), archive.docIdsOf("kb_other"));
    }

    // ==================== inventory：键布局的逆运算 ====================

    @Test
    @DisplayName("inventory：按 kb/chunks/{index}/{docId}.json 解析，异形键跳过不猜")
    void inventory_parsesLayout_andSkipsForeignKeys() {
        archive.write(INDEX, "d1", List.of(chunk("c1", "正文", "d1", null)));
        // 三类不符合布局的键：猜错会把活档案当孤儿删掉
        blob.put("kb/chunks/noSlash.json", new ByteArrayInputStream(new byte[]{1}));
        blob.put("kb/chunks/trailing/.json", new ByteArrayInputStream(new byte[]{1}));
        blob.put("other/prefix/file.json", new ByteArrayInputStream(new byte[]{1}));

        Map<String, Set<String>> inv = archive.inventory();

        assertEquals(Set.of(INDEX), inv.keySet(), "异形键必须被跳过：" + inv.keySet());
        assertEquals(Set.of("d1"), inv.get(INDEX));
    }

    @Test
    @DisplayName("docIdsOf：不存在的索引返回空集（缺档判定的 archive 侧输入）")
    void docIdsOf_unknownIndex_empty() {
        assertTrue(archive.docIdsOf("kb_absent").isEmpty());
    }

    // ==================== 年龄护栏 ====================

    @Test
    @DisplayName("isYoung：零护栏恒 false；新档为 young；老档不为 young")
    void isYoung_guardsByAge() throws Exception {
        archive.write(INDEX, "fresh", List.of(chunk("c1", "正文", "fresh", null)));

        assertFalse(archive.isYoung(ChunkArchive.keyOf(INDEX, "fresh"), Duration.ZERO),
                "零护栏 = 关护栏，重灌等显式操作传 ZERO");
        assertTrue(archive.isYoung(ChunkArchive.keyOf(INDEX, "fresh"), Duration.ofMinutes(5)),
                "刚写的档在护栏窗口内");
        assertFalse(archive.isYoung(ChunkArchive.keyOf(INDEX, "missing"), Duration.ofMinutes(5)),
                "取不到时间的键不当成 young（护栏不是判据）");

        Path aged = tmp.resolve(ChunkArchive.keyOf(INDEX, "fresh"));
        Files.setLastModifiedTime(aged, java.nio.file.attribute.FileTime.from(
                Instant.now().minus(Duration.ofMinutes(10))));
        assertFalse(archive.isYoung(ChunkArchive.keyOf(INDEX, "fresh"), Duration.ofMinutes(5)),
                "超过护栏窗口的不算 young");
    }
}
