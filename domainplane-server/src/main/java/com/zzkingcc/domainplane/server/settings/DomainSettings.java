package com.zzkingcc.domainplane.server.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 域提示词设置（落盘 {@code config/prompts.json}）
 *
 * 没有"公共基线"这一层：根域 {@code default} 就是基底，它的提示词存在
 * {@code prompts["default"]} 里，与别的域完全同构。生效提示词是沿链拼接的结果：
 * 从根域到当前域，依次取出每段的片段拼成一段。
 *
 * 键是完整路径域（{@code default.sales.order}），与工具声明、知识库同构。
 *
 * @author zzkingcc
 */
public class DomainSettings {

    /** 域（完整路径） → 该域的提示词片段 */
    private Map<String, String> prompts = new LinkedHashMap<>();

    /**
     * 域（完整路径） → 是否回写工具执行信息（工具调用请求与执行结果）。
     *
     * 未配置 = 关：调用方只收到最终答复，收不到模型调了什么、结果是什么。
     * 只认自身显式值，不沿链回落：回落会让子域关不掉祖先打开的开关。
     * 只对可调用单元（叶子域）有意义：装配节点不能作为入口，它的值永远不会被读到；
     * 管控台也只给可调用单元显示开关，设置信息原样保留（域变回叶子时自动恢复生效）。
     * 审批挂起（待授权工具清单）不归这个开关管：那是功能骨架，恒下发。
     */
    private Map<String, Boolean> toolCallEcho = new LinkedHashMap<>();

    public Map<String, String> getPrompts() {
        return prompts;
    }

    public void setPrompts(Map<String, String> prompts) {
        this.prompts = prompts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prompts);
    }

    public Map<String, Boolean> getToolCallEcho() {
        return toolCallEcho;
    }

    public void setToolCallEcho(Map<String, Boolean> toolCallEcho) {
        this.toolCallEcho = toolCallEcho == null ? new LinkedHashMap<>() : new LinkedHashMap<>(toolCallEcho);
    }

    /** 指定域的片段；未配置返回 {@code null} */
    public String domainPrompt(String domainId) {
        return prompts.get(domainId);
    }

    /** 指定域是否回写工具执行信息；未配置按关处理 */
    public boolean toolCallEcho(String domainId) {
        return Boolean.TRUE.equals(toolCallEcho.get(domainId));
    }
}
