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
            return new ModelProfileSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            ModelProfileSettings settings = MAPPER.readValue(json, ModelProfileSettings.class);
            if (settings == null) {
                return new ModelProfileSettings();
            }
            /* 旧布局把纯向量档案也放在 profiles 里，只靠 endpoints 区分。读盘时归一，
               并把新布局写回文件 —— 否则"分开了没有"在文件里看不出来。
               落盘失败只告警：启动不该因为一次迁移写盘失败而失败（内存里已经分好了）。 */
            if (settings.splitVectorProfiles()) {
                log.info("[模型档案] 已将 {} 个纯向量档案从 profiles 分离到 embeddingProfiles：{}",
                        settings.getEmbeddingProfiles().size(), settings.getEmbeddingProfiles().keySet());
                try {
                    save(settings);
                } catch (Exception e) {
                    log.warn("[模型档案] 分离后的布局落盘失败（内存已生效，下次写入会重试）: {}", e.getMessage());
                }
            }
            return settings;
        } catch (IOException e) {
            log.error("[模型档案] 读取 {} 失败，按空档案继续（可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e.getMessage());
            return new ModelProfileSettings();
        }
    }

    /**
     * 保存（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败 —— 必须让调用方感知，静默失败会让用户以为已生效
     */
    public void save(ModelProfileSettings settings) {
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[模型档案] 已保存：模型档案 {} 个、向量档案 {} 个、域绑定 {} 条",
                    settings.getProfiles().size(),
                    settings.getEmbeddingProfiles().size(),
                    settings.getDomainBindings().size());
        } catch (IOException e) {
            throw new IllegalStateException("模型档案保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }
}
