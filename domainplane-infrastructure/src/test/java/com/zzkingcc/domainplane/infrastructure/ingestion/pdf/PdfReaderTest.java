package com.zzkingcc.domainplane.infrastructure.ingestion.pdf;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pdf 解析器的回归样例（样例 pdf 由 {@link PdfSamples} 现造）。
 *
 * <p>盯住的是设计里明确列出的四个难点：页边界 → 分节、页眉页脚删干净（<b>含带页码的页眉</b>）、
 * 跨页断句拼回来、扫描件明确报错。</p>
 */
class PdfReaderTest {

    private static final String FILE_NAME = "会员手册.pdf";

    private static List<Block> blocks(PdfReader.PdfParseResult result) {
        return result.blocks();
    }

    /** 把某一类块的正文连起来，方便断言"某段文字在不在里面" */
    private static String textOf(PdfReader.PdfParseResult result) {
        StringBuilder sb = new StringBuilder();
        for (Block block : result.blocks()) {
            sb.append(block.text()).append('\n');
        }
        return sb.toString();
    }

    private static List<Block> kind(PdfReader.PdfParseResult result, BlockKind kind) {
        return result.blocks().stream().filter(b -> b.kind() == kind).toList();
    }

    // ==================== 页边界 → 分节 ====================

    @Test
    void 每页成为一节_正文行拼成段落() {
        byte[] pdf = PdfSamples.build(List.of(
                new PdfSamples.Page(null, null, List.of(
                        PdfSamples.Line.of("Membership registration requires"),
                        PdfSamples.Line.of("a valid phone number."),
                        PdfSamples.Line.paragraph("Registration is free of charge."),
                        PdfSamples.Line.of("No annual fee is applied."))),
                PdfSamples.body(
                        "Refunds are processed within",
                        "seven days of delivery.")));

        PdfReader.PdfParseResult result = PdfReader.read(pdf, FILE_NAME);

        assertEquals(2, result.pages());
        assertEquals(3, result.paragraphs(), "第一页两段（段间多空一行）+ 第二页一段");
        assertEquals(0, result.dropped(), "样例里没有页眉页脚，不该删任何行");

        List<Block> blocks = blocks(result);
        assertEquals(BlockKind.TITLE, blocks.get(0).kind());
        assertEquals(1, blocks.get(0).level());
        assertEquals("会员手册", blocks.get(0).text(), "一级标题 = 去掉扩展名的文件名");

        assertEquals(List.of(BlockKind.TITLE, BlockKind.TITLE, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH,
                        BlockKind.TITLE, BlockKind.PARAGRAPH),
                blocks.stream().map(Block::kind).toList());

        List<Block> titles = kind(result, BlockKind.TITLE);
        assertEquals("第1页", titles.get(1).text());
        assertEquals(2, titles.get(1).level(), "页码是二级 —— 一级留给文件名");
        assertEquals("第2页", titles.get(2).text());

        List<Block> paragraphs = kind(result, BlockKind.PARAGRAPH);
        assertEquals("Membership registration requires a valid phone number.", paragraphs.get(0).text());
        assertEquals("Registration is free of charge. No annual fee is applied.", paragraphs.get(1).text());
        assertEquals("Refunds are processed within seven days of delivery.", paragraphs.get(2).text());
        assertEquals(List.of(1, 1, 2), paragraphs.stream().map(Block::pageNo).toList());
    }

    // ==================== 页眉页脚 ====================

    @Test
    void 页眉带页码也删得掉_数字先归一化再比重复() {
        String header1 = "Internal Handbook - Confidential - Page 1";
        String header2 = "Internal Handbook - Confidential - Page 2";
        String footer = "www.example.com/internal - do not distribute";
        List<PdfSamples.Line> body = List.of(
                PdfSamples.Line.of("Refunds are processed within seven days."),
                PdfSamples.Line.of("Contact support for late deliveries."));

        byte[] pdf = PdfSamples.build(List.of(
                new PdfSamples.Page(header1, footer, body),
                new PdfSamples.Page(header2, footer, body)));

        PdfReader.PdfParseResult result = PdfReader.read(pdf, FILE_NAME);
        String text = textOf(result);

        // 两页的页眉文本并不相同（末尾页码不同），靠"数字归一化"才认得出是同一个页眉
        assertTrue(!text.contains("Confidential"), "带页码的页眉没删掉：" + text);
        assertTrue(!text.contains("do not distribute"), "页脚没删掉：" + text);
        assertEquals(4, result.dropped(), "两页各 1 个页眉 + 1 个页脚");
        assertEquals(2, result.paragraphs());
    }

    @Test
    void 单页里的页码行也删() {
        byte[] pdf = PdfSamples.build(List.of(
                new PdfSamples.Page(null, "- 1 -",
                        List.of(PdfSamples.Line.of("Refunds are processed within seven days.")))));

        PdfReader.PdfParseResult result = PdfReader.read(pdf, FILE_NAME);

        assertEquals(1, result.dropped());
        assertTrue(!textOf(result).contains("- 1 -"), textOf(result));
        assertEquals(1, result.paragraphs());
    }

    // ==================== 跨页拼接 ====================

    @Test
    void 断在页尾的半句_与下一页首行拼回一段() {
        byte[] pdf = PdfSamples.build(List.of(
                PdfSamples.body("Membership registration requires a valid phone number and must be completed"),
                PdfSamples.body("within 30 days from the delivery date.")));

        PdfReader.PdfParseResult result = PdfReader.read(pdf, FILE_NAME);

        assertEquals(1, result.paragraphs(), "拼回来的话就只有一段");
        List<Block> paragraphs = kind(result, BlockKind.PARAGRAPH);
        assertEquals(1, paragraphs.size());
        assertTrue(paragraphs.get(0).text().contains("completed within 30 days"), paragraphs.get(0).text());
        assertEquals(1, paragraphs.get(0).pageNo(), "段落归起始页，页码锚点不能挪到第二页");

        List<Block> titles = kind(result, BlockKind.TITLE);
        assertEquals(2, titles.size(), "第二页没有正文段落了，就不该再有第2页的标题");
        assertEquals("第1页", titles.get(1).text());
    }

    @Test
    void 以一个完整句子收尾_就不跨页拼() {
        byte[] pdf = PdfSamples.build(List.of(
                PdfSamples.body("Membership registration must be completed."),
                PdfSamples.body("Refunds are processed within seven days of delivery.")));

        PdfReader.PdfParseResult result = PdfReader.read(pdf, FILE_NAME);

        assertEquals(2, result.paragraphs());
        assertEquals(List.of(1, 2), kind(result, BlockKind.PARAGRAPH).stream().map(Block::pageNo).toList());
    }

    // ==================== 兜底 ====================

    @Test
    void 扫描件_抽不出文字_正文段落数为零() {
        PdfReader.PdfParseResult result = PdfReader.read(PdfSamples.withoutText(), "扫描件.pdf");

        assertEquals(0, result.paragraphs());
        assertEquals(1, result.pages());
        assertTrue(result.blocks().isEmpty(), "抽不到文字就不产出任何块");
    }

    @Test
    void 加密的pdf_给明确话术() {
        byte[] pdf = PdfSamples.encrypted("user-pwd");

        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> PdfReader.read(pdf, FILE_NAME));
        assertTrue(ex.getMessage().contains("加密"), ex.getMessage());
    }
}
