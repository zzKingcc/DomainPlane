package com.zzkingcc.domainplane.domain.rag.fusion;

import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import com.zzkingcc.domainplane.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双轨切换：来源（索引）数为 1 → 分数制；≥2 → RRF。
 *
 * <p>分辨方式：分数制的名次 1 归一化后正好拿满权重（本例权重 1.0 → 融合分 1.0），
 * RRF 的名次 1 是 {@code w/(k+1)}（约 0.016）—— 量级相差两个数量级，不可能混淆。</p>
 */
class AdaptiveFusionStrategyTest {

    private static Content content(String text, float rawScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    /** 只认向量、无 boost、topN=10：让融合分只由所选算法决定 */
    private static final FusionConfig CFG = new FusionConfig(1.0, 0.0, 0.0, 0.0, 10, 60);

    @Test
    void singleIndex_usesScoreBasedTrack() {
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                new RankedList("i1", Modality.VECTOR, List.of(content("A", 0.9f)))),
                CFG);
        assertEquals(1, result.size());
        assertEquals(1.0, result.get(0).textSegment().metadata()
                .getDouble(RetrievalScoreKeys.FUSED_SCORE), 1e-9,
                "单索引走分数制：唯一一条被归一化为满分");
    }

    @Test
    void twoIndexes_switchToRrf() {
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                new RankedList("i1", Modality.VECTOR, List.of(content("A", 0.9f))),
                new RankedList("i2", Modality.VECTOR, List.of(content("B", 0.9f)))),
                CFG);
        assertEquals(2, result.size());
        double top = result.get(0).textSegment().metadata()
                .getDouble(RetrievalScoreKeys.FUSED_SCORE);
        assertTrue(top < 0.1, "多索引走 RRF：融合分是 w/(k+rank) 量级，不是归一化分数");
    }

    @Test
    void emptyOrNull_isSafe() {
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        assertTrue(s.fuse("q", null, CFG).isEmpty());
        assertTrue(s.fuse("q", List.of(), CFG).isEmpty());
    }

    /**
     * 判据是"<b>有命中</b>的来源数"，不是"通道里出现过几个来源"。
     *
     * <p>通道按域链无条件解析出来，祖先域没上传过文档时它根本没有索引。若按通道数判，
     * 任何非根域都会被判成多来源、一律走 RRF —— 分数制只在根域生效，等于白留一轨。</p>
     */
    @Test
    void singleNonEmptySourceAmongMany_usesScoreBasedTrack() {
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                new RankedList("ancestor-without-index", Modality.VECTOR, 1, List.of()),
                new RankedList("i2", Modality.VECTOR, 0, List.of(content("A", 0.9f))),
                new RankedList("ancestor-without-index", Modality.KEYWORD, 1, List.of())),
                CFG);
        assertEquals(1, result.size());
        assertEquals(1.0, result.get(0).textSegment().metadata()
                .getDouble(RetrievalScoreKeys.FUSED_SCORE), 1e-9,
                "只有一处有命中 -> 分数制：唯一一条被归一化为满分");
    }
}
