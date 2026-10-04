package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.doc.DocFixtures;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧版 {@code .doc} 策略到切片层的串起来的样子：标题层级 → {@code section_path}，
 * 表格 → 可检索的切片。
 */
class DocDocumentProcessStrategyTest {

    private static IngestSource doc(String fixtureName, String fileName) {
        return IngestSource.ofBinary(DocFixtures.load(fixtureName),
                fileName, Metadata.from("file_name", fileName));
    }

    @Test
    void 旧版doc是二进制_入口不解码() {
        assertTrue(new DocDocumentProcessStrategy().binary());
        assertEquals(List.of("doc"), new DocDocumentProcessStrategy().supportedExtensions());
    }

    @Test
    void 没有标题样式的老doc_整篇落进文件名那一段() {
        // simple-table.doc 实测全是 Normal 段落（老 .doc 的常态）
        SplitResult result = new DocDocumentProcessStrategy()
                .splitDocuments(List.of(doc("simple-table.doc", "simple-table.doc")));

        List<TextSegment> segments = result.segments();
        assertTrue(segments.size() >= 2, "段落 + 表格至少两片：" + segments.size());
        for (TextSegment segment : segments) {
            assertEquals("simple-table", segment.metadata().getString("section_path"),
                    "没有标题时整篇同属一个 section，靠 chunk_seq 保序");
        }
        assertTrue(segments.stream().anyMatch(s -> s.text().contains("Cell 1,1")),
                "表格内容必须能检索到");
    }

    @Test
    void 有标题样式的老doc_标题成为section_path() {
        SplitResult result = new DocDocumentProcessStrategy()
                .splitDocuments(List.of(doc("Lists.doc", "列表样例.doc")));

        List<TextSegment> segments = result.segments();
        assertEquals("Heading Level 1", segments.get(0).metadata().getString("section_path"));
        assertEquals("Heading Level 1", segments.get(0).metadata().getString("section_title"));
        assertTrue(segments.get(0).text().startsWith("Heading Level 1"), segments.get(0).text());
    }
}
