package com.zzkingcc.domainplane.domain.rag.fusion;

import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import com.zzkingcc.domainplane.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 锁（一审修订）：融合层数"有命中的来源数"只认 {@code sourceId}（知识来源 = 域），
 * 不认 {@code locationId}（{@code backendId|collection|modality} 物理位置）。
 *
 * <p>为什么值得单独立测试：混合后端（keyword 走一家、vector 走另一家）或混合模态
 * （同一域同时开向量路与关键词路）下，同一来源会带着<b>不同 locationId</b> 出现多次。
 * 若来源计数按 locationId 去重，"同一域两路"会被数成 2 个来源，分数制融合静默退化
 * 成 RRF——不报错、不错结果，只是排序质量凭空降一档，且没有任何线索指向融合层。
 * 本测试把这条退路钉死。</p>
 *
 * <p>分辨方式同 {@code AdaptiveFusionStrategyTest}：分数制的名次 1 归一化后拿满权重
 * （融合分 1.0），RRF 是 {@code w/(k+1)} 量级（约 0.016），相差两个数量级。</p>
 *
 * @author zzkingcc
 */
@DisplayName("A1：来源计数只认 sourceId，locationId 不分裂来源")
class AdaptiveFusionSourceKeyTest {

    private static Content content(String text, float rawScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    /** 只认向量、无 boost、topN=10：让融合分只由所选算法决定 */
    private static final FusionConfig CFG = new FusionConfig(1.0, 0.0, 0.0, 0.0, 10, 60);

    /** 两路等权：用于"唯一命中来自关键词路"的场景（CFG 关键词权重为 0 时融合分恒 0，看不出轨） */
    private static final FusionConfig BOTH_WEIGHTED = new FusionConfig(1.0, 1.0, 0.0, 0.0, 10, 60);

    private static double topScore(List<Content> result) {
        return result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
    }

    @Test
    @DisplayName("同一域两模态（locationId 不同）仍算 1 个来源 → 分数制")
    void sameSourceTwoModalities_staysSingleSource() {
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                new RankedList("default.sales", "es-main|kb_x|VECTOR", Modality.VECTOR, 0,
                        List.of(content("向量命中", 0.9f))),
                new RankedList("default.sales", "es-main|kb_x|KEYWORD", Modality.KEYWORD, 0,
                        List.of(content("关键词命中", 0.8f)))),
                CFG);

        assertEquals(2, result.size(), "两路各一条不同内容，去重后应都在");
        assertEquals(1.0, topScore(result), 1e-9,
                "同一来源必须走分数制（归一化满分）；若按 locationId 数成两个来源会静默退化成 RRF");
    }

    @Test
    @DisplayName("对照：不同 sourceId 才算多来源 → RRF")
    void differentSources_switchToRrf() {
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                new RankedList("default.sales", "es-main|kb_x|VECTOR", Modality.VECTOR, 0,
                        List.of(content("向量命中", 0.9f))),
                new RankedList("default", "es-main|kb_y|KEYWORD", Modality.KEYWORD, 0,
                        List.of(content("关键词命中", 0.8f)))),
                CFG);

        assertTrue(topScore(result) < 0.1,
                "两个来源走 RRF：融合分是 w/(k+rank) 量级，实际 " + topScore(result));
    }

    @Test
    @DisplayName("混合后端：同一域跨两家后端，来源计数不变（仍 1 个）")
    void sameSourceAcrossBackends_staysSingleSource() {
        // 混合后端的生产形态：向量路走向量库、关键词路走 ES，sourceId 同为查询域
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                new RankedList("default.sales", "vec-db|kb_x|VECTOR", Modality.VECTOR, 0,
                        List.of(content("向量命中", 0.9f))),
                new RankedList("default.sales", "es-main|kb_x|KEYWORD", Modality.KEYWORD, 0,
                        List.of(content("关键词命中", 0.8f)))),
                CFG);

        assertEquals(1.0, topScore(result), 1e-9,
                "跨后端不改变来源计数：backendId 是 locationId 的一部分，不是 sourceId");
    }

    @Test
    @DisplayName("只有一路有命中时同样按 sourceId 去重后计数")
    void oneModalityEmpty_stillCountsBySource() {
        AdaptiveFusionStrategy s = new AdaptiveFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                new RankedList("default.sales", "es-main|kb_x|VECTOR", Modality.VECTOR, 0, List.of()),
                new RankedList("default.sales", "es-main|kb_x|KEYWORD", Modality.KEYWORD, 0,
                        List.of(content("关键词命中", 0.8f)))),
                BOTH_WEIGHTED);

        assertEquals(1, result.size());
        assertEquals(1.0, topScore(result), 1e-9,
                "单来源单命中：分数制归一化满分（RRF 会是 w/(k+1)≈0.016 量级）");
    }
}
