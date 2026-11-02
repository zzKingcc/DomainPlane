package com.zzkingcc.domainplane.runtime.prompt;

/**
 * 系统提示词解析器：按本轮所处的域给出该域的提示词。
 *
 * <p>提示词的组成是【根域即基底 + 沿链累加】：根域（{@code Domains.DEFAULT}）配置的那段
 * 就是公共基线，后代域各自可选补充，生效值是"从根域到本域"这条链上所有片段依次拼接
 * （见 {@code DomainSystemPromptResolver}）。与工具可见性、知识库、模型绑定是同一套
 * 累加语义 —— 祖先的内容做后代的基底，不是"公共基线 + 域差异"两份并列。</p>
 *
 * <p>这个差别有实际后果：说成"公共基线 + 域差异"会让人以为存在一个独立于域树的
 * 基线文件/基线配置项，于是去找它、去改它，而它并不存在 —— 基线就是根域那一段。</p>
 *
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
