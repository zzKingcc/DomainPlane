package com.zzkingcc.domainplane.infrastructure.ingestion.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 造 pdf 样例（测试专用）。
 *
 * <p>与 {@code DocxReaderTest} 的做法一致：<b>样例在测试里现造</b>，不往仓库塞二进制 fixture ——
 * 二进制样例一旦失准，没人看得懂它为什么失败。这里用 PDFBox 自己写 pdf，坐标由我们定，
 * 于是"页眉在第 800 磅、正文从 760 磅开始、段间多空一行"这些前提都是显式的。</p>
 *
 * <p>只用 Helvetica（标准 14 字体）—— 它没有中文字形，所以样例一律用英文；
 * 行距/分段的判定与语言无关。</p>
 */
public final class PdfSamples {

    private static final float PAGE_WIDTH = 595f;
    private static final float PAGE_HEIGHT = 842f;
    private static final float LEFT = 50f;
    private static final float HEADER_Y = 800f;
    private static final float BODY_TOP_Y = 755f;
    private static final float FOOTER_Y = 45f;
    /** 段落内正常行距 */
    private static final float LINE_STEP = 15f;
    private static final float FONT_SIZE = 11f;

    private PdfSamples() {
    }

    /** 一行正文；{@code paragraphGap} 为 true 表示它在原文里另起一段（段间空一行） */
    public record Line(String text, boolean paragraphGap) {

        public static Line of(String text) {
            return new Line(text, false);
        }

        public static Line paragraph(String text) {
            return new Line(text, true);
        }
    }

    /** 一页：页眉 / 页脚可为 null */
    public record Page(String header, String footer, List<Line> body) {

        public static Page of(List<Line> body) {
            return new Page(null, null, body);
        }
    }

    /** 只写文字的一页（无页眉页脚），每个字符串一行、互相独立成段 */
    public static Page body(String... lines) {
        List<Line> list = new ArrayList<>(lines.length);
        for (String line : lines) {
            list.add(Line.of(line));
        }
        return Page.of(list);
    }

    public static byte[] build(List<Page> pages) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (Page sample : pages) {
                PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, FONT_SIZE);
                    float cursor = Float.NaN;
                    if (sample.header() != null) {
                        cursor = writeLine(stream, cursor, HEADER_Y, sample.header());
                    }
                    float y = BODY_TOP_Y;
                    for (Line line : sample.body()) {
                        if (line.paragraphGap()) {
                            y -= LINE_STEP;
                        }
                        cursor = writeLine(stream, cursor, y, line.text());
                        y -= LINE_STEP;
                    }
                    if (sample.footer() != null) {
                        writeLine(stream, cursor, FOOTER_Y, sample.footer());
                    }
                    stream.endText();
                }
            }
            return save(document);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一页只有图形、没有文字 —— 扫描件的等价物（文本层是空的） */
    public static byte[] withoutText() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.addRect(100f, 300f, 200f, 200f);
                stream.stroke();
            }
            return save(document);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 加了密码的 pdf（用户口令非空 → 空口令打不开） */
    public static byte[] encrypted(String userPassword) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                stream.newLineAtOffset(LEFT, BODY_TOP_Y);
                stream.showText("secret");
                stream.endText();
            }
            document.protect(new StandardProtectionPolicy("owner-pwd", userPassword, new AccessPermission()));
            return save(document);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 定位到绝对 y 后写一行：文本对象里每次移动都是相对量，所以得自己攒着上一个 y */
    private static float writeLine(PDPageContentStream stream, float previousY, float y, String text)
            throws IOException {
        if (Float.isNaN(previousY)) {
            stream.newLineAtOffset(LEFT, y);
        } else {
            stream.newLineAtOffset(0f, y - previousY);
        }
        stream.showText(text);
        return y;
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
