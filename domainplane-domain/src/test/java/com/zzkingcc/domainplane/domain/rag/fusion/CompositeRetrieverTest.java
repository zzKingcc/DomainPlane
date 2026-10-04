package com.zzkingcc.domainplane.domain.rag.fusion;

import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import com.zzkingcc.domainplane.domain.rag.model.RetrievalScoreKeys;
import com.zzkingcc.domainplane.domain.rag.retriever.CompositeRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link CompositeRetriever} 只做编排与失败处理：按通道召回 → 组成排名表 → 融合委托给
 * {@link FusionStrategy}，自己不含任何融合算法。
 */
class CompositeRetrieverTest {

    private static Content c(String text, float raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) raw);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static final ContentRetriever VEC = query -> List.of(c("v", 0.9f));
    private static final ContentRetriever KW = query -> List.of(c("k", 0.8f));

    private static CompositeRetriever.Channel channel(String index, Modality modality,
                                                      ContentRetriever retriever) {
        return new CompositeRetriever.Channel(index, modality, retriever);
    }

    private static CompositeRetriever.ChannelProvider fixed(CompositeRetriever.Channel... channels) {
        return () -> List.of(channels);
    }

    @Test
    void fansOutToChannels_andDelegatesFusion() {
        CompositeRetriever r = new CompositeRetriever(
                fixed(channel("i1", Modality.VECTOR, VEC), channel("i1", Modality.KEYWORD, KW)),
                FusionConfig.defaults(), null, 0L, null);
        List<Content> out = r.retrieve(new Query("q"));
        // 两个通道各 1 条不同内容 -> 去重后 2 条
        assertEquals(2, out.size());
    }

    @Test
    void providerResolvingNothing_returnsEmptyInsteadOfThrowing() {
        // 域链上没有任何索引是可发生的常态（该域还没上传过文档），不是故障
        CompositeRetriever r = new CompositeRetriever(
                List::of, FusionConfig.defaults(), null, 0L, null);
        assertTrue(r.retrieve(new Query("q")).isEmpty());
    }

    @Test
    void oneChannelFails_stillReturnsFromOther() {
        ContentRetriever failing = query -> {
            throw new RuntimeException("boom");
        };
        CompositeRetriever r = new CompositeRetriever(
                fixed(channel("i1", Modality.VECTOR, VEC), channel("i1", Modality.KEYWORD, failing)),
                FusionConfig.defaults(), null, 0L, null);
        List<Content> out = r.retrieve(new Query("q"));
        assertEquals(1, out.size());
        assertEquals("vector", out.get(0).textSegment().metadata().getString(RetrievalScoreKeys.MATCH_CHANNEL));
    }

    @Test
    void allChannelsFail_throws() {
        ContentRetriever failing = query -> {
            throw new RuntimeException("boom");
        };
        CompositeRetriever r = new CompositeRetriever(
                fixed(channel("i1", Modality.VECTOR, failing), channel("i1", Modality.KEYWORD, failing)),
                FusionConfig.defaults(), null, 0L, null);
        assertThrows(IllegalStateException.class, () -> r.retrieve(new Query("q")));
    }

    @Test
    void customStrategy_isUsed() {
        // 一个只返回固定 1 条的假策略，验证构造器注入生效
        FusionStrategy fixedStrategy = (queryText, lists, fusion) -> List.of(c("injected", 0.5f));
        CompositeRetriever r = new CompositeRetriever(
                fixed(channel("i1", Modality.VECTOR, VEC), channel("i1", Modality.KEYWORD, KW)),
                FusionConfig.defaults(), null, 0L, fixedStrategy);
        List<Content> out = r.retrieve(new Query("q"));
        assertEquals(1, out.size());
        assertEquals("injected", out.get(0).textSegment().text());
    }

    /**
     * 通道的顺序与来源必须原样传给融合策略（多索引时 sourceId 就是索引名，
     * 融合策略靠它判断"几个来源"来决定双轨走哪条）。
     */
    @Test
    void rankedListsCarrySourceAndModality() {
        List<RankedList> captured = new java.util.ArrayList<>();
        FusionStrategy recorder = (queryText, lists, fusion) -> {
            captured.addAll(lists);
            return List.of();
        };
        CompositeRetriever r = new CompositeRetriever(
                fixed(channel("i1", Modality.VECTOR, VEC),
                        channel("i2", Modality.KEYWORD, KW)),
                FusionConfig.defaults(), null, 0L, recorder);
        r.retrieve(new Query("q"));

        assertEquals(2, captured.size());
        assertEquals("i1", captured.get(0).sourceId());
        assertEquals(Modality.VECTOR, captured.get(0).modality());
        assertEquals("i2", captured.get(1).sourceId());
        assertEquals(Modality.KEYWORD, captured.get(1).modality());
    }
}
