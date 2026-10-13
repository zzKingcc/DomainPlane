package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.agent.Domains;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型档案注册表 —— 档案与「域 → 档案」绑定的唯一真相。
 *
 * 两条规则（与产品口径一致）：
 *
 *   • 档案不自动绑定任何域：绑定只能由管控台显式写入；新档案建完就是"未绑定"状态；
 *   • 一个域只绑一组模型：绑定是有序列表，整体覆盖，列表首个为当前使用、其余留给多 agent / 降级；
 *       空列表即"无可调用模型"。
 *
 *
 * 没有内置对话模型：对话模型只来自「模型设置」页里用户自建的模型档案。域未绑定时沿域链向上找
 * 最近一个绑了模型的祖先（回落的是域链，不是任何内置默认值）；整条链都没绑即"无可调用"，
 * 调用时直接报"未配置"（由 {@code DefaultModelResolver} 抛出）。
 *
 * 内存态 + 落盘：每次写操作先改内存、再整体落盘（写盘失败会抛异常，调用方必须感知）。
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelProfileRegistry {

    private final ModelProfileStore store;

    /** 当前配置（写操作整体替换，读操作拿不可变副本） */
    private volatile ModelProfileSettings settings;

    public ModelProfileRegistry(ModelProfileStore store) {
        this.store = store;
        this.settings = store.load();
        log.info("[模型档案] 初始化：模型档案 {} 个 {}，向量档案 {} 个 {}，域绑定 {} 条",
                settings.getProfiles().size(), settings.getProfiles().keySet(),
                settings.getEmbeddingProfiles().size(), settings.getEmbeddingProfiles().keySet(),
                settings.getDomainBindings().size());
    }

    // ==================== 解析 ====================

    /**
     * 解析该域的可调用模型列表，沿域链回落：自身没绑就向上找最近一个绑了模型的祖先。
     *
     * 回落的是域链，不是任何内置默认值，根域也未绑定时返回空，
     * 调用方据此判断"无可调用"。于是根域绑一次，整棵树都能用，不必逐域配一遍。
     */
    public ModelProfileSettings.DomainBinding resolve(String domain) {
        return settings.resolveAlong(domain);
    }

    /**
     * 取档案（两类里都找）。
     *
     * 全部档案都存于本注册表，没有"内置 default"那一类了；向量档案单独存放，
     * 但按别名取用时调用方不该关心它存在哪个桶里。
     */
    public Optional<ModelProfile> profile(String alias) {
        if (!hasText(alias)) {
            return Optional.empty();
        }
        ModelProfileSettings.ProfileData data = settings.find(alias.trim());
        return data == null ? Optional.empty() : Optional.of(data.toProfile(alias.trim()));
    }

    /**
     * 非向量档案（对话 / 生图 / 语音…），按别名排序。
     *
     * 纯向量档案不在此列，它们单独存放，见 {@link #embeddingProfiles()}。
     * 管控台「模型」段直接渲染这个列表，因此不会冒出向量模型。
     */
    public List<ModelProfile> profiles() {
        List<ModelProfile> list = new ArrayList<>();
        settings.getProfiles().forEach((alias, data) -> list.add(data.toProfile(alias)));
        list.sort(java.util.Comparator.comparing(ModelProfile::alias));
        return List.copyOf(list);
    }

    /** 纯向量档案（单独存放的那一批），按别名排序 */
    public List<ModelProfile> embeddingProfiles() {
        List<ModelProfile> list = new ArrayList<>();
        settings.getEmbeddingProfiles().forEach((alias, data) -> list.add(data.toProfile(alias)));
        list.sort(java.util.Comparator.comparing(ModelProfile::alias));
        return List.copyOf(list);
    }

    /** 两类档案的合并视图（全量统计 / 诊断用），按别名排序 */
    public List<ModelProfile> allProfiles() {
        List<ModelProfile> list = new ArrayList<>();
        settings.allProfiles().forEach((alias, data) -> list.add(data.toProfile(alias)));
        list.sort(java.util.Comparator.comparing(ModelProfile::alias));
        return List.copyOf(list);
    }

    /**
     * 是否存在任意可用的对话类模型（供「对话模型是否就绪」这类全局判据使用）。
     *
     * 判据与绑域门禁同一口径（{@link ModelProfile#domainBindable()}）：未识别 / 模态未知的档案
     * 报"未就绪"，与「它其实绑不了域」保持一致，避免出现"绑不了域却报已就绪"的自相矛盾。
     * 未探过的标准 OpenAI 规范档案因此会先被报为未就绪，引导用户去点「探测」。
     */
    public boolean hasChatModel() {
        return allProfiles().stream().anyMatch(p -> p.isUsable() && p.domainBindable());
    }

    /** 是否存在任意可用的向量档案（仅供管控台提示"有档案可选"，不代表已启用） */
    public boolean hasEmbeddingProfile() {
        return allProfiles().stream().anyMatch(p -> p.isEmbedding() && p.isUsable());
    }

    /**
     * 可当向量模型用的档案数（两个桶通吃，双类型档案也计在内）。
     *
     * 口径与「模型设置」向量段的数据源一致（见 {@code AdminModelProfileController#embeddingProfiles}），
     * 供概览区分「未配置」（一个档案都没有）与「未启用」（有档案但没启用），两处数不一致，
     * 会让人以为登记过的档案丢了。
     */
    public long embeddingProfileCount() {
        return allProfiles().stream().filter(ModelProfile::isEmbedding).count();
    }

    // ==================== 向量模型：全局单选 ====================

    /**
     * 当前启用的向量档案；未配置返回 {@link Optional#empty()}。
     *
     * 只在档案真是向量档案且必填项齐备时才返回，落盘可能被手工改坏，
     * 解析期就该把"选中了一个不能用的档案"暴露成"未配置"，而不是留一个 null
     * 让灌库链路在几层之后才炸。
     */
    public Optional<ModelProfile> embeddingProfile() {
        String alias = settings.getEmbeddingAlias();
        if (!hasText(alias)) {
            return Optional.empty();
        }
        return profile(alias).filter(ModelProfile::isEmbedding).filter(ModelProfile::isUsable);
    }

    /** 当前启用的向量档案别名（未配置为 {@code null}；不校验档案是否仍可用，用于展示"选中了什么"） */
    public String embeddingAlias() {
        return hasText(settings.getEmbeddingAlias()) ? settings.getEmbeddingAlias().trim() : null;
    }

    /**
     * 切换向量模型（全局单选）。{@code alias} 传空表示取消配置。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String setEmbeddingAlias(String alias) {
        String key = hasText(alias) ? alias.trim() : null;
        if (key != null) {
            ModelProfileSettings.ProfileData data = settings.find(key);
            if (data == null) {
                return "档案不存在：" + key + "（请先到「模型设置」创建该档案）";
            }
            ModelProfile profile = data.toProfile(key);
            if (!profile.isEmbedding()) {
                return "档案 " + key + "（" + profile.modelName() + "）不是向量模型："
                        + "探测结果里没有 embedding 端点，不能用作向量模型";
            }
            if (!profile.isUsable()) {
                return "档案 " + key + " 缺少服务商地址 / API Key / 模型名，不能启用";
            }
        }
        String previous = embeddingAlias();
        if (java.util.Objects.equals(previous, key)) {
            return null;
        }
        ModelProfileSettings next = copyOf(settings);
        next.setEmbeddingAlias(key);
        persist(next);
        log.info("[模型档案] 向量模型由 {} 改为 {}（全局单选，立即影响全树的知识库检索）", previous, key);
        return null;
    }

    /**
     * 域 → 该域绑定的模型（主模型 + 能力族）的绑定视图（不可变副本，保序）。
     *
     * 深拷贝 {@link ModelProfileSettings.DomainModels}：调用方（管控台下发 / 序列化）拿到的是副本，
     * 改不脏内存态。
     */
    public Map<String, ModelProfileSettings.DomainModels> domainBindings() {
        Map<String, ModelProfileSettings.DomainModels> copy = new LinkedHashMap<>();
        settings.getDomainBindings().forEach((domain, models) -> copy.put(domain, models.copy()));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 沿域链解析某个能力族绑定的模型列表（{@code images} / {@code tts} / {@code asr} / {@code video}）。
     *
     * <p>与主模型的 {@link #resolve} 一并构成"一个域绑了哪些模型"的两半：主模型决定谁回答，
     * 能力族决定主模型能额外调用什么。</p>
     */
    public ModelProfileSettings.CapabilityBinding resolveCapability(String domain, String family) {
        return settings.resolveCapabilityAlong(domain, family);
    }

    // ==================== 写操作 ====================

    /**
     * 新建或整体覆盖一个档案（同名即覆盖，语义同"后设定的顶替之前的"）。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String save(ModelProfile profile) {
        if (profile == null || !hasText(profile.alias())) {
            return "别名不能为空";
        }
        String alias = profile.alias().trim();
        if (!profile.isUsable()) {
            return "服务商地址、API Key、模型名都不能为空";
        }

        ModelProfileSettings next = copyOf(settings);
        next.put(alias, ModelProfileSettings.ProfileData.from(profile));
        persist(next);
        log.info("[模型档案] 已保存档案 {}（model={}，baseUrl={}，endpoints={}）—— 绑定它的域立即生效",
                alias, profile.modelName(), profile.baseUrl(), profile.endpoints());
        return null;
    }

    /**
     * 删除档案，并级联清理所有域对该别名的绑定。
     *
     * 不做"仍被绑定就拒绝"：级联摘掉后，那些域立即进入"无可调用"状态，由管控台（域空间页）明确提示，
     * 而不是把删除拦在半路。返回结果里带出被清掉了绑定的域，便于前端提示。
     *
     * @return 删除结果（含被级联清理的域）
     */
    public synchronized ProfileDeleteResult delete(String alias) {
        if (!hasText(alias)) {
            return new ProfileDeleteResult(false, "别名不能为空", List.of(), false);
        }
        String key = alias.trim();
        /* 两类档案里都查：纯向量档案不在 profiles 里，只查一处会删不掉它 */
        if (settings.find(key) == null) {
            return new ProfileDeleteResult(false, "档案不存在：" + key, List.of(), false);
        }

        /* 级联：先把该别名从所有域的绑定里摘掉，再删档案。
           copyOf 已深拷贝，摘掉的是副本，写失败也不会脏内存。 */
        ModelProfileSettings next = copyOf(settings);
        Set<String> affected = new LinkedHashSet<>();
        next.getDomainBindings().forEach((domain, models) -> {
            boolean hit = false;
            for (String family : ModelProfile.BINDABLE_FAMILIES) {
                List<String> bound = models.family(family);
                if (!bound.contains(key)) {
                    continue;
                }
                List<String> left = new ArrayList<>(bound);
                left.remove(key);
                models.putFamily(family, left);
                hit = true;
            }
            if (hit) {
                affected.add(domain);
            }
        });
        /* 摘空了的域绑定直接移除，保持落盘干净 */
        next.getDomainBindings().entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
        /* 被删档案若正是当前向量模型，单选一并撤销，否则会留下一个指向不存在档案的向量模型，
           表现是灌库时报"未配置"却查不出是哪个档案没了。 */
        boolean wasEmbedding = key.equals(next.getEmbeddingAlias());
        if (wasEmbedding) {
            next.setEmbeddingAlias(null);
        }
        next.remove(key);
        persist(next);
        log.info("[模型档案] 已删除档案 {}{}；级联清理了 {} 个域的绑定：{}",
                key, wasEmbedding ? "（它正是当前向量模型，已一并取消向量配置）" : "",
                affected.size(), affected);
        ProfileDeleteResult result = new ProfileDeleteResult(true, null, List.copyOf(affected), wasEmbedding);
        return result;
    }

    /**
     * 设置域的**主模型**列表（族 = {@code chat}）。整体覆盖该族，列表顺序即优先级。
     *
     * <p>只动主模型那一桶：能力族（生图 / 语音 / 视频）由
     * {@link #bindDomain(String, List, Map)} 单独写，改主模型不该顺手把生图绑定抹掉。</p>
     *
     * <p>沿旧口径：{@code aliases} 为 {@code null} 或空都表示<b>解绑</b>该域的主模型
     * （解绑后沿域链向上继承）。注意这与 {@link #bindDomain} 里 {@code chatAliases == null}
     * 的含义不同，那个 {@code null} 是"本次不动主模型"。</p>
     *
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String bind(String domain, List<String> aliases) {
        return bindDomain(domain, aliases == null ? List.of() : aliases, null);
    }

    /**
     * 一次写入一个域的绑定（主模型 + 若干能力族），<b>全部校验通过才落盘</b>。
     *
     * <p>为什么要有这个"一次性写"的入口：管控台保存一个域时提交的是同一批界面上的值
     * （主模型 + 四个能力段）。若按族逐个写，第二族校验失败时第一族已经落盘，
     * 用户看到的是一句错误，磁盘上却留下"主模型改了、生图没改"的中间态，
     * 而这两者在界面上是同一个动作。</p>
     *
     * <p><b>不动 vs 解绑</b>：参数为 {@code null} 表示"本次不动这一桶"；
     * 传空列表才表示"解绑"。能力族 map 里没出现的族同样不动，
     * 于是同一个入口既能做"只改生图"（{@code bindDomain(domain, null, Map.of("images", List.of("a")))}），
     * 也能做"整域覆盖"。</p>
     *
     * @param chatAliases       主模型别名列表；{@code null} = 本次不动主模型
     * @param capabilityAliases 族 → 别名列表；{@code null} = 本次不动任何能力族
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String bindDomain(String domain, List<String> chatAliases,
                                          Map<String, List<String>> capabilityAliases) {
        if (!hasText(domain)) {
            return "域标识不能为空";
        }
        // 域标识必须是从根域出发的完整路径：绑到一个树里不存在的域，要等到调用时才炸 10004，
        // 不如在写入时就拒绝，绑定是唯一写入口，校验放这里最集中
        String pathReason = Domains.validatePath(domain);
        if (pathReason != null) {
            return pathReason;
        }
        String key = Domains.normalize(domain);

        /* 先把整批校验走完，一条都不落盘，校验与写入必须分开，混在一起就会出现"写了一半"。 */
        List<String> chat = null;
        if (chatAliases != null) {
            chat = cleanAliases(chatAliases);
            for (String target : chat) {
                String failure = validateForFamily(ModelProfile.EP_CHAT, target);
                if (failure != null) {
                    return failure;
                }
            }
        }
        Map<String, List<String>> capabilities = null;
        if (capabilityAliases != null) {
            capabilities = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : capabilityAliases.entrySet()) {
                String family = entry.getKey();
                if (ModelProfile.EP_CHAT.equals(family)) {
                    /* 主模型走 chatAliases 那个参数。落进能力族里多半是调用方拼错了键，
                       回一句"主模型不参与域绑定"只会把人带偏 */
                    return "主模型请通过 aliases 传入，不要放进 capabilities 里";
                }
                if (family == null || !ModelProfile.CAPABILITY_FAMILIES.contains(family)) {
                    return "「" + ModelProfile.familyLabel(family) + "」不参与域绑定："
                            + "向量模型是全局单选（到「模型设置」启用），重排模型不按域配置";
                }
                List<String> cleaned = cleanAliases(entry.getValue());
                for (String target : cleaned) {
                    String failure = validateForFamily(family, target);
                    if (failure != null) {
                        return failure;
                    }
                }
                capabilities.put(family, cleaned);
            }
        }

        ModelProfileSettings next = copyOf(settings);
        /* 取出整域绑定再改这几族：改某几族不该顺带清掉没提到的族 */
        ModelProfileSettings.DomainModels models = next.getDomainBindings()
                .computeIfAbsent(key, d -> new ModelProfileSettings.DomainModels());
        if (chat != null) {
            models.putFamily(ModelProfile.EP_CHAT, chat);
        }
        if (capabilities != null) {
            capabilities.forEach(models::putFamily);
        }
        if (models.isEmpty()) {
            next.getDomainBindings().remove(key);
        }
        persist(next);

        logBindings(key, chat, capabilities);
        return null;
    }

    /** 绑定写入后的日志：按族说清"从什么改成了什么"，排障时不必去翻落盘文件 */
    private static void logBindings(String domain, List<String> chat,
                                    Map<String, List<String>> capabilities) {
        if (chat != null) {
            log.info("[模型档案] 域 {} 的{}设为 {}", domain, ModelProfile.familyLabel(ModelProfile.EP_CHAT), chat);
        }
        if (capabilities == null) {
            return;
        }
        capabilities.forEach((family, aliases) -> log.info("[模型档案] 域 {} 的{}设为 {}",
                domain, ModelProfile.familyLabel(family), aliases));
    }

    /** 单个别名在某族下的准入校验；通过返回 {@code null} */
    private String validateForFamily(String family, String target) {
        if (Domains.DEFAULT.equals(target)) {
            return "default 已不再作为可绑定的模型别名，请选择自建模型档案";
        }
        /* 两类档案里都查：同时支持 chat 与 embedding 的档案留在 profiles，纯向量档案在另一桶 */
        ModelProfileSettings.ProfileData data = settings.find(target);
        if (data == null) {
            return "档案不存在：" + target + "（请先到「模型设置」创建该档案）";
        }
        ModelProfile candidate = data.toProfile(target);

        if (!ModelProfile.EP_CHAT.equals(family)) {
            if (!candidate.endpoints().contains(family)) {
                return "档案 " + target + "（" + candidate.modelName() + "）没有声明「"
                        + ModelProfile.familyLabel(family) + "」端点族（它声明的是 " + candidate.endpoints()
                        + "），不能绑到这个能力上。请先到「模型设置」对它点一次「探测」，"
                        + "或确认服务商确实支持该模态。";
            }
            return null;
        }
        /* 域绑定管「类型已知 + 模态已知 + 能当对话模型用」三件事，判据与
           ModelProfile#domainBindable 一致。未识别（端点族为空，如标准 OpenAI 规范的服务商
           还没探过）与模态未知的档案一律拒绝，否则一个"不知道是什么、能吃什么"的档案会
           悄悄进域，下游（多模态 / 向量）行为不可预测。要绑就先到档案页点「探测」。 */
        if (!candidate.identified()) {
            return "档案 " + target + "（" + candidate.modelName() + "）尚未识别（端点族为空）。"
                    + "请先到「模型档案」页点「探测」确定它的类型与模态，再绑定到域。";
        }
        if (candidate.input().isEmpty() || candidate.output().isEmpty()) {
            return "档案 " + target + "（" + candidate.modelName() + "）输入 / 输出模态未知，"
                    + "不能绑到域上；请先「探测」补齐模态。";
        }
        if (!candidate.mayBeChat()) {
            return "档案 " + target + "（" + candidate.modelName() + "）是 " + candidate.endpoints()
                    + " 端点族，不是对话模型，不能作为主模型绑定。"
                    + "它是能力模型，请绑到对应的能力段：" + capabilityHints(candidate);
        }
        return null;
    }

    /** 拒绝"非对话档案当主模型"时顺带指出它该绑到哪一段，省掉用户来回试 */
    private static String capabilityHints(ModelProfile candidate) {
        List<String> hits = ModelProfile.CAPABILITY_FAMILIES.stream()
                .filter(candidate.endpoints()::contains)
                .map(ModelProfile::familyLabel)
                .toList();
        return hits.isEmpty() ? "该档案未声明任何能力端点族（先点一次「探测」）"
                : String.join(" / ", hits);
    }

    /**
     * 删域时清理：摘掉这些域的模型绑定。
     *
     * 不清理的话，绑定会变成孤儿，同路径域将来重建时会沿链静默继承旧绑定。
     */
    public synchronized void unbindDomains(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        Set<String> toRemove = new LinkedHashSet<>(domains);
        ModelProfileSettings next = copyOf(settings);
        boolean changed = next.getDomainBindings().keySet().removeIf(toRemove::contains);
        if (changed) {
            persist(next);
            log.info("[模型档案] 已清理 {} 个被删域的模型绑定", toRemove.size());
        }
    }

    /** 去空白、去重、保序 */
    private static List<String> cleanAliases(List<String> aliases) {
        List<String> out = new ArrayList<>();
        if (aliases == null) {
            return out;
        }
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            String value = alias.trim();
            if (!out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * 删除档案时的结果。
     *
     * @param wasEmbedding 该档案是否正是删除前的向量模型（已随删除一并取消配置）
     */
    public record ProfileDeleteResult(boolean deleted, String reason, List<String> usedByDomains,
                                      boolean wasEmbedding) {
    }

    // ==================== 内部 ====================

    /** 绑定了该别名的域（作为主模型或任一能力族被绑定，都算"在用"） */
    public Set<String> domainsUsing(String alias) {
        Set<String> result = new LinkedHashSet<>();
        if (alias == null) {
            return result;
        }
        settings.getDomainBindings().forEach((domain, models) -> {
            boolean hit = ModelProfile.BINDABLE_FAMILIES.stream()
                    .anyMatch(family -> models.family(family).contains(alias));
            if (hit) {
                result.add(domain);
            }
        });
        return result;
    }

    /**
     * 深拷贝当前配置后修改，避免写失败时内存态已被改脏。
     */
    private static ModelProfileSettings copyOf(ModelProfileSettings source) {
        ModelProfileSettings copy = new ModelProfileSettings();
        Map<String, ModelProfileSettings.DomainModels> bindings = new LinkedHashMap<>();
        source.getDomainBindings().forEach((domain, models) ->
                bindings.put(domain, models.copy()));
        copy.setDomainBindings(bindings);
        /* 两个桶都要深拷贝：漏掉向量桶的话，任何一次写入（改绑定、加档案）
           都会把分离出去的向量档案整个丢掉。 */
        copy.setProfiles(copyProfiles(source.getProfiles()));
        copy.setEmbeddingProfiles(copyProfiles(source.getEmbeddingProfiles()));
        copy.setEmbeddingAlias(source.getEmbeddingAlias());
        return copy;
    }

    private static Map<String, ModelProfileSettings.ProfileData> copyProfiles(
            Map<String, ModelProfileSettings.ProfileData> source) {
        Map<String, ModelProfileSettings.ProfileData> out = new LinkedHashMap<>();
        source.forEach((alias, data) -> out.put(alias,
                ModelProfileSettings.ProfileData.from(data.toProfile(alias))));
        return out;
    }

    /** 落盘成功后整体替换内存态（写失败时抛异常，内存态保持原样） */
    private void persist(ModelProfileSettings next) {
        store.save(next);
        this.settings = next;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
