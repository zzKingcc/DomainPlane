package com.zzkingcc.domainplane.common.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 中英混合分词：CJK 连续段切 bigram，拉丁/数字段整词小写。
 *
 * <p>给会话历史的倒排索引用（方案 §6.2）。选 bigram 而不是引分词库：
 * 中文没有空格，任何"词级"方案都要带一部词典，而这里要检索的是<b>口语化的对话</b>
 * （"那个订单咋还没到"），词典分词在这种文本上既不比 bigram 准、还多一个依赖。
 * bigram 的代价是噪声与索引体积，用停用词与词根上限压住。</p>
 *
 * <p><b>词根权重</b>：默认 整词 3 / bigram 2 / 单字 1（长词根更稀缺，天然该更重）。
 * 实际打分走 {@link TokenizerProfile#weightOf(Kind)}（M4 起可调，见方案 §10-R8），
 * {@link Term#weight()} 只是默认值。</p>
 *
 * <p><b>与 {@link CjkWidth} 的分工</b>：那边判"这个字符按多宽计 token"，
 * 连标点带全角符号都算 CJK；这边判"这个字符是不是词的一部分"，
 * 标点必须是<b>边界</b>（否则 "订单，" 会切出 "单，" 这种词根）。两套口径不同，
 * 复用会得到一个把标点当词根的索引。</p>
 *
 * @author zzkingcc
 */
public final class CjkTokenizer {

    /**
     * 停用词：极小表，只滤<b>单字</b>与<b>整词</b>。
     *
     * <p>不滤 bigram："的了" 本身虽无信息量，但它在"订单的了"这类连续段里
     * 与相邻字组成的 bigram（"的了""的单"）由相邻切分自然稀释，单独维护一张
     * bigram 停用词表的收益不足以抵掉它的维护成本。</p>
     *
     * <p>M4 起这份默认表<b>只增不减</b>：运行期要额外滤词请用
     * {@link TokenizerProfile#extraStopWords()}（取并集），替换整张表会让索引
     * 与既有查询口径不一致。</p>
     */
    public static final Set<String> DEFAULT_STOP_WORDS = Set.of(
            "的", "了", "是", "在", "我", "和", "就", "都", "也", "不", "与",
            "及", "或", "很", "着", "呢", "吧", "啊", "哦", "呀", "吗", "么",
            "把", "被", "让", "向", "从", "到", "有", "没", "一个", "一下",
            "什么", "怎么", "这个", "那个", "可以", "the", "a", "an", "is",
            "are", "was", "were", "to", "of", "and", "or", "it", "this", "that");

    /** 默认词根权重：整词 */
    public static final int DEFAULT_WORD_WEIGHT = 3;

    /** 默认词根权重：CJK 二字 */
    public static final int DEFAULT_BIGRAM_WEIGHT = 2;

    /** 默认词根权重：孤立单字 */
    public static final int DEFAULT_UNIGRAM_WEIGHT = 1;

    /** 单文档词根上限：防一条长回答刷爆索引（方案 §6.2） */
    public static final int MAX_TERMS_PER_DOC = 200;

    /** 词根种类，决定检索打分时的权重 */
    public enum Kind {
        /** 拉丁/数字整词（权重 3） */
        WORD,
        /** CJK 相邻二字（权重 2） */
        BIGRAM,
        /** 孤立单字（权重 1）：整段就一个字时才产出 */
        UNIGRAM
    }

    /**
     * 一个词根：文本 + 种类。
     *
     * <p>{@link #weight()} 是<b>默认</b>权重；检索打分以
     * {@link TokenizerProfile#weightOf(Kind)} 为准（M4 起权重可调），
     * 这里保留默认值是为了"拿到词根就能算个分"的便利场景。</p>
     */
    public record Term(String text, Kind kind) {

        public int weight() {
            return switch (kind) {
                case WORD -> DEFAULT_WORD_WEIGHT;
                case BIGRAM -> DEFAULT_BIGRAM_WEIGHT;
                case UNIGRAM -> DEFAULT_UNIGRAM_WEIGHT;
            };
        }
    }

    private CjkTokenizer() {
    }

    /**
     * 按默认口径切词（等价于 {@code tokenize(text, TokenizerProfile.DEFAULT)}）。
     *
     * @param text 待切文本；{@code null} / 空白返回空列表
     * @return 词根列表，数量不超过 {@link #MAX_TERMS_PER_DOC}
     */
    public static List<Term> tokenize(String text) {
        return tokenize(text, TokenizerProfile.DEFAULT);
    }

    /**
     * 按给定调优参数切词。结果按出现顺序去重（同一词根在文中出现多次只算一个，
     * 权重已在打分时体现，重复计数会让长文本靠复读压过短文本）。
     *
     * @param text    待切文本；{@code null} / 空白返回空列表
     * @param profile 权重与追加停用词；{@code null} 时按默认口径
     * @return 词根列表，数量不超过 {@link #MAX_TERMS_PER_DOC}
     */
    public static List<Term> tokenize(String text, TokenizerProfile profile) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        TokenizerProfile effective = profile == null ? TokenizerProfile.DEFAULT : profile;
        // LinkedHashSet 保序去重：词根顺序影响候选合并时的稳定性，
        // 而 HashSet 的迭代顺序在 JVM 间不稳定，会让同一查询的排序结果不可复现
        Set<Term> out = new LinkedHashSet<>();
        int i = 0;
        int length = text.length();
        while (i < length) {
            int cp = text.codePointAt(i);
            if (isCjkWord(cp)) {
                int start = i;
                while (i < length) {
                    int next = text.codePointAt(i);
                    if (!isCjkWord(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                emitCjkRun(text, start, i, out, effective);
            } else if (isWordChar(cp)) {
                int start = i;
                while (i < length) {
                    int next = text.codePointAt(i);
                    if (!isWordChar(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                String word = text.substring(start, i).toLowerCase();
                if (!effective.isStopWord(word)) {
                    out.add(new Term(word, Kind.WORD));
                }
            } else {
                // 标点 / 空格 / 全角符号：边界，跳过（按码点步进，代理对不会被拆成两半）
                i += Character.charCount(cp);
            }
            if (out.size() >= MAX_TERMS_PER_DOC) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * CJK 连续段 → bigram；段长为 1 时出单字（且过停用词表）。
     *
     * <p>段长 2 时只出一个 bigram（它就是整段），不再补单字：
     * 补了等于每个二字词都自动带上两个单字词根，索引白涨一倍。</p>
     */
    private static void emitCjkRun(String text, int start, int end, Set<Term> out,
                                   TokenizerProfile profile) {
        if (text.codePointCount(start, end) == 1) {
            String single = text.substring(start, end);
            if (!profile.isStopWord(single)) {
                out.add(new Term(single, Kind.UNIGRAM));
            }
            return;
        }
        // 按码点滑动：每次前进一个码点，切出的 bigram 永远落在码点边界上。
        // 按 UTF-16 单元切（i, i+2）时，遇到扩展区汉字或 emoji 就会切出半个字符。
        int i = start;
        while (i < end) {
            int first = text.codePointAt(i);
            int next = i + Character.charCount(first);
            if (next >= end) {
                break;
            }
            int second = text.codePointAt(next);
            int after = next + Character.charCount(second);
            out.add(new Term(text.substring(i, after), Kind.BIGRAM));
            if (out.size() >= MAX_TERMS_PER_DOC) {
                return;
            }
            i = next;
        }
    }

    /**
     * 是不是"词的一部分"的 CJK 字符：表意文字 + 假名 + 韩文。
     *
     * <p>不含标点与全角符号（{@code \u3000-\u303F} / {@code \uFF00-\uFFEF}）：
     * 它们在 {@link CjkWidth} 里算 CJK，但在这里必须是边界。</p>
     *
     * <p>参数是 code point（不是 {@code char}）：扩展 B 及以上的汉字在 UTF-16 里是代理对，
     * 按 char 判会把两半都当成非 CJK 丢掉，这类文本永远产不出 bigram，
     * 与 {@code CjkWidth} / {@code FusionSupport} 的口径也就不一致了。</p>
     */
    private static boolean isCjkWord(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK 基本区
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0xF900 && cp <= 0xFAFF)  // 兼容表意
                || (cp >= 0x3040 && cp <= 0x30FF)  // 平假名 + 片假名
                || (cp >= 0xAC00 && cp <= 0xD7AF)  // 韩文音节
                || (cp >= 0x1100 && cp <= 0x11FF)  // 韩文字母
                || (cp >= 0x3130 && cp <= 0x318F)  // 韩文兼容字母
                || (cp >= 0x20000 && cp <= 0x2FA1F) // 扩展 B~F + 兼容补充
                || (cp >= 0x30000 && cp <= 0x323AF); // 扩展 G / H
    }

    /** 拉丁 / 数字：整词，允许内部带点横下连（版本号、标识符） */
    private static boolean isWordChar(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
                || (cp >= '0' && cp <= '9') || cp == '.' || cp == '-' || cp == '_';
    }

    /**
     * 是否含 CJK 词根（给调用方判断"这查询还有没有必要走倒排"）。
     */
    public static boolean hasCjkTerm(List<Term> terms) {
        if (terms == null) {
            return false;
        }
        for (Term t : terms) {
            if (t.kind() != Kind.WORD) {
                return true;
            }
        }
        return false;
    }
}
