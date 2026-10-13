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
 * 基础设施存储配置的持久化存储
 * @author zzkingcc
 */
@Slf4j
@Component
public class InfraSettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    /**
     * 最近一次读盘是否失败（解析出错或结果为 null）。
     *
     * 失败时 {@link #load()} 返回空设置，若此时照常 save 覆盖写，等于把坏文件"修"成空文件，
     * ES 地址、Redis 地址与密码全部永久消失。故置位后拒绝任何写入。
     */
    private volatile boolean loadFailed;

    public InfraSettingsStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("infra-settings.json");
    }

    /** 读取设置；文件不存在或解析失败时返回空设置（不抛异常，让服务能起来） */
    public InfraSettings load() {
        if (!Files.exists(settingsFile)) {
            log.info("[存储配置] 未找到设置文件 {}，使用 yaml / 环境变量（或视为未配置）",
                    settingsFile.toAbsolutePath());
            loadFailed = false;
            return new InfraSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            InfraSettings settings = MAPPER.readValue(json, InfraSettings.class);
            if (settings == null) {
                loadFailed = true;
                log.error("[存储配置] {} 的解析结果为 null，按未配置继续（已禁止写入，避免覆盖清空）",
                        settingsFile.toAbsolutePath());
                return new InfraSettings();
            }
            loadFailed = false;
            log.info("[存储配置] 已加载设置文件 {}：ES={}，Redis={}",
                    settingsFile.toAbsolutePath(),
                    settings.getEs() == null ? "未配置" : settings.getEs().describe(),
                    settings.getRedis() == null ? "未配置" : settings.getRedis().describe());
            return settings;
        } catch (IOException e) {
            // 读不出来不应阻断启动，否则用户连管控台都进不去，无法修复
            loadFailed = true;
            log.error("[存储配置] 读取设置文件失败，将以未配置状态启动（已禁止写入，可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e);
            return new InfraSettings();
        }
    }

    /**
     * 保存设置（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败。必须让调用方感知：
     *         静默失败会让用户以为配置已生效，实际重启后丢失
     */
    public void save(InfraSettings settings) {
        if (loadFailed) {
            throw new IllegalStateException("存储配置文件无法解析，拒绝覆盖写入以免把既有配置清空："
                    + settingsFile.toAbsolutePath() + "；请修复或删除该文件后重启服务");
        }
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[存储配置] 已保存到 {}：ES={}，Redis={}",
                    settingsFile.toAbsolutePath(),
                    settings.getEs() == null ? "未配置" : settings.getEs().describe(),
                    settings.getRedis() == null ? "未配置" : settings.getRedis().describe());
        } catch (IOException e) {
            throw new IllegalStateException("存储配置保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 设置文件路径（供管控台展示，便于运维确认落盘位置） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }
}
