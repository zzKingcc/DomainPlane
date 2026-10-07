package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 基础设施存储配置（ES / Redis，管控台可编辑）
 * @author zzkingcc
 */
@Data
public class InfraSettings {

    /** Elasticsearch 连接 */
    private Elasticsearch es = new Elasticsearch();

    /** Redis 连接 */
    private Redis redis = new Redis();

    /**
     * Elasticsearch 连接参数。
     *
     * 只留"连接身份"与超时；其余（索引名、分词、灌库开关）仍属平台行为配置，留在 yaml。
     */
    @Data
    public static class Elasticsearch {

        /** 主机地址（IP 或域名）。为空即视为"未配置" */
        private String host;

        /** HTTP 端口（留空用 9200） */
        private Integer port;

        /** 协议（http / https，留空用 http） */
        private String scheme;

        /** 用户名，未启用安全认证时可空 */
        private String username;

        /** 密码，未启用安全认证时可空 */
        private String password;

        /** 连接超时（毫秒，留空用 5000） */
        private Integer connectTimeout;

        /** 读写超时（毫秒，留空用 10000） */
        private Integer socketTimeout;

        /** 是否已配置（只判主机，端口/协议都有默认值） */
        @JsonIgnore
        public boolean isUsable() {
            return hasText(host);
        }

        @JsonIgnore
        public int effectivePort() {
            return port != null && port > 0 ? port : 9200;
        }

        @JsonIgnore
        public String effectiveScheme() {
            return hasText(scheme) ? scheme.trim() : "http";
        }

        @JsonIgnore
        public int effectiveConnectTimeout() {
            return connectTimeout != null && connectTimeout > 0 ? connectTimeout : 5000;
        }

        @JsonIgnore
        public int effectiveSocketTimeout() {
            return socketTimeout != null && socketTimeout > 0 ? socketTimeout : 10000;
        }

        /** 展示用掩码（不落盘、不出接口的明文形式） */
        @JsonIgnore
        public String getMaskedPassword() {
            return mask(password);
        }

        @JsonIgnore
        public String describe() {
            return isUsable() ? effectiveScheme() + "://" + host + ":" + effectivePort() : "未配置";
        }

        /**
         * 形态校验：只判断"填得像不像"，不测连通性
         */
        @JsonIgnore
        public List<String> validate() {
            List<String> problems = new ArrayList<>();
            if (!isUsable()) {
                return problems;
            }
            String scheme = effectiveScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                problems.add("协议只支持 http / https，当前为「" + scheme + "」");
            }
            if (port != null && (port < 1 || port > 65535)) {
                problems.add("端口需在 1~65535，当前为「" + port + "」");
            }
            if (looksLikeUrl(host)) {
                problems.add("主机地址只填主机名或 IP，不要带 http:// 前缀或路径");
            } else if (hasEmbeddedPort(host)) {
                problems.add("主机地址里不要带端口，端口请填在端口字段");
            }
            return problems;
        }
    }

    /**
     * Redis 连接参数。
     */
    @Data
    public static class Redis {

        /** 主机地址（IP 或域名）。为空即视为"未配置" */
        private String host;

        /** 端口（留空用 6379） */
        private Integer port;

        /** 密码，未设置则为空（无密码模式） */
        private String password;

        /**
         * 库号，留空用 0
         */
        private Integer database;

        @JsonIgnore
        public boolean isUsable() {
            return hasText(host);
        }

        @JsonIgnore
        public int effectivePort() {
            return port != null && port > 0 ? port : 6379;
        }

        @JsonIgnore
        public int effectiveDatabase() {
            return database != null && database >= 0 ? database : 0;
        }

        @JsonIgnore
        public String getMaskedPassword() {
            return mask(password);
        }

        @JsonIgnore
        public String describe() {
            return isUsable() ? host + ":" + effectivePort() + "/db" + effectiveDatabase() : "未配置";
        }

        /**
         * 形态校验：只判断"填得像不像"，不测连通性
         */
        @JsonIgnore
        public List<String> validate() {
            List<String> problems = new ArrayList<>();
            if (!isUsable()) {
                return problems;
            }
            if (port != null && (port < 1 || port > 65535)) {
                problems.add("端口需在 1~65535，当前为「" + port + "」");
            }
            if (database != null && database < 0) {
                problems.add("库号不能为负数，当前为「" + database + "」");
            }
            if (looksLikeUrl(host)) {
                problems.add("主机地址只填主机名或 IP，不要带 redis:// 前缀或路径");
            } else if (hasEmbeddedPort(host)) {
                problems.add("主机地址里不要带端口，端口请填在端口字段");
            }
            return problems;
        }
    }

    // ===== 派生值（顶层快捷方式，便于调用方少写一层） =====

    @JsonIgnore
    public boolean isEsUsable() {
        return es != null && es.isUsable();
    }

    @JsonIgnore
    public boolean isRedisUsable() {
        return redis != null && redis.isUsable();
    }

    /**
     * 汇总两个中间件的形态问题；空列表表示"没发现问题"
     */
    @JsonIgnore
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (es != null) {
            problems.addAll(es.validate());
        }
        if (redis != null) {
            problems.addAll(redis.validate());
        }
        return problems;
    }

    /** 主机字段里混进了完整 URL（带协议头或路径） */
    private static boolean looksLikeUrl(String host) {
        return host != null && (host.contains("://") || host.contains("/"));
    }

    /** 主机字段里混进了端口：含单个冒号，且不是 IPv6 字面量（{@code [::1]}） */
    private static boolean hasEmbeddedPort(String host) {
        return host != null
                && host.startsWith("[") == false
                && host.indexOf(':') > 0
                && host.indexOf(':') == host.lastIndexOf(':');
    }

    private static String mask(String secret) {
        if (!hasText(secret)) {
            return null;
        }
        String s = secret.trim();
        if (s.length() <= 4) {
            return "******";
        }
        return s.substring(0, 2) + "******" + s.substring(s.length() - 2);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
