package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.runtime.config.Timeouts;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 超时总表的配置绑定（{@code domainplane.timeout.*}）。
 *
 * <p>这五个数字此前是三处互不相干的写法：编排里两个 {@code static final} 常量
 * （10 分钟 / 200 毫秒），工具、看图、能力调用各一个 {@code @Value} 注入的裸数字。
 * 收成一张表不是为了"统一管理"这种虚名，而是为了<b>改一个漏一个</b>这件事不再可能：
 * "等多久算太长"这个判断在平台里只该有一份答案。</p>
 *
 * <p>旧键（{@code domainplane.tool.invoke-timeout-ms} / {@code vision.timeout-ms} /
 * {@code capability.timeout-ms}）随本次收口一并作废，由本表的
 * {@code tool-invoke-ms} / {@code vision-call-ms} / {@code capability-call-ms} 取代。
 * 作废而不是兼容两个数：兼容意味着"到底哪个生效"要再查一遍代码，
 * 而那正是这次要消灭的东西。默认值与旧值完全一致，没在 yaml 里显式配过的部署行为不变。</p>
 *
 * <p>合并的是<b>配置表</b>，不是执行单元：三个执行池继续各是各的
 * （"生图不饿死检索"靠池独立，不靠超时不同），池的"满即拒"语义也不在这张表里。</p>
 */
@ConfigurationProperties(prefix = "domainplane.timeout")
public class TimeoutsProperties {

    /** 等模型终态的总上限（毫秒） */
    private long modelWaitMs = Timeouts.DEFAULT_MODEL_WAIT_MS;

    /** 等模型终态的单次阻塞上限（毫秒）：决定"点停止"的响应延迟 */
    private long modelWaitSliceMs = Timeouts.DEFAULT_MODEL_WAIT_SLICE_MS;

    /** 规划前置调用的总上限（毫秒）：到点降级直进 loop，不拖累本轮 */
    private long plannerWaitMs = Timeouts.DEFAULT_PLANNER_WAIT_MS;

    /** 本地工具单次调用上限（毫秒） */
    private long toolInvokeMs = Timeouts.DEFAULT_TOOL_INVOKE_MS;

    /** 看图调用上限（毫秒） */
    private long visionCallMs = Timeouts.DEFAULT_VISION_CALL_MS;

    /** 能力调用上限（毫秒） */
    private long capabilityCallMs = Timeouts.DEFAULT_CAPABILITY_CALL_MS;

    /**
     * 落成 runtime 的值对象。
     *
     * <p>校验写在 {@link Timeouts} 的紧凑构造器里（正数、分片不大于总上限），
     * 这里只负责搬运 —— 判据只有一份，测试可以直接对着 {@code Timeouts} 钉，
     * 不必先把它装配成一个 Spring bean。</p>
     */
    public Timeouts timeouts() {
        return new Timeouts(modelWaitMs, modelWaitSliceMs, plannerWaitMs, toolInvokeMs, visionCallMs, capabilityCallMs);
    }

    public long getModelWaitMs() {
        return modelWaitMs;
    }

    public void setModelWaitMs(long modelWaitMs) {
        this.modelWaitMs = modelWaitMs;
    }

    public long getModelWaitSliceMs() {
        return modelWaitSliceMs;
    }

    public void setModelWaitSliceMs(long modelWaitSliceMs) {
        this.modelWaitSliceMs = modelWaitSliceMs;
    }

    public long getPlannerWaitMs() {
        return plannerWaitMs;
    }

    public void setPlannerWaitMs(long plannerWaitMs) {
        this.plannerWaitMs = plannerWaitMs;
    }

    public long getToolInvokeMs() {
        return toolInvokeMs;
    }

    public void setToolInvokeMs(long toolInvokeMs) {
        this.toolInvokeMs = toolInvokeMs;
    }

    public long getVisionCallMs() {
        return visionCallMs;
    }

    public void setVisionCallMs(long visionCallMs) {
        this.visionCallMs = visionCallMs;
    }

    public long getCapabilityCallMs() {
        return capabilityCallMs;
    }

    public void setCapabilityCallMs(long capabilityCallMs) {
        this.capabilityCallMs = capabilityCallMs;
    }
}
