package com.zzkingcc.domainplane.runtime.orchestration;

/**
 * 域级「执行计划展示」开关解析器。
 *
 * <p>控制的是编排层要不要把规划前置产出的本轮执行计划（{@code PLAN} 事件）下发给
 * 调用方；关掉后规划照做（模型照样按计划执行），只是调用方看不到计划。
 * 与 {@code ToolCallEchoResolver} 同一模式：接口在 runtime、实现在 server
 * （读域设置存储，保存即生效）、由装配层注入 —— 模块边界不破。</p>
 *
 * @author zzkingcc
 */
public interface PlanDisplayResolver {

    /**
     * 该域是否把本轮执行计划下发给调用方。
     *
     * @param domainId 本轮所处的域（入口已归一化）
     * @return 显式设为 true 才下发；未配置 / 装配节点 / 解析不到一律按关处理
     */
    boolean displayOn(String domainId);
}
