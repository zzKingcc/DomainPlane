package com.zzkingcc.domainplane.infrastructure.kb.es;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES {@code _source} ↔ SPI 数据模型的双向编解码。
 *
 * 收敛至此是因为这份搬运此前后在两个检索器里各写一份，且两份都只搬了
 * {@code file_name} 与 {@code section_title}。切片侧明明写全了
 * （{@code BlockSplitter} 会写 {@code chunk_seq} / {@code chunk_total} / {@code page_from}），
 * 读取侧漏了，于是融合阶段的排序二级键「按 chunk_seq 升序」恒等于
 * {@code Integer.MAX_VALUE}：承诺的稳定排序实际退化成按内容哈希排。
 * 修一处漏一处的典型隐患，所以收敛到唯一实现。
 *
 * 两个方向的白名单**刻意不同**：
 *
 *   • {@link #toHit}（检索路）只搬 8 个键——与旧检索器逐字节等价，命中对象的 metadata
 *     多一个键少一个键都算行为变化；
 *   • {@link #toChunk}（扫描路）搬全部键——重灌与对账补写要还原"当初写进去的完整切片"，
 *     少了 {@code content_hash} 会让补写/重灌出的文档失去去重身份。
 *
 * @author zzkingcc
 */
public final class EsSourceCodec {

    /**
     * 检索路白名单：与旧 {@code EsMetadata.copy} 一致。
     *
     * 含 {@code chunk_seq}：排序稳定性靠它，缺了会让两次检索的顺序不可复现。
     */
    private static final List<String> HIT_METADATA_KEYS = List.of(
            ChunkMetadataKeys.FILE_NAME,
            ChunkMetadataKeys.SECTION_PATH,
            ChunkMetadataKeys.SECTION_TITLE,
            ChunkMetadataKeys.DOC_ID,
            ChunkMetadataKeys.DOMAIN,
            ChunkMetadataKeys.CHUNK_SEQ,
            ChunkMetadataKeys.CHUNK_TOTAL,
            ChunkMetadataKeys.PAGE_FROM);

    /**
     * 写回路按 ES mapping 收窄成整数的键。
     *
     * 旧写入路径（langchain4j 的 store）把切片侧的 {@code Integer} 原样写进 _source，
     * 这三个键在 mapping 里是 {@code integer}。适配器收的是 {@code Map<String,String>}，
     * 不转的话 ES 侧要靠 coercion 把 "3" 认成整数——能work，但 _source 里存的是字符串，
     * 与改造前不同形。转一下，写入结果与旧路径逐字节同形。
     */
    private static final List<String> NUMERIC_METADATA_KEYS = List.of(
            ChunkMetadataKeys.CHUNK_SEQ,
            ChunkMetadataKeys.CHUNK_TOTAL,
            ChunkMetadataKeys.PAGE_FROM);

    private EsSourceCodec() {
    }

    /**
     * ES 命中 → {@link KbHit}（检索路）。
     *
     * @param id     ES 文档 _id（作为切片标识）
     * @param source {@code _source}（已按 text/metadata 过滤）
     * @param score  ES 原始分（向量路是 {@code cosineSimilarity + 1.0}，不外泄偏移）
     * @return 命中；{@code text} 为空白时返回 {@code null}——调用方丢弃该 hit
     *         （红线④：与旧检索器一致，空白正文不进融合）
     */
    public static KbHit toHit(String id, Map<String, Object> source, double score) {
        if (source == null) {
            return null;
        }
        Object textObj = source.get("text");
        String text = textObj == null ? "" : textObj.toString();
        if (text.isBlank()) {
            return null;
        }
        return new KbHit(id, text, metadataOf(source, HIT_METADATA_KEYS), score);
    }

    /**
     * {@link KbChunk} → ES 文档（写回路）。
     *
     * 字段名与 mapping 一致：{@code vector}（dense_vector）/ {@code text} / {@code metadata}。
     * keyword-only 后端用不上的空向量直接省略字段，不写 null 数组。
     */
    public static Map<String, Object> toDocument(KbChunk chunk) {
        Map<String, Object> doc = new LinkedHashMap<>();
        if (chunk.vector() != null) {
            // 装箱成 List<Float>：走 Jackson 序列化，与直接给 float[] 等价，
            // 但避开 ES 客户端 mapper 对原始数组的特殊处理路径。
            // （Java 没有 FloatStream，Arrays.stream 不吃 float[]，只能手写循环）
            List<Float> vec = new ArrayList<>(chunk.vector().length);
            for (float v : chunk.vector()) {
                vec.add(v);
            }
            doc.put("vector", vec);
        }
        doc.put("text", chunk.text());
        doc.put("metadata", metadataForWrite(chunk.metadata()));
        return doc;
    }

    /**
     * ES {@code _source} → {@link KbChunk}（扫描路：重灌兜底与对账补写）。
     *
     * 与 {@link #toHit} 的差别：搬全部 metadata 键（含 {@code content_hash}），
     * 并读出向量。缺失向量的文档（keyword-only 历史数据）向量为 {@code null}。
     */
    public static KbChunk toChunk(String id, Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object textObj = source.get("text");
        String text = textObj == null ? "" : textObj.toString();
        if (text.isBlank()) {
            return null;
        }
        return new KbChunk(id, text, metadataOf(source, null), toVector(source.get("vector")));
    }

    /**
     * _source 里的向量数组 → {@code float[]}。
     *
     * ES 反序列化出来是 {@code List<Double>}（JSON 数字默认走 double）。
     */
    static float[] toVector(Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object v = list.get(i);
                out[i] = v instanceof Number n ? n.floatValue() : 0f;
            }
            return out;
        }
        return null;
    }

    /**
     * 按白名单（{@code keys == null} 表示全部）搬运 metadata，值统一转字符串。
     *
     * 数值字段先收窄成 int 再转字符串：ES 的 integer 字段取回来是 Long，
     * 与旧实现（{@code EsMetadata.putIfPresent} / {@code VectorReindexer.segmentOf}）的
     * 收窄口径一致——下游 {@code getInteger} 两种形态都吃，但口径只有一个更安全。
     */
    private static Map<String, String> metadataOf(Map<String, Object> source, List<String> keys) {
        Object metadataObj = source.get("metadata");
        if (!(metadataObj instanceof Map<?, ?> meta)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : meta.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (keys != null && !keys.contains(key)) {
                continue;
            }
            Object value = e.getValue();
            if (value == null) {
                continue;
            }
            out.put(key, stringify(value));
        }
        return out;
    }

    /**
     * 写回路 metadata：数值键转成 Integer（ES mapping 的 integer 字段），
     * 其余原样字符串。与旧写入路径的 _source 同形。
     */
    private static Map<String, Object> metadataForWrite(Map<String, String> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            String value = e.getValue();
            if (value == null) {
                continue;
            }
            if (NUMERIC_METADATA_KEYS.contains(e.getKey())) {
                try {
                    out.put(e.getKey(), Integer.valueOf(value.trim()));
                    continue;
                } catch (NumberFormatException ignored) {
                    // 不是合法整数就按字符串写：ES 侧会因类型不符拒掉这个字段，
                    // 但那是数据问题，不该在这里静默改成 0
                }
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    private static String stringify(Object value) {
        if (value instanceof Number n) {
            return String.valueOf(n.intValue());
        }
        return value.toString();
    }
}
