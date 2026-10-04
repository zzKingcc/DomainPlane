package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话状态键：(域, sessionId) 是记忆 / 检查点 / 取消 / 流的统一键。
 *
 * <p>两个域用同一个 sessionId 必须得到<b>不同的键</b> —— 隔离由键本身保证，
 * 不再依赖一张"会话属于哪个域"的进程内映射表。</p>
 */
class SessionKeysTest {

    @Test
    void twoDomainsWithSameSessionIdGetDifferentKeys() {
        String keyA = SessionKeys.of("default.sales", "s-1");
        String keyB = SessionKeys.of("default.finance", "s-1");

        assertNotNull(keyA);
        assertNotNull(keyB);
        assert !keyA.equals(keyB) : "不同域的同名会话必须隔离";
        assertEquals("default.sales|s-1", keyA);
        assertEquals("default.finance|s-1", keyB);
    }

    @Test
    void nullOrBlankDomainFallsBackToRoot() {
        assertEquals(Domains.DEFAULT + "|s-1", SessionKeys.of(null, "s-1"));
        assertEquals(Domains.DEFAULT + "|s-1", SessionKeys.of("  ", "s-1"));
    }

    @Test
    void keyRoundTripsDomainAndSessionId() {
        String key = SessionKeys.of("default.sales.order", "s-1");

        assertEquals("default.sales.order", SessionKeys.domainOf(key));
        assertEquals("s-1", SessionKeys.sessionIdOf(key));
    }

    @Test
    void legacyKeyWithoutSeparatorStillYieldsSessionId() {
        // 升级前按裸 sessionId 存的检查点：读不出域，但会话标识还能识别出来
        assertEquals("s-1", SessionKeys.sessionIdOf("s-1"));
        assertNull(SessionKeys.domainOf("s-1"));
    }

    @Test
    void sessionIdCannotBeBlankOrContainTheSeparator() {
        assertNotNull(SessionKeys.validateSessionId(null));
        assertNotNull(SessionKeys.validateSessionId("  "));
        assertNotNull(SessionKeys.validateSessionId("a|b"));
        assertNull(SessionKeys.validateSessionId("s-1"), "合法 sessionId 应通过");

        assertThrows(IllegalArgumentException.class, () -> SessionKeys.of("default.sales", "a|b"));
        assertThrows(IllegalArgumentException.class, () -> SessionKeys.of("default.sales", ""));
    }
}
