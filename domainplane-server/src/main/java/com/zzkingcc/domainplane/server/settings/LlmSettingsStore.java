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
 * 模型设置的持久化存储
 * @author zzkingcc
 */
@Slf4j
@Component
public class LlmSettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    /**
     * 最近一次读盘是否失败（解析出错或结果为 null）。
     *
     * 失败时 {@link #load()} 返回空设置，若此时照常 {@link #save} 覆盖写，等于把坏文件
     * "修"成空文件：上游地址、密钥、模型名全部永久消失。故置位后拒绝任何写入。
     */
    private volatile boolean loadFailed;

    public LlmSettingsStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("llm-settings.json");
    }

    /** 读取设置；文件不存在或解析失败时返回空设置（不抛异常，让服务能起来） */
    public LlmSettings load() {
        if (!Files.exists(settingsFile)) {
            log.info("[模型设置] 未找到设置文件 {}，使用 yaml 配置/未配置状态", settingsFile.toAbsolutePath());
            loadFailed = false;
            return new LlmSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            LlmSettings settings = MAPPER.readValue(json, LlmSettings.class);
            if (settings == null) {
                // 文件内容是字面量 null：当作未配置处理，不能让一次 NPE 把启动带崩
                loadFailed = true;
                log.warn("[模型设置] 设置文件 {} 内容为空，按未配置处理（已禁止写入，避免覆盖清空）",
                        settingsFile.toAbsolutePath());
                return new LlmSettings();
            }
            loadFailed = false;
            log.info("[模型设置] 已加载设置文件 {}：对话模型={}，向量模型={}，可用={}",
                    settingsFile.toAbsolutePath(), settings.getChatModelName(),
                    settings.getEmbeddingModelName(), settings.isUsable());
            return settings;
        } catch (IOException e) {
            // 配置读不出来不应阻断服务启动，否则用户连管控台都进不去，无法修复
            loadFailed = true;
            log.error("[模型设置] 读取设置文件失败，将以未配置状态启动（已禁止写入，可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e);
            return new LlmSettings();
        }
    }

    /**
     * 保存设置（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败。这里必须让调用方感知：
     *         静默失败会让用户以为配置已生效，实际重启后丢失
     */
    public void save(LlmSettings settings) {
        if (loadFailed) {
            throw new IllegalStateException("模型设置文件无法解析，拒绝覆盖写入以免把既有配置清空："
                    + settingsFile.toAbsolutePath() + "；请修复或删除该文件后重启服务");
        }
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[模型设置] 已保存到 {}：对话模型={}，向量模型={}",
                    settingsFile.toAbsolutePath(), settings.getChatModelName(),
                    settings.getEmbeddingModelName());
        } catch (IOException e) {
            throw new IllegalStateException("模型设置保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 设置文件路径（供管控台展示，便于运维确认落盘位置） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }
}
