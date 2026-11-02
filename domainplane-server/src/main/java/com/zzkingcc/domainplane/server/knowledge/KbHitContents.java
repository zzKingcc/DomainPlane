package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SPI 命中（{@link KbHit}）→ 融合层入参（{@link Content}）的桥接。
 *
 * 存在理由：编排层（{@code CompositeRetriever}）与融合层消费的是 langchain4j 的
 * {@code Content}，而 SPI（{@code KeywordSearcher} / {@code VectorSearcher}）出口是
 * 与后端无关的 {@code KbHit}。中间这一跳必须与旧 ES 检索器（
 * {@code NativeScriptScoreContentRetriever} / {@code KeywordMatchContentRetriever}）
 * **逐字节等价**，否则基线比对（改造前后 10 条 query 的命中集合与顺序）会失败，
 * 而失败会被误读成"融合算法被改坏"。
 *
 * 三条等价红线（设计第 5 节，一个数都不许改）：
 *   ① 写回 metadata 的 {@link ChunkMetadataKeys#RAW_SCORE} 是 {@code hit.score()}
 *      **原值**——ES 向量路就是 {@code cosineSimilarity + 1.0} 的结果，不做任何变换；
 *   ② 切片元数据整体搬运（数值键收窄成 int，与旧 {@code EsMetadata.putIfPresent}
 *      同语义：ES 数值字段默认 long，不收窄下游 {@code getInteger} 取不到）；
 *   ③ {@code text} 空白的 hit 在 SPI 出口侧已被丢弃（适配器保证），这里不再过滤。
 *
 * @author zzkingcc
 */
final class KbHitContents {

    private KbHitContents() {
    }

    static List<Content> toContents(List<KbHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Content> out = new ArrayList<>(hits.size());
        for (KbHit hit : hits) {
            out.add(toContent(hit));
        }
        return out;
    }

    static Content toContent(KbHit hit) {
        TextSegment segment = TextSegment.from(hit.text());
        // 原始分先写，再搬元数据：RAW_SCORE 不在白名单里，不存在被覆盖的问题
        segment.metadata().put(ChunkMetadataKeys.RAW_SCORE, hit.score());
        Map<String, String> metadata = hit.metadata();
        if (metadata != null && !metadata.isEmpty()) {
            metadata.forEach((key, value) -> {
                Integer narrowed = narrow(key, value);
                if (narrowed != null) {
                    segment.metadata().put(key, narrowed);
                } else {
                    segment.metadata().put(key, value);
                }
            });
        }
        return Content.from(segment);
    }

    /**
     * 数值键收窄回 int：KbHit 的 metadata 是 {@code Map<String,String>}（跨后端中立），
     * 而旧链路 ES 数值字段是 long、put 进 metadata 的是 int。下游融合层按
     * {@code getInteger} 读，两种形态都能取到值，但保持与旧链路相同的存储类型，
     * 排掉"某处直接 instanceof Integer 判断"这类隐式消费。
     *
     * @return 可收窄的 int；不可收窄（非数值键或解析失败）返回 {@code null}，调用方按字符串落
     */
    private static Integer narrow(String key, String value) {
        if (value == null) {
            return null;
        }
        if (ChunkMetadataKeys.CHUNK_SEQ.equals(key)
                || ChunkMetadataKeys.CHUNK_TOTAL.equals(key)
                || ChunkMetadataKeys.PAGE_FROM.equals(key)) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignore) {
                // 非数值（理论不可能：这些键由切片侧写死）按字符串落，不抛
            }
        }
        return null;
    }
}
