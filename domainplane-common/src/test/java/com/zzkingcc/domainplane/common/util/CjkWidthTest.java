package com.zzkingcc.domainplane.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CJK 判定的契约。
 *
 * <p>每一条都对应一个真实故障：假名漏判（日文低估约 6 倍）、扩展区段漏判、
 * 代理对被算成两个字符，以及判定表"按相对偏移建、按相对偏移读"引发的越界崩溃
 * —— 那一版在记忆封顶判定里直接抛 {@code ArrayIndexOutOfBoundsException}，
 * 整条会话链路挂掉。</p>
 *
 * @author zzkingcc
 */
class CjkWidthTest {

    @Test
    @DisplayName("假名按 CJK 计（此前区间止于中文标点 U+303F，日文按 0.25/字 估）")
    void kanaIsCjk() {
        assertTrue(CjkWidth.isCjk('あ'));   // 平假名 U+3041
        assertTrue(CjkWidth.isCjk('ア'));   // 片假名 U+30A2
        assertTrue(CjkWidth.isCjk('ー'));   // 长音 U+30FC
        // 断点就在这一对：U+303F 是中文句读，U+3040 才是平假名起点
        assertTrue(CjkWidth.isCjk('。'));   // 中文标点仍按 CJK
        assertTrue(CjkWidth.isCjk('、'));   // 全角顿号
        assertFalse(CjkWidth.isCjk('a'));
    }

    @Test
    @DisplayName("CJK 扩展区段都算 CJK")
    void extendedRangesAreCjk() {
        assertTrue(CjkWidth.isCjk('一'));   // 基本区
        assertTrue(CjkWidth.isCjk('㐀'));   // 扩展 A U+3400
        assertTrue(CjkWidth.isCjk('豈'));   // 兼容表意 U+F900
        assertTrue(CjkWidth.isCjk('あ'));   // 韩文音节区的邻居：'한' U+D55C
        assertTrue(CjkWidth.isCjk('한'));
    }

    @Test
    @DisplayName("生僻汉字（代理对）按 1 个 CJK 字符算，不拆成两个普通字符")
    void surrogatePairCountsAsOneCjkChar() {
        String rare = "𠮷";   // 扩展 B
        assertEquals(2, rare.length());                    // UTF-16 上占两个 char
        assertEquals(1, CjkWidth.countChars(rare));         // 但只算 1 个字符
        assertEquals(2, CjkWidth.estimateTokens(rare));     // 1.5 → 向上取整 2
        assertTrue(CjkWidth.isCjk(rare, 0));
    }

    @Test
    @DisplayName("纯 ASCII 按 0.25/字，混合文本逐字符累加")
    void asciiAndMixed() {
        assertEquals(0, CjkWidth.estimateTokens(null));
        assertEquals(0, CjkWidth.estimateTokens(""));
        assertEquals(1, CjkWidth.estimateTokens("abcd"));   // 4 × 0.25 = 1.0
        assertEquals(3, CjkWidth.estimateTokens("中文"));   // 2 × 1.5  = 3.0
        assertEquals(2, CjkWidth.estimateTokens("a中"));    // 0.25 + 1.5 = 1.75 → 2
        assertEquals(0, CjkWidth.countChars(null));
    }

    @Test
    @DisplayName("判定表对整个 BMP 都可安全索引（曾按相对偏移读，导致 AIOOBE 崩溃）")
    void everyBmpCharIsIndexable() {
        // 关键：不抛异常即为通过。曾经的实现在 FULLWIDTH 分支上越界，
        // 而记忆封顶判定每轮都会走到这里 —— 表现为会话直接崩，不是算错
        for (int c = 0x3000; c < 0xD800; c++) {
            CjkWidth.isCjk((char) c);
        }
        assertTrue(CjkWidth.isCjk('鿿'));   // U+9FFF 基本区末字
        assertFalse(CjkWidth.isCjk('꓏'));  // U+A000 段，越过原先所有分支的偏移基数
    }
}