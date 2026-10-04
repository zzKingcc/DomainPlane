package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.SplitResult;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * txt 切片的回归样例：定稿的设计别再被改回去。
 */
class TxtSplitterTest {
    private static final Charset GB18030 = Charset.forName("GB18030");

    private static Document doc(String text, String fileName) {
        return Document.from(text, Metadata.from("file_name", fileName));
    }

    private static SplitResult split(String text, String fileName) {
        return new TxtSplitter().splitWithStats(doc(text, fileName));
    }

    /**
     * 回归：超长无标点行不能把切片拖成 O(n²)。
     *
     * <p>此前每字符都重建一次 String 并全量数长度，一个 20,000 字的行会放大到数亿次字符操作；
     * 而导入是全局串行的（{@code Semaphore(1)}），一条这样的数据就能卡死整个知识库队列。
     * 这里用「同样的输入，耗时不随长度线性放大」来守住 —— 留足余量避免 CI 抖动误报。</p>
     */
    @Test
    void veryLongLineWithoutPunctuationDoesNotBlowUp() {
        String noPunctuation = "A".repeat(20_000);      // 无句末标点、无换行
        Chunker chunker = new Chunker(400, 0, 50);

        long t0 = System.nanoTime();
        List<String> parts = chunker.chunk(List.of(noPunctuation));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertFalse(parts.isEmpty(), "超长行仍应被切开");
        assertTrue(elapsedMs < 3_000,
                "20,000 字无标点行耗时 " + elapsedMs + "ms —— 超过说明又退回 O(n²) 了");
    }

    /** 切出来的片不能因为换了判断口径就明显超限 */
    @Test
    void longSentenceIsSplitNearConfiguredLimit() {
        Chunker chunker = new Chunker(200, 0, 20);
        String sentence = "甲".repeat(1_000);            // 1000 字一句，无软断点

        List<String> parts = chunker.chunk(List.of(sentence));

        assertTrue(parts.size() >= 5, "应被切成多片，实际 " + parts.size());
        for (String part : parts) {
            assertTrue(part.length() <= 200,
                    "单片长度 " + part.length() + " 超过配置上限 200");
        }
    }

    // ==================== 例子走全程 ====================

    @Test
    void 规范编号文档_路径与切片数正确() {
        String raw = """
                会员服务手册
                第 1 页
                第一章 会员规则
                一、注册
                会员注册需提供手机号。注册后即生效。
                ====
                （一）实名
                实名认证需上传身份证，48 小时内审核。
                2024 年新增实名用户 12800 人。
                第 2 页
                二、退换货
                退货运费由谁承担，视商品而定。
                会员服务手册
                """;

        SplitResult result = split(raw, "会员服务手册.txt");

        List<String> paths = result.segments().stream()
                .map(s -> s.metadata().getString("section_path"))
                .toList();
        assertEquals(List.of(
                "第一章 会员规则 > 一、注册",
                "第一章 会员规则 > （一）实名",
                "第一章 会员规则 > 二、退换货"), paths);

        assertEquals(4, result.sections(), "识别到 4 个标题（1 个一级 + 3 个二级）");
        assertEquals(3, result.segments().size(), "切出 3 片");
        assertEquals(5, result.droppedLines(), "删掉 2 行页眉 + 2 行页码 + 1 行分隔线");
    }

    @Test
    void 切片正文带章节路径且正文字号与序号正确() {
        String raw = """
                第一章 规则
                一、注册
                会员注册需提供手机号。注册后即生效。
                """;
        SplitResult result = split(raw, "手册.txt");
        assertEquals(1, result.segments().size());

        TextSegment segment = result.segments().get(0);
        assertEquals("第一章 规则 > 一、注册\n\n会员注册需提供手机号。注册后即生效。", segment.text());
        assertEquals("一、注册", segment.metadata().getString("section_title"));
        assertEquals(1, segment.metadata().getInteger("chunk_seq").intValue());
        assertEquals(1, segment.metadata().getInteger("chunk_total").intValue());
    }

    // ==================== 清洗 ====================

    @Test
    void 页码页眉分隔线被删且数据行不被当标题() {
        String raw = """
                用户手册
                第 1 页
                ====
                正文第一段内容，说明使用方法。
                2024 年新增实名用户 12800 人。
                第 2 页
                用户手册
                """;

        SplitResult result = split(raw, "测试.txt");

        assertEquals(0, result.sections(), "没有任何标题形态：2024 年… 不是标题");
        assertEquals(5, result.droppedLines(), "2 行页眉 + 2 行页码 + 1 行分隔线");
        assertEquals(1, result.segments().size());

        String text = result.segments().get(0).text();
        assertFalse(text.contains("第 1 页"), "页码要删掉");
        assertFalse(text.contains("===="), "分隔线要删掉");
        assertFalse(text.contains("用户手册\n"), "重复出现的页眉要删掉");
        assertTrue(text.contains("2024 年新增实名用户 12800 人。"), "数据行是正文，必须留下");
    }

    @Test
    void 条款编号不是页码() {
        String raw = """
                第一章 总则
                第 3 条 会员应当遵守本规则。
                """;
        SplitResult result = split(raw, "规则.txt");
        assertEquals(0, result.droppedLines(), "「第 X 条」是正文条款，不能当页码删");
        assertTrue(result.segments().get(0).text().contains("第 3 条"));
    }

    // ==================== 切片 ====================

    @Test
    void 无空行的长文本按句子切_不拦腰截断() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("句").append(String.format("%03d", i)).append("内容内容内容内容内容内容。");
        }
        SplitResult result = split(sb.toString(), "长文.txt");

        List<TextSegment> segments = result.segments();
        assertTrue(segments.size() >= 2, "1700 字必须切成多片，实际 " + segments.size());
        for (TextSegment segment : segments) {
            String text = segment.text();
            assertTrue(text.endsWith("。"), "每片都该断在句末，而不是半句：" + text.substring(text.length() - 12));
        }
    }

    @Test
    void 超过上限的长句会在软断点或硬切处断开() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("内容内容内容内容内容，");
        }
        sb.append("最后结束。");
        SplitResult result = split(sb.toString(), "超长句.txt");

        for (TextSegment segment : result.segments()) {
            int chars = segment.text().codePointCount(0, segment.text().length());
            int body = chars - "超长句\n\n".length();
            assertTrue(body <= 400 + 60, "长句必须被兜底切开，实际 " + body + " 字");
        }
    }

    @Test
    void 尾片过短并入前一片() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append("句").append(String.format("%03d", i)).append("内容内容内容内容内容内容。");
        }
        // 24 × 17 = 408 字：第一片约 391 字，剩下 17 字会作为尾片并入
        SplitResult result = split(sb.toString(), "尾片.txt");
        assertEquals(1, result.segments().size(), "尾片短于 60 字应并入前一片");
    }

    @Test
    void 不跨标题合并() {
        String raw = """
                一、注册
                注册很简单。
                二、退款
                退款也很简单。
                """;
        SplitResult result = split(raw, "两节.txt");
        assertEquals(2, result.segments().size(), "两个标题各出一片，绝不跨标题拼在一起");
        assertEquals("一、注册", result.segments().get(0).metadata().getString("section_path"));
        assertEquals("二、退款", result.segments().get(1).metadata().getString("section_path"));
    }

    // ==================== 句子切分 ====================

    @Test
    void 英文句点只在跟空白或行尾时断句() {
        List<String> sentences = Chunker.splitSentences(
                "版本 v1.0-beta.1 已发布。官网 api.example.com 可以查。");
        assertEquals(2, sentences.size(), "1.0 / api.example.com 不能被句点切开：" + sentences);
        assertTrue(sentences.get(0).contains("v1.0-beta.1"));
        assertTrue(sentences.get(1).contains("api.example.com"));
    }

    @Test
    void 数字编号必须带分隔符才算标题() {
        assertTrue(OutlineReader.isTitle("1. 注册条件"));
        assertTrue(OutlineReader.isTitle("1.2 参数配置"));
        assertTrue(OutlineReader.isTitle("1、注册"));
        assertFalse(OutlineReader.isTitle("1 注册条件"), "只有空格不算，宁漏不误");
        assertFalse(OutlineReader.isTitle("2024 年新增实名用户 12800 人。"), "数据行不是标题");
        assertFalse(OutlineReader.isTitle("这是一句话。"), "以句末标点结尾的不是标题");
    }

    // ==================== 字符集统一 ====================

    @Test
    void GBK文件转成UTF8且无乱码() {
        String original = "第一章 会员规则\n一、注册\n会员注册需提供手机号。";
        TxtNormalizer.Decoded decoded = TxtNormalizer.decode(original.getBytes(GB18030));

        assertEquals(original, decoded.text(), "GB18030 必须能正确还原");
        assertEquals(TxtNormalizer.GB18030, decoded.encoding());
    }

    @Test
    void UTF8带BOM也能正常读() {
        String original = "第一章 会员规则\n正文内容。";
        byte[] raw = ("\uFEFF" + original).getBytes(Charset.forName("UTF-8"));
        TxtNormalizer.Decoded decoded = TxtNormalizer.decode(raw);

        assertEquals(original, decoded.text(), "BOM 不能留在正文里");
        assertEquals(TxtNormalizer.UTF8_BOM, decoded.encoding());
    }

    @Test
    void 两种编码都判不出就拒绝该文件() {
        // 0xFF 在 UTF-8 与 GB18030 里都不是合法字节
        byte[] raw = {0x61, (byte) 0xFF, 0x62};
        assertThrows(KnowledgeBaseException.class, () -> TxtNormalizer.decode(raw),
                "判不出编码必须拒绝，绝不能猜 —— 猜错等于静默灌入乱码知识");
    }

    @Test
    void 规范化会统一换行并去掉行尾空白但保留缩进() {
        String normalized = TxtNormalizer.normalize("\u3000缩进行  \r\n下一行\t\r\n");
        assertEquals("\u3000缩进行\n下一行\n", normalized);
    }
}
