package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 提示词配置模型（平台自有，统一前缀 {@code domainplane.ai.prompt}）
 *
 * 与管控台 {@code prompts.json} 同构：没有公共基线，根域 {@code default} 的片段就是基底，
 * 生效提示词按域链从根拼接。键是完整路径域。
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.ai.prompt")
public class PromptProperties {

    /** 域（完整路径） → 该域的提示词片段 */
    private Map<String, String> prompts = new LinkedHashMap<>();

    /** 指定域的差异片段；未配置返回 {@code null} */
    public String domainPrompt(String domainId) {
        if (domainId == null || prompts == null) {
            return null;
        }
        return prompts.get(domainId);
    }
}
