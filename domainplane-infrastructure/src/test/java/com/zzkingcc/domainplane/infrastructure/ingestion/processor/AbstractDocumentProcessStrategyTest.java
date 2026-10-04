package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestLimits;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入侧的全局限制：单文件切片数上限。
 */
class AbstractDocumentProcessStrategyTest {

    /** 只造切片、不碰 ES 的策略，用来单测「超上限就拒绝」这一条 */
    private static final class CountingStrategy extends AbstractDocumentProcessStrategy {

        @Override
        protected SplitResult splitDocuments(List<IngestSource> documents) {
            return SplitResult.empty();
        }

        @Override
        public List<String> supportedExtensions() {
            return List.of("fake");
        }

        @Override
        public String strategyName() {
            return "测试用";
        }

        void guard(List<IngestSource> documents, int chunks) {
            List<TextSegment> segments = new ArrayList<>(chunks);
            for (int i = 0; i < chunks; i++) {
                segments.add(TextSegment.from("片" + i));
            }
            guardChunkLimit(documents, segments);
        }
    }

    private static IngestSource doc(String fileName) {
        return IngestSource.ofText("正文", new byte[0], fileName,
                Metadata.from("file_name", fileName), "UTF-8");
    }

    @Test
    void 切片数超过上限_直接拒绝并说明怎么改() {
        IngestLimits.configure(10);
        try {
            KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                    () -> new CountingStrategy().guard(List.of(doc("大文档.pdf")), 11));
            assertTrue(ex.getMessage().contains("超过单文件上限"), ex.getMessage());
            assertTrue(ex.getMessage().contains("大文档.pdf"), ex.getMessage());
        } finally {
            IngestLimits.configure(IngestLimits.DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
        }
    }

    @Test
    void 切片数正好等于上限_放行() {
        IngestLimits.configure(10);
        try {
            new CountingStrategy().guard(List.of(doc("大文档.pdf")), 10);
        } finally {
            IngestLimits.configure(IngestLimits.DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
        }
    }
}
