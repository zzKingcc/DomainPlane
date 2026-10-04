package com.zzkingcc.domainplane.infrastructure.ingestion.excel;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.MarkdownTable;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 表格（{@code .xls} / {@code .xlsx}）解析器：{@code byte[] → List<Block>}。
 *
 * <p>表格是最接近「切片天生就该是表格」的格式 —— 没有任何结构可丢，所以这里的活只有两件：
 * <b>把每个工作表取出来</b>、<b>渲染成 markdown 表格</b>。分节按<b>工作表</b>走：
 * {@code section_path = 文件名 > 工作表名}，与 pdf 的「文件名 &gt; 第N页」同形。</p>
 *
 * <p>.xls（HSSF）与 .xlsx（XSSF）用同一个入口 {@link WorkbookFactory#create} ——
 * 它按文件头判类型，所以「.xls 改名成 .xlsx」也能正确打开，不必自己按扩展名分叉。</p>
 *
 * <p><b>四处实测结论</b>（探针结论，别再靠推理改回去）：</p>
 * <ul>
 *   <li><b>公式格必须传 {@link FormulaEvaluator}</b>：不传的话 {@code formatCellValue} 返回的是
 *       <b>公式文本</b>（{@code C2*2}）而不是值。个别函数 POI 算不出来（外部引用、新函数），
 *       这时退回公式文本 —— 宁可让这一格露出原文，也不能让一个格子毁掉整张表。</li>
 *   <li><b>不能按 {@code getFirstRowNum()..getLastRowNum()} 遍历</b>：稀疏表的区间可以很大，
 *       而 {@link Sheet} 的迭代器只会产出真实存在的行。</li>
 *   <li><b>合并单元格只有左上角有值</b>，其余格子是空的 —— 必须按合并区域回填，
 *       否则纵向合并的「类别」列只有第一行有值，后面几行全空。</li>
 *   <li><b>取值用 {@link DataFormatter}</b>：日期 / 数字按单元格自己的格式串出来
 *       （{@code 2026-05-29}），而不是序列号 {@code 46170}。</li>
 * </ul>
 *
 * @author zzkingcc
 */
public final class ExcelReader {

    /**
     * 单行最多取多少列。
     *
     * <p>这是防脏数据的闸门，不是功能上限：某个格子被误格式化到很远的列上时，
     * {@code getLastCellNum()} 会返回上万，一行就会撑成几万列的空表头。
     * 真实的表格不会超过 256 列。</p>
     */
    private static final int MAX_COLUMNS = 256;

    /** 一个合并区域最多回填多少个格子（横跨整表的合并区没有回填价值，跳过省时间） */
    private static final int MAX_MERGED_CELLS = 4096;

    private ExcelReader() {
    }

    /**
     * @param sheets         有内容的工作表数（写日志用）
     * @param skippedSheets  完全空的工作表数
     */
    public record ExcelParseResult(List<Block> blocks, int sheets, int skippedSheets) {
    }

    public static ExcelParseResult read(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "excel 内容为空");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            List<SheetData> sheets = new ArrayList<>();
            int skipped = 0;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                List<List<String>> rows = rowsOf(sheet, formatter, evaluator);
                if (rows.isEmpty()) {
                    skipped++;
                    continue;
                }
                sheets.add(new SheetData(sheet.getSheetName(), rows));
            }

            List<Block> blocks = new ArrayList<>();
            // 一级 = 文件名：没有任何工作表名可用的极端情况下，section_path 至少能说清"出自哪份表"
            if (!sheets.isEmpty() && fileName != null && !fileName.isBlank()) {
                blocks.add(Block.title(1, stripExtension(fileName)));
            }
            for (SheetData sheet : sheets) {
                blocks.add(Block.title(2, sheet.name()));
                blocks.add(Block.table(MarkdownTable.render(sheet.rows())));
            }
            return new ExcelParseResult(List.copyOf(blocks), sheets.size(), skipped);
        } catch (KnowledgeBaseException e) {
            throw e;
        } catch (Exception e) {
            // 加密 / 损坏 / 实际是 csv 改了扩展名 —— 都在这一处，给明确的话术
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "excel 解析失败（文件损坏、加密，或其实是 csv 改了扩展名）：" + e.getMessage(), e);
        }
    }

    /** 一个工作表的名字 + 它的所有数据行（已按 markdown 单元格归一） */
    private record SheetData(String name, List<List<String>> rows) {
    }

    // ==================== 工作表 → 行 ====================

    private static List<List<String>> rowsOf(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<Long, String> fills = mergedFills(sheet, formatter, evaluator);
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            int last = Math.min(row.getLastCellNum(), MAX_COLUMNS);
            if (last <= 0) {
                continue;
            }
            List<String> cells = new ArrayList<>(last);
            boolean any = false;
            for (int column = 0; column < last; column++) {
                String value = MarkdownTable.cell(cellText(row, column, formatter, evaluator, fills));
                if (!value.isEmpty()) {
                    any = true;
                }
                cells.add(value);
            }
            if (!any) {
                // 只残留格式、一个值都没有的行；也是"远端杂散单元格"的主要来源
                continue;
            }
            // 行尾的空列交给 render 统一补齐，这里先剪掉，免得每一行都拖一长串空的 '|'
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                cells.remove(cells.size() - 1);
            }
            rows.add(cells);
        }
        return rows;
    }

    /**
     * 取值：先让 POI 算公式，算不出来就退回原文。
     */
    private static String cellText(Row row, int column, DataFormatter formatter,
                                   FormulaEvaluator evaluator, Map<Long, String> fills) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell != null) {
            try {
                String value = formatter.formatCellValue(cell, evaluator);
                if (!value.isBlank()) {
                    return value;
                }
            } catch (Exception e) {
                // POI 算不出来的函数（外部引用、新函数）：退回公式原文，别让一格毁掉整张表
                return formatter.formatCellValue(cell);
            }
        }
        String filled = fills.get(key(row.getRowNum(), column));
        return filled == null ? "" : filled;
    }

    /**
     * 合并区域回填索引：{@code (行, 列) → 左上角格子的值}。
     *
     * <p>纵向合并的「类别」列、横向合并的表头都靠这一步才能填满，否则表格会缺一大片。</p>
     */
    private static Map<Long, String> mergedFills(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<CellRangeAddress> regions = sheet.getMergedRegions();
        if (regions.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> fills = new HashMap<>();
        for (CellRangeAddress region : regions) {
            long area = (long) (region.getLastRow() - region.getFirstRow() + 1)
                    * (region.getLastColumn() - region.getFirstColumn() + 1);
            if (area > MAX_MERGED_CELLS) {
                continue;
            }
            Row first = sheet.getRow(region.getFirstRow());
            Cell anchor = first == null ? null
                    : first.getCell(region.getFirstColumn(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (anchor == null) {
                continue;
            }
            String value = cellText(first, region.getFirstColumn(), formatter, evaluator, Map.of());
            if (value.isBlank()) {
                continue;
            }
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    fills.putIfAbsent(key(r, c), value);
                }
            }
        }
        return fills;
    }

    // ==================== 工具 ====================

    /** Excel 行数上限 1048576 = 2^20，列用低 21 位，行不会撞列 */
    private static long key(int row, int column) {
        return ((long) row << 21) | column;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
