package com.zzkingcc.domainplane.server.model;

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
 * 模型档案的持久化存储（{@code config/models.json}）。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ModelProfileStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    /**
     * 最近一次读盘是否失败（解析出错或结果为 null）。
     *
     * 失败时 {@link #load()} 返回空档案，若此时照常 {@link #save} 覆盖写，等于把坏文件
     * "修"成空文件，既有档案与域绑定永久消失。故置位后拒绝任何写入。
     */
    private volatile boolean loadFailed;

    public ModelProfileStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("models.json");
    }

    /**
     * 读取档案配置；文件不存在或解析失败时返回空配置（不阻断启动）。
     */
    public ModelProfileSettings load() {
        if (!Files.exists(settingsFile)) {
            log.debug("[模型档案] 未找到 {}，按「未配置任何档案」处理（域需显式配置模型，否则为无可调用）",
                    settingsFile.toAbsolutePath());
            loadFailed = false;
            return new ModelProfileSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            ModelProfileSettings settings = MAPPER.readValue(json, ModelProfileSettings.class);
            if (settings == null) {
                loadFailed = true;
                log.error("[模型档案] {} 的解析结果为 null，按空档案继续（已禁止写入，避免覆盖清空）",
                        settingsFile.toAbsolutePath());
                return new ModelProfileSettings();
            }
            loadFailed = false;
            /* 旧布局把纯向量档案单独放在 embeddingProfiles 里。读盘时合回唯一一张表，
               并把新布局写回文件，否则"合桶了没有"在文件里看不出来。
               落盘失败只告警：启动不该因为一次迁移写盘失败而失败（内存里已经合好了）。 */
            boolean mutated = settings.mergeLegacyProfiles();
            if (mutated) {
                log.info("[模型档案] 已把旧 embeddingProfiles 里的档案合回 profiles：当前共 {} 个",
                        settings.getProfiles().size());
            }
            /* 对话 schema 下架后，注入前的 key→wire 翻译没了，存量键必须就地归一，
               否则 reasoningEffort 会被原样发给上游、被静默忽略（见 ChatParamMigration）。 */
            if (settings.normalizeChatParams()) {
                log.info("[模型档案] 已把存量对话档案的 params 键归一为线上字段名"
                        + "（如 reasoning_effort / top_p），适配 schema 下架后不再有注入前的翻译步骤");
                mutated = true;
            }
            if (mutated) {
                try {
                    save(settings);
                } catch (Exception e) {
                    log.warn("[模型档案] 归一后的布局落盘失败（内存已生效，下次写入会重试）: {}", e.getMessage());
                }
            }
            return settings;
        } catch (IOException e) {
            loadFailed = true;
            log.error("[模型档案] 读取 {} 失败，按空档案继续（已禁止写入，可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e.getMessage());
            return new ModelProfileSettings();
        }
    }

    /**
     * 保存（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败，必须让调用方感知，静默失败会让用户以为已生效
     */
    public void save(ModelProfileSettings settings) {
        if (loadFailed) {
            throw new IllegalStateException("模型档案文件无法解析，拒绝覆盖写入以免把既有配置清空："
                    + settingsFile.toAbsolutePath() + "；请修复或删除该文件后重启服务");
        }
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[模型档案] 已保存：档案 {} 个、域绑定 {} 条、向量槽位 {}",
                    settings.getProfiles().size(),
                    settings.getDomainBindings().size(),
                    settings.getEmbeddingAlias() == null ? "未配置" : settings.getEmbeddingAlias());
        } catch (IOException e) {
            throw new IllegalStateException("模型档案保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }
}
