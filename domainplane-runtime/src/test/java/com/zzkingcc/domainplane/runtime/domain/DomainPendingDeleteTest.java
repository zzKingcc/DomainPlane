package com.zzkingcc.domainplane.runtime.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软删标记的注册表语义：标记与域树结构叠加，不是替换。
 *
 * 钉死三条不变式：
 *   • 软删不碰结构判据（{@code isCallable} / {@code callableIds} 原样）；
 *   • 硬删（{@code delete}）时标记随域一起消失——留着的话同路径域重建后
 *     会莫名拒新会话（标记在 domains.json 里，重启照样恢复）；
 *   • 回滚重建（{@code replaceDeclarations}）后声明与标记整体换成快照状态。
 *
 * @author zzkingcc
 */
class DomainPendingDeleteTest {

    private DomainRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DomainRegistry();
        registry.create("default.sales");
        registry.create("default.sales.order");
    }

    @Test
    void 标记不影响结构判据() {
        registry.markPendingDelete("default.sales.order", 1000L);

        assertTrue(registry.isPendingDelete("default.sales.order"));
        assertTrue(registry.isCallable("default.sales.order"),
                "软删是生命周期状态，结构判据必须原样");
        assertTrue(registry.callableIds().contains("default.sales.order"));
    }

    @Test
    void 撤销即清除标记() {
        registry.markPendingDelete("default.sales.order", 1000L);

        registry.clearPendingDelete("default.sales.order");

        assertFalse(registry.isPendingDelete("default.sales.order"));
        assertNull(registry.pendingDeleteAt("default.sales.order"));
    }

    @Test
    void 硬删时标记随域一起消失() {
        registry.markPendingDelete("default.sales", 1000L);
        registry.markPendingDelete("default.sales.order", 1000L);

        registry.delete("default.sales");

        assertFalse(registry.isPendingDelete("default.sales"));
        assertFalse(registry.isPendingDelete("default.sales.order"),
                "子孙一并硬删，标记也必须一并消失");
        assertTrue(registry.pendingDeletes().isEmpty());
    }

    @Test
    void 启动恢复按落盘标记灌入() {
        registry.loadPendingDeletes(Map.of("default.sales", 2000L, "default.sales.order", 2000L));

        assertEquals(2000L, registry.pendingDeleteAt("default.sales"));
        assertEquals(2000L, registry.pendingDeleteAt("default.sales.order"));
        assertEquals(2, registry.pendingDeletes().size());
    }

    @Test
    void 回滚重建整体替换声明与标记() {
        registry.markPendingDelete("default.sales", 1000L);

        // 快照状态：只有 default.hr 可调用，且它带着软删标记
        registry.replaceDeclarations(Map.of("default.hr", true), Map.of("default.hr", 3000L));

        assertFalse(registry.contains("default.sales"), "旧声明被清掉");
        assertTrue(registry.contains("default.hr"));
        assertTrue(registry.isCallable("default.hr"));
        assertFalse(registry.isPendingDelete("default.sales"), "旧标记被清掉");
        assertEquals(3000L, registry.pendingDeleteAt("default.hr"), "快照里的标记原样恢复");
    }
}
