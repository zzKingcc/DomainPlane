package com.zzkingcc.domainplane.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 分词器：CJK bigram + 拉丁整词。
 *
 * <p>分词器是倒排索引的入口，切错一条边，后面打分、删除全歪。这里锁四条：</p>
 * <ul>
 *   <li><b>标点必须是边界</b>：这是与 {@link CjkWidth} 的分界线，那边把全角符号算 CJK，
 *       这边必须切飞，否则索引里全是"单，"这种噪声词根；</li>
 *   <li><b>去重且保序</b>：复读不能靠重复计数压过短文本，顺序要可复现；</li>
 *   <li><b>停用词只滤单字与整词，不滤 bigram</b>：bigram 停用表维护成本抵不上收益；</li>
 *   <li><b>上限生效</b>：一条长回答不能刷爆索引。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M3 分词器：bigram 边界、去重、停用词、上限")
class CjkTokenizerTest {

    private static List<String> texts(List<CjkTokenizer.Term> terms) {
        return terms.stream().map(CjkTokenizer.Term::text).toList();
    }

    private static List<CjkTokenizer.Kind> kinds(List<CjkTokenizer.Term> terms) {
        return terms.stream().map(CjkTokenizer.Term::kind).toList();
    }

    @Test
    @DisplayName("null / 空白：空列表，不抛")
    void blankInputYieldsEmpty() {
        assertEquals(List.of(), CjkTokenizer.tokenize(null));
        assertEquals(List.of(), CjkTokenizer.tokenize(""));
        assertEquals(List.of(), CjkTokenizer.tokenize("   \t\n "));
    }

    @Test
    @DisplayName("拉丁段：整词小写，标点断开")
    void latinWordsAreLowercasedAndSplit() {
        List<CjkTokenizer.Term> terms = CjkTokenizer.tokenize("Hello, WORLD! order-42");
        assertEquals(List.of("hello", "world", "order-42"), texts(terms));
        assertEquals(List.of(CjkTokenizer.Kind.WORD, CjkTokenizer.Kind.WORD, CjkTokenizer.Kind.WORD),
                kinds(terms));
    }

    @Test
    @DisplayName("标识符内部的点 / 横 / 下连不拆：版本号、字段名是一个词根")
    void wordCharsKeepDotsHyphensUnderscores() {
        assertEquals(List.of("v1.2.3"), texts(CjkTokenizer.tokenize("v1.2.3")));
        assertEquals(List.of("user_name"), texts(CjkTokenizer.tokenize("user_name")));
    }

    @Test
    @DisplayName("CJK 段：二字一切；段长为 1 才出单字")
    void cjkRunsBecomeBigrams() {
        assertEquals(List.of("订单", "单查", "查询"),
                texts(CjkTokenizer.tokenize("订单查询")));
        assertEquals(List.of(CjkTokenizer.Kind.BIGRAM, CjkTokenizer.Kind.BIGRAM,
                CjkTokenizer.Kind.BIGRAM), kinds(CjkTokenizer.tokenize("订单查询")));

        List<CjkTokenizer.Term> single = CjkTokenizer.tokenize("查");
        assertEquals(List.of("查"), texts(single));
        assertEquals(List.of(CjkTokenizer.Kind.UNIGRAM), kinds(single));
    }

    @Test
    @DisplayName("标点（含全角）是边界：不切出带标点的词根")
    void punctuationIsBoundary() {
        // 半角逗号 + 句号：把 4 字段切成两个 2 字段，各产一个 bigram
        assertEquals(List.of("订单", "查询"),
                texts(CjkTokenizer.tokenize("订单,查询。")));
        // 全角标点：在 CjkWidth 里算 CJK，在这里必须是边界
        assertEquals(List.of("订单", "查询"),
                texts(CjkTokenizer.tokenize("订单，查询；")));
        // 顿号、引号、省略号
        assertEquals(List.of("订单", "查询"),
                texts(CjkTokenizer.tokenize("“订单、查询……”")));
    }

    @Test
    @DisplayName("中英混排：按段切，顺序按出现先后（拉丁段也是边界）")
    void mixedCjkAndLatin() {
        List<CjkTokenizer.Term> terms = CjkTokenizer.tokenize("订单order状态");
        assertEquals(List.of("订单", "order", "状态"), texts(terms));
        assertEquals(List.of(CjkTokenizer.Kind.BIGRAM, CjkTokenizer.Kind.WORD,
                CjkTokenizer.Kind.BIGRAM), kinds(terms));
    }

    @Test
    @DisplayName("假名按 CJK 切 bigram")
    void kanaRunsBecomeBigrams() {
        assertEquals(List.of("テス", "スト"), texts(CjkTokenizer.tokenize("テスト")));
    }

    @Test
    @DisplayName("去重且保序：复读只留一个词根，顺序不变")
    void dedupKeepsFirstOccurrenceOrder() {
        List<CjkTokenizer.Term> terms = CjkTokenizer.tokenize("查询查询");
        // 查询 / 询查 / 查询(重) → 去重后按首次出现
        assertEquals(List.of("查询", "询查"), texts(terms));

        // 段中部新组合出的词根排在后面（询查询 → 询查、查询、询询）
        assertEquals(List.of("查询", "询查", "询询"),
                texts(CjkTokenizer.tokenize("查询查询询查")));
    }

    @Test
    @DisplayName("停用词：单字与整词滤掉，bigram 不滤")
    void stopWordsFiltered() {
        // 单字停用词：整段就一个字时不入索引
        assertEquals(List.of(), texts(CjkTokenizer.tokenize("的")));
        // 整词停用词
        assertEquals(List.of(), texts(CjkTokenizer.tokenize("the")));
        assertEquals(List.of("order"), texts(CjkTokenizer.tokenize("the order")));
        // bigram 不停用："的了"在"订单的了"里由相邻切分自然稀释
        assertEquals(List.of("的了"), texts(CjkTokenizer.tokenize("的了")));
    }

    @Test
    @DisplayName("权重：整词 3 / bigram 2 / 单字 1")
    void weightsByKind() {
        assertEquals(3, new CjkTokenizer.Term("order", CjkTokenizer.Kind.WORD).weight());
        assertEquals(2, new CjkTokenizer.Term("订单", CjkTokenizer.Kind.BIGRAM).weight());
        assertEquals(1, new CjkTokenizer.Term("查", CjkTokenizer.Kind.UNIGRAM).weight());
    }

    @Test
    @DisplayName("hasCjkTerm：有非拉丁词根才为 true")
    void hasCjkTerm() {
        assertFalse(CjkTokenizer.hasCjkTerm(null));
        assertFalse(CjkTokenizer.hasCjkTerm(
                List.of(new CjkTokenizer.Term("order", CjkTokenizer.Kind.WORD))));
        assertTrue(CjkTokenizer.hasCjkTerm(
                List.of(new CjkTokenizer.Term("订单", CjkTokenizer.Kind.BIGRAM))));
        assertTrue(CjkTokenizer.hasCjkTerm(
                List.of(new CjkTokenizer.Term("查", CjkTokenizer.Kind.UNIGRAM))));
    }

    @Test
    @DisplayName("上限：超长文本截到 MAX_TERMS_PER_DOC，不抛")
    void capsAtMaxTerms() {
        // 300 个互不相同的汉字连续段：bigram 299 个且互异，应被截到 200
        // （用同一个字重复不行：去重后只剩一个词根，测不到上限）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append((char) (0x4E00 + i));
        }
        List<CjkTokenizer.Term> cjk = CjkTokenizer.tokenize(sb.toString());
        assertEquals(CjkTokenizer.MAX_TERMS_PER_DOC, cjk.size());

        // 300 个互异拉丁词：截到 200
        String longLatin = java.util.stream.IntStream.range(0, 300)
                .mapToObj(i -> "w" + i).collect(java.util.stream.Collectors.joining(" "));
        List<CjkTokenizer.Term> latin = CjkTokenizer.tokenize(longLatin);
        assertEquals(CjkTokenizer.MAX_TERMS_PER_DOC, latin.size());
    }

    // ===== 补充：扩展 B 以上 CJK（码点 > 0xFFFF）与代理对不被劈开 =====

    /** 用码点构造字符串，避免在源码里直接写补充平面字符（保持源码纯 ASCII）。 */
    private static String supp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    @DisplayName("扩展 B 以上（码点 > 0xFFFF）的 CJK 仍产出 bigram")
    void supplementaryPlaneCjkProducesBigram() {
        // U+20BB7(𠮷) 与 U+29E3D 都在扩展 B：按单个 char 看是两组非 CJK 的代理半区。
        // 修复前按 char 判 isCjkWord 会漏掉它们，整段产不出 bigram，与 CjkWidth / FusionSupport 口径不一致。
        List<CjkTokenizer.Term> terms = CjkTokenizer.tokenize(supp(0x20BB7, 0x29E3D));
        assertEquals(List.of(supp(0x20BB7, 0x29E3D)), texts(terms));
        assertEquals(List.of(CjkTokenizer.Kind.BIGRAM), kinds(terms));

        // 补充平面 + 基本区混排，也应按码点切出 bigram，不被代理对边界切断
        List<CjkTokenizer.Term> mixed = CjkTokenizer.tokenize(supp(0x20BB7, 0x91CE)); // 𠮷 + 野
        assertEquals(List.of(supp(0x20BB7, 0x91CE)), texts(mixed));
        assertEquals(List.of(CjkTokenizer.Kind.BIGRAM), kinds(mixed));
    }

    @Test
    @DisplayName("孤立的补充平面字符是单个 UNIGRAM，整个代理对不被劈成两半")
    void supplementaryCharIsSingleUnigram() {
        // 𠮷 单独成段：码点数为 1 -> UNIGRAM，且文本是整个补充平面字符（2 个 UTF-16 单元）。
        // 修复前按 char 判会把代理对的两半都当非 CJK 跳过，结果一个词根都不剩。
        List<CjkTokenizer.Term> terms = CjkTokenizer.tokenize(supp(0x20BB7));
        assertEquals(1, terms.size());
        assertEquals(CjkTokenizer.Kind.UNIGRAM, terms.get(0).kind());
        String text = terms.get(0).text();
        assertEquals(supp(0x20BB7), text);
        assertEquals(1, text.codePointCount(0, text.length())); // 一个码点
        assertEquals(2, text.length()); // 但占 2 个 UTF-16 单元（代理对）
    }
}
