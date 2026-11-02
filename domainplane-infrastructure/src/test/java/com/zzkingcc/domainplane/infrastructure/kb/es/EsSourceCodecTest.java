package com.zzkingcc.domainplane.infrastructure.kb.es;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片编解码的白名单与收窄口径（D-08 的读取侧另一半）。
 *
 * <p>{@code EsMetadataMappingTest} 守"键名三处一致"，本测试守"搬运行为"：
 * 两个方向的白名单<b>刻意不同</b>——检索路只搬 8 个键（与旧检索器逐字节等价，
 * 命中对象多一个键少一个键都算行为变化），扫描路搬全部键（重灌与对账补写要还原
 * 完整切片，少了 {@code content_hash} 会让补写/重灌出的文档失去去重身份）。</p>
 *
 * <p>收窄口径：ES 的 integer 字段取回来是 {@code Long}，统一按 {@code intValue()}
 * 转字符串——下游 {@code getInteger} 两种形态都吃，但口径只有一个更安全。</p>
 *
 * @author zzkingcc
 */
@DisplayName("切片编解码：检索路 8 键白名单 / 扫描路全键 / 数值收窄")
class EsSourceCodecTest {

    private static Map<String, Object> source(Map<String, Object> metadata, String text) {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("text", text);
        src.put("metadata", metadata);
        return src;
    }

    /** 模拟切片侧写入的全部键（比检索路白名单多三个） */
    private static Map<String, Object> fullMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.DOC_ID, "doc-abc");
        meta.put(ChunkMetadataKeys.FILE_NAME, "a.pdf");
        meta.put("file_name_lower", "a.pdf");
        meta.put("upload_time", "2026-10-24T00:00:00Z");
        meta.put(ChunkMetadataKeys.DOMAIN, "default.sales");
        meta.put(ChunkMetadataKeys.CONTENT_HASH, "hash-1");
        meta.put(ChunkMetadataKeys.CHUNK_SEQ, 3L);       // ES integer 取回来是 Long
        meta.put(ChunkMetadataKeys.CHUNK_TOTAL, 10L);
        meta.put(ChunkMetadataKeys.PAGE_FROM, 7L);
        meta.put(ChunkMetadataKeys.SECTION_PATH, "第一章 > 退款");
        meta.put(ChunkMetadataKeys.SECTION_TITLE, "退款时效");
        return meta;
    }

    // ==================== 检索路：8 键白名单 ====================

    @Test
    @DisplayName("toHit：只搬 7 个白名单键，content_hash / upload_time 等不外泄")
    void toHitUsesWhitelist() {
        KbHit hit = EsSourceCodec.toHit("id-1", source(fullMetadata(), "正文"), 1.35);

        Map<String, String> md = hit.metadata();
        assertEquals(8, md.size(), "检索路白名单恰好 8 个键，多一个少一个都算行为变化：" + md.keySet());
        assertTrue(md.containsKey(ChunkMetadataKeys.CHUNK_SEQ), "chunk_seq 必须在（融合稳定排序的二级键）");
        assertFalse(md.containsKey(ChunkMetadataKeys.CONTENT_HASH), "content_hash 不进检索路");
        assertFalse(md.containsKey("file_name_lower"), "file_name_lower 不进检索路");
        assertFalse(md.containsKey("upload_time"), "upload_time 不进检索路");
        assertEquals("doc-abc", md.get(ChunkMetadataKeys.DOC_ID));
        assertEquals("default.sales", md.get(ChunkMetadataKeys.DOMAIN));
        assertEquals(1.35, hit.score(), 0.0001, "分数原值透传（向量路是 cosineSimilarity+1.0，不收窄不改写）");
        assertEquals("正文", hit.text());
    }

    @Test
    @DisplayName("toHit：text 为空白返回 null（红线④：空白正文不进融合）")
    void toHitDropsBlankText() {
        assertNull(EsSourceCodec.toHit("id", source(fullMetadata(), "   "), 1.0));
        assertNull(EsSourceCodec.toHit("id", source(fullMetadata(), null), 1.0));
        assertNull(EsSourceCodec.toHit("id", null, 1.0));
    }

    @Test
    @DisplayName("toHit：integer 字段的 Long 取值收窄成 int 再转字符串")
    void toHitNarrowsLongToInt() {
        KbHit hit = EsSourceCodec.toHit("id-1", source(fullMetadata(), "正文"), 1.0);

        assertEquals("3", hit.metadata().get(ChunkMetadataKeys.CHUNK_SEQ),
                "ES 的 integer 取回来是 Long；不收窄下游 getInteger 拿到的是 Long 形态");
        assertEquals("10", hit.metadata().get(ChunkMetadataKeys.CHUNK_TOTAL));
        assertEquals("7", hit.metadata().get(ChunkMetadataKeys.PAGE_FROM));
    }

    // ==================== 扫描路：全键 ====================

    @Test
    @DisplayName("toChunk：搬全部键（含 content_hash），并读出向量")
    void toChunkCopiesEverything() {
        Map<String, Object> src = source(fullMetadata(), "正文");
        src.put("vector", List.of(0.1d, 0.2d, 0.3d));   // ES 反序列化出来是 List<Double>

        KbChunk chunk = EsSourceCodec.toChunk("id-9", src);

        assertEquals("id-9", chunk.id());
        assertEquals("正文", chunk.text());
        assertTrue(chunk.metadata().containsKey(ChunkMetadataKeys.CONTENT_HASH),
                "扫描路必须带 content_hash：补写/重灌出的文档要保留去重身份");
        assertEquals("hash-1", chunk.metadata().get(ChunkMetadataKeys.CONTENT_HASH));
        assertEquals("3", chunk.metadata().get(ChunkMetadataKeys.CHUNK_SEQ), "同样走 int 收窄");
        assertEquals(0.1f, chunk.vector()[0], 0.0001f);
        assertEquals(3, chunk.vector().length);
    }

    @Test
    @DisplayName("toChunk：text 为空白返回 null；缺向量时 vector 为 null")
    void toChunkDropsBlankTextAndMissingVector() {
        assertNull(EsSourceCodec.toChunk("id", source(fullMetadata(), " ")));
        KbChunk chunk = EsSourceCodec.toChunk("id", source(fullMetadata(), "正文"));
        assertNull(chunk.vector(), "keyword-only 历史数据没有向量，按 null 处理");
    }

    // ==================== 写回路：数值键转 Integer ====================

    @Test
    @DisplayName("toDocument：数值键写成 Integer，其余字符串，与旧 _source 同形")
    void toDocumentNarrowsNumericKeys() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.DOC_ID, "doc-abc");
        meta.put(ChunkMetadataKeys.CHUNK_SEQ, "3");
        meta.put(ChunkMetadataKeys.SECTION_TITLE, "退款时效");
        KbChunk chunk = new KbChunk("id-1", "正文", meta, new float[]{0.5f, 0.25f});

        Map<String, Object> doc = EsSourceCodec.toDocument(chunk);
        Map<?, ?> written = (Map<?, ?>) doc.get("metadata");

        assertEquals("正文", doc.get("text"));
        assertTrue(written.get(ChunkMetadataKeys.CHUNK_SEQ) instanceof Integer,
                "mapping 里是 integer，写字符串靠 ES coercion 能work 但与旧 _source 不同形");
        assertEquals(3, written.get(ChunkMetadataKeys.CHUNK_SEQ));
        assertEquals("doc-abc", written.get(ChunkMetadataKeys.DOC_ID));
        assertEquals("退款时效", written.get(ChunkMetadataKeys.SECTION_TITLE));
        List<?> vec = (List<?>) doc.get("vector");
        assertEquals(2, vec.size());
        assertEquals(0.5f, ((Number) vec.get(0)).floatValue(), 0.0001f);
    }

    @Test
    @DisplayName("toDocument：无向量时省略 vector 字段，不写 null 数组")
    void toDocumentOmitsNullVector() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.DOC_ID, "doc-abc");
        KbChunk chunk = new KbChunk("id-1", "正文", meta);

        Map<String, Object> doc = EsSourceCodec.toDocument(chunk);

        assertFalse(doc.containsKey("vector"), "null 数组写进去会被 ES 拒（dense_vector 不接受 null）");
    }

    @Test
    @DisplayName("toDocument：数值键不是合法整数时按字符串写，不静默改成 0")
    void toDocumentKeepsUnparsableNumericAsString() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.CHUNK_SEQ, "坏数据");
        KbChunk chunk = new KbChunk("id-1", "正文", meta);

        Map<String, Object> doc = EsSourceCodec.toDocument(chunk);

        assertEquals("坏数据", ((Map<?, ?>) doc.get("metadata")).get(ChunkMetadataKeys.CHUNK_SEQ),
                "静默改成 0 会让排序键集体相等，比让 ES 拒掉这个字段更难排查");
    }

    // ==================== 往返 ====================

    @Test
    @DisplayName("往返：write 的路由读回来，键与值不变（重灌保真）")
    void roundTripThroughWritePath() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ChunkMetadataKeys.DOC_ID, "doc-abc");
        meta.put(ChunkMetadataKeys.FILE_NAME, "a.pdf");
        meta.put(ChunkMetadataKeys.DOMAIN, "default.sales");
        meta.put(ChunkMetadataKeys.CHUNK_SEQ, "5");
        meta.put(ChunkMetadataKeys.SECTION_TITLE, "退款时效");
        KbChunk original = new KbChunk("id-1", "正文", meta, new float[]{0.1f, 0.2f});

        // 写回路 → 模拟 ES _source（整数字段存 Integer，与 toDocument 一致）
        Map<String, Object> doc = EsSourceCodec.toDocument(original);
        Map<String, Object> metadata = new LinkedHashMap<>((Map<String, Object>) doc.get("metadata"));
        metadata.put(ChunkMetadataKeys.CHUNK_SEQ, 5L);   // ES integer 读回来是 Long

        Map<String, Object> src = source(metadata, "正文");
        src.put("vector", doc.get("vector"));   // 写回路装箱成 List<Float>，ES 读回来是 List<Double>，toVector 两种都吃

        KbChunk readBack = EsSourceCodec.toChunk("id-1", src);

        assertEquals("id-1", readBack.id());
        assertEquals("正文", readBack.text());
        assertEquals("5", readBack.metadata().get(ChunkMetadataKeys.CHUNK_SEQ));
        assertEquals("doc-abc", readBack.metadata().get(ChunkMetadataKeys.DOC_ID));
        assertEquals("a.pdf", readBack.metadata().get(ChunkMetadataKeys.FILE_NAME));
        assertEquals(2, readBack.vector().length);
    }
}
