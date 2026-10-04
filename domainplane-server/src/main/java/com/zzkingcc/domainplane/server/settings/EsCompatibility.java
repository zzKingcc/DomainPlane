package com.zzkingcc.domainplane.server.settings;

/**
 * Elasticsearch 服务端版本 / 发行版的判读
 * @author zzkingcc
 */
public final class EsCompatibility {

    private EsCompatibility() {
    }

    /** 判读档位，从"已验证"到"不承诺" */
    public enum Level {
        /** 与内置客户端同主版本，官方矩阵保证 */
        VERIFIED,
        /** 所需能力具备，但无官方背书、未实测 */
        LIKELY,
        /** 已知会失败或不可靠，不建议使用 */
        DOUBTFUL,
        /** 缺少平台必需能力 */
        UNSUPPORTED,
        /** 读不到版本，无法判断（不算问题） */
        UNKNOWN
    }

    /**
     * 判读结论。
     *
     * @param level 档位（前端据此上色）
     * @param label 一行短标签（chip 用）
     * @param note  判读依据：为什么是这个档位
     * @param hint  额外注意事项（可空）：与档位无关、但会挡人的环境差异
     */
    public record Verdict(Level level, String label, String note, String hint) {

        /** 前端 chip 的样式键：ok / maybe / risky / no / unknown */
        public String tone() {
            return switch (level) {
                case VERIFIED -> "ok";
                case LIKELY -> "maybe";
                case DOUBTFUL -> "risky";
                case UNSUPPORTED -> "no";
                case UNKNOWN -> "unknown";
            };
        }
    }

    /**
     * 判读一个 ES 实例。
     *
     * @param versionNumber {@code GET /} 的 {@code version.number}，如 {@code 8.15.3}；可为 null
     * @param buildFlavor   {@code version.build_flavor}，{@code default} 或 {@code oss}；可为 null
     * @param distribution  {@code version.distribution}，OpenSearch 会给出 {@code opensearch}；可为 null
     */
    public static Verdict judge(String versionNumber, String buildFlavor, String distribution) {
        if (distribution != null && distribution.toLowerCase().contains("opensearch")) {
            return new Verdict(Level.UNSUPPORTED, "OpenSearch · 不承诺",
                    "这是 OpenSearch，不是 Elasticsearch。两者 API 与语义已分叉，"
                            + "本平台不保证可用，请改用 Elasticsearch。",
                    null);
        }

        String ossHint = isOss(buildFlavor)
                ? "该实例是 OSS 发行版（不含 X-Pack）：用户名/密码认证不可用；"
                + "IK 分词器也需要自行安装。"
                : null;

        int[] v = parse(versionNumber);
        if (v == null) {
            return new Verdict(Level.UNKNOWN, "版本未知",
                    "没能读到版本号 —— 通常是该账号缺少 cluster monitor 权限，或该实例不返回该字段。"
                            + "这不影响使用，只是平台无法给出兼容性判断。",
                    ossHint);
        }

        int major = v[0];
        int minor = v[1];

        if (major >= 9) {
            return new Verdict(Level.VERIFIED, major + ".x · 已验证",
                    "与平台内置客户端（9.4.4）同主版本，官方兼容矩阵保证。",
                    ossHint);
        }
        if (major == 8) {
            return new Verdict(Level.LIKELY, "8.x · 大概率可用（未验证）",
                    "平台用到的接口（info / ping / count / search / indices.* / bulk，"
                            + "以及 index: true 的 dense_vector）在 8.x 全部具备，"
                            + "但官方矩阵只保证同主版本，8.x 未经实测。"
                            + "请以「测试连接」与一次知识库重建的实际结果为准。",
                    "ES 8.x 起默认启用 HTTPS 与安全认证：协议要填 https，且必须带账号；"
                            + "若对方用自签证书，会报 TLS 握手失败（当前不支持导入自签证书）。" + suffix(ossHint));
        }
        if (major == 7 && minor >= 17) {
            return new Verdict(Level.DOUBTFUL, "7.17 · 存疑",
                    "7.x 的 dense_vector 不支持 index: true（近似 kNN 是 8.0 起的特性），"
                            + "维度上限也只有 1024 —— 平台的索引 mapping 会创建失败，"
                            + "随后回退到默认创建，中文检索质量也会下降。",
                    ossHint);
        }
        if (major >= 1 && major <= 7) {
            return new Verdict(Level.UNSUPPORTED, major + ".x · 不承诺",
                    "低于 7.17 的 Elasticsearch 缺少平台依赖的能力（近似 kNN、"
                            + "按需的 dense_vector 维度上限等），请不要使用。",
                    ossHint);
        }
        return new Verdict(Level.UNSUPPORTED, "无法识别 · 不承诺",
                "读到的版本号（" + versionNumber + "）不对应任何受支持的 Elasticsearch 版本，"
                        + "可能是 OpenSearch、云厂商分支或代理层注入的假值。",
                ossHint);
    }

    /** 解析 {@code major.minor[.patch]}；解析不出来返回 null */
    private static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isOss(String buildFlavor) {
        return buildFlavor != null && "oss".equalsIgnoreCase(buildFlavor.trim());
    }

    private static String suffix(String hint) {
        return hint == null ? "" : " " + hint;
    }
}
