package com.zzkingcc.domainplane.infrastructure.ingestion.excel;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockKind;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 解析器的回归样例。
 *
 * <p>工作簿是<b>测试里现造的</b>（用 POI 写一份再读回来），两种容器格式都覆盖：
 * {@code .xlsx}（XSSF）与 {@code .xls}（HSSF）走的是同一个 {@code WorkbookFactory} 入口。</p>
 */
class ExcelReaderTest {

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

    private static List<BlockKind> kinds(ExcelReader.ExcelParseResult result) {
        return result.blocks().stream().map(Block::kind).toList();
    }

    private static String onlyTable(ExcelReader.ExcelParseResult result) {
        List<Block> tables = result.blocks().stream().filter(b -> b.kind() == BlockKind.TABLE).toList();
        assertEquals(1, tables.size(), "这个样例只有一张表");
        return tables.get(0).text();
    }

    // ==================== 分节 ====================

    @Test
    void 每个工作表一节_路径是文件名加工作表名() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet first = workbook.createSheet("退货规则");
        Row row = first.createRow(0);
        row.createCell(0).setCellValue("型号");
        row.createCell(1).setCellValue("期限");
        first.createRow(1).createCell(0).setCellValue("SKU-10086");

        Sheet second = workbook.createSheet("换货规则");
        Row secondRow = second.createRow(0);
        secondRow.createCell(0).setCellValue("型号");
        secondRow.createCell(1).setCellValue("说明");
        second.createRow(1).createCell(0).setCellValue("SKU-20001");

        ExcelReader.ExcelParseResult result = ExcelReader.read(bytes(workbook), "售后规则.xlsx");

        assertEquals(List.of(BlockKind.TITLE, BlockKind.TITLE, BlockKind.TABLE,
                BlockKind.TITLE, BlockKind.TABLE), kinds(result));
        // 一级 = 文件名（去掉扩展名），二级 = 工作表名，与 pdf 的「文件名 > 第N页」同形
        assertEquals("售后规则", result.blocks().get(0).text());
        assertEquals(1, result.blocks().get(0).level());
        assertEquals("退货规则", result.blocks().get(1).text());
        assertEquals("换货规则", result.blocks().get(3).text());
        assertEquals(2, result.blocks().get(3).level());
        assertEquals(2, result.sheets());
    }

    @Test
    void 行尾的空列剪掉_表格不带一长串空列() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("规格");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("上装");
        header.createCell(1).setCellValue("下装");
        // 尾部残留几个"有格子但没值"的脏列（只被格式化过的区域就是这样）
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("S");
        row.createCell(1).setCellValue("M");
        row.createCell(2).setCellValue("");
        row.createCell(3).setCellValue("");

        ExcelReader.ExcelParseResult result = ExcelReader.read(bytes(workbook), "规格.xlsx");

        assertEquals("| 上装 | 下装 |\n| --- | --- |\n| S | M |", onlyTable(result));
    }

    @Test
    void 全空行跳过_不影响其余行() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("规格");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("尺码");
        Row blank = sheet.createRow(1);
        blank.createCell(0).setCellValue("");
        sheet.createRow(2).createCell(0).setCellValue("L");

        ExcelReader.ExcelParseResult result = ExcelReader.read(bytes(workbook), "规格.xlsx");

        assertEquals("| 尺码 |\n| --- |\n| L |", onlyTable(result));
    }

    // ==================== 取值 ====================

    @Test
    void 公式取的是值_不是公式文本() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("计算");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("数量");
        header.createCell(1).setCellValue("倍数");
        header.createCell(2).setCellValue("结果");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(3);
        row.createCell(1).setCellValue(2);
        row.createCell(2).setCellFormula("A2*B2");

        ExcelReader.ExcelParseResult result = ExcelReader.read(bytes(workbook), "计算.xlsx");

        // 不传 FormulaEvaluator 的话 POI 会给出 "A2*B2" 这个公式文本 —— 那就把公式当知识入库了
        assertEquals("| 数量 | 倍数 | 结果 |\n| --- | --- | --- |\n| 3 | 2 | 6 |", onlyTable(result));
    }

    @Test
    void 日期按单元格自己的格式串出来_不是序列号() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("有效期");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("类型");
        header.createCell(1).setCellValue("生效日");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("退货");
        row.createCell(1).setCellValue(Date.valueOf("2026-05-29"));
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        row.getCell(1).setCellStyle(style);

        ExcelReader.ExcelParseResult result = ExcelReader.read(bytes(workbook), "有效期.xlsx");

        assertTrue(onlyTable(result).contains("| 退货 | 2026-05-29 |"), onlyTable(result));
    }

    @Test
    void 合并单元格回填_纵向合并的类别列不会只有第一行有值() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("退货规则");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("类别");
        header.createCell(1).setCellValue("型号");
        // 先合并、再往左上角写值：被盖住的格子是真的空的
        sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
        Row first = sheet.createRow(1);
        first.createCell(0).setCellValue("食品");
        first.createCell(1).setCellValue("SKU-10086");
        Row second = sheet.createRow(2);
        second.createCell(1).setCellValue("SKU-20001");

        ExcelReader.ExcelParseResult result = ExcelReader.read(bytes(workbook), "退货规则.xlsx");

        assertEquals("| 类别 | 型号 |\n| --- | --- |\n| 食品 | SKU-10086 |\n| 食品 | SKU-20001 |",
                onlyTable(result));
    }

    // ==================== 两种容器格式 ====================

    @Test
    void xls与xlsx走同一条路_结果一致() {
        String expected = "| 型号 | 期限 |\n| --- | --- |\n| SKU-10086 | 30 |";

        assertEquals(expected, onlyTable(ExcelReader.read(bytes(sheet("xlsx")), "规则.xlsx")));
        assertEquals(expected, onlyTable(ExcelReader.read(bytes(sheet("xls")), "规则.xls")));
    }

    private static Workbook sheet(String kind) {
        Workbook workbook = "xls".equals(kind) ? new HSSFWorkbook() : new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("规则");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("型号");
        header.createCell(1).setCellValue("期限");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("SKU-10086");
        row.createCell(1).setCellValue(30);
        return workbook;
    }

    // ==================== 空表与报错 ====================

    @Test
    void 空工作表跳过_全是空表时不产出任何块() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        workbook.createSheet("空表");
        Sheet onlyFormat = workbook.createSheet("只有格式");
        onlyFormat.createRow(5);   // 有行对象、但一个格子都没有

        ExcelReader.ExcelParseResult result = ExcelReader.read(bytes(workbook), "空表.xlsx");

        assertTrue(result.blocks().isEmpty());
        assertEquals(0, result.sheets());
        assertEquals(2, result.skippedSheets());
    }

    @Test
    void 不是excel时_给出明确错误而不是静默空结果() {
        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> ExcelReader.read("这不是 excel".getBytes(StandardCharsets.UTF_8), "假.xlsx"));

        assertTrue(ex.getMessage().contains("excel 解析失败"), ex.getMessage());
    }

    @Test
    void 内容为空_直接拒绝() {
        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> ExcelReader.read(new byte[0], "空.xlsx"));
        assertTrue(ex.getMessage().contains("内容为空"), ex.getMessage());
    }
}
