package com.zzkingcc.domainplane.server.env;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 运行环境探测：启动时据此决定平台默认的存储目录，并打印到横幅。
 */
public final class RuntimeEnvironment {

    /** 操作系统族；决定默认存储目录落在哪 */
    public enum OsFamily { LINUX, WINDOWS, MACOS, OTHER }

    private RuntimeEnvironment() {
    }

    /** 当前操作系统族；无法判定时回落 OTHER（等同按 Linux 处理） */
    public static OsFamily osFamily() {
        String lower = safeProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return OsFamily.WINDOWS;
        }
        if (lower.contains("mac") || lower.contains("darwin") || lower.contains("os x")) {
            return OsFamily.MACOS;
        }
        if (lower.contains("nux") || lower.contains("nix") || lower.contains("sunos") || lower.contains("freebsd")) {
            return OsFamily.LINUX;
        }
        return OsFamily.OTHER;
    }

    /** 受保护资源（账号 / 模型 / 存储 / 域提示词）的默认落盘目录，随运行系统变化 */
    public static String defaultSettingsPath() {
        return switch (osFamily()) {
            case WINDOWS -> programData() + "\\DomainPlane\\config";
            case MACOS -> "/Library/Application Support/DomainPlane/config";
            case LINUX, OTHER -> "/var/lib/domainplane/config";
        };
    }

    /** 文件形态日志的默认目录，随运行系统变化 */
    public static String defaultLogPath() {
        return switch (osFamily()) {
            case WINDOWS -> programData() + "\\DomainPlane\\logs";
            case MACOS -> "/Library/Logs/DomainPlane";
            case LINUX, OTHER -> "/var/log/domainplane";
        };
    }

    /** 切片预览文件的默认目录（与配置/日志同级），随运行系统变化 */
    public static String defaultExportPath() {
        return switch (osFamily()) {
            case WINDOWS -> programData() + "\\DomainPlane\\chunks";
            case MACOS -> "/Library/Application Support/DomainPlane/chunks";
            case LINUX, OTHER -> "/var/lib/domainplane/chunks";
        };
    }

    /** 横幅用的一行环境摘要：操作系统 / 架构 / JDK 版本与厂商 */
    public static String describe() {
        return safeProperty("os.name", "unknown") + " / " + safeProperty("os.arch", "unknown")
                + ", JDK " + safeProperty("java.version", "unknown")
                + " (" + safeProperty("java.vendor", "unknown") + ")";
    }

    /** 容器运行时：kubernetes / docker / podman / none；探测失败回落 none（按裸机/VM 处理） */
    public static String containerRuntime() {
        return detectContainer();
    }

    /** 是否运行在容器内（排障时区分"容器挂载卷"与"裸机目录"） */
    public static boolean isContainer() {
        return !"none".equals(containerRuntime());
    }

    private static String detectContainer() {
        try {
            if (safeEnv("KUBERNETES_SERVICE_HOST") != null) {
                return "kubernetes";
            }
        } catch (SecurityException ignored) {
            // 环境变量读不到就按裸机处理
        }
        if (exists("/.dockerenv")) {
            return "docker";
        }
        if (exists("/.containerenv")) {
            return "podman";
        }
        try {
            Path cgroup = Path.of("/proc/1/cgroup");
            if (Files.exists(cgroup)) {
                String content = Files.readString(cgroup);
                if (content.contains("kubepods")) {
                    return "kubernetes";
                }
                if (content.contains("docker")) {
                    return "docker";
                }
                if (content.contains("podman")) {
                    return "podman";
                }
            }
        } catch (IOException | SecurityException ignored) {
            // 非 Linux 或无权限读 cgroup：视为裸机
        }
        return "none";
    }

    private static boolean exists(String path) {
        try {
            return Files.exists(Path.of(path));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Windows 上转到系统级 ProgramData；取不到时回落 C:\\ProgramData */
    private static String programData() {
        String p = safeEnv("ProgramData");
        return (p == null || p.isBlank()) ? "C:\\ProgramData" : p;
    }

    private static String safeProperty(String key, String fallback) {
        try {
            String v = System.getProperty(key);
            return v == null ? fallback : v;
        } catch (SecurityException e) {
            return fallback;
        }
    }

    private static String safeEnv(String key) {
        try {
            return System.getenv(key);
        } catch (SecurityException e) {
            return null;
        }
    }
}
