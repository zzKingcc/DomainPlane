package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzkingcc.domainplane.common.util.AtomicFiles;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置工具文案覆盖的持久化（{@code config/tool-text.json}）。
 *
 * <p>只存「改了哪几个字」，不存整份工具规格：规格的其它字段由注解和注册表负责，
 * 存下来就会在注解升级后变成一份过时副本。</p>
 *
 * <p>{@code baseline} 是保存那一刻的注解原文快照，用来检测「上游说明已变化」。
 * 它不是生效值 —— 生效值永远由基线 + 覆盖实时算出。</p>
 */
@Component
public class ToolTextStore {

    private static final Logger log = LoggerFactory.getLogger(ToolTextStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    /** 最近一次读盘失败时拒绝写入：把坏文件"修"成空文件会永久丢掉用户写的文案 */
    private volatile boolean loadFailed;

    @Autowired
    public ToolTextStore(StorageLocations storage) {
        this(storage.settingsDir().resolve("tool-text.json"));
    }

    /** 测试与离线工具直接指定文件路径 */
    public ToolTextStore(Path file) {
        this.file = file;
    }

    /** 某个工具的全部覆盖项；空白值在保存口已被过滤掉，这里不该出现 */
    public record Entry(String description,
                        Map<String, String> paramDescriptions,
                        Baseline baseline) {
        public Entry {
            paramDescriptions = paramDescriptions == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(paramDescriptions);
        }
    }

    /** 注解原文快照 */
    public record Baseline(String description, Map<String, String> paramDescriptions) {
        public Baseline {
            paramDescriptions = paramDescriptions == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(paramDescriptions);
        }
    }

    public record ToolTextFile(Map<String, Entry> tools) {
        public ToolTextFile() {
            this(new LinkedHashMap<>());
        }

        public ToolTextFile {
            tools = tools == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tools);
        }
    }

    /** 读盘；文件不存在或解析失败时返回空覆盖（不阻断启动），同时禁止写入 */
    public ToolTextFile load() {
        if (!Files.exists(file)) {
            loadFailed = false;
            return new ToolTextFile();
        }
        try {
            ToolTextFile data = MAPPER.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), ToolTextFile.class);
            if (data == null) {
                loadFailed = true;
                log.error("[工具文案] {} 解析结果为 null，以空覆盖继续（已禁止写入）", file.toAbsolutePath());
                return new ToolTextFile();
            }
            loadFailed = false;
            return data;
        } catch (IOException e) {
            loadFailed = true;
            log.error("[工具文案] 读取 {} 失败，以空覆盖继续（已禁止写入）: {}",
                    file.toAbsolutePath(), e.getMessage());
            return new ToolTextFile();
        }
    }

    /**
     * 整份覆盖写 + 原子替换。
     *
     * <p>{@code synchronized}：保存口是「读 → 改一个工具 → 整份写回」。两次保存并发时，
     * 后写的那份会把先写的结果盖掉，全程无报错。合并逻辑放在这里而不是调用方，
     * 是为了让「读改写」三步在同一把锁内完成。</p>
     *
     * @param entry {@code null} = 删除该工具的全部覆盖（恢复默认）
     */
    public synchronized void update(String name, Entry entry) {
        ToolTextFile data = load();
        if (entry == null) {
            data.tools().remove(name);
        } else {
            data.tools().put(name, entry);
        }
        save(data);
        log.info("[工具文案] {} {} 覆盖", name, entry == null ? "已清除" : "已保存");
    }

    private void save(ToolTextFile data) {
        if (loadFailed) {
            throw new IllegalStateException("工具文案文件无法解析，拒绝覆盖写入以免清空既有配置："
                    + file.toAbsolutePath() + "；请修复或删除该文件后重启服务");
        }
        try {
            AtomicFiles.write(file, MAPPER.writeValueAsBytes(data));
        } catch (IOException e) {
            throw new IllegalStateException("工具文案保存失败：" + file.toAbsolutePath(), e);
        }
    }

    public String filePath() {
        return file.toAbsolutePath().toString();
    }
}
