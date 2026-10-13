package com.zzkingcc.domainplane.server.tool;

import com.zzkingcc.domainplane.runtime.tool.ToolCallEchoResolver;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具执行信息回写开关的管控台实现：读 {@code config/prompts.json} 里的
 * {@code toolCallEcho} 段（与 {@code DomainSystemPromptResolver} 同一套路，
 * 每次解析现读盘上设置，保存即生效、无需重启）。
 *
 * @author zzkingcc
 */
@Slf4j
public class DomainToolCallEchoResolver implements ToolCallEchoResolver {

    private final DomainSettingsStore store;

    public DomainToolCallEchoResolver(DomainSettingsStore store) {
        this.store = store;
    }

    @Override
    public boolean echoOn(String domainId) {
        DomainSettings settings = store.load();
        return settings.toolCallEcho(domainId);
    }
}
