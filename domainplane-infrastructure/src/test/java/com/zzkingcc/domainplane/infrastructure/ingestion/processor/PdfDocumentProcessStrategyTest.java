package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.pdf.PdfSamples;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pdf 策略到切片层的串起来的样子：页 → {@code section_path} / {@code page_from}，
 * 以及"抽不出文字"必须报错。
 */
class PdfDocumentProcessStrategyTest {

    private static IngestSource pdf(byte[] content, String fileName) {
        return IngestSource.ofBinary(content, fileName, Metadata.from("file_name", fileName));
    }

    @Test
    void 扫描件_报错而不是导入成功零片() {
        IngestSource document = pdf(PdfSamples.withoutText(), "扫描件.pdf");

        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> new PdfDocumentProcessStrategy().splitDocuments(List.of(document)));

        assertTrue(ex.getMessage().contains("没有可抽取的文本"), ex.getMessage());
        assertTrue(ex.getMessage().contains("扫描件.pdf"), ex.getMessage());
    }

    @Test
    void 页码进section_path与page_from() {
        byte[] bytes = PdfSamples.build(List.of(
                PdfSamples.body("Refunds are processed within seven days of delivery."),
                PdfSamples.body("Contact support for late deliveries.")));

        SplitResult result = new PdfDocumentProcessStrategy()
                .splitDocuments(List.of(pdf(bytes, "会员手册.pdf")));

        List<TextSegment> segments = result.segments();
        assertEquals(2, segments.size());

        Metadata first = segments.get(0).metadata();
        assertEquals("会员手册 > 第1页", first.getString("section_path"));
        assertEquals(1, first.getInteger("page_from"));
        assertTrue(segments.get(0).text().startsWith("会员手册 > 第1页"), segments.get(0).text());

        Metadata second = segments.get(1).metadata();
        assertEquals("会员手册 > 第2页", second.getString("section_path"));
        assertEquals(2, second.getInteger("page_from"));
    }
}
