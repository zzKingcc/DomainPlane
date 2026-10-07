package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片（txt 入库第 4 步）。
 *
 * {@code maxChars} 是长度上限，不是目标长度。切点永远落在标题或句子上：撞到标题立刻断、
 * 整段不足上限就是实际字数。想强行凑满只能跨标题拼内容，反而让模型看不出层级。
 *
 * 入参是一个小节的正文行（小节之间不合并），出参是该小节的若干切片正文。
 *
 * @author zzkingcc
 */
public final class Chunker {

    /** 句子级断点（中文，遇到即断） */
    private static final String CJK_TERMINATORS = "。！？；";

    /** 句子级断点（英文，只有后面跟空白或行尾时才算） */
    private static final String ASCII_TERMINATORS = ".!?;";

    /** 长句内部可用的软断点 */
    private static final String SOFT_BREAKS = "，,：:";

    private final int maxChars;
    private final int overlapSentences;
    private final int minChars;

    public Chunker(int maxChars, int overlapSentences, int minChars) {
        this.maxChars = Math.max(1, maxChars);
        this.overlapSentences = Math.max(0, overlapSentences);
        this.minChars = Math.max(0, minChars);
    }

    /**
     * 把一个小节的正文切成若干片。
     *
     * @param bodyLines 该小节的正文行（不含标题行）
     * @return 切片正文列表；无内容时返回空列表
     */
    public List<String> chunk(List<String> bodyLines) {
        String body = String.join("\n", bodyLines).strip();
        if (body.isEmpty()) {
            return List.of();
        }

        List<String> sentences = new ArrayList<>();
        for (String sentence : splitSentences(body)) {
            if (count(sentence) > maxChars) {
                sentences.addAll(breakLongSentence(sentence));
            } else {
                sentences.add(sentence);
            }
        }

        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String sentence : sentences) {
            if (!current.isEmpty() && lengthOf(current) + count(sentence) > maxChars) {
                chunks.add(join(current));
                current = overlapSeed(current, sentence);
            }
            current.add(sentence);
        }
        if (!current.isEmpty()) {
            chunks.add(join(current));
        }

        return mergeShortTail(chunks);
    }

    /** 重叠种子：拿上一片的最后 N 句开新片；若加上下一句会超上限，就放弃重叠（保证不超长） */
    private List<String> overlapSeed(List<String> finished, String next) {
        List<String> seed = new ArrayList<>();
        if (overlapSentences <= 0 || finished.isEmpty()) {
            return seed;
        }
        int from = Math.max(0, finished.size() - overlapSentences);
        for (int i = from; i < finished.size(); i++) {
            seed.add(finished.get(i));
        }
        if (lengthOf(seed) + count(next) > maxChars) {
            return new ArrayList<>();
        }
        return seed;
    }

    /** 尾片过短就并进前一片（本小节第一篇不丢） */
    private List<String> mergeShortTail(List<String> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        int lastIndex = chunks.size() - 1;
        String last = chunks.get(lastIndex);
        if (count(last) >= minChars) {
            return chunks;
        }
        List<String> merged = new ArrayList<>(chunks.subList(0, lastIndex));
        String previous = merged.remove(merged.size() - 1);
        merged.add(previous + "\n" + last);
        return merged;
    }

    // ==================== 句子 ====================

    /**
     * 切句：在 {@code 。！？；} 和换行处切；英文 {@code .!?;} 只在后面跟空白或位于行尾时才算终止符
     * —— 否则 {@code 1.5}、{@code v1.0-beta.1}、{@code api.example.com} 会被切坏。
     */
    static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            current.append(c);
            i++;

            boolean flush;
            if (c == '\n') {
                flush = true;
            } else if (CJK_TERMINATORS.indexOf(c) >= 0) {
                flush = true;
            } else if (ASCII_TERMINATORS.indexOf(c) >= 0) {
                char next = i < n ? text.charAt(i) : '\0';
                flush = next == '\0' || Character.isWhitespace(next);
            } else {
                flush = false;
            }

            if (flush) {
                String sentence = current.toString().strip();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }

    /**
     * 单句超过上限时的兜底：先在软断点处断，再不行按字数硬切
     *
     * 长度判断用 {@code StringBuilder.length()}（O(1)）而不是「每字符重建字符串再全量数」：
     * 后者是 O(n²) —— 一个 10,000 字的无标点行（minified JS、base64、异常堆栈）会放大到
     * 约 1 亿次字符操作，而导入是全局串行的（{@code Semaphore(1)}），一条这样的数据
     * 就能把整个知识库入库队列卡住。
     *
     * {@code length()} 数的是 UTF-16 单元，对代理对会多算 1 —— 用于「是否超上限」的粗判
     * 完全够；需要精确字符数时用 {@code CjkWidth.countChars}。
     */
    private List<String> breakLongSentence(String sentence) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int softAt = Math.max(1, maxChars / 2);
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            current.append(c);
            boolean soft = SOFT_BREAKS.indexOf(c) >= 0 && current.length() >= softAt;
            if (soft || current.length() >= maxChars) {
                parts.add(current.toString().strip());
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        parts.removeIf(String::isEmpty);
        return parts;
    }

    /** 句子拼成切片正文：中文侧直接接，英文侧补一个空格 */
    private static String join(List<String> sentences) {
        StringBuilder sb = new StringBuilder();
        for (String sentence : sentences) {
            if (sb.length() == 0) {
                sb.append(sentence);
                continue;
            }
            if (cjkSide(sb.charAt(sb.length() - 1))) {
                sb.append(sentence);
            } else {
                sb.append(' ').append(sentence);
            }
        }
        return sb.toString();
    }

    private static boolean cjkSide(char c) {
        return c >= 0x2E80;
    }

    private static int lengthOf(List<String> sentences) {
        int total = 0;
        for (String s : sentences) {
            total += count(s);
        }
        return total;
    }

    private static int count(String s) {
        return s.codePointCount(0, s.length());
    }
}
