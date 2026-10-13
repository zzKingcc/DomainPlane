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
 * 锁住分数制融合（<b>单索引</b>轨）：去重 / 归一化加权 / TopN 重排 / 标题文件名 boost / 通道标记。
 * 这些是升级前 {@code CompositeRetriever} 的内联行为，抽成 {@link DefaultFusionStrategy} 后
 * 必须逐字节等价：本测试即"对拍"。
 */
class DefaultFusionStrategyTest {

    private static Content content(String text, float rawScore, String title, String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        if (title != null) {
            m.put("section_title", title);
        }
        if (fileName != null) {
            m.put("file_name", fileName);
        }
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static RankedList vec(Content... contents) {
        return new RankedList("idx", Modality.VECTOR, List.of(contents));
    }

    private static RankedList vec(List<Content> contents) {
        return new RankedList("idx", Modality.VECTOR, contents);
    }

    private static RankedList kw(Content... contents) {
        return new RankedList("idx", Modality.KEYWORD, List.of(contents));
    }

    private static RankedList kw(List<Content> contents) {
        return new RankedList("idx", Modality.KEYWORD, contents);
    }

    @Test
    void emptyLists_returnEmpty() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        assertTrue(s.fuse("q", List.of(), FusionConfig.defaults()).isEmpty());
    }

    @Test
    void dedupAcrossChannels_keepsBothScoresAndMarksBoth() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content shared = content("same text", 0.9f, "退款", "a.md");
        // 同一条内容出现在两路 -> 去重为 1 条，但两个通道分都记录
        List<Content> result = s.fuse("退款", List.of(vec(shared), kw(shared)), FusionConfig.defaults());
        assertEquals(1, result.size());
        Metadata m = result.get(0).textSegment().metadata();
        assertEquals("both", m.getString(RetrievalScoreKeys.MATCH_CHANNEL));
        assertTrue(m.toMap().containsKey(RetrievalScoreKeys.VECTOR_SCORE));
        assertTrue(m.toMap().containsKey(RetrievalScoreKeys.KEYWORD_SCORE));
        assertEquals(1, m.getInteger(RetrievalScoreKeys.FUSION_RANK));
    }

    @Test
    void topN_truncatesAndRanksByFusedScoreDesc() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content c1 = content("A", 0.9f, null, null);
        Content c2 = content("B", 0.5f, null, null);
        Content c3 = content("C", 0.1f, null, null);
        // 向量权重 1.0、无 boost、TopN=2
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.0, 0.0, 2, 60);
        List<Content> result = s.fuse("q", List.of(vec(List.of(c1, c2, c3))), cfg);
        assertEquals(2, result.size());
        double first = result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        double second = result.get(1).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        assertTrue(first >= second, "融合分应按降序");
        assertEquals(1, result.get(0).textSegment().metadata().getInteger(RetrievalScoreKeys.FUSION_RANK));
        assertEquals(2, result.get(1).textSegment().metadata().getInteger(RetrievalScoreKeys.FUSION_RANK));
    }

    @Test
    void titleMatch_getsBoostAndRanksFirst() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        // 两路原始分相同；一条标题命中查询词 -> 应加 boost 并排第一
        Content withTitle = content("X", 0.5f, "退款政策", "a.md");
        Content withoutTitle = content("Y", 0.5f, "其他说明", "b.md");
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.2, 0.0, 10, 60);
        List<Content> result = s.fuse("退款", List.of(vec(List.of(withTitle, withoutTitle))), cfg);
        assertEquals(2, result.size());
        assertEquals("X", result.get(0).textSegment().text());
        double boosted = result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        double plain = result.get(1).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        assertTrue(boosted > plain, "标题命中应获得更高融合分");
    }

    @Test
    void differentChannels_distinctEntries() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content v = content("vec-only", 0.7f, null, null);
        Content k = content("kw-only", 0.6f, null, null);
        List<Content> result = s.fuse("q", List.of(vec(v), kw(k)), FusionConfig.defaults());
        assertEquals(2, result.size());
        Map<String, Object> meta = result.stream()
                .filter(c -> "vec-only".equals(c.textSegment().text()))
                .findFirst().orElseThrow().textSegment().metadata().toMap();
        assertEquals("vector", meta.get(RetrievalScoreKeys.MATCH_CHANNEL));
    }

    /**
     * 多张同模态的排名表在分数制下会拼回一路（这是"单来源轨"的假设）；
     * 真正多来源时应由 {@link AdaptiveFusionStrategy} 走 RRF：此用例只钉住拼表行为。
     */
    @Test
    void multipleListsOfSameModality_areConcatenated() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content a = content("A", 0.9f, null, null);
        Content b = content("B", 0.4f, null, null);
        List<Content> result = s.fuse("q",
                List.of(new RankedList("i1", Modality.VECTOR, List.of(a)),
                        new RankedList("i2", Modality.VECTOR, List.of(b))),
                new FusionConfig(1.0, 0.0, 0.0, 0.0, 10, 60));
        assertEquals(2, result.size());
        assertEquals("A", result.get(0).textSegment().text());
    }

    /**
     * 增益是<b>乘法</b>而非加法：中间那条归一化 0.5，标题命中后应是 0.5×1.2=0.6，
     * 加法会得到 0.5+0.2=0.7：同一常量在两轨下含义必须一致，故统一为乘法。
     */
    @Test
    void titleBoost_isMultiplicative_notAdditive() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content top = content("top", 1.0f, null, null);
        Content mid = content("mid", 0.5f, "退款政策", "a.md");
        Content bottom = content("bottom", 0.0f, null, null);
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.2, 0.0, 10, 60);
        List<Content> result = s.fuse("退款", List.of(vec(List.of(top, mid, bottom))), cfg);

        Map<String, Double> scores = new LinkedHashMap<>();
        result.forEach(c -> scores.put(c.textSegment().text(),
                c.textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE)));
        assertEquals(0.5 * 1.2, scores.get("mid"), 1e-9);
        assertTrue(scores.get("mid") < scores.get("top"), "增益后仍不应越过更高分的那条");
    }
}
