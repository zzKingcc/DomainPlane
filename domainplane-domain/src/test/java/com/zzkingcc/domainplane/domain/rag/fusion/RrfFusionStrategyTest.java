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
 * 锁住排名制融合（<b>多索引</b>轨）：跨索引只看名次、按模态均摊权重、去重累计。
 */
class RrfFusionStrategyTest {

    private static Content content(String text, float rawScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static RankedList list(String index, Modality modality, Content... contents) {
        return new RankedList(index, modality, List.of(contents));
    }

    @Test
    void sameContentInSeveralLists_accumulatesRankScore() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        // X 在 i1 名次 1、i2 名次 2；Y 只在 i2 名次 1
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("X", 0.9f)),
                list("i2", Modality.VECTOR, content("Y", 0.9f), content("X", 0.8f))
        ), FusionConfig.defaults());

        assertEquals(2, result.size());
        assertEquals("X", result.get(0).textSegment().text(), "多次命中累计分更高，应排第一");
        double x = result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        double y = result.get(1).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        assertTrue(x > y);
        assertEquals(1, result.get(0).textSegment().metadata().getInteger(RetrievalScoreKeys.FUSION_RANK));
        assertEquals("vector", result.get(0).textSegment().metadata().getString(RetrievalScoreKeys.MATCH_CHANNEL));
    }

    /**
     * 原始分的量级完全不参与计算：这正是多索引必须用 RRF 的理由：
     * BM25 的 idf 用本索引的文档频率，同一词在不同索引里量纲不同，分数池化后必然失真。
     */
    @Test
    void rankOrderDrivesFusion_notRawScoreMagnitude() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(0.6, 0.4, 0.0, 0.0, 10, 60);
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("small-1", 0.01f), content("small-2", 0.005f)),
                list("i2", Modality.KEYWORD, content("big-1", 19.0f), content("big-2", 9.0f))
        ), cfg);

        Map<String, Double> score = new LinkedHashMap<>();
        result.forEach(c -> score.put(c.textSegment().text(),
                c.textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE)));

        // 两表各自的名次 1 只差权重（0.6/1 与 0.4/1），与 0.01 / 19.0 的量级无关
        assertEquals(0.6 / (60 + 1), score.get("small-1"), 1e-9);
        assertEquals(0.4 / (60 + 1), score.get("big-1"), 1e-9);
        // 名次相同时按权重决胜：向量两名的分都高于关键词同名次
        assertEquals(List.of("small-1", "small-2", "big-1", "big-2"),
                result.stream().map(c -> c.textSegment().text()).toList());
    }

    /**
     * 权重按模态均摊：2 张向量表各拿 vectorWeight/2，1 张关键词表独占 keywordWeight。
     * 否则祖先域越多、向量表越多，关键词那一路会被越压越扁。
     */
    @Test
    void weightsAreSplitPerModalityTable() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(0.6, 0.4, 0.0, 0.0, 10, 60);
        // 关键词只在 i1 名次 1（权 0.4/1），向量在 i1/i2 名次 1（各 0.6/2 = 0.3）
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("V1", 0.9f)),
                list("i1", Modality.KEYWORD, content("K1", 9.0f)),
                list("i2", Modality.VECTOR, content("V2", 0.9f))
        ), cfg);

        Map<String, Double> score = new LinkedHashMap<>();
        result.forEach(c -> score.put(c.textSegment().text(),
                c.textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE)));

        double k1 = score.get("K1");
        double expectKeyword = 0.4 / (60 + 1);
        assertEquals(expectKeyword, k1, 1e-9, "关键词权重独占，不按向量表数摊薄");
        assertEquals(0.6 / 2 / (60 + 1), score.get("V1"), 1e-9, "向量权重被两张表均摊");
        assertTrue(k1 > score.get("V1"));
    }

    @Test
    void topNTruncates() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(0.6, 0.4, 0.0, 0.0, 1, 60);
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("A", 0.9f), content("B", 0.5f))
        ), cfg);
        assertEquals(1, result.size());
    }

    @Test
    void emptyLists_returnEmpty() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        assertTrue(s.fuse("q", List.of(), FusionConfig.defaults()).isEmpty());
        assertTrue(s.fuse("q",
                List.of(new RankedList("i1", Modality.VECTOR, List.of())),
                FusionConfig.defaults()).isEmpty());
    }

    /**
     * 空表不参与权重分摊：否则"祖先域还没建索引"会把权重摊给空气，
     * 让真正有命中的表被压低（域链上大部分祖先没有索引是常态）。
     */
    @Test
    void emptyLists_doNotConsumeWeightShare() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(0.6, 0.4, 0.0, 0.0, 10, 60);
        List<Content> result = s.fuse("q", List.of(
                list("empty-ancestor", Modality.VECTOR),
                list("i1", Modality.VECTOR, content("only", 0.9f))
        ), cfg);

        assertEquals(1, result.size());
        // 唯一有命中的向量表应独占 vectorWeight，而不是只拿 0.6/2
        assertEquals(0.6 / (60 + 1),
                result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE), 1e-9);
    }

    /** 层级衰减：距查询域越远，权重越低；总量仍守恒。 */
    @Test
    void ancestorDecay_favorsQueryDomainOwnResults() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.0, 0.0, 10, 10, 0.7);
        List<Content> result = s.fuse("q", List.of(
                new RankedList("self", Modality.VECTOR, 0, List.of(content("own", 0.9f))),
                new RankedList("parent", Modality.VECTOR, 1, List.of(content("inherited", 0.9f)))
        ), cfg);

        double own = score(result, "own");
        double inherited = score(result, "inherited");
        assertTrue(own > inherited, "本域自有知识应排在继承来的知识之前");
        // 两者名次都是 1、权重比 = 1 : 0.7（再各自归一化），故分比值就是 1/0.7
        assertEquals(1.0 / 0.7, own / inherited, 1e-9);
    }

    @Test
    void ancestorDecayOne_meansEqualWeight() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.0, 0.0, 10, 10, 1.0);
        List<Content> result = s.fuse("q", List.of(
                new RankedList("self", Modality.VECTOR, 0, List.of(content("own", 0.9f))),
                new RankedList("parent", Modality.VECTOR, 1, List.of(content("inherited", 0.9f)))
        ), cfg);
        assertEquals(score(result, "own"), score(result, "inherited"), 1e-9);
    }

    /**
     * 增益必须是<b>乘法</b>：RRF 分约 0.01 量级，若沿用加法（+0.15）会变成十几倍，
     * 排序直接被 boost 统治、且域链越长越严重。
     */
    @Test
    void titleBoost_isMultiplicative() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.5, 0.0, 10, 10, 1.0);
        List<Content> result = s.fuse("退款", List.of(
                list("i1", Modality.VECTOR, content("plain", 0.9f)),
                list("i2", Modality.VECTOR, titled("hit", "退款政策", 0.9f))
        ), cfg);

        assertEquals(score(result, "plain") * 1.5, score(result, "hit"), 1e-9,
                "标题命中应是 ×1.5，而不是 +0.5");
    }

    /**
     * 中文长问句也必须能触发标题增益。
     *
     * <p>这是 {@link #titleBoost_isMultiplicative()} 的中文版：原实现在这条上是 0 命中
     * （整段匹配要求标题包含整句问句），{@code title-boost} 配置在中文下从未生效。</p>
     */
    @Test
    void titleBoost_appliesToChineseLongQuestion() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.5, 0.0, 10, 10, 1.0);
        List<Content> result = s.fuse("会员退款的时效说明", List.of(
                list("i1", Modality.VECTOR, content("plain", 0.9f)),
                list("i2", Modality.VECTOR, titled("hit", "退款政策", 0.9f))
        ), cfg);

        assertEquals(score(result, "plain") * 1.5, score(result, "hit"), 1e-9,
                "「退款政策」应被「会员退款的时效说明」命中标题并 ×1.5");
    }

    /** 融合分完全相同时按 chunk_seq 稳定排序 —— 同一问题两次检索必须给同一份答案。 */
    @Test
    void tieOnFusedScore_orderedByChunkSeq() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, seqContent("second", 0.9f, 2)),
                list("i2", Modality.VECTOR, seqContent("first", 0.9f, 1))
        ), FusionConfig.defaults());

        assertEquals(List.of("first", "second"),
                result.stream().map(c -> c.textSegment().text()).toList());
    }

    private static Content titled(String text, String title, float rawScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        m.put("section_title", title);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static Content seqContent(String text, float rawScore, int chunkSeq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        m.put("chunk_seq", chunkSeq);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static double score(List<Content> result, String text) {
        return result.stream()
                .filter(c -> text.equals(c.textSegment().text()))
                .findFirst().orElseThrow()
                .textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
    }
}
