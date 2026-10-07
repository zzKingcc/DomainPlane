package com.zzkingcc.domainplane.infrastructure.ingestion.pdf;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * pdf 解析器（pdf 入库的格式适配层）：{@code byte[] → List<Block>}。
 *
 * pdf 与 md/docx 的根本差别：它没有结构，只有「文字 + 坐标 + 字号」。
 * md 的 {@code #} 和 docx 的 {@code Heading 1} 是文档里写明的；pdf 里连「这是一行」都是靠 y 坐标推出来的。
 * 所以这里比 {@link com.zzkingcc.domainplane.infrastructure.ingestion.docx.DocxReader} 多做三件事：
 *
 *
 *   • 抽行：{@link PDFTextStripper} 按坐标把字符聚成行，顺带拿到 y 与字号。
 *   • 删页眉页脚：它们混在正文里（docx 的页眉页脚是独立部件，压根读不到），
 *       不去掉就是每一页都往库里灌一遍噪音。
 *   • 跨页拼接：页边界几乎总是断在半句上，不拼回来就会切出一堆半截句子。
 *
 *
 * 本轮不做字号推断标题（设计里的阶段 5）：没有可信的标题时，分节退回「页」——
 * {@code section_path = 文件名 > 第N页}。页是 pdf 天然的结构边界，也是最适合引用回原文的锚点。
 *
 * 明确不做：表格还原（只有文本流，硬做必然错）、双栏顺序还原（要坐标聚类，失败是静默的）、
 * 扫描件（抽不出文字时明确报错，不静默成功）。
 *
 * @author zzkingcc
 */
public final class PdfReader {

    /** 每页取最前 / 最后几行作为页眉页脚候选 */
    private static final int EDGE_LINES = 2;

    /**
     * 页眉页脚候选的最大行长。
     *
     * 比 txt 那条 40 字宽松得多：pdf 的页眉常写着"产品名 - 机密 - 第 N 页"这种，动辄四五十字。
     * 敢放宽是因为这里还有两道更硬的约束（在页面边缘 + 两页以上同位置重复），长度只作兜底 ——
     * 真有一整段正文挤在页面边缘、还每页一字不差地重复，那它就是版式噪音。
     */
    private static final int MAX_EDGE_CHARS = 120;

    private static final String SENTENCE_END_CHARS = "。！？；.!?;…";

    /** 数字归一化：页眉里带页码时两页文本并不相同（{@code ... Page 1} / {@code ... Page 2}），不归一化就比不出重复 */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 整行就是一个页码。注意「第 X 条 / 章 / 节 / 款」是正文条款，不在其列 */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?:第[\\s\\u3000]*\\d{1,5}[\\s\\u3000]*页(?:[\\s\\u3000]*共[\\s\\u3000]*\\d{1,5}[\\s\\u3000]*页)?"
                    + "|Page\\s*\\d{1,5}(?:\\s+of\\s+\\d{1,5})?"
                    + "|-\\s*\\d{1,5}\\s*-)$",
            Pattern.CASE_INSENSITIVE);

    /** 段首标记：出现在行首说明这是新的一段，不该并进上一段 */
    private static final Pattern BULLET_START = Pattern.compile(
            "^(?:[-•·*◦]\\s|\\d{1,2}[.、)）]\\s|[(（]\\d{1,2}[)）]|第[\\s\\u3000]*[一二三四五六七八九十百\\d]+[章节条])");

    /** 行距超过全文中位行距的这个倍数，就认为是换段 */
    private static final float PARAGRAPH_GAP_RATIO = 1.8f;

    /** 跨页拼接时，下一页首行短于这个长度就不拼 —— 短行大概率是标题，拼进去会把标题吃进正文 */
    private static final int MIN_CROSS_PAGE_JOIN_CHARS = 12;

    private PdfReader() {
    }

    /**
     * @param blocks     Block 流：{@code TITLE(1, 文件名)} → 每页 {@code TITLE(2, 第N页)} + 若干 PARAGRAPH
     * @param dropped    删掉的页眉页脚 / 页码行数
     * @param paragraphs 正文段落数（不含页码标题）—— 为 0 就是抽不出文字的扫描件
     * @param pages      pdf 页数
     */
    public record PdfParseResult(List<Block> blocks, int dropped, int paragraphs, int pages) {
    }

    /** 抽出来的一行：序号（去噪要按行定位）、页码、y 坐标（自页顶向下）、该行主字号 */
    private record Line(int index, int page, float y, float size, String text) {
    }

    /** 一段正文：已把该段跨越的物理行拼回一行，page = 该段起始页 */
    private record Paragraph(int page, String text) {
    }

    public static PdfParseResult read(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "pdf 内容为空");
        }

        List<Line> lines;
        int pages;
        try (PDDocument document = Loader.loadPDF(content)) {
            pages = document.getNumberOfPages();
            LineStripper stripper = new LineStripper();
            // 不按坐标排序的话，行顺序会是内容流顺序 —— 双栏或后插入的浮层会乱序
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            stripper.getText(document);
            lines = stripper.lines();
        } catch (InvalidPasswordException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "pdf 解析失败：文档已加密，需要密码才能打开，请上传未加密的版本", e);
        } catch (KnowledgeBaseException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "pdf 解析失败（文件损坏，或并不是真正的 pdf）：" + e.getMessage(), e);
        }

        Set<Integer> noise = noiseIndexes(lines);
        List<Line> kept = new ArrayList<>(lines.size());
        int dropped = 0;
        for (Line line : lines) {
            if (noise.contains(line.index())) {
                dropped++;
                continue;
            }
            kept.add(line);
        }

        List<Paragraph> paragraphs = paragraphs(kept);

        List<Block> blocks = new ArrayList<>();
        // 一整页文字都抽不到（扫描件）时不产出任何块：让"没有内容"保持为空集，调用方好判断
        if (!paragraphs.isEmpty() && fileName != null && !fileName.isBlank()) {
            // 一级 = 文件名：正文段落没有任何标题时，section_path 至少能说清"出自哪份文档的哪一页"
            blocks.add(Block.title(1, stripExtension(fileName)));
        }
        int markerPage = -1;
        for (Paragraph paragraph : paragraphs) {
            if (paragraph.page() != markerPage) {
                markerPage = paragraph.page();
                blocks.add(Block.title(2, "第" + markerPage + "页"));
            }
            blocks.add(Block.paragraph(paragraph.text(), markerPage));
        }

        return new PdfParseResult(List.copyOf(blocks), dropped, paragraphs.size(), pages);
    }

    // ==================== 抽行 ====================

    /**
     * 按行回调的 TextStripper。
     *
     * {@link PDFTextStripper#writeString(String, List)} 是「一行」的落点（开 {@code sortByPosition} 后
     * 行是它自己聚出来的），覆写它并不调用 super，就不再往字符串里写，只收我们需要的行。
     */
    private static final class LineStripper extends PDFTextStripper {

        private final List<Line> lines = new ArrayList<>();
        private int index = 0;

        private LineStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null) {
                return;
            }
            String clean = text.replace('\u0000', ' ').strip();
            if (clean.isEmpty()) {
                return;
            }
            float y = Float.NaN;
            float size = 0f;
            if (positions != null && !positions.isEmpty()) {
                y = positions.get(0).getYDirAdj();
                for (TextPosition position : positions) {
                    size = Math.max(size, position.getFontSizeInPt());
                }
            }
            lines.add(new Line(index++, getCurrentPageNo(), y, size, clean));
        }

        private List<Line> lines() {
            return lines;
        }
    }

    // ==================== 删页眉页脚 ====================

    /**
     * 找出页眉页脚行的序号。
     *
     * 判定要三条同时成立 —— 误删一行正文的代价远大于留下一行噪音：
     *
     *   • 是所在页最前 / 最后的 1~2 行（页眉页脚天然在页面边缘）；
     *   • 短（< {@value #MAX_EDGE_CHARS} 字）且不以句末标点结尾、不是列表项；
     *   • 把数字归一化后，同一位置在 ≥ 2 个页面出现。
     *
     *
     * 第 3 条里的"归一化"是实测踩出来的：页眉写着 {@code ... Page 1} / {@code ... Page 2}，
     * 直接比文本永远不相等 —— 归掉数字才认得出是同一个页眉。
     */
    private static Set<Integer> noiseIndexes(List<Line> lines) {
        Map<Integer, List<Line>> byPage = new LinkedHashMap<>();
        for (Line line : lines) {
            byPage.computeIfAbsent(line.page(), k -> new ArrayList<>()).add(line);
        }

        Map<String, Set<Integer>> keyPages = new HashMap<>();
        Map<String, String> keySample = new HashMap<>();
        Map<Integer, List<String>> lineKeys = new HashMap<>();

        for (List<Line> page : byPage.values()) {
            int size = page.size();
            int edge = Math.min(EDGE_LINES, size);
            for (int i = 0; i < edge; i++) {
                collectCandidate(page.get(i), "T", keyPages, keySample, lineKeys);
            }
            for (int i = Math.max(0, size - EDGE_LINES); i < size; i++) {
                collectCandidate(page.get(i), "B", keyPages, keySample, lineKeys);
            }
        }

        Set<String> noiseKeys = new HashSet<>();
        for (Map.Entry<String, Set<Integer>> entry : keyPages.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String sample = keySample.get(entry.getKey());
            if (sample.length() >= MAX_EDGE_CHARS) {
                continue;
            }
            if (SENTENCE_END_CHARS.indexOf(sample.charAt(sample.length() - 1)) >= 0) {
                continue;
            }
            if (BULLET_START.matcher(sample).find()) {
                continue;
            }
            noiseKeys.add(entry.getKey());
        }

        Set<Integer> noise = new HashSet<>();
        for (Map.Entry<Integer, List<String>> entry : lineKeys.entrySet()) {
            for (String key : entry.getValue()) {
                if (noiseKeys.contains(key)) {
                    noise.add(entry.getKey());
                    break;
                }
            }
        }

        // 整行就是个页码：哪怕只出现一次（单页 pdf）也确定是噪音，不必等重复
        for (Line line : lines) {
            if (lineKeys.containsKey(line.index()) && PAGE_NUMBER.matcher(line.text()).matches()) {
                noise.add(line.index());
            }
        }
        return noise;
    }

    private static void collectCandidate(Line line, String band,
                                        Map<String, Set<Integer>> keyPages,
                                        Map<String, String> keySample,
                                        Map<Integer, List<String>> lineKeys) {
        String key = band + "|" + normalize(line.text());
        keyPages.computeIfAbsent(key, k -> new HashSet<>()).add(line.page());
        keySample.putIfAbsent(key, line.text());
        lineKeys.computeIfAbsent(line.index(), k -> new ArrayList<>()).add(key);
    }

    /** 数字 → {@code #}，空白压成一个：让"只差一个页码"的两行变成同一个键 */
    private static String normalize(String text) {
        return DIGITS.matcher(text.strip().replaceAll("\\s+", " ")).replaceAll("#");
    }

    // ==================== 跨页拼接 + 分段 ====================

    /**
     * 把行聚成段落：行距明显变大、或行首出现列表标记 → 换段；
     * 跨页时若上一段断在半句上、下一页首行又不像标题 → 拼回同一段。
     *
     * 拼接后的段落归属起始页：这样它仍排在"上一页"的位置，页码锚点不会错位。
     */
    private static List<Paragraph> paragraphs(List<Line> lines) {
        List<Paragraph> out = new ArrayList<>();
        if (lines.isEmpty()) {
            return out;
        }
        float reference = referenceGap(lines);

        StringBuilder current = new StringBuilder();
        Line first = null;
        Line previous = null;

        for (Line line : lines) {
            boolean startNew = previous != null && startsNewParagraph(previous, line, reference);
            if (first == null || startNew) {
                if (current.length() > 0) {
                    out.add(new Paragraph(first.page(), current.toString()));
                    current.setLength(0);
                }
                first = line;
            }
            appendLine(current, previous, line.text());
            previous = line;
        }
        if (current.length() > 0) {
            out.add(new Paragraph(first.page(), current.toString()));
        }
        return out;
    }

    private static boolean startsNewParagraph(Line previous, Line line, float reference) {
        boolean bullet = BULLET_START.matcher(line.text()).find();
        if (bullet) {
            return true;
        }
        if (previous.page() != line.page()) {
            // 跨页：断在半句上且下一页首行够长 → 拼回去（pdf 最常见的断法）
            return !(canJoinAcrossPages(previous, line));
        }
        float gap = line.y() - previous.y();
        return reference > 0 && gap > reference * PARAGRAPH_GAP_RATIO;
    }

    private static boolean canJoinAcrossPages(Line previous, Line line) {
        String text = previous.text();
        if (text.isEmpty() || SENTENCE_END_CHARS.indexOf(text.charAt(text.length() - 1)) >= 0) {
            return false;
        }
        return count(line.text()) >= MIN_CROSS_PAGE_JOIN_CHARS;
    }

    /** 行拼成段：上一行结尾是中文就直接接，否则补一个空格（英文换行断开的单词要靠它复原） */
    private static void appendLine(StringBuilder current, Line previous, String text) {
        if (current.length() == 0) {
            current.append(text);
            return;
        }
        boolean cjkSide = previous != null && !previous.text().isEmpty()
                && previous.text().charAt(previous.text().length() - 1) >= 0x2E80;
        if (cjkSide) {
            current.append(text);
        } else {
            current.append(' ').append(text);
        }
    }

    /**
     * 正文的"正常行距"参考值。
     *
     * 不能直接取中位数：页内还夹着"页眉→正文""正文→页脚"这两个大跳变（能到几百磅），
     * 页数少的文档里它们会把中位数整个带偏。做法是先用最小行距圈出候选区间（≤ 最小行距的 2.5 倍），
     * 再在候选里取中位数 —— 最小行距就是正文的行距，大跳变会被这个区间挡在外面。
     */
    private static float referenceGap(List<Line> lines) {
        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            Line before = lines.get(i - 1);
            Line after = lines.get(i);
            if (before.page() != after.page()) {
                continue;
            }
            float gap = after.y() - before.y();
            if (gap > 0.5f) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 0f;
        }
        gaps.sort(Float::compare);
        float smallest = gaps.get(0);
        List<Float> candidates = new ArrayList<>();
        for (float gap : gaps) {
            if (gap <= smallest * 2.5f) {
                candidates.add(gap);
            }
        }
        return candidates.get(candidates.size() / 2);
    }

    // ==================== 工具 ====================

    private static int count(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
