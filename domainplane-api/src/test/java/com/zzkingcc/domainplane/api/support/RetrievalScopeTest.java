package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 检索域：绑定即归一化，未绑定即"不过滤"。
 */
class RetrievalScopeTest {

    @Test
    void 绑定后归一化并可读() {
        try {
            RetrievalScope.bind(" customer ");
            assertEquals("customer", RetrievalScope.current());
        } finally {
            RetrievalScope.clear();
        }
    }

    @Test
    void 空域归一化为根域() {
        try {
            RetrievalScope.bind(null);
            assertEquals(Domains.DEFAULT, RetrievalScope.current());
            RetrievalScope.bind("   ");
            assertEquals(Domains.DEFAULT, RetrievalScope.current());
        } finally {
            RetrievalScope.clear();
        }
    }

    @Test
    void 未绑定时为null且清除后不残留() {
        RetrievalScope.clear();
        assertNull(RetrievalScope.current());
        RetrievalScope.bind("customer");
        RetrievalScope.clear();
        assertNull(RetrievalScope.current(), "解除后不能污染同线程的下一轮");
    }
}
