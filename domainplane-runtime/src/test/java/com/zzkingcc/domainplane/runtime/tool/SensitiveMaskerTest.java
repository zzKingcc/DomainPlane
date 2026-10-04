package com.zzkingcc.domainplane.runtime.tool;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感参数掩码：只改"给人看的"参数文本，且要盖得住嵌套。
 */
class SensitiveMaskerTest {

    @Test
    void masksDeclaredNamesIncludingNestedOnes() {
        String masked = SensitiveMasker.mask(
                "{\"orderNo\":\"FR1\",\"phone\":\"13800000000\",\"contact\":{\"email\":\"a@b.c\"}}",
                Set.of("phone", "email"));

        assertEquals("{\"orderNo\":\"FR1\",\"phone\":\"***\",\"contact\":{\"email\":\"***\"}}", masked);
    }

    @Test
    void masksWholeValueWhateverItsShape() {
        String masked = SensitiveMasker.mask(
                "{\"idCard\":{\"no\":\"123\"},\"phones\":[\"1\",\"2\"]}",
                Set.of("idCard", "phones"));

        assertEquals("{\"idCard\":\"***\",\"phones\":\"***\"}", masked);
    }

    @Test
    void returnsInputUntouchedWhenNothingToDo() {
        String args = "{\"orderNo\":\"FR1\"}";

        assertEquals(args, SensitiveMasker.mask(args, Set.of()));
        assertEquals(args, SensitiveMasker.mask(args, null));
        assertEquals("", SensitiveMasker.mask("", Set.of("phone")));
        assertNull(SensitiveMasker.mask(null, Set.of("phone")));
    }

    @Test
    void nonJsonArgumentsAreBlockedWholesaleRatherThanLeaked() {
        // 模型生成畸形 JSON 是常态。此时原样返回 = 把身份证/手机号明文送进
        // 工具调用事件与审批 payload —— 而那正是脱敏要守的两处。
        // 保护机制在失败时退化成不保护，比不保护更危险，所以整段屏蔽。
        String masked = SensitiveMasker.mask("not-json", Set.of("phone"));

        assertEquals(SensitiveMasker.UNPARSEABLE, masked);
        assertTrue(SensitiveMasker.isMasked(masked), "调用方要能识别出这是整体屏蔽，据此拒绝盲批");
    }

    /** 整体屏蔽同样适用于「JSON 合法但结构 unexpected」的情况 */
    @Test
    void truncatedJsonIsBlockedNotLeaked() {
        String broken = "{\"phone\":\"13800000000\",\"name\"";   // 少一个右括号

        String masked = SensitiveMasker.mask(broken, Set.of("phone"));

        assertFalse(masked.contains("13800000000"), "截断的 JSON 里同样不能漏出原值");
        assertTrue(SensitiveMasker.isMasked(masked));
    }

    /** 没有声明敏感项时不做无谓处理：原文照常返回（调用方自己负责） */
    @Test
    void untouchedWhenNoSensitiveDeclared() {
        String args = "not-json";
        assertEquals(args, SensitiveMasker.mask(args, Set.of()),
                "未声明敏感项时不该改动内容");
    }
}
