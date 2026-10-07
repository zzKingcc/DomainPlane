package com.zzkingcc.domainplane.infrastructure.elasticsearch.retriever;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import dev.langchain4j.data.segment.TextSegment;

import java.util.Map;

/**
 * 把 ES 命中的 {@code _source.metadata} 搬进 {@link TextSegment} 的 metadata。
 *
 * 抽出来是因为这份搬运此前在两个检索器里各写了一份，且两份都只搬了
 * {@code file_name} 与 {@code section_title}。切片侧明明写全了
 * （{@code BlockSplitter} 会写 {@code chunk_seq} / {@code chunk_total} / {@code page_from}），
 * 读取侧漏了，于是融合阶段的排序二级键「按 chunk_seq 升序」恒等于
 * {@code Integer.MAX_VALUE} —— 承诺的稳定排序实际退化成按内容哈希排。
 * 修一处漏一处的典型隐患，所以收敛到唯一实现。
 */
final class EsMetadata {

    /** 检索原始分写入 TextSegment metadata 用的键 */
    static final String RAW_SCORE = ChunkMetadataKeys.RAW_SCORE;

    private EsMetadata() {
    }

    /**
     * 搬运全部切片元数据。
     *
     * 按键名白名单搬运，不做整包透传：{@code metadata} 在 mapping 里是
     * {@code dynamic:false}，字段集是固定的；显式列出同时也避免将来有人往
     * {@code _source} 里塞别的东西时被顺手带进上下文。
     */
    @SuppressWarnings("unchecked")
    static void copy(Map<String, Object> source, TextSegment segment) {
        if (source == null) {
            return;
        }
        Object metadataObj = source.get("metadata");
        if (!(metadataObj instanceof Map)) {
            return;
        }
        Map<String, Object> meta = (Map<String, Object>) metadataObj;

        putIfPresent(segment, meta, ChunkMetadataKeys.FILE_NAME);
        putIfPresent(segment, meta, ChunkMetadataKeys.SECTION_PATH);
        putIfPresent(segment, meta, ChunkMetadataKeys.SECTION_TITLE);
        putIfPresent(segment, meta, ChunkMetadataKeys.DOC_ID);
        putIfPresent(segment, meta, ChunkMetadataKeys.DOMAIN);
        // 排序稳定性的二级键：融合分相同时按它升序，缺了会让两次检索的顺序不可复现
        putIfPresent(segment, meta, ChunkMetadataKeys.CHUNK_SEQ);
        putIfPresent(segment, meta, ChunkMetadataKeys.CHUNK_TOTAL);
        putIfPresent(segment, meta, ChunkMetadataKeys.PAGE_FROM);
    }

    private static void putIfPresent(TextSegment segment, Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value == null) {
            return;
        }
        if (value instanceof Integer i) {
            segment.metadata().put(key, i);
        } else if (value instanceof Long l) {
            // ES 的数值字段默认是 long；不转成 int 的话下游 getInteger 会取不到
            segment.metadata().put(key, l.intValue());
        } else if (value instanceof Number n) {
            segment.metadata().put(key, n.intValue());
        } else {
            segment.metadata().put(key, value.toString());
        }
    }
}
