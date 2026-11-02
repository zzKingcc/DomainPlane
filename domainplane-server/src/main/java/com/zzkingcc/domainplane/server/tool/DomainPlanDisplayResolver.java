package com.zzkingcc.domainplane.server.tool;

import com.zzkingcc.domainplane.runtime.orchestration.PlanDisplayResolver;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import lombok.extern.slf4j.Slf4j;

/**
 * 域级「执行计划展示」开关的管控台实现：读 {@code config/prompts.json} 里的
 * {@code planDisplay} 段（与 {@code DomainToolCallEchoResolver} 同一套路，
 * 每次解析现读盘上设置，保存即生效、无需重启）。
 *
 * <p>关掉时规划照做（模型照样按计划执行），只是调用方收不到 {@code PLAN} 事件。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class DomainPlanDisplayResolver implements PlanDisplayResolver {

    private final DomainSettingsStore store;

    public DomainPlanDisplayResolver(DomainSettingsStore store) {
        this.store = store;
    }

    @Override
    public boolean displayOn(String domainId) {
        DomainSettings settings = store.load();
        return settings.planDisplay(domainId);
    }
}
