package com.zzkingcc.domainplane.server.prompt;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.domainplane.server.config.PromptProperties;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统提示词解析器：管控台（{@code config/prompts.json}）优先，yaml 兜底。
 *
 * <p>生效提示词是<b>沿链拼接</b>的：从根域到当前域，依次取出每段配置的片段，按由根向下拼成一段。
 * 祖先的内容做后代的基底 —— 与工具可见性、知识库、模型绑定同一套累加语义。</p>
 *
 * <p>拼接不加任何边界标记：链上每一段都是平台自己配置的提示词，
 * 不存在"把外部数据降级成非指令"的必要，加标记反而会削弱根域内容的权威性。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class DomainSystemPromptResolver implements SystemPromptResolver {

    /** 相邻两段片段之间的分隔 */
    private static final String JOIN = "\n\n";

    private final DomainSettingsStore store;
    private final PromptProperties yamlProperties;

    /**
     * 已提示过"整条链都没有提示词"的域。
     */
    private final Set<String> warnedDomains = ConcurrentHashMap.newKeySet();

    public DomainSystemPromptResolver(DomainSettingsStore store, PromptProperties yamlProperties) {
        this.store = store;
        this.yamlProperties = yamlProperties == null ? new PromptProperties() : yamlProperties;
    }

    @Override
    public String resolve(String domainId) {
        return resolve(domainId, store.load());
    }

    /**
     * 用<b>已读到的</b>设置拼提示词（并在整条链都为空时提示，每域一次）。
     */
    public String resolve(String domainId, DomainSettings console) {
        String prompt = compose(domainId, console);
        if (isBlank(prompt) && warnedDomains.add(Domains.normalize(domainId))) {
            // 缺提示词只是体验降级，不是故障：域对工具的限制由代码保证，与提示词无关。
            log.warn("[提示词] 域[{}] 及其祖先链都未配置提示词，本轮不带系统提示词"
                            + "（可在管控台「提示词设定」页给该域或其祖先域补充；不补也不影响该域的工具限制）",
                    Domains.normalize(domainId));
        }
        return prompt;
    }

    /**
     * 只拼文本，<b>不发任何日志</b>。
     */
    public String preview(String domainId, DomainSettings console) {
        return compose(domainId, console);
    }

    /**
     * 沿链拼接：根域在前、当前域在后；未配置的段跳过。
     */
    private String compose(String domainId, DomainSettings console) {
        DomainSettings fromConsole = console == null ? new DomainSettings() : console;
        List<String> parts = new ArrayList<>();
        for (String step : Domains.chainOf(Domains.normalize(domainId))) {
            String text = firstNonBlank(fromConsole.domainPrompt(step), yamlProperties.domainPrompt(step));
            if (!isBlank(text)) {
                parts.add(text.trim());
            }
        }
        return String.join(JOIN, parts);
    }

    private static String firstNonBlank(String consoleValue, String yamlValue) {
        return isBlank(consoleValue) ? yamlValue : consoleValue;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
