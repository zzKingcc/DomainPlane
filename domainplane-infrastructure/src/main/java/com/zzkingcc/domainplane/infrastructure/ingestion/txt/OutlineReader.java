package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 认标题（txt 入库第 3 步）。
 *
 * 把清洗后的行序列切成「小节」：命中标题形态的行开一个新小节，其余行是正文。
 * 每个小节带一条 {@code section_path}（如 {@code 第一章 会员规则 > 一、注册}），
 * 这条路径会写进切片正文、也单独进 metadata。
 *
 * 两条关键取舍（别改回去）：
 *
 *   • 数字编号必须带分隔符（{@code .} {@code 、} {@code ．}）才算标题。只要求"数字开头"的话，
 *       {@code 2024 年新增实名用户 12800 人。} 这类数据行会被当成标题，把正文切碎。
 *       代价是 {@code 1 注册条件}（数字后只有空格）会漏判，刻意接受，漏一个标题远好过误切正文。
 *   • 层级只分两级。纯文本里编号混着用（一会儿 {@code 一、}、一会儿 {@code （一）}、一会儿 {@code 1.1}），
 *       往下猜第三、第四级必然出错。
 *
 *
 * @author zzkingcc
 */
public final class OutlineReader {

    /** 标题行长上限（字） */
    private static final int MAX_TITLE_CHARS = 40;

    /** 空白：半角空白 + 全角空格（中文文档里两种都常见） */
    private static final String WS = "[\\s\\u3000]";

    /** 第一级：第 X 章 / 节 / 部分 … */
    private static final Pattern LEVEL1 = Pattern.compile(
            "^第" + WS + "*[0-9一二三四五六七八九十百千零〇两]+" + WS
                    + "*(?:章|节|部分|篇|卷|编|讲|单元|集)(?:" + WS + "|$)");

    /** 第二级：中文序号，如 {@code 一、注册}。用 {@code matches()} 所以要写完整 */
    private static final Pattern CN_ORDINAL = Pattern.compile(
            "^[一二三四五六七八九十百千零〇两]+[、.．]" + WS + "*\\S[\\s\\S]*$");

    /** 第二级：括号序号，如 {@code （一）实名}、{@code (1) 条件} */
    private static final Pattern BRACKET_ORDINAL = Pattern.compile(
            "^[（(][0-9一二三四五六七八九十百千零〇两]+[)）]" + WS + "*\\S[\\s\\S]*$");

    /** 第二级：方括号标题，如 {@code 【注意】} */
    private static final Pattern BRACKET_TITLE = Pattern.compile(
            "^[【\\[][^】\\]]{1,12}[】\\]]" + WS + "*$");

    /** 第二级：字母序号，如 {@code A. 安装} */
    private static final Pattern LETTER_ORDINAL = Pattern.compile(
            "^[A-Za-z][、.．]" + WS + "*\\S[\\s\\S]*$");

    /** 句末标点：标题不会以它结尾 */
    private static final String SENTENCE_END_CHARS = "。！？；.!?;…";

    private OutlineReader() {
    }

    /** 一个小节：路径 + 正文行（不含标题行） */
    public record Section(List<String> path, List<String> bodyLines) {

        public String pathText() {
            return String.join(" > ", path);
        }

        public String title() {
            return path.isEmpty() ? "" : path.get(path.size() - 1);
        }
    }

    /** 识别结果：小节列表 + 识别到的标题总数 */
    public record OutlineResult(List<Section> sections, int titles) {
    }

    /**
     * 按标题边界把行序列切成小节。
     *
     * @param lines    清洗后的行
     * @param fallback 完全没有标题时，整篇作为一个 section 用的路径名（一般给文件名）
     */
    public static OutlineResult read(List<String> lines, String fallback) {
        List<Section> sections = new ArrayList<>();
        int titles = 0;

        // 最近的第一级（章/节/部分）标题；没有它时，第二级标题自己就是顶层
        String chapter = null;
        List<String> currentPath = List.of();
        List<String> currentBody = new ArrayList<>();
        boolean openSection = false;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                if (openSection && !currentBody.isEmpty() && !currentBody.get(currentBody.size() - 1).isEmpty()) {
                    currentBody.add("");
                }
                continue;
            }

            if (isTitle(trimmed)) {
                titles++;
                if (openSection || !currentBody.isEmpty()) {
                    sections.add(new Section(List.copyOf(currentPath), trimTail(currentBody)));
                }
                currentBody = new ArrayList<>();
                openSection = true;

                if (LEVEL1.matcher(trimmed).find()) {
                    // 第一级：重置下层
                    chapter = trimmed;
                    currentPath = List.of(chapter);
                } else if (chapter != null) {
                    // 第二级：只替换第二层，第一级不动
                    currentPath = List.of(chapter, trimmed);
                } else {
                    // 还没出现过第一级标题，第二级自己当顶层（连续两个第二级互相替换，不嵌套）
                    currentPath = List.of(trimmed);
                }
                continue;
            }

            currentBody.add(line);
        }

        if (openSection || !currentBody.isEmpty()) {
            sections.add(new Section(List.copyOf(currentPath), trimTail(currentBody)));
        }

        // 标题之前的开篇正文不能丢：单独成节，路径取 fallback
        List<Section> normalized = new ArrayList<>(sections.size());
        for (Section s : sections) {
            if (s.path().isEmpty()) {
                if (s.bodyLines().isEmpty()) {
                    continue;
                }
                normalized.add(new Section(List.of(fallback == null || fallback.isBlank() ? "(文档开头)" : fallback),
                        s.bodyLines()));
            } else if (!s.bodyLines().isEmpty()) {
                normalized.add(s);
            }
        }

        if (normalized.isEmpty()) {
            List<String> body = trimTail(new ArrayList<>(lines));
            if (!body.isEmpty()) {
                normalized.add(new Section(
                        List.of(fallback == null || fallback.isBlank() ? "(文档开头)" : fallback), body));
            }
        }
        return new OutlineResult(List.copyOf(normalized), titles);
    }

    /**
     * 判断一行是否为标题：命中任一形态 且 行长 ≤ 40 且 不以句末标点结尾。
     *
     * 清洗阶段（{@link TxtCleaner}）也用它做「不要删标题」的护栏，所以是纯静态函数。
     */
    public static boolean isTitle(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String trimmed = line.strip();
        if (trimmed.length() > MAX_TITLE_CHARS) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (SENTENCE_END_CHARS.indexOf(last) >= 0) {
            return false;
        }
        if (LEVEL1.matcher(trimmed).find()
                || CN_ORDINAL.matcher(trimmed).matches()
                || BRACKET_ORDINAL.matcher(trimmed).matches()
                || BRACKET_TITLE.matcher(trimmed).matches()
                || LETTER_ORDINAL.matcher(trimmed).matches()) {
            return true;
        }
        return isNumericOrdinal(trimmed);
    }

    /**
     * 数字编号：编号里必须出现分隔符（{@code .} {@code 、} {@code ．}）。
     *
     * 例：{@code 1. 注册条件} ✓、{@code 1.2 参数配置} ✓、{@code 1、注册} ✓、
     * {@code 1 注册条件} ✗（只有空格）、{@code 2024 年新增实名用户 12800 人。} ✗（无分隔符）。
     */
    private static boolean isNumericOrdinal(String line) {
        int len = line.length();
        int i = 0;
        boolean hasSeparator = false;
        boolean hasDigit = false;
        while (i < len) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
                i++;
            } else if (c == '.' || c == '、' || c == '．') {
                hasSeparator = true;
                i++;
            } else {
                break;
            }
        }
        if (!hasDigit || !hasSeparator) {
            return false;
        }
        // 编号后面必须还有正文（去掉空白后非空）
        return !line.substring(i).strip().isEmpty();
    }

    /** 从尾部去掉连续空行 */
    private static List<String> trimTail(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) {
            end--;
        }
        return new ArrayList<>(lines.subList(0, end));
    }
}
