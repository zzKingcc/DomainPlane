package com.zzkingcc.domainplane.runtime.prompt;

/**
 * 系统提示词解析器：按本轮所处的域给出该域的提示词。
 *
 * 提示词的组成是【公共基线 + 域差异】：基线所有域共享，域差异每域可选补充
 * @author zzkingcc
 */
public interface SystemPromptResolver {

    /**
     * 给出指定域的系统提示词。
     *
     * @param domainId 本轮所处的域；{@code null} 时只返回公共基线
     * @return 该域的提示词；为空表示不下发 SystemMessage
     */
    String resolve(String domainId);
}
