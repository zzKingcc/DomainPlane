package com.zzkingcc.domainplane.domain.rag.fusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住标题/文件名增益的<b>关键词切分口径</b>。
 *
 * <p>改造背景：原实现只按标点/空白切段，中文没有词边界 → 「会员退款的时效说明」被切成一整段 10 个字，
 * 要求标题包含这一整串才命中。实测 10 条真实中文查询<b>全部不命中</b>，
 * {@code title-boost} / {@code file-name-boost} 两个配置项在中文场景下形同空转。</p>
 *
 * <p>这些用例刻意都用「真实文档标题 + 用户长问句」的形态，而不是短词对短词 ——
 * 后者旧实现本来就能过，测不出问题。</p>
 */
class FusionSupportKeywordMatchTest {

    // ==================== 正例：中文长问句必须能命中标题 ====================

    @Test
    void chineseLongQuestion_hitsShortTitle() {
        // 旧实现在这一组上 10 条全 MISS
        assertTrue(FusionSupport.containsAnyKeyword("退款政策", "会员退款的时效说明"));
        assertTrue(FusionSupport.containsAnyKeyword("退款政策", "退款怎么申请"));
        assertTrue(FusionSupport.containsAnyKeyword("订单状态说明", "订单状态有哪些"));
        assertTrue(FusionSupport.containsAnyKeyword("售后服务", "售后服务包括哪些内容"));
        assertTrue(FusionSupport.containsAnyKeyword("发票申请", "怎么开发票申请"));
        assertTrue(FusionSupport.containsAnyKeyword("价格表", "价格是多少"));
        assertTrue(FusionSupport.containsAnyKeyword("会员等级", "会员等级怎么划分"));
    }

    /** 章节序号在查询与标题里形态不同（「第三章」vs「第3章」），但实义词「退款流程」能命中。 */
    @Test
    void chapterNumberDiffers_butContentWordStillHits() {
        assertTrue(FusionSupport.containsAnyKeyword("第3章 退款流程", "第三章退款的流程是怎样的"));
    }

    /** 标题比查询长（查询是标题的子串）也要命中。 */
    @Test
    void shortQueryMatchesInsideLongTitle() {
        assertTrue(FusionSupport.containsAnyKeyword("数据安全管理办法", "数据安全管理办法中关于跨境传输的规定"));
    }

    /** 纯英文路径行为不变：仍按空格分词。 */
    @Test
    void englishQueryStillMatchesByWord() {
        assertTrue(FusionSupport.containsAnyKeyword("Onboarding Guide", "how does onboarding work for new hires"));
        assertTrue(FusionSupport.containsAnyKeyword("Password Reset", "how to reset my password"));
    }

    /** 中英混排：拉丁段与中文段各自切分，两边都能命中。 */
    @Test
    void mixedLanguageQuery_hitsBothSides() {
        assertTrue(FusionSupport.containsAnyKeyword("API 设计规范", "API 设计规范里分页参数怎么定"));
    }

    // ==================== 反例：虚字闸 ====================

    /**
     * 全是虚字的 bigram 不该算命中 ——
     * 否则标题「这个」「是什么」会被任何问句命中，增益彻底失效。
     */
    @Test
    void allFunctionCharBigrams_doNotCountAsHit() {
        assertFalse(FusionSupport.containsAnyKeyword("这个", "这个怎么弄"));
        assertFalse(FusionSupport.containsAnyKeyword("是什么", "这个东西是什么"));
    }

    @Test
    void unrelatedTitleAndQuery_doNotHit() {
        assertFalse(FusionSupport.containsAnyKeyword("用户协议", "如何注销账户"));
        assertFalse(FusionSupport.containsAnyKeyword("续费规则", "开发票在哪里申请"));
        assertFalse(FusionSupport.containsAnyKeyword("配送范围", "什么时候发货"));
        assertFalse(FusionSupport.containsAnyKeyword("售后服务", "库存怎么补货"));
    }

    /**
     * 虚字闸的判据是「两个字<b>全是</b>虚字才丢」，不是「含虚字就丢」。
     * 「申请」含虚字「请」但「申」是实义字，必须留下 —— 否则「怎么开发票申请」这类查询会全部失效。
     */
    @Test
    void bigramWithOneContentCharIsKept() {
        assertTrue(FusionSupport.containsAnyKeyword("发票申请", "怎么开发票申请"));
    }

    // ==================== 切分细节 ====================

    /**
     * 跨文种相邻<b>不</b>产生 bigram。
     *
     * <p>反向用例更尖锐：若不跳过非 CJK 相邻对，「api设计规范」会切出 {@code pi}（i+设的边界）
     * 与 {@code pi}，而 {@code "api"} 里正好含 {@code "pi"} → 标题「api」会被中文查询误判为命中。
     * 这就是必须跳过的理由。</p>
     */
    @Test
    void crossScriptBigramsAreNotGenerated() {
        assertFalse(FusionSupport.containsAnyKeyword("api", "api设计规范"),
                "不能切出「pi」这类跨文种碎片，否则标题 api 会被中文查询误命中");
    }

    /** 拉丁独立段（前后有空格或标点）仍走整段匹配，行为不变。 */
    @Test
    void latinSegmentSplitByWhitespace_stillMatches() {
        assertTrue(FusionSupport.containsAnyKeyword("api", "API 设计规范"));
    }

    /**
     * 生僻字是代理对，按 char 切会切出半个字符，且 CJK 判定会因落在 D800-DFFF 而判成非 CJK
     * → 该字永远产不出 bigram。这里用扩展 B 的「𠮷」（U+20BB7）锁住按 code point 走。
     */
    @Test
    void surrogatePairRareCharacter_stillProducesBigram() {
        assertTrue(FusionSupport.containsAnyKeyword("𠮷字表", "𠮷字表怎么用"));
    }

    /** 单个 CJK 字符不作候选：单字命中率接近 100%，增益会变成常量。 */
    @Test
    void singleCjkCharAloneNeverMatches() {
        assertFalse(FusionSupport.containsAnyKeyword("退款政策", "的"));
    }

    @Test
    void nullOrBlankInputs_returnFalse() {
        assertFalse(FusionSupport.containsAnyKeyword((String) null, "退款"));
        assertFalse(FusionSupport.containsAnyKeyword("退款政策", (String) null));
        assertFalse(FusionSupport.containsAnyKeyword("", "退款"));
        assertFalse(FusionSupport.containsAnyKeyword("退款政策", "   "));
        assertFalse(FusionSupport.containsAnyKeyword("退款政策", (java.util.Set<String>) null));
    }

    /** 标题本身为空/缺字段时不该命中（由 titleHit 的守卫负责，这里确认底层不越权）。 */
    @Test
    void emptyTitle_neverHits() {
        assertFalse(FusionSupport.containsAnyKeyword("", "退款"));
    }
}
