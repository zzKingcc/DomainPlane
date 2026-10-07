package com.zzkingcc.domainplane.infrastructure.ingestion.doc;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.MarkdownTable;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.OldWordFileFormatException;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旧版 {@code .doc}（Word 97–2003，OLE2 二进制）解析器：{@code byte[] → List<Block>}。
 *
 * 为什么不是 Tika：Tika 的 HWPF 抽取器输出的是「格式无关纯文本」，标题层级与表格结构
 * 都会被拍平 —— 而这两样恰好是切片要的东西。所以直连 HWPF。
 *
 * 标题层级有两路来源，顺序不能反：
 *
 *   • 样式名（{@code Heading 1} / {@code 标题 1}）—— 与 docx 同一条规则。但
 *       样式名是本地化的：俄文文档里 {@code Normal} 叫 {@code Базовый}，
 *       所以这一路只能当"能用就用"的正向信号，不能当唯一依据。
 *   • 大纲级别 {@link Paragraph#getLvl()} —— 语言无关的那一路，也是真正兜底的那一路。
 *       {@code Heading 1} 的段落 = {@code 0}、正文 = {@code 9}（Word 正文的默认值）。
 *
 *
 * 实测出来的三个坑，别改回去：
 *
 *   • {@code TableRow.isTableHeader()} 在真实表格里也返回 {@code false} → 表头只能按「第一行」。
 *   • 单元格文本以 {@code \u0007} 结尾，且一个单元格内可以含多个 {@code \r}（多段落）→ 必须折叠。
 *   • 表格里每一行末尾还有一个"行结束段落"（{@code isTableRowEnd()}），它的文本是纯控制符。
 *       遍历时必须用 {@link Table#getEndOffset()} 把整张表一次跳过去，否则会重复计数。
 *
 *
 * 不支持的：Word 6/95（{@link OldWordFileFormatException}，另有一套 {@code HWPFOldDocument}
 * API，本格式不接）、文本框里的文字（{@code getMainTextboxRange()} 是另一个 story，与 docx 保持一致不读）。
 *
 * @author zzkingcc
 */
public final class DocReader {

    /** 我们最多认六级标题，对应大纲级别 0~5 */
    private static final int MAX_OUTLINE_TITLE_LEVEL = 5;

    private static final Pattern HEADING_NUMBER = Pattern.compile("(\\d+)\\s*$");

    private DocReader() {
    }

    /** 解析结果：块序列 + 丢掉的行数（doc 不做理噪，恒为 0） */
    public record DocParseResult(List<Block> blocks, int dropped) {
    }

    public static DocParseResult read(byte[] content) {
        if (content == null || content.length == 0) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "doc 内容为空");
        }
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content))) {
            return new DocParseResult(collect(doc), 0);
        } catch (OldWordFileFormatException e) {
            // Word 6 / Word 95 是另一套二进制格式，HWPF 明确拒绝；给一句能照着操作的话
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "这是 Word 95 或更早版本保存的格式，解析器不支持；请用 Word 打开后另存为 .docx 再上传", e);
        } catch (Exception e) {
            // 加密文档 / 损坏的 OLE2 / 实际是 docx 改了扩展名 —— 都落到这一处，给明确的话术
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "doc 解析失败（文件损坏、加密，或其实是 .docx 改了扩展名）：" + e.getMessage(), e);
        }
    }

    // ==================== 正文遍历 ====================

    private static List<Block> collect(HWPFDocument doc) {
        Range range = doc.getRange();
        StyleSheet styles = doc.getStyleSheet();
        List<Block> blocks = new ArrayList<>();

        int total = range.numParagraphs();
        int i = 0;
        while (i < total) {
            Paragraph paragraph = range.getParagraph(i);
            if (paragraph.isInTable()) {
                Block table = tableBlock(range.getTable(paragraph));
                if (table != null) {
                    blocks.add(table);
                }
                // 整张表一次跳过：表格里的单元格段落、行结束段落都在 numParagraphs 里
                int end = range.getTable(paragraph).getEndOffset();
                int before = i;
                while (i < total && range.getParagraph(i).getEndOffset() <= end) {
                    i++;
                }
                if (i == before) {
                    // 兜底：取不到有效结束位置时也要往前走，否则死循环
                    i++;
                }
                continue;
            }
            String text = clean(paragraph.text());
            if (!text.isEmpty()) {
                Integer level = headingLevel(paragraph, styles);
                blocks.add(level == null ? Block.paragraph(text) : Block.title(level, text));
            }
            i++;
        }
        return List.copyOf(blocks);
    }

    // ==================== 标题 ====================

    /**
     * 段落的标题层级：先看样式名，再用大纲级别兜底（两路都要，理由见类注释）。
     */
    private static Integer headingLevel(Paragraph paragraph, StyleSheet styles) {
        Integer fromName = levelOfStyleName(styleName(paragraph, styles));
        if (fromName != null) {
            return fromName;
        }
        int outline = paragraph.getLvl();
        if (outline >= 0 && outline <= MAX_OUTLINE_TITLE_LEVEL) {
            return outline + 1;
        }
        return null;
    }

    /** 样式索引在样式表里对应的名字；取不到就算了（不影响主链路） */
    private static String styleName(Paragraph paragraph, StyleSheet styles) {
        try {
            StyleDescription description = styles.getStyleDescription(paragraph.getStyleIndex());
            return description == null ? null : description.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer levelOfStyleName(String name) {
        if (name == null) {
            return null;
        }
        String raw = name.trim();
        if (raw.isEmpty()) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean isHeading = lower.startsWith("heading") || lower.startsWith("title")
                || lower.startsWith("标题") || lower.startsWith("標題");
        if (!isHeading) {
            return null;
        }
        Matcher m = HEADING_NUMBER.matcher(raw);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 && n <= 6 ? n : null;
        }
        // 确实是标题样式但没有编号 —— 当一级处理，总好过退化成正文
        return 1;
    }

    // ==================== 表格 ====================

    private static Block tableBlock(Table table) {
        if (table == null) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < table.numRows(); r++) {
            TableRow row = table.getRow(r);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.numCells(); c++) {
                TableCell cell = row.getCell(c);
                cells.add(MarkdownTable.cell(cell == null ? null : cell.text()));
            }
            rows.add(cells);
        }
        String md = MarkdownTable.render(rows);
        return md.isEmpty() ? null : Block.table(md);
    }

    // ==================== 工具 ====================

    /**
     * 段落文本归一：所有控制符折叠成空格。
     *
     * HWPF 把这些控制符混在正文里，一个都不能漏：{@code \r} 段落标记、{@code \u0007} 单元格标记、
     * {@code \f} 分页符、{@code \u000B} 软回车。
     */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder folded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            folded.append(c < 0x20 ? ' ' : c);
        }
        return folded.toString().replaceAll("\\s{2,}", " ").strip();
    }
}
