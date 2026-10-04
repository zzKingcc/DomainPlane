package com.zzkingcc.domainplane.server.env;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储目录的单一真相源：统一解析"受保护资源"、"日志"与"切片导出"三个目录的最终路径
 */
@Slf4j
@Component
public class StorageLocations {

    private final Path settingsDir;
    private final Path logDir;
    private final Path exportDir;
    private final String settingsSource;
    private final String logSource;
    private final String exportSource;

    public StorageLocations(Environment env) {
        String settingsRaw = env.getProperty("domainplane.settings.path", RuntimeEnvironment.defaultSettingsPath());
        String logRaw = env.getProperty("domainplane.logging.path", RuntimeEnvironment.defaultLogPath());
        String exportRaw = env.getProperty("domainplane.export.path", RuntimeEnvironment.defaultExportPath());
        this.settingsDir = Paths.get(settingsRaw);
        this.logDir = Paths.get(logRaw);
        this.exportDir = Paths.get(exportRaw);
        this.settingsSource = sourceOf("domainplane.settings.path", "DOMAINPLANE_SETTINGS_PATH",
                settingsDir, RuntimeEnvironment.defaultSettingsPath());
        this.logSource = sourceOf("domainplane.logging.path", "DOMAINPLANE_LOG_PATH",
                logDir, RuntimeEnvironment.defaultLogPath());
        this.exportSource = sourceOf("domainplane.export.path", "DOMAINPLANE_EXPORT_PATH",
                exportDir, RuntimeEnvironment.defaultExportPath());
        ensureDirectory(settingsDir, "配置");
        ensureDirectory(logDir, "日志");
        ensureDirectory(exportDir, "切片导出");
    }

    /** 受保护资源目录：accounts / llm-settings / infra-settings / prompts 都落在这里 */
    public Path settingsDir() {
        return settingsDir;
    }

    /** 文件形态日志目录 */
    public Path logDir() {
        return logDir;
    }

    /** 切片预览 txt 的落盘目录（管控台只展示路径，不提供在线查看） */
    public Path exportDir() {
        return exportDir;
    }

    /** 横幅用：配置目录来自平台默认还是显式覆盖 */
    public String settingsSource() {
        return settingsSource;
    }

    /** 横幅用：日志目录来自平台默认还是显式覆盖 */
    public String logSource() {
        return logSource;
    }

    /** 横幅用：切片导出目录来自平台默认还是显式覆盖 */
    public String exportSource() {
        return exportSource;
    }

    private static void ensureDirectory(Path dir, String what) {
        try {
            Files.createDirectories(dir);
            log.info("[存储目录] {}目录已就绪: {}", what, dir.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[存储目录] 无法创建{}目录（服务继续，但落盘可能失败）: {} - {}",
                    what, dir.toAbsolutePath(), e.getMessage());
        }
    }

    /** 判定某个目录是平台默认还是被显式指定（用于横幅标注来源） */
    private static String sourceOf(String key, String envName, Path resolved, String platformDefault) {
        if (System.getenv(envName) != null) {
            return "环境变量 " + envName;
        }
        String prop = safeProperty(key);
        if (prop != null && !prop.equals(platformDefault)) {
            return "显式指定（-D" + key + " / --" + key + "）";
        }
        return "平台默认";
    }

    private static String safeProperty(String key) {
        try {
            return System.getProperty(key);
        } catch (SecurityException e) {
            return null;
        }
    }
}
