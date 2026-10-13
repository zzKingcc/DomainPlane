package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册表 —— Agent 可见工具集合的唯一来源
 * @author zzkingcc
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 逻辑工具条目：工具名 → 条目（含该工具的全部副本地址） */
    private final Map<String, Registered> tools = new ConcurrentHashMap<>();

    /**
     * 反向索引：instanceId → 该实例注册的工具名集合。
     */
    private final Map<String, Set<String>> byInstance = new ConcurrentHashMap<>();

    /**
     * 本进程内出现过的域：某个工具一旦声明过它，就记在这里不再移除。
     * 域是调用方沿用的命名空间，工具断开（实例熔断 / 判死）不该让域跟着消失，
     * 因此"域是否可用"看的是它被声明过没有，而不是此刻还有没有工具在声明它。
     */
    private final Set<String> declaredDomains = ConcurrentHashMap.newKeySet();

    /**
     * 被显式撤销过的域：管控台删域后记在这里，从此不再接受工具声明。
     *
     * 为什么必须是"记住"而不是"删一次"：{@link #forgetDomains} 只是把域从现有声明里剥离，
     * 存活实例的下一次心跳仍会带着原始 manifest 回来，{@link #remember} 与
     * {@link #refreshReplica} 会立刻把域重新声明回来、并把工具重建为该域的可调用叶子：
     * 删域等于没删，授权边界也一并恢复。撤销态在进程内保持，直到管控台重新创建同名域
     * （{@link #allowDomains}）。
     */
    private final Set<String> revokedDomains = ConcurrentHashMap.newKeySet();

    /**
     * 域变更互斥锁：{@link #forgetDomains} 与 {@link #remember} 必须串行，
     * 否则"删域正在剥离声明"与"心跳正在登记声明"交错，撤销结果取决于两条线程的时序。
     */
    private final Object domainLock = new Object();

    /**
     * 域注册表：工具声明的域在这里沿链派生（完整路径，缺失祖先一并补齐）。
     *
     * 只给工具注册表时，用缺省实例（仅含根域）：供单元测试与最小装配使用。
     */
    private final DomainRegistry domainRegistry;

    public ToolRegistry() {
        this(new DomainRegistry());
    }

    public ToolRegistry(DomainRegistry domainRegistry) {
        this.domainRegistry = domainRegistry == null ? new DomainRegistry() : domainRegistry;
    }

    // ==================== 来源一：本地 Bean 扫描（启动期） ====================

    /**
     * 批量注册本地工具（启动装配期，单线程调用）
     */
    public void registerAll(Collection<Registered> registered) {
        if (registered == null) {
            return;
        }
        registered.forEach(this::register);
    }

    /**
     * 注册单个本地工具；重名直接失败
     */
    public void register(Registered registered) {
        String name = registered.descriptor().name();
        Registered local = registered.endpoints().isEmpty()
                ? new Registered(registered.descriptor(), registered.specification(),
                registered.executor(), List.of(InstanceEndpoint.local()))
                : registered;
        Registered previous = tools.putIfAbsent(name, local);
        if (previous != null) {
            throw new IllegalStateException("工具名冲突: " + name
                    + "（已存在来源 " + previous.descriptor().source()
                    + "，冲突来源 " + local.descriptor().source() + "）");
        }
        remember(local.descriptor().domains());
        log.info("[工具注册] {} ← {}（category={}, sideEffect={}, 需授权={}）",
                name, local.descriptor().source(),
                local.descriptor().category(), local.descriptor().sideEffect(),
                local.descriptor().requiresApproval());
    }

    // ==================== 来源二：远程整包注册（运行期） ====================

    /**
     * 收到某实例的整包 manifest：diff 增量同步该实例的副本。
     *
     * @param instanceId 实例标识（不可为 {@code local}，那是本地工具的保留标识）
     * @param endpoint   实例的调用回流地址
     * @param manifest   本次整包声明的工具（可为空列表 = 该实例不再提供任何工具）
     * @throws IllegalArgumentException 声明的工具名已被服务端本地工具占用
     */
    public void replaceInstanceTools(String instanceId, String endpoint, List<Registered> manifest) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (InstanceEndpoint.LOCAL_INSTANCE_ID.equals(instanceId)) {
            throw new IllegalArgumentException(
                    "instanceId '" + InstanceEndpoint.LOCAL_INSTANCE_ID + "' 为本地工具保留，远程实例不可使用");
        }
        // 说明："mcp:" 前缀守卫不放在本方法：MCP 自身盖章（McpToolBridge）也走这里，
        // 以 mcp:<连接名> 作 instanceId。对外部 tool-provider 占用 mcp: 的拦截在 ToolRegistrationController。
        List<Registered> raw = manifest == null ? List.of() : List.copyOf(manifest);
        // 已撤销的域不得随心跳复活：整包声明先按撤销集过滤，后续 diff / refresh / add
        // 全部基于过滤后的声明。否则存活实例的一次心跳就能把工具重新绑回被删的域，
        // 删域对该域的授权边界也就一并撤销了。
        List<Registered> incoming = new ArrayList<>(raw.size());
        for (Registered r : raw) {
            ToolDescriptor d = r.descriptor();
            List<String> declared = d.domains() == null ? List.of() : d.domains();
            List<String> kept = declared.stream()
                    .filter(domain -> domain == null || !revokedDomains.contains(domain))
                    .toList();
            if (kept.size() == declared.size()) {
                incoming.add(r);
                continue;
            }
            // 剥光 = 该工具原本只服务于被删的域：写哨兵让它对任何域都不可见。
            // 留空会回落根域（空列表 = 挂在根域上），根域在每个域的祖先链里，等于放大授权。
            List<String> effective = kept.isEmpty() ? List.of(ToolDescriptor.REVOKED_DOMAIN) : kept;
            incoming.add(new Registered(
                    new ToolDescriptor(d.name(), d.description(), d.category(), d.version(), d.sideEffect(),
                            d.idempotent(), d.toModel(), d.params(), effective, d.approval(), d.source()),
                    r.specification(), r.executor(), r.endpoints()));
        }
        String callEndpoint = endpoint == null ? "" : endpoint;

        synchronized (StripedLocks.of(instanceId)) {
            Set<String> oldNames = byInstance.getOrDefault(instanceId, Set.of());
            Set<String> newNames = incoming.stream()
                    .map(r -> r.descriptor().name())
                    .filter(n -> n != null && !n.isBlank())
                    .collect(Collectors.toUnmodifiableSet());

            // 先全量校验新增工具是否与本地工具重名：碰撞必须在任何副本写入之前抛出，
            // 否则中途抛异常会让已写入的副本没有 byInstance 记录，实例下线时 removeInstance
            // 无法回收它们，留下"幽灵副本"。校验通过后再做增量 diff，整段非抛即全部生效。
            for (Registered r : incoming) {
                String name = r.descriptor().name();
                if (name == null || name.isBlank() || oldNames.contains(name)) {
                    continue;
                }
                Registered existing = tools.get(name);
                if (existing != null && hasLocalReplica(existing)) {
                    throw new IllegalArgumentException("工具名 " + name + " 已被服务端本地工具占用（来源 "
                            + existing.descriptor().source() + "），远程实例不可注册同名工具");
                }
            }

            // 1) 本次没再声明的 → 删本实例副本（列表空则整条移除）
            for (String gone : oldNames) {
                if (!newNames.contains(gone)) {
                    removeReplica(gone, instanceId);
                }
            }

            // 2) 新增的挂副本；已存在的刷新地址与声明
            for (Registered r : incoming) {
                String name = r.descriptor().name();
                if (name == null || name.isBlank()) {
                    continue;
                }
                remember(r.descriptor().domains());
                if (oldNames.contains(name)) {
                    refreshReplica(name, instanceId, callEndpoint, r);
                } else {
                    addReplica(instanceId, callEndpoint, r);
                }
            }

            byInstance.put(instanceId, newNames);
        }
    }

    /**
     * 摘除某实例的全部副本（超时判死 / 强制下线时调用）。幂等：该实例已无副本时是空操作。
     */
    public void removeInstance(String instanceId) {
        if (instanceId == null || InstanceEndpoint.LOCAL_INSTANCE_ID.equals(instanceId)) {
            return;
        }
        synchronized (StripedLocks.of(instanceId)) {
            Set<String> names = byInstance.remove(instanceId);
            if (names == null || names.isEmpty()) {
                return;
            }
            for (String name : names) {
                removeReplica(name, instanceId);
            }
            log.info("[实例下线] {} 已从注册表摘除，涉及 {} 个工具条目", instanceId, names.size());
        }
    }

    /** 追加一个副本（条目不存在则新建，取本次声明作为该条目的元数据） */
    private void addReplica(String instanceId, String endpoint, Registered incoming) {
        InstanceEndpoint replica = new InstanceEndpoint(instanceId, endpoint);
        String name = incoming.descriptor().name();
        Registered existing = tools.get(name);
        if (existing != null && hasLocalReplica(existing)) {
            throw new IllegalArgumentException("工具名 " + name + " 已被服务端本地工具占用（来源 "
                    + existing.descriptor().source() + "），远程实例不可注册同名工具");
        }
        tools.compute(name, (key, old) -> {
            if (old == null) {
                log.info("[工具注册] {} ← remote://{}@{}（category={}, sideEffect={}, 需授权={}）",
                        name, instanceId, endpoint,
                        incoming.descriptor().category(), incoming.descriptor().sideEffect(),
                        incoming.descriptor().requiresApproval());
                return new Registered(incoming.descriptor(), incoming.specification(),
                        incoming.executor(), List.of(replica));
            }
            // 条目已存在且是远程工具：只追加/覆盖本实例的副本。
            // 未变副本复用同一 InstanceEndpoint 引用，不 new 新对象：排障时对象身份可追、日志可对齐。
            List<InstanceEndpoint> merged = new ArrayList<>(old.endpoints().size() + 1);
            for (InstanceEndpoint e : old.endpoints()) {
                if (!e.instanceId().equals(instanceId)) {
                    merged.add(e);
                }
            }
            merged.add(replica);
            log.info("[工具注册] {} 追加副本 instance={}@{}，当前共 {} 个副本",
                    name, instanceId, endpoint, merged.size());
            return new Registered(old.descriptor(), old.specification(), old.executor(),
                    List.copyOf(merged));
        });
    }

    /**
     * 刷新本实例的副本：地址无条件校对，声明与条目不一致时以本次声明为准。
     */
    private void refreshReplica(String name, String instanceId, String endpoint, Registered incoming) {
        tools.computeIfPresent(name, (key, old) -> {
            List<InstanceEndpoint> merged = new ArrayList<>(old.endpoints().size());
            boolean matched = false;
            for (InstanceEndpoint e : old.endpoints()) {
                if (e.instanceId().equals(instanceId)) {
                    matched = true;
                    merged.add(new InstanceEndpoint(instanceId, endpoint));
                } else {
                    merged.add(e);
                }
            }
            if (!matched) {
                // 反向索引说该实例声明过这个工具，条目里却没有它的副本：补上，别让两边长期不一致
                merged.add(new InstanceEndpoint(instanceId, endpoint));
            }
            List<InstanceEndpoint> refreshed = List.copyOf(merged);
            boolean declarationChanged =
                    !withoutSource(old.descriptor()).equals(withoutSource(incoming.descriptor()));

            if (!declarationChanged && refreshed.equals(old.endpoints())) {
                // 地址与声明都没变：原样返回旧条目，读端继续拿到同一个对象
                return old;
            }
            if (declarationChanged) {
                log.info("[工具注册] {} 的声明已更新（来源 {}）：描述、参数 schema、域归属、审批策略以本次为准",
                        name, incoming.descriptor().source());
                return new Registered(incoming.descriptor(), incoming.specification(),
                        old.executor(), refreshed);
            }
            log.info("[工具注册] {} 的副本地址已更新：instance={} → {}（工具定义未变）",
                    name, instanceId, endpoint);
            return new Registered(old.descriptor(), old.specification(), old.executor(), refreshed);
        });
    }

    /** 删掉某工具上属于指定实例的副本；列表空了整条移除条目（该域可能因此消失） */
    private void removeReplica(String name, String instanceId) {
        tools.computeIfPresent(name, (key, old) -> {
            List<InstanceEndpoint> remaining = new ArrayList<>(old.endpoints().size());
            boolean removed = false;
            for (InstanceEndpoint e : old.endpoints()) {
                if (e.instanceId().equals(instanceId)) {
                    removed = true;
                    continue;
                }
                remaining.add(e);
            }
            if (!removed) {
                return old;
            }
            if (remaining.isEmpty()) {
                log.info("[工具注销] {} ← 最后一个副本（instance={}）已下线，条目整体移除",
                        name, instanceId);
                // compute 返回 null = 移除该条目
                return null;
            }
            log.info("[工具注销] {} 移除副本 instance={}，剩余 {} 个副本",
                    name, instanceId, remaining.size());
            return new Registered(old.descriptor(), old.specification(), old.executor(),
                    List.copyOf(remaining));
        });
    }

    /** 条目里是否含本地副本（本地工具与远程工具同名时必须拒绝，不能合并） */
    private static boolean hasLocalReplica(Registered registered) {
        return registered.endpoints().stream().anyMatch(InstanceEndpoint::isLocal);
    }

    /**
     * 比较声明是否一致时排除 {@code source}：远程工具的 source 里带的是
     * "某个 instanceId@endpoint"，两个副本天然不同，带上它会把每次心跳都误判成"声明漂移"。
     */
    private static ToolDescriptor withoutSource(ToolDescriptor d) {
        return new ToolDescriptor(d.name(), d.description(), d.category(), d.version(), d.sideEffect(),
                d.idempotent(), d.toModel(), d.params(), d.domains(), d.approval(), null);
    }

    // ==================== 读取 ====================

    /**
     * 按名查找。
     *
     * 名称为 {@code null} / 空白时直接判"不存在"：{@link ConcurrentHashMap#get(Object)}
     * 不接受 null 键，而工具名来自模型输出，"模型漏了 name 字段"是常态。
     * 抛 NPE 会让这条调用绕开既有的 TOOL_NOT_FOUND 回喂路径，整轮对话直接失败。
     */
    public Optional<Registered> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    /** 全部已注册工具的条目 */
    public Collection<Registered> all() {
        return List.copyOf(tools.values());
    }

    /** 某个工具当前的副本地址列表（副本数为 0 时该条目不存在） */
    public List<InstanceEndpoint> endpoints(String name) {
        return find(name).map(Registered::endpoints).orElse(List.of());
    }

    /** 某实例注册过的工具名集合（副本仍在注册表内时有效） */
    public Set<String> toolsOfInstance(String instanceId) {
        return byInstance.getOrDefault(instanceId, Set.of());
    }

    /** 喂给 LLM 的工具规格列表（全量，供管理页 / 无域校验场景使用） */
    public List<ToolSpecification> toolSpecifications() {
        return tools.values().stream()
                .map(Registered::specification)
                .collect(Collectors.toList());
    }

    /**
     * 按本轮域过滤后的工具规格列表 —— 喂给 LLM 的唯一来源。
     *
     * @param domainId 本轮所处的域
     */
    public List<ToolSpecification> toolSpecifications(String domainId) {
        return tools.values().stream()
                .filter(r -> r.descriptor().visibleIn(domainId))
                .map(Registered::specification)
                .collect(Collectors.toList());
    }

    /** 需人工授权的工具名集合（全量，供管理页 / 无域场景使用） */
    public Set<String> toolsRequiringApproval() {
        return tools.values().stream()
                .filter(r -> r.descriptor().requiresApproval())
                .map(r -> r.descriptor().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 按本轮域过滤后的"需人工授权工具名"集合。
     */
    public Set<String> toolsRequiringApproval(String domainId) {
        return tools.values().stream()
                .filter(r -> r.descriptor().requiresApproval())
                .filter(r -> r.descriptor().visibleIn(domainId))
                .map(r -> r.descriptor().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 注册表里出现过的全部域：含"曾经有人声明、此刻已无工具"的域，
     * 因此入口不会因为一次工具断开就把某个域判成不存在。
     */
    public Set<String> knownDomains() {
        Set<String> all = new HashSet<>(declaredDomains);
        for (Registered r : tools.values()) {
            addDomains(all, r.descriptor().domains());
        }
        // 根域恒可用：工具声明留空与调用未指定域都落到它。
        // 它必须在 knownDomains 里，否则"归一化到根域"会被入口判成域不存在（10004）。
        all.add(Domains.DEFAULT);
        return Set.copyOf(all);
    }

    /**
     * 记下一次域声明：域一经被声明就在进程内保留，提供它的工具断开后它依然可用；
     * 同时把它沿链派生进域树（完整路径，缺失的祖先一并补齐，不留悬空节点）。
     */
    private void remember(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        // 与 forgetDomains 共用一把锁：否则"撤销正在剥离"与"心跳正在登记"交错，
        // 结果取决于两条线程的时序：撤销可能被一次恰好落在窗口内的心跳抹掉。
        synchronized (domainLock) {
            List<String> allowed = domains.stream()
                    .filter(domain -> domain != null && !revokedDomains.contains(domain))
                    .toList();
            if (allowed.isEmpty()) {
                return;
            }
            addDomains(declaredDomains, allowed);
            domainRegistry.ensureChains(allowed);
        }
    }

    private static void addDomains(Set<String> target, List<String> domains) {
        if (domains == null) {
            return;
        }
        for (String p : domains) {
            if (p != null && !p.isBlank() && !ToolDescriptor.REVOKED_DOMAIN.equals(p)) {
                // 哨兵不是域：放进 knownDomains 会让「域空间」多出一个假域
                target.add(p);
            }
        }
    }

    /**
     * 该域是否被至少一个工具声明过（含已断开的声明，供管控台展示与排障）。
     */
    public boolean containsDomain(String domainId) {
        return knownDomains().contains(domainId);
    }

        /**
     * 删域时清理：把这些域从"本进程出现过的域"里摘掉，并从仍活着的工具声明里剥离。
     *
     * 剥离与清 {@code declaredDomains} 缺一不可：{@link #knownDomains()} 会
     * {@link #addDomains 从活着的工具条目} 把域重新捞回来，只清 declaredDomains 的话，
     * 被删的域仍留在「域空间」列表里（来源显示"工具派生"），再点删除还会报"域不存在"：
     * 看着像没删干净，实际是"已删但仍被工具声明复活"。
     *
     * 剥离还有一个安全含义：工具可见性完全由 descriptor 的域列表决定
     * （见 {@code ToolDescriptor.visibleIn}），不清掉的话，删域对该域的授权边界不撤销：
     * 正在飞行的破坏性工具调用照常通过复核。
     */
    public void forgetDomains(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        synchronized (domainLock) {
            // 先记撤销再剥离：顺序反过来的话，剥离完成到记撤销之间的一次心跳就能把域加回来
            revokedDomains.addAll(domains.stream()
                    .filter(domain -> domain != null && !domain.isBlank())
                    .toList());
            declaredDomains.removeAll(domains);
            stripFromLiveTools(domains);
        }
    }

    /**
     * 解除撤销：管控台重新创建同名域时调用，允许工具重新声明这些域。
     *
     * 只清撤销标记，不主动补声明：工具声明来自工具侧，重启或下一次心跳会自然带回；
     * 这里替它们"补"一遍等于凭空造出一次没人真正声明过的授权。
     */
    public void allowDomains(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        synchronized (domainLock) {
            revokedDomains.removeAll(domains);
        }
    }

    private void stripFromLiveTools(Collection<String> domains) {
        for (Map.Entry<String, Registered> entry : tools.entrySet()) {
            Registered registered = entry.getValue();
            ToolDescriptor descriptor = registered.descriptor();
            List<String> kept = descriptor.domains().stream()
                    .filter(domain -> !domains.contains(domain))
                    .toList();
            if (kept.size() == descriptor.domains().size()) {
                continue;
            }
            // 剥光 = 这个工具原本只服务于被删的域。必须写哨兵让它对任何域都不可见：
            // 留空会回落根域（空列表 = 挂在根域上），根域在每个域的祖先链里，
            // 于是删域反而把授权「放大到全域」。见 ToolDescriptor.REVOKED_DOMAIN。
            List<String> effective = kept.isEmpty()
                    ? List.of(ToolDescriptor.REVOKED_DOMAIN)
                    : kept;
            if (kept.isEmpty()) {
                log.warn("[工具注册] 工具 {} 的全部域声明（{}）已随删域被撤销，"
                                + "该工具当前对任何域都不可见；作者重新声明域并重启/重新注册后自动恢复",
                        entry.getKey(), descriptor.domains());
            }
            // 副本列表与执行体原样保留：这里只改这个工具对哪些域可见，不碰它还能不能被调用
            tools.computeIfPresent(entry.getKey(), (name, old) -> new Registered(
                    new ToolDescriptor(descriptor.name(), descriptor.description(), descriptor.category(),
                            descriptor.version(), descriptor.sideEffect(), descriptor.idempotent(),
                            descriptor.toModel(), descriptor.params(), effective, descriptor.approval(),
                            descriptor.source()),
                    old.specification(), old.executor(), old.endpoints()));
        }
        log.info("[工具注册] 已从域全集摘掉被删的域：{}，并剥离了相关工具的域声明", domains);
    }

    /**
     * 某工具被声明为敏感的参数名集合（递归含 DTO 展开出的字段名）。
     *
     * 用途只有一个：把"给人看的参数文本"（工具调用事件、审批 payload）掩码，
     * 执行时用的仍是原值。工具不存在、或没声明过 sensitive，都返回空集。
     */
    public Set<String> sensitiveParams(String toolName) {
        return find(toolName)
                .map(registered -> collectSensitive(registered.descriptor().params(), new HashSet<>()))
                .orElse(Set.of());
    }

    private static Set<String> collectSensitive(List<ToolDescriptor.Param> params, Set<String> out) {
        if (params == null) {
            return out;
        }
        for (ToolDescriptor.Param param : params) {
            if (param.sensitive()) {
                out.add(param.name());
            }
            collectSensitive(param.properties(), out);
        }
        return out;
    }

    /** 全部工具描述符（无 Class 引用的元数据，供管理页 / 审计 / 远程注册使用） */
    public List<ToolDescriptor> descriptors() {
        return tools.values().stream()
                .map(Registered::descriptor)
                .collect(Collectors.toList());
    }

    /** 是否没有任何工具（启动时用于提醒部署方） */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /** 已注册的逻辑工具条目数（同名多副本算 1 个） */
    public int size() {
        return tools.size();
    }

    /**
     * 注册项：描述符 + 送给 LLM 的规格 + 执行器 + 副本地址列表
     *
     * @param descriptor    平台侧元数据（多副本共享单份）
     * @param specification LLM 侧规格（同上）
     * @param executor      执行器（本地工具为 LangChain4j {@code DefaultToolExecutor}，
     *                      远程工具为 {@code RemoteToolExecutor}，对上层透明）
     * @param endpoints     副本地址列表（本地工具恒为单元素 {@link InstanceEndpoint#local()}）
     */
    public record Registered(ToolDescriptor descriptor,
                             ToolSpecification specification,
                             ToolExecutor executor,
                             List<InstanceEndpoint> endpoints) {

        public Registered {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        }

        /**
         * 本地 Bean 工具：只有一个本地副本（不走 HTTP 回流，执行器直接调 Bean 方法）
         */
        public static Registered local(ToolDescriptor descriptor,
                                       ToolSpecification specification,
                                       ToolExecutor executor) {
            return new Registered(descriptor, specification, executor, List.of(InstanceEndpoint.local()));
        }
    }
}
