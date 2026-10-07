package com.zzkingcc.domainplane.runtime.domain;

import com.zzkingcc.domainplane.api.agent.Domains;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 域注册表 —— 域树的唯一登记处。
 *
 * 域以完整路径为标识（{@code default.sales.order}），根为 {@link Domains#DEFAULT}。
 * 登记一律走沿链补齐：给一条完整路径，链上缺失的祖先节点一并建出，已存在的节点原样复用
 * （不改它的 {@link Source}）。因此不存在悬空节点 —— 每个域都挂得到根上。
 *
 * {@link Source} 只标记这个域是怎么来的，不是等级：三种来源同级，删除规则一视同仁
 * （只有根域因是整棵树的起点而不可删）。来源差异仅体现在生命周期上 ——
 * {@code MANUAL} 落盘、重启仍在；{@code DERIVED} 由工具声明产生、不落盘，重启后随声明重建。
 *
 * @author zzkingcc
 */
public class DomainRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainRegistry.class);

    /** 域的来源（仅标记出身，不构成等级） */
    public enum Source {
        /** 根域，启动即存在、不可删除 */
        BUILTIN,
        /** 管控台创建，落盘后重启仍在 */
        MANUAL,
        /** 工具声明 / 沿链补齐产生，不落盘，重启后随声明重建 */
        DERIVED
    }

    /**
     * 域条目
     *
     * @param id        完整路径标识
     * @param parentId  父域标识（根域为 {@code null}）
     * @param source    来源
     * @param callable  是否为可调用单元：只有它为 {@code true} 的域能作为入口被调用，
     *                  其余是装配节点（供后代继承工具 / 提示词 / 模型绑定 / 知识）
     * @param createdAt 创建时间戳（毫秒）
     */
    public record Domain(String id, String parentId, Source source, boolean callable, long createdAt) {
    }

    /** 创建结果 */
    public record CreateResult(boolean created, String reason) {
        public static CreateResult ok() {
            return new CreateResult(true, null);
        }

        public static CreateResult fail(String reason) {
            return new CreateResult(false, reason);
        }
    }

    /**
     * 删除结果
     *
     * @param removed 实际被删掉的域（含自身与其全部子孙），未删除时为空
     */
    public record DomainDeleteResult(boolean deleted, String reason, List<String> removed) {
        public static DomainDeleteResult ok(List<String> removed) {
            return new DomainDeleteResult(true, null, List.copyOf(removed));
        }

        public static DomainDeleteResult fail(String reason) {
            return new DomainDeleteResult(false, reason, List.of());
        }
    }

    /**
     * 沿链登记结果
     *
     * @param domain  登记的路径
     * @param created 本次新建的节点（已存在的不计入）
     * @param reason  {@code null} 表示成功
     */
    public record LinkResult(String domain, List<String> created, String reason) {
        public static LinkResult ok(String domain, List<String> created) {
            return new LinkResult(domain, List.copyOf(created), null);
        }

        public static LinkResult fail(String reason) {
            return new LinkResult(null, List.of(), reason);
        }

        public boolean ok() {
            return reason == null;
        }
    }

    private final Map<String, Domain> domains = new ConcurrentHashMap<>();

    /**
     * 被显式声明过可调用性的域 → 该值（落盘用）。
     *
     * 与 {@code domains} 的区别：沿链补齐出来的祖先、以及工具声明派生出来的域都不在这里 ——
     * 它们的可调用性由创建路径的默认值决定（祖先不可调用、被声明的域可调用），重启后可按同样的
     * 规则重建，不需要落盘。只有"人点过"的决定才需要持久化。
     */
    private final Map<String, Boolean> explicitCallable = new ConcurrentHashMap<>();

    public DomainRegistry() {
        // 根域恒为可调用：调用方不传域时会被归一化到它，若它不可调用会让"不传域"直接变成硬错误
        domains.put(Domains.DEFAULT,
                new Domain(Domains.DEFAULT, null, Source.BUILTIN, true, System.currentTimeMillis()));
    }

    // ==================== 登记 ====================

    /**
     * 载入管控台声明的域（启动期从落盘恢复）。
     *
     * 只按条目显式给定的可调用性；沿链补齐出来的祖先一律不可调用（装配节点），
     * 因此"给 default.a.b 打标记"不会连带把 default 与 default.a 变成可调用单元。
     *
     * 条目按路径长度升序处理：显式条目先落，避免某个条目在成为更深条目的祖先时被建成不可调用、
     * 之后又因为"已存在即保留"而丢掉它自己声明的可调用性。
     *
     * 非法路径只跳过并告警，不阻断启动。
     *
     * @param callableById 域标识 → 是否可调用
     */
    public void loadManual(Map<String, Boolean> callableById) {
        if (callableById == null || callableById.isEmpty()) {
            return;
        }
        List<String> ordered = new ArrayList<>(callableById.keySet());
        ordered.sort(Comparator.comparingInt(String::length));
        for (String id : ordered) {
            Boolean callable = callableById.get(id);
            boolean effective = callable == null || callable;
            LinkResult linked = link(id, Source.MANUAL, effective);
            if (!linked.ok()) {
                log.warn("[域注册表] 落盘域 '{}' 非法（{}），已跳过。"
                                + "域标识须为从 {} 出发的完整路径，如 default.sales",
                        id, linked.reason(), Domains.DEFAULT);
                continue;
            }
            explicitCallable.put(Domains.normalize(id), effective);
        }
    }

    /**
     * 管控台创建域，默认建为可调用单元。
     */
    public CreateResult create(String domainId) {
        return create(domainId, true);
    }

    /**
     * 管控台创建域：目标域按 {@code callable} 落地，沿链补齐的祖先一律不可调用。
     *
     * @return 成功；或失败原因（路径非法 / 已存在）
     */
    public CreateResult create(String domainId, boolean callable) {
        LinkResult linked = link(domainId, Source.MANUAL, callable);
        if (!linked.ok()) {
            return CreateResult.fail(linked.reason());
        }
        if (linked.created().isEmpty()) {
            return CreateResult.fail("域已存在：" + domainId.trim()
                    + "（来源 " + sourceOf(domainId) + "）");
        }
        // 落盘的必须是"生效值"：请求可调用但已有子域时，它按叶子规则只能是装配节点。
        // 不这么写的话，文件会留下一个与实际角色不符的 true，运维对着文件会看错。
        String id = Domains.normalize(domainId);
        boolean effective = callable && !hasChildren(id);
        explicitCallable.put(id, effective);
        if (callable && !effective) {
            log.warn("[域注册表] 域 {} 已有子域 {}，按「只有叶子域可调用」的规则建为装配节点",
                    id, childrenOf(id));
        }
        log.info("[域注册表] 已创建域 {}（新建节点 {}，来源 MANUAL，可调用={}）",
                linked.domain(), linked.created(), effective);
        return CreateResult.ok();
    }

    /**
     * 工具声明派生：按 {@link Source#DERIVED} 沿链补齐。已存在节点保持原来源不变。
     *
     * 幂等 —— 重复声明同一个域不会改写它。
     */
    public LinkResult ensureChain(String domainId) {
        LinkResult linked = link(domainId, Source.DERIVED, true);
        if (linked.ok() && !linked.created().isEmpty()) {
            log.info("[域注册表] 工具声明派生域 {}（新建节点 {}，来源 DERIVED）",
                    linked.domain(), linked.created());
        }
        return linked;
    }

    /** 批量派生；非法路径只跳过并告警 */
    public void ensureChains(Collection<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            LinkResult linked = ensureChain(id);
            if (!linked.ok()) {
                log.warn("[域注册表] 工具声明的域 '{}' 非法（{}），已跳过。"
                                + "须为从 {} 出发的完整路径，如 default.sales",
                        id, linked.reason(), Domains.DEFAULT);
            }
        }
    }

    /**
     * 沿链登记：路径上每一段都确保存在，缺失的按 {@code source} 建出，已存在的原样保留
     * （不改它的 source，也不改它的 callable —— 显式声明过的可调用性优先）。
     *
     * @param targetCallable 链尾那个被显式声明的域是否可调用；沿链补齐出来的祖先一律不可调用
     */
    private synchronized LinkResult link(String domainId, Source source, boolean targetCallable) {
        String reason = Domains.validatePath(domainId);
        if (reason != null) {
            return LinkResult.fail(reason);
        }
        String id = domainId.trim();
        List<String> created = new ArrayList<>();
        List<String> chain = Domains.chainOf(id);
        for (int i = 0; i < chain.size(); i++) {
            String step = chain.get(i);
            if (domains.containsKey(step)) {
                continue;
            }
            // 祖先只负责装配（供后代继承）；链尾那个被显式声明的域才可能是可调用单元，
            // 且它必须"出生时就是叶子"——若它已有子域（子域先于父域创建），
            // 按叶子规则它只能是装配节点。
            boolean callable = targetCallable && i == chain.size() - 1 && !hasChildren(step);
            domains.put(step, new Domain(step, Domains.parentOf(step), source, callable,
                    System.currentTimeMillis()));
            created.add(step);
        }
        return LinkResult.ok(id, created);
    }

    /**
     * 切换某个域的可调用性（管控台「域空间」页）。
     *
     * 只影响这一个域，不向下传播。但向上受叶子规则约束：
     * 有子域的域只能作为装配节点，设成可调用会被拒绝并给出原因
     * （要"父域整体"的入口，请另建一个叶子域）。
     */
    public synchronized CallableResult setCallable(String domainId, boolean callable) {
        String id = Domains.normalize(domainId);
        Domain current = domains.get(id);
        if (current == null) {
            return CallableResult.fail("域不存在：" + id);
        }
        if (callable && hasChildren(id)) {
            return CallableResult.fail("域 " + id + " 已有子域（" + childrenOf(id)
                    + "），只能作为装配节点：本平台只有叶子域可以作为可调用单元。"
                    + "若需要一个覆盖该层能力的入口，请另建一个没有子域的域");
        }
        if (current.callable() != callable) {
            domains.put(id, new Domain(current.id(), current.parentId(), current.source(), callable,
                    current.createdAt()));
            log.info("[域注册表] 域 {} 的可调用性改为 {}", id, callable);
        }
        explicitCallable.put(id, callable);
        return CallableResult.ok();
    }

    /**
     * 切换可调用性的结果。
     *
     * @param changed 是否已按请求生效
     * @param reason  未生效时的原因（可直接展示给用户）
     */
    public record CallableResult(boolean changed, String reason) {

        public static CallableResult ok() {
            return new CallableResult(true, null);
        }

        public static CallableResult fail(String reason) {
            return new CallableResult(false, reason);
        }
    }

    // ==================== 删除 ====================

    /**
     * 删除域及其全部子孙（递归，不向上提升层级）。
     *
     * 只有根域拒绝删除 —— 它是整棵树的起点。其余域不论来源都可删；
     * 派生域删掉后重启会随工具声明重建，这是它生命周期的固有结果，不另设限制。
     */
    public DomainDeleteResult delete(String domainId) {
        String reason = Domains.validatePath(domainId);
        if (reason != null) {
            return DomainDeleteResult.fail(reason);
        }
        String id = domainId.trim();
        if (Domains.DEFAULT.equals(id)) {
            return DomainDeleteResult.fail("根域不可删除：" + id + "（它是整棵域树的起点）");
        }
        if (!domains.containsKey(id)) {
            return DomainDeleteResult.fail("域不存在：" + id);
        }
        String prefix = id + Domains.SEPARATOR;
        List<String> removed = new ArrayList<>();
        for (String candidate : domains.keySet()) {
            if (candidate.equals(id) || candidate.startsWith(prefix)) {
                removed.add(candidate);
            }
        }
        removed.forEach(domains::remove);
        removed.forEach(explicitCallable::remove);
        removed.sort(Comparator.naturalOrder());
        log.info("[域注册表] 已删除域 {} 及其 {} 个子孙 {}", id, removed.size() - 1, removed);
        return DomainDeleteResult.ok(removed);
    }

    // ==================== 查询 ====================

    /** 该域是否已被登记 */
    public boolean contains(String domainId) {
        return domains.containsKey(Domains.normalize(domainId));
    }

    /**
     * 该域是否为可调用单元（入口唯一判据）。
     *
     * 判据 = 已登记 ∧ 显式标记可调用 ∧ 是叶子域（无子域）。
     *
     * 叶子约束是本域树的硬性规则：一个域只要有子域，就必须作为装配节点存在、
     * 不能当入口；根域也服从这条规则 —— 只要它有了子域，它同样只是装配节点。
     * 于是"整棵树只有一个根域"时根域可调用，一旦往下建了子域，入口就必须落到叶子。
     * 需要"父域整体"的入口时，请另建一个叶子域，而不是把父域本身变成入口。
     *
     * 为什么在读取时派生而不是在写入路径上维护标记：标记有多个来源
     * （管控台显式声明、工具声明派生、落盘文件里的历史值），任何一处漏改都会破掉规则。
     * 派生则只有这一个判据实现，绕不过去 —— 写入侧存的是"意图"，这里算的是"生效"。
     */
    public boolean isCallable(String domainId) {
        Domain domain = domains.get(Domains.normalize(domainId));
        return domain != null && isEffectiveCallable(domain);
    }

    /**
     * 生效的可调用性 = 显式标记 ∧ 无子域。读取侧一律走这里，不要再各自判断。
     */
    private boolean isEffectiveCallable(Domain domain) {
        return domain.callable() && !hasChildren(domain.id());
    }

    /** 全部可调用单元（供客户端清单与入口提示）—— 只含叶子域 */
    public Set<String> callableIds() {
        return domains.values().stream()
                .filter(this::isEffectiveCallable)
                .map(Domain::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 该域的直接子域（不含更深的后代），按标识排序。
     */
    public List<String> childrenOf(String domainId) {
        String id = Domains.normalize(domainId);
        return domains.values().stream()
                .filter(d -> id.equals(d.parentId()))
                .map(Domain::id)
                .sorted()
                .collect(Collectors.toList());
    }

    /** 该域是否有子域（用于"装配节点"提示与死节点校验） */
    public boolean hasChildren(String domainId) {
        String id = Domains.normalize(domainId);
        return domains.values().stream().anyMatch(d -> id.equals(d.parentId()));
    }

    /** 已登记的域标识（全部来源） */
    public Set<String> ids() {
        return Set.copyOf(domains.keySet());
    }

    /** 全部条目，按标识排序，便于展示 */
    public List<Domain> all() {
        List<Domain> list = new ArrayList<>(domains.values());
        list.sort(Comparator.comparing(Domain::id));
        return List.copyOf(list);
    }

    /** 该域的登记来源；未登记返回 {@code null} */
    public Source sourceOf(String domainId) {
        Domain domain = domains.get(Domains.normalize(domainId));
        return domain == null ? null : domain.source();
    }

    /** 是否根域（{@code null} / 空白视为根域） */
    public boolean isBuiltin(String domainId) {
        return sourceOf(domainId) == Source.BUILTIN;
    }

    /**
     * 该域的全部子孙（不含自身），按标识排序。
     */
    public List<String> descendantsOf(String domainId) {
        String id = Domains.normalize(domainId);
        String prefix = id + Domains.SEPARATOR;
        return domains.keySet().stream()
                .filter(candidate -> candidate.startsWith(prefix))
                .sorted()
                .collect(Collectors.toList());
    }

    /** 管控台创建的域标识（落盘用） */
    public Set<String> manualIds() {
        return domains.values().stream()
                .filter(d -> d.source() == Source.MANUAL)
                .map(Domain::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 需要落盘的域与其可调用性（只含被显式声明过的域），按标识排序。
     *
     * 派生域与沿链补齐的祖先不进这里 —— 它们的可调用性能按创建规则重建。
     *
     * 写出去的是生效值（显式标记 ∧ 无子域），不是原始意图：一个有子域的域
     * 无论曾被声明成什么，落盘都记装配节点。这样文件与管控台展示、与入口判据三者一致，
     * 不会出现"文件说可调用、实际却是装配节点"的错位。
     */
    public Map<String, Boolean> manualConfig() {
        Map<String, Boolean> config = new java.util.LinkedHashMap<>();
        explicitCallable.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    Domain domain = domains.get(e.getKey());
                    config.put(e.getKey(), domain != null && isEffectiveCallable(domain));
                });
        return config;
    }

}
