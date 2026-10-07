package com.zzkingcc.domainplane.runtime.tool;

/**
 * 工具实例的存活状态
 *
 *
 *         整包心跳(受理)            lastSeen 超时 / 强制下线标记
 * ONLINE ──────────────▶ ONLINE ─────────────────────▶ DRAINING ──▶ OFFLINE
 *   │                                                                  │
 *   └──▶ MUTED ──（控制台恢复 / 实例重启）──▶ ONLINE                     │
 *        副本已摘除但心跳照常受理           重连 整包心跳(受理) ◀────────┘
 *                                    (FORCE_OFFLINE 不可恢复，重连心跳返 410)
 *
 *
 * DRAINING 是为"判死与清理并发"准备的中间态：超时定时器与强制下线可能同时触发，
 * 靠状态机单飞——只有把 ONLINE 改成 DRAINING 成功的那一方，才获得清理权。
 * @author zzkingcc
 */
public enum InstanceState {

    /** 在线：副本可见，可被路由调用 */
    ONLINE("在线"),

    /** 已熔断：副本已摘除、工具不可调用，但心跳仍被受理（实例重启后其副本会随慢路径自动重建） */
    MUTED("已熔断"),

    /** 已判死，正在清理：仅摘除该实例的副本（不触达会话层），期间调用它的副本会失败并由调用层换副本重试 */
    DRAINING("清理中"),

    /** 清理完成，已从在线表移除；重连即回 ONLINE */
    OFFLINE("已下线"),

    /** 被管理员强制下线：立即清副本，不可恢复，重连心跳返 410 Gone */
    FORCE_OFFLINE("强制下线");

    private final String label;

    InstanceState(String label) {
        this.label = label;
    }

    /** 中文展示名（管控台用） */
    public String label() {
        return label;
    }

    /** 是否为"拒绝心跳"的状态（管控台显示与 410 判定共用同一判据；熔断不在其中——它只掐工具，不掐心跳） */
    public boolean isRejecting() {
        return this == FORCE_OFFLINE;
    }
}
