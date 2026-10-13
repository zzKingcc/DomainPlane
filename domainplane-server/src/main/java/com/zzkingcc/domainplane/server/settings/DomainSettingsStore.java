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

/**
 * 域提示词的持久化存储（{@code config/prompts.json}）
 * @author zzkingcc
 */
@Slf4j
@Component
public class DomainSettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    /**
     * 最近一次读盘是否失败（解析出错或结果为 null）。
     *
     * 失败时 {@link #load()} 返回空设置，此时照常 {@link #save} 覆盖写会把坏文件
     * "修"成空文件，域提示词与工具回写开关全部永久消失。故置位后拒绝任何写入。
     */
    private volatile boolean loadFailed;

    public DomainSettingsStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("prompts.json");
    }

    /**
     * 读取设置；文件不存在或解析失败时返回空设置（不阻断启动，可进管控台修正）。
     */
    public DomainSettings load() {
        if (!Files.exists(settingsFile)) {
            log.debug("[域提示词] 未找到设置文件 {}，回落 yaml（domainplane.ai.prompt）",
                    settingsFile.toAbsolutePath());
            loadFailed = false;
            return new DomainSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            DomainSettings settings = MAPPER.readValue(json, DomainSettings.class);
            if (settings == null) {
                loadFailed = true;
                log.error("[域提示词] {} 的解析结果为 null，以空设置继续（已禁止写入，避免覆盖清空）",
                        settingsFile.toAbsolutePath());
                return new DomainSettings();
            }
            loadFailed = false;
            log.debug("[域提示词] 已加载设置文件 {}：域片段 {} 个",
                    settingsFile.toAbsolutePath(), settings.getPrompts().size());
            return settings;
        } catch (IOException e) {
            loadFailed = true;
            log.error("[域提示词] 读取设置文件失败，将以空设置继续（已禁止写入，可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e);
            return new DomainSettings();
        }
    }

    /**
     * 保存设置（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败。必须让调用方感知，静默失败会让用户以为已生效
     */
    public void save(DomainSettings settings) {
        if (loadFailed) {
            throw new IllegalStateException("域提示词文件无法解析，拒绝覆盖写入以免把既有配置清空："
                    + settingsFile.toAbsolutePath() + "；请修复或删除该文件后重启服务");
        }
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[域提示词] 已保存到 {}：域片段 {} 个",
                    settingsFile.toAbsolutePath(), settings.getPrompts().size());
        } catch (IOException e) {
            throw new IllegalStateException("域提示词保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /**
     * 删域时清理：连提示词片段与工具执行信息回写开关一起移除，一次读完一次写回。
     *
     * <p>分成两次调用会丢数据：每次调用都重新读文件、改、整份写回；只要另一条线程的保存
     * 挤在两次之间，后写的那份就把先写的清理结果覆盖掉，全程不报错，表现为
     * "删了域，提示词还在"。</p>
     *
     * <p>{@code synchronized}：整段是读改写，并发保存会互相覆盖。</p>
     */
    public synchronized void removeDomains(java.util.Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        DomainSettings settings = load();
        boolean prompts = settings.getPrompts().keySet()
                .removeIf(domain -> domains.contains(domain));
        boolean echoes = settings.getToolCallEcho().keySet()
                .removeIf(domain -> domains.contains(domain));
        if (!prompts && !echoes) {
            return;
        }
        save(settings);
        log.info("[域设置] 已清理 {} 个被删域的配置（提示词片段变更={}，工具回写开关变更={}）",
                domains.size(), prompts, echoes);
    }

    /**
     * 删域时清理：移除这些域的提示词片段。
     *
     * 不清理的话，键会变成孤儿：同路径域将来重建时会静默复活旧提示词。
     */
    public synchronized void removePrompts(java.util.Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        DomainSettings settings = load();
        boolean changed = settings.getPrompts().keySet()
                .removeIf(domain -> domains.contains(domain));
        if (changed) {
            save(settings);
            log.info("[域提示词] 已清理 {} 个被删域的提示词片段", domains.size());
        }
    }

    /**
     * 删域时清理：移除这些域的工具执行信息回写开关。
     *
     * 与 {@link #removePrompts} 同一理由：不清理会同路径域重建时复活旧开关。
     * 装配节点只是不显示开关、值原样保留；删域才是彻底移除。
     */
    public synchronized void removeToolCallEcho(java.util.Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        DomainSettings settings = load();
        boolean changed = settings.getToolCallEcho().keySet()
                .removeIf(domain -> domains.contains(domain));
        if (changed) {
            save(settings);
            log.info("[域设置] 已清理 {} 个被删域的工具执行信息回写开关", domains.size());
        }
    }

    /** 设置文件路径（供管控台展示，便于运维确认落盘位置） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }
}
