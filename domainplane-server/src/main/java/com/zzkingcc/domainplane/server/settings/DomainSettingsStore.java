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
            return new DomainSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            DomainSettings settings = MAPPER.readValue(json, DomainSettings.class);
            if (settings == null) {
                return new DomainSettings();
            }
            log.debug("[域提示词] 已加载设置文件 {}：域片段 {} 个",
                    settingsFile.toAbsolutePath(), settings.getPrompts().size());
            return settings;
        } catch (IOException e) {
            log.error("[域提示词] 读取设置文件失败，将以空设置继续（可进管控台修正）: {}",
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
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[域提示词] 已保存到 {}：域片段 {} 个",
                    settingsFile.toAbsolutePath(), settings.getPrompts().size());
        } catch (IOException e) {
            throw new IllegalStateException("域提示词保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /**
     * 删域时清理：移除这些域的提示词片段。
     *
     * 不清理的话，键会变成孤儿 —— 同路径域将来重建时会静默复活旧提示词。
     */
    public void removePrompts(java.util.Collection<String> domains) {
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
    public void removeToolCallEcho(java.util.Collection<String> domains) {
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
