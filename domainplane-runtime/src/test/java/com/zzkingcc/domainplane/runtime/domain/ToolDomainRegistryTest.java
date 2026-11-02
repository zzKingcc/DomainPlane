package com.zzkingcc.domainplane.runtime.domain;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具侧域记忆（从 {@code ToolRegistry} 拆出来的那一半）的行为。
 *
 * <p>拆这个类的目的不是缩行数，而是让域治理有一处独立的落点 —— 那么它的行为就必须
 * 能被单独钉住，而不是"顺带被工具副本的测试覆盖到"。这里钉的四条都是
 * <b>删域这条授权链</b>上的关键语义，错任何一条都等于"删了但没删干净"：</p>
 * <ol>
 *   <li>声明过即保留 —— 工具断开不该让域消失；</li>
 *   <li>撤销要"记住"而不只"删一次" —— 否则下一次心跳就把域带回来；</li>
 *   <li>解除撤销不补声明 —— 授权只能来自工具侧真实的声明；</li>
 *   <li>哨兵不是域 —— 它混入域全集会在管控台多出一个假域。</li>
 * </ol>
 */
class ToolDomainRegistryTest {

    private final ToolDomainRegistry registry = new ToolDomainRegistry(new DomainRegistry());

    @Test
    @DisplayName("声明过的域在进程内保留：工具断开后域依然可用（入口不判 10004）")
    void declaredDomainsSurvive() {
        registry.remember(List.of("default.finance"));
        assertTrue(registry.declared().contains("default.finance"));
        /* 没有"移除声明"这一说：只有 forget 会摘掉它。 */
        registry.remember(List.of("default.finance"));
        assertTrue(registry.declared().contains("default.finance"));
    }

    @Test
    @DisplayName("撤销后不再接受新声明：删域不是删一次，是记住")
    void revokedDomainRejectsNewDeclarations() {
        registry.remember(List.of("default.hr.payroll"));
        registry.forget(List.of("default.hr.payroll"));

        assertTrue(registry.isRevoked("default.hr.payroll"));
        assertFalse(registry.declared().contains("default.hr.payroll"));

        /* 心跳带着原始 manifest 回来 —— 撤销必须挡住它 */
        registry.remember(List.of("default.hr.payroll"));
        assertFalse(registry.declared().contains("default.hr.payroll"),
                "撤销过的域不能被一次心跳重新声明回来，否则删域等于没删");
    }

    @Test
    @DisplayName("解除撤销后不主动补声明：授权只能来自工具侧真实的声明")
    void allowOnlyClearsRevocation() {
        registry.remember(List.of("default.ops"));
        registry.forget(List.of("default.ops"));
        registry.allow(List.of("default.ops"));

        assertFalse(registry.isRevoked("default.ops"));
        assertFalse(registry.declared().contains("default.ops"),
                "解除撤销只是恢复资格，不该凭空造出一次没人声明过的授权");

        /* 下一次心跳带回来后，声明重新生效 */
        registry.remember(List.of("default.ops"));
        assertTrue(registry.declared().contains("default.ops"));
    }

    @Test
    @DisplayName("哨兵不是域：不会被并入域全集，也不会被声明进去")
    void sentinelIsNotADomain() {
        registry.remember(List.of(ToolDescriptor.REVOKED_DOMAIN));
        assertFalse(registry.declared().contains(ToolDescriptor.REVOKED_DOMAIN),
                "哨兵只在工具声明里表示'对任何域都不可见'，混入域全集会多出一个假域");
    }

    @Test
    @DisplayName("空白与 null 项被忽略：工具 manifest 来自外部，脏数据是常态")
    void blankEntriesIgnored() {
        registry.remember(java.util.Arrays.asList("default.ok", null, "   ", ""));
        assertEquals(Set.of("default.ok"), registry.declared());
    }

    @Test
    @DisplayName("underLock 借出的临界区与内部方法同一把锁：删域的'撤销 + 剥离'能合成原子动作")
    void underLockSharesTheSameMonitor() {
        registry.remember(List.of("default.a"));
        registry.underLock(() -> {
            registry.forget(List.of("default.a"));
            /* 临界区内看不到"撤销已生效但声明还在"的中间态 */
            assertFalse(registry.declared().contains("default.a"));
            assertTrue(registry.isRevoked("default.a"));
        });
    }
}
