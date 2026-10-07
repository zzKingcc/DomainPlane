package com.zzkingcc.domainplane.server.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 域提示词设置（落盘 {@code config/prompts.json}）
 *
 * 没有"公共基线"这一层：根域 {@code default} 就是基底，它的提示词存在
 * {@code prompts["default"]} 里，与别的域完全同构。生效提示词是沿链拼接的结果 ——
 * 从根域到当前域，依次取出每段的片段拼成一段。
 *
 * 键是完整路径域（{@code default.sales.order}），与工具声明、知识库同构。
 *
 * @author zzkingcc
 */
public class DomainSettings {

    /** 域（完整路径） → 该域的提示词片段 */
    private Map<String, String> prompts = new LinkedHashMap<>();

    public Map<String, String> getPrompts() {
        return prompts;
    }

    public void setPrompts(Map<String, String> prompts) {
        this.prompts = prompts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prompts);
    }

    /** 指定域的片段；未配置返回 {@code null} */
    public String domainPrompt(String domainId) {
        return prompts.get(domainId);
    }
}
