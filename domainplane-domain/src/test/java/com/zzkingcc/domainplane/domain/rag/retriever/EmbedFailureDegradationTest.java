package com.zzkingcc.domainplane.domain.rag.retriever;

import com.zzkingcc.domainplane.domain.rag.fusion.FusionConfig;
import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.model.RetrievalScoreKeys;
import com.zzkingcc.domainplane.domain.rag.store.QueryVectorResolver;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A2 锁（一审修订）：查询向量化失败 → 本轮向量通道全部降级、关键词通道照常执行。
 *
 * <p>这条语义是"不让一次模型抖动演变成整轮检索失败"的唯一防线。没有它，
 * embed 抛异常会顺着 {@code retrieve} 逃逸出去，"链路坏了"与"真没命中"
 * 再次同形：调用方会换个问法反复试，而不是去查模型配置。反过来也钉：
 * 向量路不可用时不能把关键词路一起陪葬，更不能把失败吞成空结果。</p>
 *
 * @author zzkingcc
 */
@DisplayName("A2：embed 失败只降级向量路，关键词路照常")
class EmbedFailureDegradationTest {

    private static Content c(String text, float raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) raw);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static final QueryVectorResolver FAILING_RESOLVER =
            text -> {
                throw new RuntimeException("模型超时");
            };

    /** 向量通道检索器：被调用即测试失败——embed 失败时编排层根本不该走到这里 */
    private static final VectorContentRetriever MUST_NOT_RUN =
            (query, queryVector) -> {
                throw new AssertionError("embed 失败时不应调用向量通道检索器");
            };

    private static CompositeRetriever.Channel vecChannel(String source) {
        return new CompositeRetriever.Channel(source, source + "|loc|VECTOR",
                Modality.VECTOR, MUST_NOT_RUN, 0);
    }

    private static CompositeRetriever.Channel kwChannel(String source) {
        ContentRetriever kw = query -> List.of(c("关键词命中", 0.8f));
        return new CompositeRetriever.Channel(source, source + "|loc|KEYWORD",
                Modality.KEYWORD, kw, 0);
    }

    private static CompositeRetriever retriever(List<CompositeRetriever.Channel> channels,
                                                QueryVectorResolver resolver) {
        return new CompositeRetriever(() -> channels, FusionConfig.defaults(),
                null, 0L, null, resolver);
    }

    @Test
    @DisplayName("embed 失败：向量路降级、关键词路照常返回")
    void embedFails_vectorDegrades_keywordProceeds() {
        CompositeRetriever r = retriever(
                List.of(vecChannel("default.sales"), kwChannel("default.sales")), FAILING_RESOLVER);

        List<Content> out = r.retrieve(new Query("q"));

        assertEquals(1, out.size(), "只剩关键词路的命中");
        assertEquals("关键词命中", out.get(0).textSegment().text());
    }

    @Test
    @DisplayName("embed 失败且全是向量路：全部通道失败 → 抛（不吞成空结果）")
    void embedFails_allVectorChannels_throws() {
        CompositeRetriever r = retriever(
                List.of(vecChannel("default.sales"), vecChannel("default")), FAILING_RESOLVER);

        assertThrows(IllegalStateException.class, () -> r.retrieve(new Query("q")),
                "全部通道失败 ≠ 没有相关内容：必须抛，让上层知道链路坏了");
    }

    @Test
    @DisplayName("没有向量路时根本不调 resolver（省一次必然浪费的 embed）")
    void noVectorChannels_resolverNeverInvoked() {
        QueryVectorResolver tripwire = text -> {
            throw new AssertionError("没有向量通道时不应做查询向量化");
        };
        CompositeRetriever r = retriever(List.of(kwChannel("default.sales")), tripwire);

        List<Content> out = r.retrieve(new Query("q"));

        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("embed 失败不重试：一轮只调一次 resolver（失败也只调一次）")
    void embedFailure_isNotRetried() {
        int[] calls = {0};
        QueryVectorResolver counting = text -> {
            calls[0]++;
            throw new RuntimeException("模型超时");
        };
        CompositeRetriever r = retriever(
                List.of(vecChannel("default.sales"), kwChannel("default.sales")), counting);

        r.retrieve(new Query("q"));

        assertEquals(1, calls[0], "失败重试会把模型抖动放大成 N 次计费调用");
    }
}
