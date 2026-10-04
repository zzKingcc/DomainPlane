package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 策略到切片层的串起来的样子：工作表 → {@code section_path}，以及
 * "所有工作表都是空的"必须报错。
 */
class ExcelDocumentProcessStrategyTest {

    private static byte[] bytes(Workbook workbook) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            workbook.write(out);
            workbook.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static Workbook rules() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("退货规则");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("型号");
        header.createCell(1).setCellValue("期限");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("SKU-10086");
        row.createCell(1).setCellValue(30);
        return workbook;
    }

    private static IngestSource excel(byte[] content, String fileName) {
        return IngestSource.ofBinary(content, fileName, Metadata.from("file_name", fileName));
    }

    @Test
    void xls与xlsx都认_且是二进制() {
        ExcelDocumentProcessStrategy strategy = new ExcelDocumentProcessStrategy();
        assertTrue(strategy.binary());
        assertEquals(List.of("xls", "xlsx"), strategy.supportedExtensions());
    }

    @Test
    void 工作表进section_path_路径是文件名加工作表名() {
        SplitResult result = new ExcelDocumentProcessStrategy()
                .splitDocuments(List.of(excel(bytes(rules()), "售后规则.xlsx")));

        List<TextSegment> segments = result.segments();
        assertEquals(1, segments.size());
        TextSegment segment = segments.get(0);
        assertEquals("售后规则 > 退货规则", segment.metadata().getString("section_path"));
        assertEquals("退货规则", segment.metadata().getString("section_title"));
        assertTrue(segment.text().contains("SKU-10086"), segment.text());
        assertTrue(segment.text().contains("| 型号 | 期限 |"), segment.text());
    }

    @Test
    void 老容器格式xls_走同一条策略() {
        HSSFWorkbook workbook = new HSSFWorkbook();
        Sheet sheet = workbook.createSheet("退货规则");
        sheet.createRow(0).createCell(0).setCellValue("型号");
        sheet.createRow(1).createCell(0).setCellValue("SKU-20001");

        SplitResult result = new ExcelDocumentProcessStrategy()
                .splitDocuments(List.of(excel(bytes(workbook), "售后规则.xls")));

        assertEquals("售后规则 > 退货规则",
                result.segments().get(0).metadata().getString("section_path"));
    }

    @Test
    void 所有工作表都为空_报错而不是导入成功零片() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        workbook.createSheet("空表");

        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> new ExcelDocumentProcessStrategy()
                        .splitDocuments(List.of(excel(bytes(workbook), "空表.xlsx"))));

        assertTrue(ex.getMessage().contains("没有解析出任何表格内容"), ex.getMessage());
        assertTrue(ex.getMessage().contains("空表.xlsx"), ex.getMessage());
    }
}
