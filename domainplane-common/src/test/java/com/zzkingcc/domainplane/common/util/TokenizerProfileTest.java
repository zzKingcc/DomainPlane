package com.zzkingcc.domainplane.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 分词调优参数：权重分层与停用词的"可调"边界。
 *
 * <p>这里锁的不是"某个值对不对"，而是三条契约：</p>
 * <ul>
 *   <li><b>默认口径钉死在常量上</b>：{@link TokenizerProfile#DEFAULT} 必须与
 *       {@link CjkTokenizer} 的默认权重一致，否则"不配就用默认"这句话是假的；</li>
 *   <li><b>负权重启动期就炸</b>：权重方向反了是配置错误，不该等检索结果变怪才暴露；</li>
 *   <li><b>停用词只做加法</b>：追加表与内置表取并集，永远删不掉内置词
 *       （内置表是索引口径的一部分，可删就等于可配置一个空表）。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M4 分词调优：TokenizerProfile 权重与停用词")
class TokenizerProfileTest {

    @Test
    @DisplayName("默认口径 = CjkTokenizer 默认权重（3/2/1），无追加停用词")
    void defaultMatchesTokenizerConstants() {
        assertEquals(CjkTokenizer.DEFAULT_WORD_WEIGHT, TokenizerProfile.DEFAULT.wordWeight());
        assertEquals(CjkTokenizer.DEFAULT_BIGRAM_WEIGHT, TokenizerProfile.DEFAULT.bigramWeight());
        assertEquals(CjkTokenizer.DEFAULT_UNIGRAM_WEIGHT, TokenizerProfile.DEFAULT.unigramWeight());
        assertTrue(TokenizerProfile.DEFAULT.extraStopWords().isEmpty());
    }

    @Test
    @DisplayName("weightOf：按词根种类取对应权重")
    void weightOfPicksByKind() {
        TokenizerProfile p = new TokenizerProfile(7, 5, 2, Set.of());
        assertEquals(7, p.weightOf(CjkTokenizer.Kind.WORD));
        assertEquals(5, p.weightOf(CjkTokenizer.Kind.BIGRAM));
        assertEquals(2, p.weightOf(CjkTokenizer.Kind.UNIGRAM));
    }

    @Test
    @DisplayName("负权重构造期抛：方向反了是配置错误，不该带病运行")
    void negativeWeightRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerProfile(-1, 2, 1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerProfile(3, -2, 1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerProfile(3, 2, -1, Set.of()));
        // 0 合法：表示"这类词根不参与打分"，是合法的口味（比如让单字完全不生效）
        assertEquals(0, new TokenizerProfile(3, 2, 0, Set.of()).unigramWeight());
    }

    @Test
    @DisplayName("isStopWord：内置表 ∪ 追加表，加法语义（删不掉内置词）")
    void isStopWordIsUnionOfBuiltinAndExtra() {
        TokenizerProfile p = new TokenizerProfile(3, 2, 1, Set.of("噪声", "noise"));
        assertTrue(p.isStopWord("的"), "内置停用词仍然生效");
        assertTrue(p.isStopWord("噪声"), "追加停用词生效");
        assertTrue(p.isStopWord("noise"), "追加停用词对整词同样生效");
        assertFalse(p.isStopWord("订单"), "不在任何一张表里的词不是停用词");
        assertFalse(p.isStopWord(null), "null 不是停用词");
    }

    @Test
    @DisplayName("追加停用词为 null → 当空集合，不当异常")
    void nullExtraStopWordsBecomesEmpty() {
        assertTrue(new TokenizerProfile(3, 2, 1, null).extraStopWords().isEmpty());
    }

    @Test
    @DisplayName("追加停用词进 profile 后影响切词：单字与整词被滤，bigram 不受影响")
    void extraStopWordsShapeTokenizer() {
        // "查" 是孤立单字（UNIGRAM），"order" 是整词（WORD）→ 都会被追加停用词滤掉
        TokenizerProfile p = new TokenizerProfile(3, 2, 1, Set.of("查", "order"));
        assertEquals(List.of(), CjkTokenizer.tokenize("查", p));
        assertEquals(List.of(), CjkTokenizer.tokenize("order", p));

        // bigram 不受停用词影响：即便 "订单" 进了追加表，"订单查询" 仍切出 "订单"
        TokenizerProfile bigramUntouched = new TokenizerProfile(3, 2, 1, Set.of("订单"));
        assertTrue(CjkTokenizer.tokenize("订单查询", bigramUntouched).stream()
                        .anyMatch(t -> t.text().equals("订单") && t.kind() == CjkTokenizer.Kind.BIGRAM),
                "停用词只滤单字与整词，bigram 由相邻切分自然稀释");
    }

    @Test
    @DisplayName("默认口径下切词与无参 tokenize 完全一致")
    void explicitDefaultEqualsImplicitDefault() {
        String text = "订单怎么还没到 order-42";
        assertEquals(CjkTokenizer.tokenize(text), CjkTokenizer.tokenize(text, TokenizerProfile.DEFAULT));
        assertEquals(CjkTokenizer.tokenize(text), CjkTokenizer.tokenize(text, null),
                "profile 为 null 时按默认口径");
    }
}
