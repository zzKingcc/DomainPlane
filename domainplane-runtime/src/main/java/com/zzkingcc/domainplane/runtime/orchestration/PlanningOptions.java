package com.zzkingcc.domainplane.runtime.orchestration;

/**
 * 规划前置能力的部署级开关。
 *
 * <p>纯值对象（runtime 不依赖 Spring）：绑定 {@code domainplane.planning.enabled}
 * 的 {@code @ConfigurationProperties} 在 server 侧，这里只收结果。</p>
 *
 * <p>默认关闭：规划前置是每轮多一次主模型往返的能力，灰度起步 —— 打开后每一轮
 * 进图前都会多一次规划调用（direct / plan 由模型在同一次调用里自判），
 * 关闭时编排行为与该能力不存在时分毫不变。</p>
 *
 * @param enabled true = 每轮进图前先做一次规划前置调用
 */
public record PlanningOptions(boolean enabled) {

    public static PlanningOptions disabled() {
        return new PlanningOptions(false);
    }

    public static PlanningOptions on() {
        return new PlanningOptions(true);
    }
}
