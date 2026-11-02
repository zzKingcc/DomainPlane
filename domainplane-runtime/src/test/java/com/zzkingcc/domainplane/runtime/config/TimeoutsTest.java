package com.zzkingcc.domainplane.runtime.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超时总表的取值约束。
 *
 * <p>钉的是"配错要在启动时炸、而不是在调用时表现成怪事"。这些数字此前散在三个模块
 * 里、形态各异（两个 {@code static final} 常量 + 三个 {@code @Value} 裸数字），
 * 没有任何一处检查过它们的合法性 —— 于是 {@code 0} 在有的地方是"永不超时"、
 * 在有的地方是"立刻超时"，配错的人要到线上才发现。</p>
 *
 * <p>最要紧的一条是<b>分片不得大于总上限</b>：等模型是"睡一个分片、醒来先看停止标志
 * 再判断有没有超总上限"，分片比总上限还长的话，第一次醒来就已经超时了 ——
 * 停止标志从此再也没有人读，用户点了停止这一轮照样跑到底。</p>
 */
class TimeoutsTest {

    @Test
    @DisplayName("默认值与改造前散在各处的常量逐一相等：这次收表是零行为变化")
    void defaultsMatchLegacyConstants() {
        Timeouts t = Timeouts.defaults();
        assertEquals(10 * 60 * 1000L, t.modelWaitMs(), "等模型终态仍是 10 分钟");
        assertEquals(200L, t.modelWaitSliceMs(), "分片仍是 200ms（决定停止的响应延迟）");
        assertEquals(60_000L, t.plannerWaitMs(), "规划前置仍是 60s（超时即降级直进 loop）");
        assertEquals(30_000L, t.toolInvokeMs(), "本地工具仍是 30s");
        assertEquals(60_000L, t.visionCallMs(), "看图仍是 60s");
        assertEquals(120_000L, t.capabilityCallMs(), "能力调用仍是 120s");
    }

    @Test
    @DisplayName("六个值都必须为正：0 与负数在调用侧的含义各不相同，统一挡在入口")
    void nonPositiveRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(0, 200, 60_000, 30_000, 60_000, 120_000));
        assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(600_000, 0, 60_000, 30_000, 60_000, 120_000));
        assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(600_000, 200, 0, 30_000, 60_000, 120_000));
        assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(600_000, 200, 60_000, -1, 60_000, 120_000));
        assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(600_000, 200, 60_000, 30_000, 0, 120_000));
        assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(600_000, 200, 60_000, 30_000, 60_000, 0));
    }

    @Test
    @DisplayName("分片大于总上限即启动失败：那样第一次醒来就已超时，停止标志再也没人读")
    void sliceMustNotExceedLimit() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(1_000, 5_000, 60_000, 30_000, 60_000, 120_000));
        assertTrue(e.getMessage().contains("model-wait-slice-ms"), e.getMessage());
        /* 相等是合法的：一个分片跑满整段，醒来刚好超时，语义仍成立 */
        assertDoesNotThrow(() -> new Timeouts(1_000, 1_000, 60_000, 30_000, 60_000, 120_000));
    }

    @Test
    @DisplayName("错误文案带键名：配错的人要一眼看出改哪一项")
    void messageCarriesKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(600_000, 200, 60_000, 0, 60_000, 120_000));
        assertTrue(e.getMessage().contains("domainplane.timeout.tool-invoke-ms"), e.getMessage());
        IllegalArgumentException planner = assertThrows(IllegalArgumentException.class,
                () -> new Timeouts(600_000, 200, -5, 30_000, 60_000, 120_000));
        assertTrue(planner.getMessage().contains("domainplane.timeout.planner-wait-ms"), planner.getMessage());
    }
}
