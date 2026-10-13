package com.zzkingcc.domainplane.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-09：用户原文不得进日志。
 *
 * <p>这条规则单靠"写日志时注意"是守不住的：现实里最常见的写法是
 * "截断 50 字再打"，而有意义的提问多数短于 50 字，等于整条入库。
 * 所以本工具<b>只提供"只记长度"一种形态</b>，并用测试把这条钉死：
 * 任何人想改成"截断后打"，先得改这个测试。</p>
 *
 * @author zzkingcc
 */
@DisplayName("D-09 日志脱敏：用户原文一字不出")
class LogSanitizerTest {

    @Test
    @DisplayName("返回长度描述，不含原文任何片段")
    void describesLengthOnly() {
        String secret = "我的银行卡号是 6222021234567890，密码 abc123";
        String described = LogSanitizer.describeUserText(secret);

        assertFalse(described.contains("6222021234567890"), "原文（含敏感片段）不得出现：" + described);
        assertFalse(described.contains("abc123"), "原文（含敏感片段）不得出现：" + described);
        assertFalse(described.contains("银行卡"), "原文的任何片段都不得出现：" + described);
    }

    @Test
    @DisplayName("长度确实是原文长度（长度本身不泄露内容，可安全记录）")
    void reportsActualLength() {
        assertEquals("「" + "字".repeat(37).length() + " 字符」",
                LogSanitizer.describeUserText("字".repeat(37)));
    }

    /**
     * 短文本也要守住：现实中最容易被"截断 50 字"这种写法漏掉的恰恰是短句，
     * 而短句同样可能含敏感信息（一个手机号、一句咒骂都能塞进 20 字内）。
     */
    @Test
    @DisplayName("短文本同样一字不出（截断式写法最容易在这里漏）")
    void shortTextIsAlsoProtected() {
        String shortSecret = "密码是hunter2";
        String described = LogSanitizer.describeUserText(shortSecret);

        assertFalse(described.contains("hunter2"), "短文本不得因'短'而被完整记录：" + described);
        assertTrue(described.contains(String.valueOf(shortSecret.length())));
    }

    @Test
    @DisplayName("null 与空串都安全，不抛 NPE 也不返回 null")
    void handlesNullAndEmpty() {
        assertEquals("「空」", LogSanitizer.describeUserText(null));
        assertEquals("「0 字符」", LogSanitizer.describeUserText(""));
    }

    /** 空串必须与 null 区分：排障时"收到空查询"和"没收到查询"是两回事 */
    @Test
    @DisplayName("空串与 null 报告不同，可区分排障")
    void distinguishesNullFromEmpty() {
        assertFalse(LogSanitizer.describeUserText(null)
                .equals(LogSanitizer.describeUserText("")));
    }
}
