package com.zzkingcc.domainplane.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规划前置能力的配置绑定（{@code domainplane.planning.*}）。
 *
 * <p>默认关闭：规划前置是每轮多一次主模型往返的能力，灰度起步。
 * 打开后每轮进图前多一次规划调用（direct / plan 由模型自判），关闭时编排行为分毫不变。
 * 计划是否<b>展示给前端</b>是另一颗开关，走域级设置（{@code DomainSettings.planDisplay}），
 * 与这里互不约束：全局管"做不做"，域级管"给不给看"。</p>
 */
@ConfigurationProperties(prefix = "domainplane.planning")
public class PlanningProperties {

    /** 是否启用规划前置（每轮进图前一次主模型规划调用） */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
