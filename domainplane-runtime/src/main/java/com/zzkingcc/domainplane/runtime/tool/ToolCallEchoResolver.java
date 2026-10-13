package com.zzkingcc.domainplane.runtime.tool;

/**
 * 域级「工具执行信息回写」开关解析器。
 *
 * 控制的是编排层要不要把工具调用请求（TOOL_CALL）与执行结果（TOOL_RESULT）
 * 下发给调用方；关掉后调用方只收到最终答复。审批挂起（待授权工具清单）不归它管：
 * 那是功能骨架，恒下发。
 *
 * 放在 runtime 侧而不是让编排服务直接依赖 server 的设置存储：与
 * {@code SystemPromptResolver} 同一模式（接口在 runtime、实现在 server、
 * 由 {@code GraphConfiguration} 装配），模块边界不破。
 *
 * @author zzkingcc
 */
public interface ToolCallEchoResolver {

    /**
     * 该域是否回写工具执行信息。
     *
     * @param domainId 本轮所处的域（入口已归一化）
     * @return 显式设为 true 才回写；未配置 / 装配节点 / 解析不到一律按关处理
     */
    boolean echoOn(String domainId);
}
