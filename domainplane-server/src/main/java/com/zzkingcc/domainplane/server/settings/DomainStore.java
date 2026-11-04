package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzkingcc.domainplane.common.util.AtomicFiles;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 域声明与可调用性的持久化存储（{@code config/domains.json}）。
 *
 * 只存管控台声明的域：内置根域由代码预置，派生域由工具声明决定，两者都不落盘。
 *
 * 落盘结构与可调用性一一对应，但**生效判据不在这里算**：文件里存的是显式意图
 * （这个域当初被声明成什么），"现在能不能当入口"由 {@code DomainRegistry.isCallable}
 * 在读取时派生（显式标记 ∧ 无子域）——写入侧存意图、读取侧算生效，两边口径以注册表为准。
 *
 * 版本与恢复（本地文件权威版，见 L1-L4 改造计划 F03 附注）：
 *   • 权威文件带单调递增 {@code version}，每次变更落一份历史快照 {@code domains-history/v<N>.json}；
 *   • 权威文件读损坏时自动回退最近一个有效快照（服务不中断）；有快照但全部不可读 =
 *     存储介质层面坏了，拒绝启动（fail-closed，不放行）；
 *   • 回滚是一次普通的新变更（version+1），不是时间倒流。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class DomainStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 历史快照文件名：v<版本>.json */
    private static final Pattern HISTORY_NAME = Pattern.compile("^v(\\d+)\\.json$");

    /** 历史版本库保留份数上限：超出删最旧 */
    private static final int HISTORY_KEEP = 100;

    /** 历史版本库总字节上限（50MB）：超出从最旧开始删，至少留 1 份 */
    private static final long HISTORY_MAX_BYTES = 50L * 1024 * 1024;

    private final Path settingsFile;

    /** 历史版本库目录：与权威文件同目录的 {@code domains-history/} */
    private final Path historyDir;

    /**
     * 一次性迁移标记的进程内缓存。
     *
     * 标记在启动时随 {@link #load()} 读一次并缓存，写盘时同步更新，不依赖后续能否读回文件：
     * 标记一旦被复位成 false，重启后迁移就会重跑，运维手工切过的可调用性被静默推翻。
     */
    private volatile boolean callableMigrated;

    /** 本进程是否已尝试过加载（用于区分"还没读"与"读到的是损坏"） */
    private volatile boolean loaded;

    /** 加载时是否读到了损坏内容（与 {@link #loaded} 一起才能判断） */
    private volatile boolean corruptOnLoad;

    /** 当前权威版本号（进程内镜像；写盘时 +1） */
    private volatile long currentVersion;

    /**
     * 软删标记：域标识 → deletedAt（毫秒 epoch）。
     *
     * 与 {@code domains} 条目分开存：派生域（不落盘声明）也能被软删，
     * 它的重启重建规则与声明无关，标记若塞进条目里会随"条目不落盘"一起丢。
     * 语义与注册表的 pendingDelete 一致：域仍在注册表、结构判据不变，
     * 只在新开轮入口（{@code AgentOrchestrationService#chat}）被拒。
     */
    private final Map<String, Long> pendingDeletes = new LinkedHashMap<>();

    /**
     * 域 → 知识体绑定（进程内镜像）。
     *
     * <p>落盘分两路：手动域的绑定随 {@link #save} 进 {@code domains.json}（与域声明、
     * 可调用性同一版本库，能一起回滚）；内置 / 派生域不进那份文件，由
     * {@link #setBody} 落到 prompts.json 的 bodyBindings 段（见 {@code DomainSettings}）。</p>
     *
     * <p><b>不继承</b>：这是每个域自己的声明，不是从父域推出来的。没绑的域就是"没有
     * 知识库"——不去找父域借。域链只影响"查得到谁的数据"，不影响"用谁的知识体"。</p>
     *
     * <p>绑定放这里而不是 {@code InfraSettings}：知识体是"有哪些存储单元"（基础设施配置），
     * 域绑定是"哪个域用哪一个"（域数据），跟着域的版本库一起回滚才自洽。</p>
     */
    private final Map<String, String> bodies = new LinkedHashMap<>();

    /**
     * 版本信号消费者：每次成功落盘（version+1）后回调一次。
     *
     * 用 setter 注入而不是构造器：本类不该知道 Redis 的存在（本地文件是唯一权威存储，
     * Redis 只传版本信号）。未注入时静默——单机部署没有广播对象。
     */
    private volatile Consumer<Long> versionListener;

    /**
     * 每次成功落盘后触发一次（本节点），给"跟着域配置走的派生状态"重算用。
     *
     * <p>与 {@link #versionListener} 的分工：那个是<b>跨节点</b>的版本信号（Redis 广播），
     * 这个是<b>本节点</b>的变更回调。典型消费者是知识检索工具的可见域——它由
     * "域绑了哪个知识体"决定，任何一次域配置变更（绑定、解绑、删域、回滚）都得重算。</p>
     *
     * <p><b>必须是列表，不能是单值</b>：本节点已不止一个派生状态（知识检索工具的可见域、
     * 知识库统计缓存）。单值时后注册的会把先注册的悄悄顶掉——现象是"某块派生视图从此
     * 再也不刷新"，且没有任何报错。这里用 {@link CopyOnWriteArrayList} 与
     * {@code ModelProfileRegistry#onChange} 保持同一套写法，逐个回调、逐个隔离异常。</p>
     */
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public DomainStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("domains.json");
        this.historyDir = storage.settingsDir().resolve("domains-history");
    }

    /**
     * 域设置存储（prompts.json）：内置域 / 派生域的知识体绑定落在这里。
     *
     * <p>setter 注入而非构造器：单参构造器被多处测试直接使用，且它不依赖任何其他 bean，
     * 加参就等于给所有测试立一道新门槛。生产环境由 Spring 注入；测试没注入时（null）
     * 内置域绑定只更新内存、不落盘，既有测试行为不变。</p>
     */
    private DomainSettingsStore domainSettingsStore;

    /**
     * 域注册表：判定"哪些域还活着"（retainAll 收敛）与"这个域是不是内置/派生"
     * （落盘去向）。由 {@code DomainConfiguration} 在建完注册表后显式接线——
     * 注册表 bean 又依赖本类，构造器注入会成环；启动完成前不会有人调 {@link #save}。
     */
    private DomainRegistry domainRegistry;

    @Autowired
    public void setDomainSettingsStore(DomainSettingsStore domainSettingsStore) {
        this.domainSettingsStore = domainSettingsStore;
    }

    /** 由 DomainConfiguration 装配期显式接线；未接线时（测试）按"只有 callables 里的域存活"收敛 */
    public void setDomainRegistry(DomainRegistry domainRegistry) {
        this.domainRegistry = domainRegistry;
    }

    // ==================== 读取 ====================

    /**
     * 读取落盘的域声明。
     *
     * 兼容上一版的纯标识列表（{@code manualDomains}）：那些条目一律按可调用处理，
     * 随后由启动期的一次性迁移把它们之中"已经有子域的"改成装配节点。
     *
     * 四种结果：
     *   • {@link Status#ABSENT}  文件不存在：首次启动或尚未配置过；
     *   • {@link Status#OK}      成功读到；
     *   • {@link Status#RECOVERED} 权威文件损坏，已从历史快照恢复（见下）；
     *   • {@link Status#CORRUPT} 文件在但读不出，且没有可用的历史快照。
     *
     * CORRUPT 与 ABSENT 必须分开：后者是"没有配置"，前者禁止触发迁移与覆盖写盘，
     * 否则会把一份可能还能修好的文件抹成空配置，且迁移标记一旦写成 true 就再不会重来。
     *
     * @throws IllegalStateException 权威文件损坏、历史快照存在但全部不可读：
     *                              存储介质层面坏了（不是"某一份文件坏了"），拒绝启动
     */
    public StoredDomains load() {
        this.pendingDeletes.clear();
        if (!Files.exists(settingsFile)) {
            log.debug("[域] 未找到 {}，按空配置处理", settingsFile.toAbsolutePath());
            this.currentVersion = 0;
            this.loaded = true;
            this.corruptOnLoad = false;
            return StoredDomains.empty();
        }
        try {
            DomainDeclarations settings = MAPPER.readValue(
                    Files.readString(settingsFile, StandardCharsets.UTF_8), DomainDeclarations.class);
            if (settings == null) {
                this.currentVersion = 0;
                this.loaded = true;
                this.corruptOnLoad = false;
                return StoredDomains.empty();
            }
            this.loaded = true;
            this.corruptOnLoad = false;
            return apply(settings, StoredDomains.Status.OK);
        } catch (IOException | RuntimeException e) {
            // 读不出（截断 / 格式坏 / 编码坏）→ 走历史快照恢复；一份都没有时维持既有行为：
            // 空配置 + 拒绝覆盖写，服务照常起，人工域由管控台重建，原始文件留着等人修
            return recoverFromHistory(e);
        }
    }

    /**
     * 权威文件损坏时的历史快照恢复：按版本号从高到低找第一份能解析的。
     *
     * 恢复后 {@code corruptOnLoad=false}：内存里是完整配置，写盘是修复权威文件
     * （把好内容写回去），不是"用空配置覆盖可能还能修的文件"。
     */
    private StoredDomains recoverFromHistory(Exception cause) {
        List<long[]> snapshots = listHistoryDescending();
        if (snapshots.isEmpty()) {
            // 没有历史可退：升级首启（旧版没有版本库）或从未落盘过。
            // 维持既有语义，见 load() 注释
            log.error("[域] 读取 {} 失败且无历史快照可退，按空配置继续（可进管控台重建）：{}",
                    settingsFile.toAbsolutePath(), cause.getMessage());
            this.currentVersion = 0;
            this.loaded = true;
            this.corruptOnLoad = true;
            return StoredDomains.corrupt();
        }
        for (long[] snapshot : snapshots) {
            long version = snapshot[0];
            try {
                DomainDeclarations settings = MAPPER.readValue(
                        Files.readString(historyFile(version), StandardCharsets.UTF_8),
                        DomainDeclarations.class);
                if (settings == null) {
                    continue;
                }
                log.warn("[域] 权威文件 {} 损坏，已从历史快照 v{} 恢复（原因：{}）。"
                                + "下次变更落盘将自动修复权威文件",
                        settingsFile.toAbsolutePath(), version, cause.getMessage());
                this.loaded = true;
                this.corruptOnLoad = false;
                return apply(settings, StoredDomains.Status.RECOVERED);
            } catch (Exception e) {
                log.warn("[域] 历史快照 v{} 也不可读（{}），继续尝试更旧的版本", version, e.getMessage());
            }
        }
        // 有快照却全部读不出 = 磁盘 / 文件系统层面坏了。此刻放行等于带着空注册表跑，
        // 而"域是安全边界"：同路径域重建会让旧对话历史与待执行的破坏性调用复活。
        // 拒绝启动，让人来修存储
        throw new IllegalStateException("域配置损坏（" + settingsFile.toAbsolutePath()
                + " 存在但无法解析），且历史快照 " + snapshots.size() + " 份全部不可读："
                + "存储可能已损坏。请先修复磁盘或从外部备份恢复 config/domains.json 与 "
                + "config/domains-history/，再重启服务");
    }

    /** 把解析结果灌入进程内状态并构造返回值 */
    private StoredDomains apply(DomainDeclarations settings, StoredDomains.Status status) {
        Map<String, Boolean> callables = new LinkedHashMap<>();
        this.bodies.clear();
        // 旧格式优先落进来：没有显式条目的历史域按"可调用"处理，保持升级前后行为一致
        if (settings.getManualDomains() != null) {
            for (String id : settings.getManualDomains()) {
                if (id != null && !id.isBlank()) {
                    callables.put(id.trim(), Boolean.TRUE);
                }
            }
        }
        if (settings.getDomains() != null) {
            for (DomainEntry entry : settings.getDomains()) {
                if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                    continue;
                }
                String id = entry.getId().trim();
                callables.put(id, entry.isCallable());
                if (entry.getBody() != null && !entry.getBody().isBlank()) {
                    this.bodies.put(id, entry.getBody().trim());
                }
            }
        }
        // 内置域 / 派生域的绑定不进本文件（本文件只存管控台声明的域），落在 prompts.json
        // 的 bodyBindings 段。这里一并灌入进程内镜像：少了这一步，重启后内置域的
        // 知识库就"静默失联"——域还在、体还在，只是绑定没了。
        if (domainSettingsStore != null) {
            Map<String, String> builtin = domainSettingsStore.load().getBodyBindings();
            if (builtin != null) {
                builtin.forEach((id, body) -> {
                    if (id != null && !id.isBlank() && body != null && !body.isBlank()) {
                        this.bodies.put(id.trim(), body.trim());
                    }
                });
            }
        }
        this.callableMigrated = settings.isCallableMigrated();
        this.currentVersion = Math.max(0, settings.getVersion());
        this.pendingDeletes.clear();
        if (settings.getPendingDeletes() != null) {
            settings.getPendingDeletes().forEach((id, deletedAt) -> {
                if (id != null && !id.isBlank() && deletedAt != null && deletedAt > 0) {
                    this.pendingDeletes.put(id.trim(), deletedAt);
                }
            });
        }
        return new StoredDomains(callables, settings.isCallableMigrated(),
                this.currentVersion, Map.copyOf(this.pendingDeletes), status);
    }

    // ==================== 写入 ====================

    /**
     * 覆盖写域声明，并保留已有的一次性迁移标记（管理端改域时用）。
     *
     * 标记取自缓存的进程内值（见 {@link #callableMigrated}）：重读文件会因损坏而读不到标记，
     * 把它复位成 false，于是重启后迁移重跑，运维手工切过的可调用性被静默推翻。
     */
    public void saveDeclarations(Map<String, Boolean> callables) {
        save(callables, this.callableMigrated);
    }

    /**
     * 覆盖写域声明（version+1，先落历史快照再原子覆盖权威文件）。
     *
     * @param callables        域标识 → 是否可调用（顺序即落盘顺序）
     * @param callableMigrated 是否已完成"非叶子→装配节点"的一次性迁移
     * @throws IllegalStateException 写盘失败，必须让调用方感知，静默失败会让用户以为已生效
     */
    public void save(Map<String, Boolean> callables, boolean callableMigrated) {
        assertWritable();
        DomainDeclarations settings = new DomainDeclarations();
        settings.setCallableMigrated(callableMigrated);
        settings.setVersion(this.currentVersion + 1);
        List<DomainEntry> entries = new ArrayList<>();
        if (callables != null) {
            callables.forEach((id, callable) -> {
                DomainEntry entry = new DomainEntry();
                entry.setId(id);
                entry.setCallable(callable == null || callable);
                entry.setBody(this.bodies.get(id));
                entries.add(entry);
            });
        }
        // 域已被删掉，它的知识体绑定也一并清掉：留着会在下次"同名域重建"时复活旧绑定。
        // 保留集合必须覆盖**所有存活域**（内置 + 派生 + 手动）：callables 只有手动域，
        // 拿它 retain 会把内置/派生域刚写入的绑定当场抹掉——绑定接口返回成功、
        // 页面刷新又变回"未绑定"，根因就在这一行。
        java.util.Set<String> aliveDomains = domainRegistry != null
                ? domainRegistry.ids()
                : (callables == null ? java.util.Set.of() : callables.keySet());
        this.bodies.keySet().retainAll(aliveDomains);
        settings.setDomains(entries);
        settings.setPendingDeletes(new LinkedHashMap<>(this.pendingDeletes));
        persist(settings);
    }

    // ==================== 知识体绑定 ====================

    /** 该域绑定的知识体 id；没绑返回 {@code null}（<b>不继承父域</b>） */
    public String bodyOf(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return null;
        }
        return this.bodies.get(domainId.trim());
    }

    /**
     * 设置（或清空，传 null/空串）某域的知识体绑定。
     *
     * <p>手动域的落盘仍由调用方走 {@link #saveDeclarations}（与可调用性、域结构同一份文件、
     * 同一个版本号）；内置 / 派生域不进那份文件，这里顺手把它们落到 prompts.json——
     * 调用方无需关心域的来源。</p>
     */
    public void setBody(String domainId, String bodyId) {
        if (domainId == null || domainId.isBlank()) {
            return;
        }
        String id = domainId.trim();
        if (bodyId == null || bodyId.isBlank()) {
            this.bodies.remove(id);
        } else {
            this.bodies.put(id, bodyId.trim());
        }
        persistBuiltinBodies();
    }

    /**
     * 把内置 / 派生域的知识体绑定落到 prompts.json 的 bodyBindings 段。
     *
     * <p>只写非手动域：手动域的绑定随 {@code domains.json} 的版本库走（能一起回滚），
     * 写到这里会出现两份权威、回滚只回得了一边。整段重建而不是单键增改：
     * 内存镜像是唯一事实，读改写单键会在并发保存时留下已删域的幽灵绑定。</p>
     *
     * <p>存储或注册表缺席（测试 / 裁剪装配）时跳过：只更新内存，行为与旧版一致。</p>
     */
    private void persistBuiltinBodies() {
        if (domainSettingsStore == null || domainRegistry == null) {
            return;
        }
        Map<String, String> nonManual = new LinkedHashMap<>();
        this.bodies.forEach((id, body) -> {
            DomainRegistry.Source source = domainRegistry.sourceOf(id);
            if (source != null && source != DomainRegistry.Source.MANUAL) {
                nonManual.put(id, body);
            }
        });
        DomainSettings settings = domainSettingsStore.load();
        settings.setBodyBindings(nonManual);
        domainSettingsStore.save(settings);
    }

    /** 绑定快照（域 → 知识体 id） */
    public Map<String, String> bodies() {
        return Map.copyOf(this.bodies);
    }

    /**
     * 把一份历史快照作为新版本提交（回滚）。
     *
     * 回滚即普通变更：快照内容 + version+1，不是把文件倒回旧版本号——
     * 否则会出现比"当前"还旧的幽灵版本被再次选中，版本比较随之失效。
     *
     * @param callables 快照里的域声明
     * @param markers   快照里的软删标记（随回滚一起回到当时的状态）
     */
    public void commitRollback(Map<String, Boolean> callables, Map<String, Long> markers,
                               boolean callableMigrated) {
        commitRollback(callables, markers, callableMigrated, this.bodies);
    }

    /**
     * 回滚到快照（含知识体绑定）。
     *
     * <p>绑定必须跟着一起回：只回可调用性、不回绑定，会出现"域结构回到过去、
     * 知识库还指着回滚前那个体"的错位——而且不会报错，只是查出来的东西不对。</p>
     */
    public void commitRollback(Map<String, Boolean> callables, Map<String, Long> markers,
                               boolean callableMigrated, Map<String, String> bodies) {
        assertWritable();
        this.pendingDeletes.clear();
        if (markers != null) {
            markers.forEach((id, deletedAt) -> {
                if (id != null && !id.isBlank() && deletedAt != null && deletedAt > 0) {
                    this.pendingDeletes.put(id.trim(), deletedAt);
                }
            });
        }
        this.bodies.clear();
        if (bodies != null) {
            bodies.forEach((id, body) -> {
                if (id != null && !id.isBlank() && body != null && !body.isBlank()) {
                    this.bodies.put(id.trim(), body.trim());
                }
            });
        }
        save(callables, callableMigrated);
    }

    /** 落盘主路径：历史快照 → 原子覆盖权威 → 限额滚动 → 广播版本信号 */
    private void persist(DomainDeclarations settings) {
        long newVersion = settings.getVersion();
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(settings);
            // 顺序是硬约束：先落历史再覆盖权威。反过来一旦在两步之间掉电，
            // 权威已是新版本而历史里没有对应快照，恢复链上永久缺一环
            AtomicFiles.write(historyFile(newVersion), bytes);
            AtomicFiles.write(settingsFile, bytes);
            trimHistory();
            this.currentVersion = newVersion;
            this.callableMigrated = settings.isCallableMigrated();
            this.loaded = true;
            log.info("[域] 已保存域配置 v{}（{} 个域声明，可调用 {} 个，软删标记 {} 个）到 {}",
                    newVersion, settings.getDomains().size(),
                    settings.getDomains().stream().filter(DomainEntry::isCallable).count(),
                    settings.getPendingDeletes().size(), settingsFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("域配置保存失败：" + settingsFile.toAbsolutePath(), e);
        }
        Consumer<Long> listener = this.versionListener;
        if (listener != null) {
            try {
                listener.accept(newVersion);
            } catch (Exception e) {
                // 信号是旁路：广播失败不改写盘结果，只让其他节点滞后
                log.warn("[域] 版本信号广播失败（v{}），本节点已生效，其他节点将滞后：{}",
                        newVersion, e.getMessage());
            }
        }
        for (Runnable changed : changeListeners) {
            try {
                changed.run();
            } catch (Exception e) {
                // 派生状态（如"哪些域该有检索工具"）重算失败不能反过来把保存搞失败：
                // 配置已经落盘并生效了，这里失败只是那一份派生视图滞后
                log.warn("[域] 变更后的派生状态刷新失败（v{}）：{}", newVersion, e.getMessage());
            }
        }
    }

    /**
     * 当前配置是否已损坏：损坏时禁止任何覆盖写。
     *
     * 损坏的含义是"文件在，但读不出来，且没有历史快照可退"。此刻内存里的注册表只包含
     * 根域与工具派生的域，人工建的域一个都没有，把它写回磁盘就是抹掉全部人工域，且不可逆。
     * 正确做法是让这次写入失败并报错，让人去修文件或从备份恢复。
     * （从历史快照恢复过的不算损坏：内存里是完整配置，写盘即修复。）
     *
     * @throws IllegalStateException 配置已损坏
     */
    public void assertWritable() {
        if (this.loaded && this.corruptOnLoad) {
            throw new IllegalStateException("域配置已损坏（" + settingsFile.toAbsolutePath()
                    + " 存在但无法解析），已拒绝写入以免抹掉其中的人工域；"
                    + "请先修复或恢复该文件，或将其改名后重新导入域配置");
        }
    }

    // ==================== 软删标记 ====================

    /** 打软删标记（进程内；随后由调用方走 {@link #saveDeclarations} 落盘） */
    public void markDeleted(String domainId) {
        this.pendingDeletes.put(domainId.trim(), System.currentTimeMillis());
    }

    /** 撤销软删（进程内；随后由调用方走 {@link #saveDeclarations} 落盘） */
    public void clearDeleted(String domainId) {
        this.pendingDeletes.remove(domainId.trim());
    }

    /** 该域是否处于软删宽限期 */
    public boolean isPendingDelete(String domainId) {
        return this.pendingDeletes.containsKey(domainId.trim());
    }

    /** 软删标记快照（域 → deletedAt 毫秒） */
    public Map<String, Long> pendingDeletes() {
        return Map.copyOf(this.pendingDeletes);
    }

    // ==================== 版本与历史 ====================

    /** 当前权威版本号（进程内镜像，供乐观并发校验） */
    public long currentVersion() {
        return this.currentVersion;
    }

    /** 注册版本信号消费者（多实例广播）；不注册则完全不广播 */
    public void setVersionListener(Consumer<Long> listener) {
        this.versionListener = listener;
    }

    /**
     * 注册"本节点域配置变更"回调：每次成功落盘后跑一次，异常被吞掉并记 WARN。
     *
     * <p>只该用来做<b>幂等的派生状态重算</b>（读当前配置算出该是什么样，再整体覆盖上去）；
     * 别在这里做增量动作——回调可能因为一次不相关的保存（改提示词、改工具）而多跑几次。</p>
     *
     * <p>可以注册多个（工具可见域、知识库统计缓存各一个），互不覆盖。</p>
     */
    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }

    /** 历史版本库目录（供管控台展示） */
    public String historyPath() {
        return historyDir.toAbsolutePath().toString();
    }

    /**
     * 历史快照清单（版本从高到低），供管控台版本库页展示。
     *
     * 每条都现场解析一遍：版本库是可重建数据，清单又是低频管理操作，
     * 不值得为它再维护一份索引文件（索引本身也会漂移）。
     */
    public List<HistoryEntry> listHistory() {
        List<HistoryEntry> entries = new ArrayList<>();
        for (long[] snapshot : listHistoryDescending()) {
            long version = snapshot[0];
            Path file = historyFile(version);
            try {
                DomainDeclarations settings = MAPPER.readValue(
                        Files.readString(file, StandardCharsets.UTF_8), DomainDeclarations.class);
                if (settings == null) {
                    continue;
                }
                entries.add(new HistoryEntry(version,
                        Files.getLastModifiedTime(file).toMillis(),
                        Files.size(file),
                        settings.getDomains() == null ? 0 : settings.getDomains().size(),
                        settings.getDomains() == null ? 0
                                : (int) settings.getDomains().stream().filter(DomainEntry::isCallable).count(),
                        settings.getPendingDeletes() == null ? 0 : settings.getPendingDeletes().size(),
                        version == this.currentVersion));
            } catch (Exception e) {
                log.warn("[域] 历史快照 v{} 不可读，清单中跳过：{}", version, e.getMessage());
            }
        }
        return entries;
    }

    /**
     * 读一份历史快照的原始内容（回滚用）。
     *
     * @throws IllegalArgumentException 快照不存在或不可读
     */
    public DomainDeclarations readSnapshot(long version) {
        Path file = historyFile(version);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("历史版本 v" + version + " 不存在");
        }
        try {
            DomainDeclarations settings = MAPPER.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), DomainDeclarations.class);
            if (settings == null) {
                throw new IllegalArgumentException("历史版本 v" + version + " 内容为空");
            }
            return settings;
        } catch (IOException e) {
            throw new IllegalArgumentException("历史版本 v" + version + " 不可读：" + e.getMessage());
        }
    }

    /** 从快照里取域声明（旧格式兼容，与 {@link #load()} 同规则） */
    public static Map<String, Boolean> callablesOf(DomainDeclarations settings) {
        Map<String, Boolean> callables = new LinkedHashMap<>();
        if (settings.getManualDomains() != null) {
            for (String id : settings.getManualDomains()) {
                if (id != null && !id.isBlank()) {
                    callables.put(id.trim(), Boolean.TRUE);
                }
            }
        }
        if (settings.getDomains() != null) {
            for (DomainEntry entry : settings.getDomains()) {
                if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                    continue;
                }
                callables.put(entry.getId().trim(), entry.isCallable());
            }
        }
        return callables;
    }

    /** 从快照里取知识体绑定（回滚用） */
    public static Map<String, String> bodiesOf(DomainDeclarations settings) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (settings != null && settings.getDomains() != null) {
            for (DomainEntry entry : settings.getDomains()) {
                if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                    continue;
                }
                if (entry.getBody() != null && !entry.getBody().isBlank()) {
                    bindings.put(entry.getId().trim(), entry.getBody().trim());
                }
            }
        }
        return bindings;
    }

    /** 从快照里取软删标记 */
    public static Map<String, Long> markersOf(DomainDeclarations settings) {
        Map<String, Long> markers = new LinkedHashMap<>();
        if (settings.getPendingDeletes() != null) {
            settings.getPendingDeletes().forEach((id, deletedAt) -> {
                if (id != null && !id.isBlank() && deletedAt != null && deletedAt > 0) {
                    markers.put(id.trim(), deletedAt);
                }
            });
        }
        return markers;
    }

    private Path historyFile(long version) {
        return historyDir.resolve("v" + version + ".json");
    }

    /** 历史快照文件按版本号降序（[版本, 大小]）；目录不存在时为空列表 */
    private List<long[]> listHistoryDescending() {
        if (!Files.isDirectory(historyDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(historyDir)) {
            return files.map(path -> {
                        Matcher matcher = HISTORY_NAME.matcher(path.getFileName().toString());
                        if (!matcher.matches()) {
                            return null;
                        }
                        try {
                            return new long[]{Long.parseLong(matcher.group(1)), Files.size(path)};
                        } catch (IOException | NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(s -> s != null)
                    .sorted(Comparator.comparingLong((long[] s) -> s[0]).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("[域] 列举历史版本库 {} 失败：{}", historyDir.toAbsolutePath(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 历史版本库限额滚动：保留最近 {@link #HISTORY_KEEP} 份；
     * 总大小超 {@link #HISTORY_MAX_BYTES} 时从最旧开始删，但至少留 1 份
     * （全删了等于把恢复链剪断，比超限更危险）。
     */
    private void trimHistory() {
        // listHistoryDescending 返回不可变列表（Stream.toList），滚动删除要先包一层
        List<long[]> snapshots = new ArrayList<>(listHistoryDescending());
        if (snapshots.isEmpty()) {
            return;
        }
        // 份数超限：第 HISTORY_KEEP 名之后的都在尾部（列表按版本降序），从最旧删起
        while (snapshots.size() > HISTORY_KEEP) {
            deleteQuietly(snapshots.remove(snapshots.size() - 1)[0]);
        }
        // 体积超限：同样从最旧删，但保住最新那一份
        long total = 0;
        for (long[] snapshot : snapshots) {
            total += snapshot[1];
        }
        while (total > HISTORY_MAX_BYTES && snapshots.size() > 1) {
            long[] oldest = snapshots.remove(snapshots.size() - 1);
            total -= oldest[1];
            deleteQuietly(oldest[0]);
        }
    }

    private void deleteQuietly(long version) {
        try {
            Files.deleteIfExists(historyFile(version));
            log.debug("[域] 历史版本库滚动，删除旧快照 v{}", version);
        } catch (IOException e) {
            log.warn("[域] 删除旧快照 v{} 失败（不影响服务）：{}", version, e.getMessage());
        }
    }

    /**
     * 读到的域声明。
     *
     * @param callables        域标识 → 是否可调用
     * @param callableMigrated 是否已完成一次性迁移
     * @param version          权威版本号
     * @param pendingDeletes   软删标记（域 → deletedAt 毫秒）
     * @param status           读取结果状态。{@link Status#CORRUPT} 时除版本外的字段都无意义，
     *                         调用方必须据此拒绝迁移与写盘：见 {@link #load()} 的说明。
     */
    public record StoredDomains(Map<String, Boolean> callables, boolean callableMigrated,
                                long version, Map<String, Long> pendingDeletes, Status status) {

        /** 读取结果的状态 —— 关键是把"文件不存在"、"损坏"、"已从快照恢复"分开 */
        public enum Status {
            /** 文件不存在：首次启动或尚未配置过 */
            ABSENT,
            /** 成功读到 */
            OK,
            /** 权威文件损坏，已从历史快照恢复（内存是完整配置，写盘即修复） */
            RECOVERED,
            /** 文件存在但读不出，且没有可用的历史快照 */
            CORRUPT
        }

        public StoredDomains {
            if (status == null) {
                throw new IllegalArgumentException("status 不能为空");
            }
            if (pendingDeletes == null) {
                throw new IllegalArgumentException("pendingDeletes 不能为空");
            }
        }

        static StoredDomains empty() {
            return new StoredDomains(Map.of(), false, 0, Map.of(), Status.ABSENT);
        }

        /** 文件在，但内容不可用 */
        static StoredDomains corrupt() {
            return new StoredDomains(Map.of(), false, 0, Map.of(), Status.CORRUPT);
        }

        /** 损坏时一律当作"不可用"，防止调用方误用其中的空集合 */
        public boolean usable() {
            return status != Status.CORRUPT;
        }
    }

    /**
     * 历史版本库清单条目（管控台版本库页）。
     *
     * @param current 是否为当前权威版本（回滚它等于确认现状，管控台置灰）
     */
    public record HistoryEntry(long version, long modifiedAt, long sizeBytes,
                               int domainCount, int callableCount, int pendingDeleteCount,
                               boolean current) {
    }

    /**
     * 落盘结构（{@code domains.json}）。
     *
     * 不要改回 {@code DomainSettings}：同包已有顶层的 {@code DomainSettings}
     * （{@code settings/DomainSettings.java}，存"域 → 提示词片段"，落盘 {@code prompts.json}），
     * 两者语义完全不同。同名会让本类内的简单名解析与包外import 分道扬镳：
     * {@code DomainStore} 内的 {@code DomainSettings} 指向本类，{@code AdminController}
     * 等通过 import 的是顶层那份，读代码时极易看错。
     */
    public static class DomainDeclarations {

        /** 域声明（当前格式） */
        private List<DomainEntry> domains = new ArrayList<>();

        /** 上一版的纯标识列表，只读兼容 */
        private List<String> manualDomains;

        /** 一次性迁移标记：为 false 时启动期会执行"有子域的域 → 装配节点" */
        private boolean callableMigrated;

        /**
         * 权威版本号：单调递增，每次变更 +1。旧文件没有该字段 → 0，
         * 首次保存即 v1，不需要迁移。
         */
        private long version;

        /**
         * 软删标记：域标识 → deletedAt（毫秒）。与 {@link #domains} 分开存：
         * 派生域的声明不落盘（重启随工具声明重建），但它的软删标记必须留得住。
         */
        private Map<String, Long> pendingDeletes = new LinkedHashMap<>();

        public List<DomainEntry> getDomains() {
            return domains;
        }

        public void setDomains(List<DomainEntry> domains) {
            this.domains = domains == null ? new ArrayList<>() : domains;
        }

        public List<String> getManualDomains() {
            return manualDomains;
        }

        public void setManualDomains(List<String> manualDomains) {
            this.manualDomains = manualDomains;
        }

        public boolean isCallableMigrated() {
            return callableMigrated;
        }

        public void setCallableMigrated(boolean callableMigrated) {
            this.callableMigrated = callableMigrated;
        }

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }

        public Map<String, Long> getPendingDeletes() {
            return pendingDeletes;
        }

        public void setPendingDeletes(Map<String, Long> pendingDeletes) {
            this.pendingDeletes = pendingDeletes == null ? new LinkedHashMap<>() : pendingDeletes;
        }
    }

    /** 单个域的声明 */
    public static class DomainEntry {

        private String id;

        /** 是否可调用单元（默认 true：显式创建一个域，本意就是拿它当入口） */
        private boolean callable = true;

        /**
         * 该域绑定的知识体 id（{@code InfraSettings.bodies} 的键）；空 = 这个域没有知识库。
         *
         * <p>刻意不是"继承父域"的写法：知识体决定"这个域的文档写进哪台机器"，
         * 跟着父域走会让新建的子域在用户不知情的情况下往父域的库里写。</p>
         */
        private String body;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public boolean isCallable() {
            return callable;
        }

        public void setCallable(boolean callable) {
            this.callable = callable;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }
}
