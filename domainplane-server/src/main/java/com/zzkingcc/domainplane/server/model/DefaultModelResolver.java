package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.runtime.model.ModelLimits;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.model.VisionTarget;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

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
           回落的是域链，不是任何内置 default，链上没有任何绑定就是真的没有。 */
        if (binding.empty()) {
            throw new NotConfiguredException("域 " + domain + " 及其祖先域均未绑定可调用模型"
                    + " —— 请到管控台「域空间」为该域或它的祖先域指定至少一个模型");
        }
        if (!Domains.normalize(domain).equals(binding.sourceDomain())) {
            // 继承自哪个祖先必须能看见，否则排障时说不清模型是哪来的
            log.info("[模型档案] 域 {} 自身未绑定模型，沿链继承 {} 的可调用列表 {}",
                    domain, binding.sourceDomain(), aliases);
        }

        /* 可调用列表按顺序试：首个可用即用（列表只有一个时行为与"单值绑定"一致）。
           判据是 domainBindable()（类型已知 + 模态已知 + 能当对话模型用），与绑域门禁同一口径：
           未识别 / 模态未知的档案在写入绑定时就已被拒，这里再卡一道是为了兜住"改口径前就已绑上"
           的历史档案，避免它们继续被解析成一个来路不明的模型。 */
        for (String alias : aliases) {
            ModelProfile profile = registry.profile(alias).orElse(null);
            if (profile == null || !profile.isUsable() || !profile.domainBindable()) {
                log.warn("[模型档案] 域 {} 的可调用模型 {} 不存在 / 不可用 / 未识别 / 模态未知 / 非对话类型，尝试列表中的下一个（档案列表：{}）",
                        domain, alias, registry.allProfiles().stream().map(ModelProfile::alias).toList());
                continue;
            }
            return factory.streamingChat(profile);
        }

        /* 列表里的档案全都不存在 / 不可用 / 非对话类型 → 同样不回落任何默认模型 */
        log.warn("[模型档案] 域 {} 的可调用列表 {} 均不可用（档案列表：{}）",
                domain, aliases, registry.allProfiles().stream().map(ModelProfile::alias).toList());
        throw new NotConfiguredException("域 " + domain + " 的可调用列表 " + aliases
                + " 中没有可用模型 —— 请到管控台「域空间」重新指定");
    }

    /**
     * 解析该域链上第一个可用的视觉模型。
     *
     * <p>两趟扫描，先把"谁是主模型"定下来，再找视觉模型：主模型不是"列表首项"，
     * 而是<b>首项里第一个能用的那个</b>，首项缺 Key / 已下线时主模型会顺延到下一位，
     * 若还按下标 0 判 {@code primary}，会出现"主模型明明是自己却被标成子模型"，
     * 于是本轮绕过原生多模态、平白多跑一次委派；反过来也会把子模型误判成主模型。</p>
     *
     * <p>视觉判据就是 {@code input} 含 {@code image}（{@link ModelProfile#MOD_IMAGE}），
     * 不新增字段：输入模态本来就承载了这个语义，另立一个 {@code supportsVision} 只会
     * 与它产生两处口径，而两处口径迟早不一致。</p>
     *
     * <p>可用性门禁与 {@link #streamingChat} 同一口径（{@code isUsable()} +
     * {@code domainBindable()}）：一个档案若当不了对话模型，也当不了视觉委派目标，
     * 后者同样是"给它一段 messages、拿回一条回答"。</p>
     */
    @Override
    public Optional<VisionTarget> visionModel(String domain) {
        ModelProfileSettings.DomainBinding binding = registry.resolve(domain);
        List<String> aliases = binding.aliases();
        if (binding.empty()) {
            return Optional.empty();
        }

        String primaryAlias = null;
        for (String alias : aliases) {
            ModelProfile profile = registry.profile(alias).orElse(null);
            if (profile != null && profile.isUsable() && profile.domainBindable()) {
                primaryAlias = alias;
                break;
            }
        }
        if (primaryAlias == null) {
            return Optional.empty();
        }

        for (String alias : aliases) {
            ModelProfile profile = registry.profile(alias).orElse(null);
            if (profile == null || !profile.isUsable() || !profile.domainBindable()
                    || !profile.input().contains(ModelProfile.MOD_IMAGE)) {
                continue;
            }
            boolean primary = alias.equals(primaryAlias);
            log.debug("[模型档案] 域 {} 解析到视觉模型 {}（model={}，主模型={}）",
                    domain, alias, profile.modelName(), primary);
            return Optional.of(new VisionTarget(alias, profile.modelName(), factory.chat(profile), primary));
        }
        log.info("[模型档案] 域 {} 的可调用列表 {} 中没有输入模态含 image 的档案，带图请求将无法处理",
                domain, aliases);
        return Optional.empty();
    }

    /**
     * 解析该域本轮主模型的分档上限。
     *
     * <p>取的是 {@link #streamingChat} 会取到的那份档案，同一套判据
     * （可调用列表 + {@code isUsable()} + {@code domainBindable()}，首个命中即用），
     * 否则会出现"预算按 A 模型收紧、请求实际发给 B 模型"。</p>
     *
     * <p>刻意不复用 {@code streamingChat} 并捕获它的异常：那会把"没绑模型"这个
     * 确实该失败的状态吞成"没有上限"，而本方法只是锦上添花的一侧收紧，两者语义不同。
     * 这里直接走一遍同样的取舍，取不到就返回空。</p>
     */
    @Override
    public Optional<ModelLimits> modelLimits(String domain) {
        ModelProfile profile = primaryProfile(domain);
        if (profile == null) {
            return Optional.empty();
        }
        return Optional.of(new ModelLimits(profile.effectiveContextWindow(), profile.effectiveMaxTokens()));
    }

    /**
     * 解析该域链上某个<b>能力族</b>的第一个可用档案
     * （{@code images} / {@code tts} / {@code asr} / {@code video}）。
     *
     * <p>与 {@link #streamingChat} 构成「域 → 模型」的两条腿：主模型决定这一轮由谁回答，
     * 能力族决定主模型能额外调用什么（生图 / 语音 / 视频）。回落规则完全一致，
     * 沿域链向上找最近一个为该族绑了模型的祖先，回落的是域链，不是任何内置默认值。</p>
     *
     * <p>可用性门禁与绑族门禁同一口径（{@code isUsable()} + 档案确实声明了该族端点），
     * <b>刻意不要求 {@link ModelProfile#mayBeChat()}</b>：生图档案本来就当不了对话模型，
     * 但它恰恰是生图桶里唯一合格的东西。也不看输入 / 输出模态，能力调用只认端点族
     * （见 {@code ModelEndpointClient} 各族方法），拿模态去卡会拒掉一个真能调通的档案。</p>
     *
     * <p>取不到就如实抛 {@link NotConfiguredException}，让工具把它转成一句明确的话回喂模型，
     * 而不是静默回落到某个来路不明的模型。</p>
     *
     * @param domain 本轮域（工具从 {@link com.zzkingcc.domainplane.api.support.RetrievalScope} 读）
     * @param family 能力族，取值同 {@link ModelProfile#CAPABILITY_FAMILIES}
     * @throws NotConfiguredException 整条域链都没为该族绑定可用档案时
     */
    public ModelProfile endpointModel(String domain, String family) {
        String label = ModelProfile.familyLabel(family);
        ModelProfileSettings.CapabilityBinding binding = registry.resolveCapability(domain, family);
        List<String> aliases = binding.aliases();

        if (binding.empty()) {
            throw new NotConfiguredException("域 " + domain + " 及其祖先域均未绑定" + label + "模型"
                    + " —— 请到管控台「域空间」为该域或它的祖先域指定至少一个" + label + "模型");
        }
        if (!Domains.normalize(domain).equals(binding.sourceDomain())) {
            log.info("[模型档案] 域 {} 自身未绑定{}模型，沿链继承 {} 的列表 {}",
                    domain, label, binding.sourceDomain(), aliases);
        }

        for (String alias : aliases) {
            ModelProfile profile = registry.profile(alias).orElse(null);
            if (profile == null || !profile.isUsable() || !profile.endpoints().contains(family)) {
                log.warn("[模型档案] 域 {} 的{}模型 {} 不存在 / 不可用 / 未声明该端点族，尝试下一个（档案列表：{}）",
                        domain, label, alias, registry.allProfiles().stream().map(ModelProfile::alias).toList());
                continue;
            }
            return profile;
        }

        log.warn("[模型档案] 域 {} 的{}模型列表 {} 均不可用（档案列表：{}）",
                domain, label, aliases, registry.allProfiles().stream().map(ModelProfile::alias).toList());
        throw new NotConfiguredException("域 " + domain + " 的" + label + "模型列表 " + aliases
                + " 中没有可用档案 —— 请到管控台「域空间」重新指定");
    }

    /**
     * 可调用列表里首个可用档案 —— {@link #streamingChat} 与 {@link #modelLimits} 共同的取舍。
     *
     * <p>本方法<b>不打日志</b>，与 {@code streamingChat} 里那串逐项告警刻意不同：
     * 后者每轮会被调多次（agent → tools → agent 每圈一次），而本方法每轮只调一次，
     * 两边都打会让同一件事在日志里出现两遍。</p>
     *
     * <p>两处的判据必须一致，改任一处都要同时改另一处，否则"预算按哪个模型算"
     * 与"请求发给哪个模型"就会分叉。</p>
     */
    private ModelProfile primaryProfile(String domain) {
        ModelProfileSettings.DomainBinding binding = registry.resolve(domain);
        if (binding.empty()) {
            return null;
        }
        for (String alias : binding.aliases()) {
            ModelProfile profile = registry.profile(alias).orElse(null);
            if (profile != null && profile.isUsable() && profile.domainBindable()) {
                return profile;
            }
        }
        return null;
    }
}
