package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 默认模型解析器：把「域 → 档案 → 客户端」这条链路接起来。
 *
 * 没有"内置 default"这一说：对话模型只来自用户自建的模型档案。别名命中档案后由
 * {@code ModelClientFactory} 按指纹缓存构建；命中不了（域没绑 / 档案不存在 / 不可用 / 非对话类型）就如实抛出
 * {@link NotConfiguredException}，让调用方拿到明确原因，而不是回落到一个幽灵模型。
 *
 * @author zzkingcc
 */
@Slf4j
public class DefaultModelResolver implements ModelResolver {

    private final ModelProfileRegistry registry;
    private final ModelClientFactory factory;

    public DefaultModelResolver(ModelProfileRegistry registry,
                                ModelClientFactory factory) {
        this.registry = registry;
        this.factory = factory;
    }

    @Override
    public StreamingChatModel streamingChat(String domain) {
        ModelProfileSettings.DomainBinding binding = registry.resolve(domain);
        List<String> aliases = binding.aliases();

        /* 整条域链一个可调用模型都没绑 → 如实抛出，由调用点转成明确失败。
           回落的是域链，不是任何内置 default —— 链上没有任何绑定就是真的没有。 */
        if (binding.empty()) {
            throw new NotConfiguredException("域 " + domain + " 及其祖先域均未绑定可调用模型"
                    + " —— 请到管控台「域空间」为该域或它的祖先域指定至少一个模型");
        }
        if (!Domains.normalize(domain).equals(binding.sourceDomain())) {
            // 继承自哪个祖先必须能看见，否则排障时说不清模型是哪来的
            log.info("[模型档案] 域 {} 自身未绑定模型，沿链继承 {} 的可调用列表 {}",
                    domain, binding.sourceDomain(), aliases);
        }

        /* 可调用列表按顺序试：首个可用即用（列表只有一个时行为与"单值绑定"一致） */
        for (String alias : aliases) {
            ModelProfile profile = registry.profile(alias).orElse(null);
            if (profile == null || !profile.isUsable() || !profile.isChat()) {
                log.warn("[模型档案] 域 {} 的可调用模型 {} 不存在 / 不可用 / 非对话类型，尝试列表中的下一个（档案列表：{}）",
                        domain, alias, registry.allProfiles().stream().map(ModelProfile::alias).toList());
                continue;
            }
            if (!profile.supportsTools()) {
                // 只告警不拒绝：先用便宜模型把链路跑通是合理诉求，但必须让人看见
                log.warn("[模型档案] 域 {} 使用的档案 {} 未声明 {} 能力：模型将不会调用任何工具",
                        domain, alias, ModelProfile.CAP_TOOLS);
            }
            return factory.streamingChat(profile);
        }

        /* 列表里的档案全都不存在 / 不可用 / 非对话类型 → 同样不回落任何默认模型 */
        log.warn("[模型档案] 域 {} 的可调用列表 {} 均不可用（档案列表：{}）",
                domain, aliases, registry.allProfiles().stream().map(ModelProfile::alias).toList());
        throw new NotConfiguredException("域 " + domain + " 的可调用列表 " + aliases
                + " 中没有可用模型 —— 请到管控台「域空间」重新指定");
    }
}
