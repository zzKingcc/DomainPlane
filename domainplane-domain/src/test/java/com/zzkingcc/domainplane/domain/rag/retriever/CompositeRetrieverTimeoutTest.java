package com.zzkingcc.domainplane.domain.rag.retriever;

import com.zzkingcc.domainplane.domain.rag.fusion.FusionConfig;
import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：超时必须是<b>整轮共享的总预算</b>，不是每条通道各等一次。
 *
 * <p>缺陷形态很隐蔽：每通道各等 5s 时，10 条通道全超时 = 50s，而配置名是
 * {@code domainplane.retrieval.timeout-ms}，调用方合理预期是"整个检索 5s"。
 * 编排线程池 core 8 / max 32，几十个这样的请求就能占满。</p>
 */
class CompositeRetrieverTimeoutTest {

    /** 一条会睡固定时长的通道 */
    private static ContentRetriever slow(long millis) {
        return q -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            TextSegment seg = TextSegment.from("内容-" + millis);
            return List.of(Content.from(seg));
        };
    }

    private static CompositeRetriever.Channel channel(String id, ContentRetriever r) {
        return new CompositeRetriever.Channel(id, Modality.VECTOR, r);
    }

    @Test
    void parallelChannelsShareOneGlobalBudget() {
        // 8 条通道各睡 5s，单通道预算 200ms。逐条各等 200ms ⇒ 约 1.6s；
        // 共享 200ms ⇒ 约 200ms 就该全部放弃。
        List<CompositeRetriever.Channel> channels = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            channels.add(channel("idx" + i, slow(5_000)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CompositeRetriever retriever = new CompositeRetriever(
                    () -> channels, FusionConfig.defaults(), pool, 200L);

            long t0 = System.currentTimeMillis();
            try {
                retriever.retrieve(new Query("q"));
            } catch (IllegalStateException expected) {
                // 全通道失败是预期结果：要看的不是抛不抛，而是耗时
            }
            long elapsed = System.currentTimeMillis() - t0;

            assertTrue(elapsed < 1_200,
                    "并行下 8 条通道应共享 200ms 总预算，实际耗时 " + elapsed
                            + "ms —— 超过说明退化成逐条各等一次超时");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void serialModeAlsoRespectsBudget() {
        // 串行模式（executor == null）此前完全没有超时保护：一条卡死通道就能无限期占住线程
        List<CompositeRetriever.Channel> channels = List.of(
                channel("slow1", slow(5_000)),
                channel("slow2", slow(5_000)));
        CompositeRetriever retriever = new CompositeRetriever(
                () -> channels, FusionConfig.defaults(), null, 150L);

        long t0 = System.currentTimeMillis();
        try {
            retriever.retrieve(new Query("q"));
        } catch (IllegalStateException expected) {
            // 全通道超时 ⇒ 全部失败，按设计抛"无法判断是否存在相关内容"
        }
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed < 1_200,
                "串行模式也应受超时约束，实际耗时 " + elapsed + "ms —— 超过说明没有任何超时保护");
    }

    @Test
    void fastChannelsStillReturnResults() {
        List<CompositeRetriever.Channel> channels = List.of(
                channel("fast", q -> List.of(Content.from(TextSegment.from("命中内容")))));
        CompositeRetriever retriever = new CompositeRetriever(
                () -> channels, FusionConfig.defaults(), null, 1_000L);

        List<Content> result = retriever.retrieve(new Query("q"));
        assertEquals(1, result.size(), "未超时的通道应正常返回结果");
    }

    @Test
    void emptyChannelListIsNoHitNotFailure() {
        CompositeRetriever retriever = new CompositeRetriever(
                List::of, FusionConfig.defaults(), null, 1_000L);
        assertEquals(List.of(), retriever.retrieve(new Query("q")),
                "域链上无索引是常态，返回空即可，不该抛");
    }
}
