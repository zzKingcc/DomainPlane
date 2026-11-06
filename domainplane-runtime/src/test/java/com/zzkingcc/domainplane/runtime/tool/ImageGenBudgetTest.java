package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.TraceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生图预算的两条口径：一轮内共享同一个桶、跨工具线程按引用搬运不换桶。
 *
 * <p>第二条是最容易写错又最难在运行时发现的一处：工具跑在隔离线程上，
 * 若搬运时漏了这个字段，工具侧读到 null 就会按「不限制」放行，
 * 于是「每轮最多 3 次」静默失效，模型照样能画六张。</p>
 *
 * @author zzkingcc
 */
@DisplayName("生图预算：每轮共享 / 隔离线程按引用搬运")
class ImageGenBudgetTest {

    @AfterEach
    void cleanup() {
        ToolInvocationContext.clear();
        RetrievalScope.clear();
        TraceId.end();
    }

    @Test
    @DisplayName("前三次放行，第四次拦住，剩余额度不会变成负数")
    void threeCallsAllowedThenBlocked() {
        ImageGenBudget budget = new ImageGenBudget();
        for (int i = 0; i < ImageGenBudget.MAX_CALLS; i++) {
            assertTrue(budget.tryAcquire(), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(budget.tryAcquire(), "超出上限应拦住");
        assertFalse(budget.tryAcquire());
        assertEquals(0, budget.remaining());
        assertEquals(3, ImageGenBudget.MAX_CALLS);
    }

    @Test
    @DisplayName("每次扣减都反映在同一个桶上")
    void sharedAcrossLoops() {
        ImageGenBudget budget = new ImageGenBudget();
        assertEquals(3, budget.remaining());
        budget.tryAcquire();
        budget.tryAcquire();
        assertEquals(1, budget.remaining());
    }

    @Test
    @DisplayName("隔离线程的上下文快照带走同一个预算对象，复原后仍扣原桶")
    void snapshotCarriesSameBudgetReference() {
        ImageGenBudget budget = new ImageGenBudget();
        RoundSearchBudget search = new RoundSearchBudget();
        ToolInvocationContext.bind("trace-1", "s-A", search, budget);
        RetrievalScope.bind("default");

        ToolExecutorService.ContextSnapshot snapshot = ToolExecutorService.ContextSnapshot.capture();
        assertSame(budget, snapshot.imageGenBudget());
        assertSame(search, snapshot.searchBudget());

        ToolInvocationContext.clear();
        assertNull(ToolInvocationContext.imageGenBudget());
        snapshot.applyToCurrentThread();
        assertSame(budget, ToolInvocationContext.imageGenBudget(), "复原后必须是同一个桶");
        assertSame(search, ToolInvocationContext.searchBudget());

        assertTrue(budget.tryAcquire());
        assertEquals(2, budget.remaining());
    }

    @Test
    @DisplayName("没有绑预算时快照里是 null，工具侧按不限制处理")
    void snapshotWithoutBudgetStaysNull() {
        ToolInvocationContext.bind("trace-2", "s-B");
        ToolExecutorService.ContextSnapshot snapshot = ToolExecutorService.ContextSnapshot.capture();
        assertNull(snapshot.imageGenBudget());
        ToolInvocationContext.clear();
        snapshot.applyToCurrentThread();
        assertNull(ToolInvocationContext.imageGenBudget());
    }
}
