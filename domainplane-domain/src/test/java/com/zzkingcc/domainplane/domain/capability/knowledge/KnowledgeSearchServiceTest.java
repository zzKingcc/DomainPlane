package com.zzkingcc.domainplane.domain.capability.knowledge;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索结果注入侧的两道收口：条数上限（可配）与正文字符预算。
 *
 * <p>历史问题是注入条数被硬编码成 5：那是唯一不可配的截断，且是二次截断
 * （融合 topN 已经收过一次）。条数本身不是瓶颈，字符数才是：同样的条数，切片长短能差几倍。</p>
 */
class KnowledgeSearchServiceTest {

    @AfterEach
    void resetLimits() {
        RetrievalLimits.configure(8, 3000);
    }

    private static Content segment(String text) {
        return Content.from(TextSegment.from(text));
    }

    private static KnowledgeSearchService serviceReturning(List<Content> contents) {
        return new KnowledgeSearchService(query -> contents);
    }

    @Test
    void injectTopN_capsSegmentCount() {
        RetrievalLimits.configure(2, 100_000);
        List<Content> contents = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            contents.add(segment("片段内容" + i));
        }

        String out = serviceReturning(contents).searchKnowledgeBase("q");

        assertTrue(out.contains("【片段1"));
        assertTrue(out.contains("【片段2"));
        assertFalse(out.contains("【片段3"), "注入条数超过 inject-top-n，应被截断");
    }

    @Test
    void maxContextChars_stopsBeforeOvershoot() {
        RetrievalLimits.configure(8, 250);
        List<Content> contents = List.of(
                segment("A".repeat(100)),
                segment("B".repeat(100)),
                segment("C".repeat(100)));

        String out = serviceReturning(contents).searchKnowledgeBase("q");

        assertTrue(out.contains("【片段2"), "200 字未越过 250 字预算");
        assertFalse(out.contains("【片段3"), "300 字会越过预算，应在此停下");
    }

    @Test
    void tinyBudget_stillInjectsFirstSegment() {
        RetrievalLimits.configure(8, 10);
        List<Content> contents = List.of(segment("X".repeat(500)), segment("Y".repeat(500)));

        String out = serviceReturning(contents).searchKnowledgeBase("q");

        assertTrue(out.contains("【片段1"), "首条无论如何都要给，否则预算配小了等于把检索关掉");
        assertFalse(out.contains("【片段2"));
    }

    @Test
    void noResult_returnsHint() {
        assertEquals("未检索到相关内容", serviceReturning(List.of()).searchKnowledgeBase("q"));
    }

    /**
     * 检索链路本身坏了，必须与"真没命中"区分开：否则调用方会换个问法反复试，
     * 而不是去查配置。
     */
    @Test
    void retrieverFailure_returnsUnavailableHint() {
        KnowledgeSearchService svc = new KnowledgeSearchService(query -> {
            throw new IllegalStateException("es down");
        });

        assertTrue(svc.searchKnowledgeBase("q").contains("不可用"));
    }
}
