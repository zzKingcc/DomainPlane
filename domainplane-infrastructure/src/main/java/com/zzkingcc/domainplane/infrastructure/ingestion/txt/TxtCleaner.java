package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 清洗（txt 入库第 2 步）。
 *
 * 原则：只删能确定是噪声的行，宁少勿多。删掉一行正文的代价，远大于留下一行噪声。
 *
 * 服务端是无人值守的批量上传，没有「人工确认」这一步，所以这里只做高置信度的删除；
 * 需要人眼核对的抽样，靠上传后自动导出的切片预览文件。
 *
 * @author zzkingcc
 */
public final class TxtCleaner {

    /** 页眉页脚候选的最大行长 */
    private static final int SHORT_LINE_CHARS = 40;

    /** 页码：{@code 第 X 页}、{@code Page X of Y}、{@code - X -}。注意「第 X 条/章/节/款」是正文条款，不在此列 */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?:第[\\s\\u3000]*\\d{1,5}[\\s\\u3000]*页(?:[\\s\\u3000]*共[\\s\\u3000]*\\d{1,5}[\\s\\u3000]*页)?"
                    + "|Page\\s*\\d{1,5}(?:\\s+of\\s+\\d{1,5})?"
                    + "|-\\s*\\d{1,5}\\s*-)$",
            Pattern.CASE_INSENSITIVE);

    /** 孤立数字（页码常以裸数字出现，但不能无条件删） */
    private static final Pattern PURE_NUMBER = Pattern.compile("^\\d{1,6}$");

    /** 纯符号行：分隔线 */
    private static final Pattern SYMBOL_LINE = Pattern.compile("^[-=*_~#^·—]{3,}$");

    private static final String SENTENCE_END_CHARS = "。！？；.!?;…";

    private TxtCleaner() {
    }

    /** 清洗结果：保留的行 + 删掉的行数 */
    public record CleanResult(List<String> lines, int dropped) {
    }

    public static CleanResult clean(String text) {
        List<List<String>> pages = splitPages(text);
        List<String> lines = new ArrayList<>();
        for (List<String> page : pages) {
            lines.addAll(page);
        }

        Map<String, Integer> occurrences = countOccurrences(lines);
        Set<String> headerFooter = headerFooterLines(pages, occurrences);

        List<String> kept = new ArrayList<>(lines.size());
        int dropped = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                kept.add("");
                continue;
            }
            if (PAGE_NUMBER.matcher(trimmed).matches()) {
                dropped++;
                continue;
            }
            if (SYMBOL_LINE.matcher(trimmed).matches()) {
                dropped++;
                continue;
            }
            if (headerFooter.contains(trimmed)) {
                dropped++;
                continue;
            }
            if (isLoneNumber(trimmed, occurrences.getOrDefault(trimmed, 0), lines, i)) {
                dropped++;
                continue;
            }
            if (isDuplicateOfPreviousKept(trimmed, kept)) {
                dropped++;
                continue;
            }
            kept.add(line);
        }

        return new CleanResult(squeezeBlankLines(kept), dropped);
    }

    // ==================== 各条规则 ====================

    /**
     * 页眉 / 页脚：短于 40 字、不以句末标点结尾、且不是标题形态的行，出现 ≥2 次即视为重复噪声。
     *
     * 有 {@code \f} 时只统计「每页前 2 行 + 后 2 行」的候选，并额外要求出现次数达到页数一半：
     * 页眉页脚天然出现在每页同一位置。
     */
    private static Set<String> headerFooterLines(List<List<String>> pages, Map<String, Integer> occurrences) {
        boolean paged = pages.size() > 1;

        Set<String> candidates = new HashSet<>();
        if (paged) {
            for (List<String> page : pages) {
                collectEdgeLines(page, candidates);
            }
        } else {
            candidates.addAll(occurrences.keySet());
        }

        int threshold = Math.max(2, (pages.size() + 1) / 2);
        Set<String> noise = new HashSet<>();
        for (String candidate : candidates) {
            if (candidate.length() >= SHORT_LINE_CHARS) {
                continue;
            }
            if (SENTENCE_END_CHARS.indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                continue;
            }
            if (OutlineReader.isTitle(candidate)) {
                continue;
            }
            int count = occurrences.getOrDefault(candidate, 0);
            if (count >= (paged ? threshold : 2)) {
                noise.add(candidate);
            }
        }
        return noise;
    }

    /** 取一页里最前面的 2 行和最后面的 2 行（非空）作为页眉页脚候选 */
    private static void collectEdgeLines(List<String> page, Set<String> candidates) {
        List<String> nonBlank = new ArrayList<>();
        for (String line : page) {
            if (!line.isBlank()) {
                nonBlank.add(line.strip());
            }
        }
        for (int i = 0; i < Math.min(2, nonBlank.size()); i++) {
            candidates.add(nonBlank.get(i));
        }
        for (int i = Math.max(0, nonBlank.size() - 2); i < nonBlank.size(); i++) {
            candidates.add(nonBlank.get(i));
        }
    }

    /**
     * 孤立数字行：只有「同一内容出现过 ≥2 次」或「上一行是标题、下一行是空行」时才删。
     * 孤立的 {@code 12800} 很可能是正文数据。
     */
    private static boolean isLoneNumber(String trimmed, int count, List<String> lines, int index) {
        if (!PURE_NUMBER.matcher(trimmed).matches()) {
            return false;
        }
        if (count >= 2) {
            return true;
        }
        String previous = previousNonBlank(lines, index);
        String next = index + 1 < lines.size() ? lines.get(index + 1) : "";
        return previous != null && OutlineReader.isTitle(previous) && next.isBlank();
    }

    /** 连续重复行：同一行连着出现 ≥2 次（短、不以句末标点结尾）时，只留第一条 */
    private static boolean isDuplicateOfPreviousKept(String trimmed, List<String> kept) {
        if (trimmed.length() >= SHORT_LINE_CHARS) {
            return false;
        }
        if (SENTENCE_END_CHARS.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            return false;
        }
        for (int i = kept.size() - 1; i >= 0; i--) {
            String prev = kept.get(i).strip();
            if (prev.isEmpty()) {
                continue;
            }
            return prev.equals(trimmed);
        }
        return false;
    }

    // ==================== 工具 ====================

    private static List<List<String>> splitPages(String text) {
        List<List<String>> pages = new ArrayList<>();
        String[] rawPages = text.split("\f", -1);
        for (String rawPage : rawPages) {
            pages.add(List.of(rawPage.split("\n", -1)));
        }
        return pages;
    }

    private static Map<String, Integer> countOccurrences(List<String> lines) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                counts.merge(trimmed, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static String previousNonBlank(List<String> lines, int index) {
        for (int i = index - 1; i >= 0; i--) {
            String trimmed = lines.get(i).strip();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    /** 连续空行压成一个，并去掉首尾空行 */
    private static List<String> squeezeBlankLines(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        boolean lastBlank = true;
        for (String line : lines) {
            boolean blank = line.isBlank();
            if (blank && lastBlank) {
                continue;
            }
            out.add(blank ? "" : line);
            lastBlank = blank;
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
