package com.zzkingcc.domainplane.infrastructure.ingestion.docx;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.MarkdownTable;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * docx 解析器（docx 入库的格式适配层）：{@code byte[] → List<Block>}。
 *
 * 为什么不用 Tika：Tika 的 XHTML 把 docx 的标题降级成了普通
 * {@code }，它保留了表格结构，但丢掉了标题层级。而标题层级正是我们要的核心信息，
 * POI 直读 {@code 段落样式}（{@code Heading1} / {@code 标题 1}）和
 * {@code 大纲级别}（{@code outlineLvl}）本来就拿得到。
 *
 * 三条刻意的取舍：
 *
 *   • 页眉页脚根本不读：这里只遍历 {@link XWPFDocument#getBodyElements()}，
 *       它们压根进不来，比事后按"是否重复"删除干净得多。
 *   • 表格与段落按文档顺序穿插：正文里"一段话 → 一张表 → 又一段话"很常见，
 *       先全段落再全部表格会把表和它的说明文字拆到两处。
 *   • 图片只留占位：不做 OCR（见 MULTI-FORMAT 设计 §五）；
 *       占位取自 Word 里的"可选文字"，没有就是 {@code [图片]}。
 *
 *
 * @author zzkingcc
 */
public final class DocxReader {

    private static final Pattern HEADING_NUMBER = Pattern.compile("(\\d+)\\s*$");

    private DocxReader() {
    }

    /** 解析结果：块序列 + 丢掉的行数（docx 不做理噪，恒为 0） */
    public record DocxParseResult(List<Block> blocks, int dropped) {
    }

    public static DocxParseResult read(byte[] content) {
        if (content == null || content.length == 0) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "docx 内容为空");
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<Block> blocks = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    collectParagraph(paragraph, blocks);
                } else if (element instanceof XWPFTable table) {
                    Block converted = tableBlock(table);
                    if (converted != null) {
                        blocks.add(converted);
                    }
                }
            }
            return new DocxParseResult(List.copyOf(blocks), 0);
        } catch (KnowledgeBaseException e) {
            throw e;
        } catch (Exception e) {
            // 加密文档 / 损坏的 zip / 实际是 doc 改名成 docx，都是这一处，给明确的话术
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "docx 解析失败（文件损坏、加密，或其实是旧版 .doc 改了扩展名）：" + e.getMessage(), e);
        }
    }

    // ==================== 段落 ====================

    private static void collectParagraph(XWPFParagraph paragraph, List<Block> blocks) {
        for (XWPFRun run : paragraph.getRuns()) {
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            for (XWPFPicture picture : pictures) {
                blocks.add(Block.image(picture.getDescription()));
            }
        }
        String text = clean(paragraph.getText());
        if (text.isEmpty()) {
            return;
        }
        Integer level = headingLevel(paragraph);
        blocks.add(level == null ? Block.paragraph(text) : Block.title(level, text));
    }

    /**
     * 段落的标题层级：先看样式（{@code Heading2} / {@code 标题 2}），再看大纲级别。
     *
     * 两个都要：中文版 Word 的内置标题样式 ID 常常只是 {@code 1} {@code 2}（没有 heading 字样），
     * 这时候只有 {@code outlineLvl} 能说明它是标题；反过来有些文档的样式名写了
     * {@code Heading 3} 却不落 {@code outlineLvl}。
     */
    private static Integer headingLevel(XWPFParagraph paragraph) {
        Integer fromId = levelOfStyleName(paragraph.getStyleID());
        if (fromId != null) {
            return fromId;
        }
        // 样式 ID 认不出来时（很多模板把内置标题样式改名或只用数字编号），靠大纲级别兜底
        Integer fromDoc = levelOfStyleName(styleNameOf(paragraph));
        if (fromDoc != null) {
            return fromDoc;
        }
        return outlineLevel(paragraph);
    }

    /** 样式 ID 在样式表里对应样式名；取不到就算了（不影响主链路） */
    private static String styleNameOf(XWPFParagraph paragraph) {
        try {
            XWPFStyle style = paragraph.getDocument().getStyles().getStyle(paragraph.getStyleID());
            return style == null ? null : style.getName();
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
            if (n >= 1 && n <= 6) {
                return n;
            }
            return null;
        }
        // 确实是标题样式但没有编号，当一级处理，总好过退化成正文
        return 1;
    }

    /** 大纲级别（0 = 一级）；Word 给正文留的值是 9，超出 1~6 范围的一律不算标题 */
    private static Integer outlineLevel(XWPFParagraph paragraph) {
        try {
            CTPPr pPr = paragraph.getCTP().getPPr();
            if (pPr == null || !pPr.isSetOutlineLvl()) {
                return null;
            }
            BigInteger value = pPr.getOutlineLvl().getVal();
            if (value == null) {
                return null;
            }
            int level = value.intValue();
            return level >= 0 && level <= 5 ? level + 1 : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 表格 ====================

    /**
     * 表格 → {@link MarkdownTable}。
     *
     * 渲染细节（列齐平、单元格转义、截断）与旧版 doc、excel 共用同一份实现：
     * 三个格式的表在检索侧必须长得一样，不然 BM25 的字段权重就没法统一。
     */
    private static Block tableBlock(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(MarkdownTable.cell(joinedParagraphs(cell)));
            }
            rows.add(cells);
        }
        String md = MarkdownTable.render(rows);
        return md.isEmpty() ? null : Block.table(md);
    }

    /** 单元格内的多个段落用空格接起来（POI 的单元格是段落容器，不清空的话会连成一片） */
    private static String joinedParagraphs(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String text = clean(paragraph.getText());
            if (!text.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        }
        return sb.toString().trim();
    }

    // ==================== 工具 ====================

    /** 段落文本：换行/制表符归一成空格，压缩连续空白 */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\n', ' ').replace('\t', ' ').replace('\u000B', ' ')
                .replaceAll("\\s{2,}", " ").strip();
    }
}
