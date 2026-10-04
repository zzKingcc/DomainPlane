package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略工厂的扩展名路由表。
 *
 * <p>这张表是"上传的文件名 → 走哪个适配器"的唯一入口，一个扩展名落到错的地方就是静默入库一堆垃圾，
 * 所以逐个钉住。工厂的静态初始化里本来就有"扩展名冲突直接抛"的检查，这里也顺手确认它没被触发。</p>
 */
class DocumentProcessStrategyFactoryTest {

    private static IngestSource doc(String fileName) {
        return IngestSource.ofBinary(new byte[0], fileName, Metadata.from("file_name", fileName));
    }

    @Test
    void 每个扩展名都落到对的策略上() {
        Map<String, Class<?>> expected = Map.ofEntries(
                Map.entry("txt", TextDocumentProcessStrategy.class),
                Map.entry("md", MarkdownDocumentProcessStrategy.class),
                Map.entry("markdown", MarkdownDocumentProcessStrategy.class),
                Map.entry("docx", DocxDocumentProcessStrategy.class),
                Map.entry("doc", DocDocumentProcessStrategy.class),
                Map.entry("pdf", PdfDocumentProcessStrategy.class),
                Map.entry("xls", ExcelDocumentProcessStrategy.class),
                Map.entry("xlsx", ExcelDocumentProcessStrategy.class));

        expected.forEach((ext, type) ->
                assertSame(type, DocumentProcessStrategyFactory.resolve("样例." + ext).getClass(),
                        "." + ext + " 应当走 " + type.getSimpleName()));
    }

    @Test
    void 扩展名路由不区分大小写() {
        assertSame(ExcelDocumentProcessStrategy.class,
                DocumentProcessStrategyFactory.resolve("规则.XLSX").getClass());
        assertSame(DocDocumentProcessStrategy.class,
                DocumentProcessStrategyFactory.resolve("规则.DOC").getClass());
    }

    @Test
    void 认不出的扩展名落到未知策略() {
        assertSame(UnknownDocumentProcessStrategy.INSTANCE,
                DocumentProcessStrategyFactory.resolve("说明.ppt"));
        assertSame(UnknownDocumentProcessStrategy.INSTANCE,
                DocumentProcessStrategyFactory.resolve("没有扩展名"));
        assertSame(UnknownDocumentProcessStrategy.INSTANCE,
                DocumentProcessStrategyFactory.resolve(null));
    }

    @Test
    void 分组时按策略归并_并且丢掉空组() {
        List<IngestSource> documents = new ArrayList<>(List.of(
                doc("规则.xls"), doc("规则.xlsx"), doc("手册.doc"),
                doc("手册.md"), doc("手册.pdf"), doc("没有扩展名")));

        Map<DocumentProcessStrategy, List<IngestSource>> grouped =
                DocumentProcessStrategyFactory.groupByStrategy(documents);

        List<String> names = grouped.keySet().stream()
                .map(DocumentProcessStrategy::strategyName).toList();
        // 6 个文档 → 5 组：xls/xlsx 合成 Excel 一组，没有文档的策略组不进 map
        assertEquals(5, grouped.size(), names.toString());
        assertEquals(2, grouped.get(findExcel(grouped)).size(), "xls 与 xlsx 归到同一组");
        assertTrue(grouped.values().stream().noneMatch(List::isEmpty));
    }

    /** 分组键是策略实例，工厂里的是私有单例，只能按类型找 */
    private static DocumentProcessStrategy findExcel(Map<DocumentProcessStrategy, List<IngestSource>> grouped) {
        return grouped.keySet().stream()
                .filter(s -> s.getClass() == ExcelDocumentProcessStrategy.class)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 每个策略的扩展名都是小写_否则路由表会有两个键() {
        for (DocumentProcessStrategy strategy : DocumentProcessStrategyFactory.allStrategies()) {
            for (String ext : strategy.supportedExtensions()) {
                assertEquals(ext.toLowerCase(Locale.ROOT), ext,
                        strategy.strategyName() + " 的扩展名 " + ext + " 应当是小写");
            }
        }
    }
}
