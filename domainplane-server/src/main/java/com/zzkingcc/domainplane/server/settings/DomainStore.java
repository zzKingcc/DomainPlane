package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzkingcc.domainplane.common.util.AtomicFiles;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域声明与可调用性的持久化存储（{@code config/domains.json}）。
 *
 * <p>只存<b>管控台声明</b>的域：内置根域由代码预置，派生域由工具声明决定，两者都不落盘。</p>
 *
 * <p>落盘结构与可调用性一一对应：域是不是"可调用单元"是显式声明，不靠"有没有子域"这种
 * 派生事实推断 —— 后者会在新增子域时静默改变调用方的可用性。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class DomainStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    /**
     * 一次性迁移标记的进程内缓存。
     *
     * <p>标记在启动时随 {@link #load()} 读一次并缓存，写盘时同步更新，不依赖后续能否读回文件：
     * 标记一旦被复位成 false，重启后迁移就会重跑，运维手工切过的可调用性被静默推翻。</p>
     */
    private volatile boolean callableMigrated;

    /** 本进程是否已尝试过加载（用于区分"还没读"与"读到的是损坏"） */
    private volatile boolean loaded;

    /** 加载时是否读到了损坏内容（与 {@link #loaded} 一起才能判断） */
    private volatile boolean corruptOnLoad;

    public DomainStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("domains.json");
    }

    /**
     * 读取落盘的域声明。
     *
     * <p>兼容上一版的纯标识列表（{@code manualDomains}）：那些条目一律按<b>可调用</b>处理，
     * 随后由启动期的一次性迁移把它们之中"已经有子域的"改成装配节点。</p>
     *
     * <p>文件不存在或解析失败都<b>不阻断启动</b>，但两者被区分开：
     * {@link StoredDomains.Status#ABSENT} 是"没有配置"，{@link StoredDomains.Status#CORRUPT}
     * 是"配置坏了" —— 后者禁止触发迁移与覆盖写盘，否则会把一份可能还能修好的文件抹成空配置，
     * 而且迁移标记一旦被写成 true 就再也不会重来。</p>
     */
    public StoredDomains load() {
        if (!Files.exists(settingsFile)) {
            log.debug("[域] 未找到 {}，按空配置处理", settingsFile.toAbsolutePath());
            return StoredDomains.empty();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            DomainDeclarations settings = MAPPER.readValue(json, DomainDeclarations.class);
            if (settings == null) {
                return StoredDomains.empty();
            }
            Map<String, Boolean> callables = new LinkedHashMap<>();
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
                    callables.put(entry.getId().trim(), entry.isCallable());
                }
            }
            this.callableMigrated = settings.isCallableMigrated();
            this.loaded = true;
            this.corruptOnLoad = false;
            return new StoredDomains(callables, settings.isCallableMigrated(), StoredDomains.Status.OK);
        } catch (IOException e) {
            // 这里必须区分"文件不存在"和"文件损坏" —— 两者都返回空配置会让启动期的一次性迁移
            // 把这份空配置当成权威写回磁盘（callableMigrated 被置为 true），原始内容就此永久丢失，
            // 而且此后不会再试第二次。损坏时宁可让启动带着空注册表跑完（域需要重建，但原始文件还在，
            // 运维修好后重启即可恢复），也不能覆盖它。
            log.error("[域] 读取 {} 失败，按空配置继续（可进管控台重建）：{}",
                    settingsFile.toAbsolutePath(), e.getMessage());
            this.loaded = true;
            this.corruptOnLoad = true;
            return StoredDomains.corrupt();
        }
    }

    /**
     * 覆盖写域声明，并<b>保留已有的一次性迁移标记</b>（管理端改域时用）。
     *
     * <p>标记取自缓存的进程内值（见 {@link #callableMigrated}）：重读文件会因损坏而读不到标记，
     * 把它复位成 false，于是重启后迁移重跑，运维手工切过的可调用性被静默推翻。</p>
     */
    public void saveDeclarations(Map<String, Boolean> callables) {
        save(callables, this.callableMigrated);
    }

    /**
     * 覆盖写域声明。
     *
     * @param callables        域标识 → 是否可调用（顺序即落盘顺序）
     * @param callableMigrated 是否已完成"非叶子→装配节点"的一次性迁移
     * @throws IllegalStateException 写盘失败 —— 必须让调用方感知，静默失败会让用户以为已生效
     */
    public void save(Map<String, Boolean> callables, boolean callableMigrated) {
        assertWritable();
        DomainDeclarations settings = new DomainDeclarations();
        settings.setCallableMigrated(callableMigrated);
        List<DomainEntry> entries = new ArrayList<>();
        if (callables != null) {
            callables.forEach((id, callable) -> {
                DomainEntry entry = new DomainEntry();
                entry.setId(id);
                entry.setCallable(callable == null || callable);
                entries.add(entry);
            });
        }
        settings.setDomains(entries);
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            this.callableMigrated = callableMigrated;
            this.loaded = true;
            log.info("[域] 已保存 {} 个域声明（可调用 {} 个）到 {}",
                    entries.size(), entries.stream().filter(DomainEntry::isCallable).count(),
                    settingsFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("域配置保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /**
     * 当前配置是否<b>已损坏</b>——损坏时禁止任何覆盖写。
     *
     * <p>损坏的含义是"文件在，但读不出来"。此刻内存里的注册表只包含根域与工具派生的域，
     * 人工建的域一个都没有，把它写回磁盘就是<b>抹掉全部人工域</b>，且不可逆。
     * 正确做法是让这次写入失败并报错，让人去修文件或从备份恢复。</p>
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

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }

    /**
     * 读到的域声明。
     *
     * @param callables        域标识 → 是否可调用
     * @param callableMigrated 是否已完成一次性迁移
     * @param status           读取结果状态。<b>{@link Status#CORRUPT} 时两个字段都无意义</b>，
     *                         调用方必须据此拒绝迁移与写盘 —— 见 {@link #load()} 的说明。
     */
    public record StoredDomains(Map<String, Boolean> callables, boolean callableMigrated, Status status) {

        /** 读取结果的三种状态 —— 关键在于把"文件不存在"与"文件损坏"分开 */
        public enum Status {
            /** 文件不存在：首次启动或尚未配置过 */
            ABSENT,
            /** 成功读到 */
            OK,
            /** 文件存在但读不出来：内容损坏、被截断、或格式不兼容 */
            CORRUPT
        }

        public StoredDomains {
            if (status == null) {
                throw new IllegalArgumentException("status 不能为空");
            }
        }

        static StoredDomains empty() {
            return new StoredDomains(Map.of(), false, Status.ABSENT);
        }

        /** 文件在，但内容不可用 */
        static StoredDomains corrupt() {
            return new StoredDomains(Map.of(), false, Status.CORRUPT);
        }

        /** 损坏时一律当作"不可用"，防止调用方误用其中的空集合 */
        public boolean usable() {
            return status != Status.CORRUPT;
        }
    }

    /**
     * 落盘结构（{@code domains.json}）。
     *
     * <p><b>不要改回 {@code DomainSettings}</b>：同包已有顶层的 {@code DomainSettings}
     * （{@code settings/DomainSettings.java}，存"域 → 提示词片段"，落盘 {@code prompts.json}），
     * 两者语义完全不同。同名会让本类内的简单名解析与包外import 分道扬镳 ——
     * {@code DomainStore} 内的 {@code DomainSettings} 指向本类，{@code AdminController}
     * 等通过 import 的是顶层那份，读代码时极易看错。</p>
     */
    public static class DomainDeclarations {

        /** 域声明（当前格式） */
        private List<DomainEntry> domains = new ArrayList<>();

        /** 上一版的纯标识列表，只读兼容 */
        private List<String> manualDomains;

        /** 一次性迁移标记：为 false 时启动期会执行"有子域的域 → 装配节点" */
        private boolean callableMigrated;

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
    }

    /** 单个域的声明 */
    public static class DomainEntry {

        private String id;

        /** 是否可调用单元（默认 true：显式创建一个域，本意就是拿它当入口） */
        private boolean callable = true;

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
    }
}
