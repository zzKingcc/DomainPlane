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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 查询向量化"一轮一次"锁：向量化上移到编排层（{@link QueryVectorResolver}）的全部
 * 意义就在于——域链 5 层就是 5 个向量通道，旧实现每条各 embed 一次，模型按次计费、
 * 延迟按串行累加，而 5 次的结果完全相同。
 *
 * <p>本测试钉三件事：① 多个向量通道只 embed 一次；② 每个向量通道收到的是同一个
 * 向量（内容一致）；③ 关键词通道不消耗 embed。任何一条被破坏，成本与延迟都会
 * 随域链深度线性放大，而功能测试完全看不出异常。</p>
 *
 * @author zzkingcc
 */
@DisplayName("查询向量化一轮一次：多向量通道共享同一次 embed")
class QueryVectorOnceTest {

    private static Content c(String text, float raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) raw);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static final float[] QUERY_VECTOR = {0.1f, 0.2f, 0.3f};

    private static CompositeRetriever.Channel vecChannel(String source, VectorContentRetriever retriever) {
        return new CompositeRetriever.Channel(source, source + "|loc|VECTOR",
                Modality.VECTOR, retriever, 0);
    }

    /** 记录收到的查询向量，并按 label 返回不同文本（融合按内容哈希去重，同文本会并成一条） */
    private static VectorContentRetriever recording(List<float[]> received, String label) {
        return (query, queryVector) -> {
            received.add(queryVector);
            return List.of(c(label, 0.9f));
        };
    }

    @Test
    @DisplayName("3 个向量通道 + 1 个关键词通道：embed 只调一次，向量全通道一致")
    void embedCalledOnceAcrossAllVectorChannels() {
        int[] calls = {0};
        QueryVectorResolver resolver = text -> {
            calls[0]++;
            return QUERY_VECTOR;
        };

        List<float[]> received = new CopyOnWriteArrayList<>();

        ContentRetriever kw = query -> List.of(c("k", 0.8f));
        CompositeRetriever.Channel keyword = new CompositeRetriever.Channel(
                "default", "default|loc|KEYWORD", Modality.KEYWORD, kw, 0);

        CompositeRetriever r = new CompositeRetriever(
                () -> List.of(vecChannel("default", recording(received, "v1")),
                        vecChannel("default.sales", recording(received, "v2")),
                        vecChannel("default.sales.eu", recording(received, "v3")), keyword),
                FusionConfig.defaults(), null, 0L, null, resolver);

        List<Content> out = r.retrieve(new Query("q"));

        assertEquals(1, calls[0], "域链上 N 个向量通道只允许一次 embed（按次计费）");
        assertEquals(3, received.size(), "3 个向量通道都拿到了向量");
        for (float[] v : received) {
            assertArrayEquals(QUERY_VECTOR, v, "每个向量通道收到的必须是同一次 embed 的结果");
        }
        assertEquals(4, out.size(), "三路向量 + 一路关键词，去重后 4 条不同内容");
    }

    @Test
    @DisplayName("单个向量通道也走编排层向量化（不退化回检索器自行 embed）")
    void singleVectorChannel_usesSharedVector() {
        int[] calls = {0};
        QueryVectorResolver resolver = text -> {
            calls[0]++;
            return QUERY_VECTOR;
        };
        List<float[]> received = new CopyOnWriteArrayList<>();
        VectorContentRetriever recording = (query, queryVector) -> {
            received.add(queryVector);
            return List.of(c("v", 0.9f));
        };

        CompositeRetriever r = new CompositeRetriever(
                () -> List.of(new CompositeRetriever.Channel(
                        "default.sales", "es-main|kb_x|VECTOR", Modality.VECTOR, recording, 0)),
                FusionConfig.defaults(), null, 0L, null, resolver);

        r.retrieve(new Query("q"));

        assertEquals(1, calls[0]);
        assertArrayEquals(QUERY_VECTOR, received.get(0));
    }
}
