package com.zzkingcc.domainplane.common.util;

import java.util.Set;

/**
 * 分词调优参数（方案 §10-R8）：权重分层与停用词的"可调"入口。
 *
 * <p><b>为什么要可调</b>：bigram 的字面召回质量只能靠命中率数据调，
 * 哪些词根是噪声、长词根该比短词根重多少，是运行期口味而不是代码逻辑，
 * 调一次权不该重新编译一次。命中率数据在 {@code /admin/metrics} 的 {@code recall*} 一组里。</p>
 *
 * <p><b>停用词只做加法</b>：内置表是"索引口径"的一部分，允许替换就等于允许
 * 配置一个空列表，让索引塞满"的了"这类词根。追加噪声词才是安全的方向。</p>
 *
 * <p><b>改动停用词 = 改动索引口径</b>：它同时决定"写索引时滤掉什么"与"查询时切出什么"，
 * 改了之后已建的索引与新的查询口径不一致，需要重放归档重建（索引是派生数据，可重建）。</p>
 *
 * @param wordWeight     拉丁/数字整词的权重
 * @param bigramWeight   CJK 二字词根的权重
 * @param unigramWeight  孤立单字的权重
 * @param extraStopWords 追加停用词（与内置表取并集，只影响单字与整词，不影响 bigram）
 * @author zzkingcc
 */
public record TokenizerProfile(int wordWeight, int bigramWeight, int unigramWeight,
                               Set<String> extraStopWords) {

    /** 默认口径，与 M3 落地时的硬编码值一致 */
    public static final TokenizerProfile DEFAULT = new TokenizerProfile(
            CjkTokenizer.DEFAULT_WORD_WEIGHT,
            CjkTokenizer.DEFAULT_BIGRAM_WEIGHT,
            CjkTokenizer.DEFAULT_UNIGRAM_WEIGHT,
            Set.of());

    public TokenizerProfile {
        if (wordWeight < 0 || bigramWeight < 0 || unigramWeight < 0) {
            // 负权重会让打分方向反过来，是配置错误而不是"口味"：启动期就炸，别等检索结果变怪
            throw new IllegalArgumentException("词根权重不能为负：word=" + wordWeight
                    + " bigram=" + bigramWeight + " unigram=" + unigramWeight);
        }
        extraStopWords = extraStopWords == null ? Set.of() : Set.copyOf(extraStopWords);
    }

    /** 词根权重 */
    public int weightOf(CjkTokenizer.Kind kind) {
        return switch (kind) {
            case WORD -> wordWeight;
            case BIGRAM -> bigramWeight;
            case UNIGRAM -> unigramWeight;
        };
    }

    /** 是否是停用词（内置表 ∪ 追加表） */
    public boolean isStopWord(String text) {
        return text != null
                && (CjkTokenizer.DEFAULT_STOP_WORDS.contains(text) || extraStopWords.contains(text));
    }
}
