package com.zzkingcc.domainplane.infrastructure.ingestion.docx;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockKind;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docx 解析器的回归样例。
 *
 * <p>样例文档是<b>测试里现造的</b>（用 POI 写一份再读回来）—— 不往仓库里塞二进制 fixture，
 * 也就不会因为 Word 版本不同而失准。</p>
 */
class DocxReaderTest {

    private static byte[] bytes(XWPFDocument doc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            doc.write(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static XWPFParagraph para(XWPFDocument doc, String styleId, String text) {
        XWPFParagraph paragraph = doc.createParagraph();
        if (styleId != null) {
            paragraph.setStyle(styleId);
        }
        paragraph.createRun().setText(text);
        return paragraph;
    }

    private static void cell(XWPFTable table, int row, int col, String text) {
        table.getRow(row).getCell(col).setText(text);
    }

    // ==================== 标题层级 ====================

    @Test
    void 标题层级_来自段落样式() {
        XWPFDocument doc = new XWPFDocument();
        para(doc, "Heading1", "第一章 会员规则");
        para(doc, "标题2", "一、注册条件");
        para(doc, null, "会员注册需提供手机号。");

        DocxReader.DocxParseResult result = DocxReader.read(bytes(doc));

        List<Block> titles = result.blocks().stream().filter(Block::isTitle).toList();
        assertEquals(2, titles.size());
        assertEquals(List.of(1, 2), titles.stream().map(Block::level).toList());
        assertEquals(List.of(BlockKind.TITLE, BlockKind.TITLE, BlockKind.PARAGRAPH),
                result.blocks().stream().map(Block::kind).toList());
    }

    @Test
    void 样式名没有heading字样时_靠大纲级别兜底() {
        XWPFDocument doc = new XWPFDocument();
        // 中文版 Word 的内置标题：样式 ID 只是个数字，认不出是标题
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setStyle("3");
        paragraph.createRun().setText("第三节 权益");
        CTP ctp = paragraph.getCTP();
        if (!ctp.isSetPPr()) {
            ctp.addNewPPr();
        }
        ctp.getPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(2));

        DocxReader.DocxParseResult result = DocxReader.read(bytes(doc));

        assertEquals(List.of(BlockKind.TITLE), result.blocks().stream().map(Block::kind).toList());
        assertEquals(3, result.blocks().get(0).level());
    }

    @Test
    void 正文的大纲级别是九_不算标题() {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.createRun().setText("这条是正文，不是标题。");
        CTP ctp = paragraph.getCTP();
        if (!ctp.isSetPPr()) {
            ctp.addNewPPr();
        }
        ctp.getPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(9));

        DocxReader.DocxParseResult result = DocxReader.read(bytes(doc));

        assertEquals(List.of(BlockKind.PARAGRAPH), result.blocks().stream().map(Block::kind).toList());
    }

    // ==================== 表格 ====================

    @Test
    void 表格转markdown_且与段落保持原文顺序() {
        XWPFDocument doc = new XWPFDocument();
        para(doc, "Heading1", "会员等级");
        para(doc, null, "下面是会员等级表：");
        XWPFTable table = doc.createTable(3, 2);
        cell(table, 0, 0, "等级");
        cell(table, 0, 1, "折扣");
        cell(table, 1, 0, "黄金");
        cell(table, 1, 1, "9折");
        cell(table, 2, 0, "白金");
        cell(table, 2, 1, "8折");
        para(doc, null, "表格说明：折扣以结算价为准。");

        DocxReader.DocxParseResult result = DocxReader.read(bytes(doc));

        assertEquals(List.of(BlockKind.TITLE, BlockKind.PARAGRAPH, BlockKind.TABLE, BlockKind.PARAGRAPH),
                result.blocks().stream().map(Block::kind).toList());
        String tableText = result.blocks().get(2).text();
        assertEquals("| 等级 | 折扣 |\n| --- | --- |\n| 黄金 | 9折 |\n| 白金 | 8折 |", tableText);
    }

    // ==================== 其它 ====================

    @Test
    void 图片只留占位() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun run = paragraph.createRun();
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        run.addPicture(new ByteArrayInputStream(png.toByteArray()), XWPFDocument.PICTURE_TYPE_PNG,
                "会员流程图.png", Units.toEMU(10), Units.toEMU(10));
        para(doc, null, "图 1 会员流程图");

        DocxReader.DocxParseResult result = DocxReader.read(bytes(doc));

        assertTrue(result.blocks().stream().anyMatch(b -> b.kind() == BlockKind.IMAGE));
    }

    @Test
    void 不是docx时_给出明确错误而不是静默空结果() {
        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> DocxReader.read("这不是 docx".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("docx 解析失败"), ex.getMessage());
    }
}
