package com.zzkingcc.domainplane.server.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 工具回流地址（{@code endpoint}）的准入策略。
 *
 * <p>这个地址由工具实例在心跳里上报，服务端随后主动 POST 过去并把响应回喂模型。
 * 也就是说：<b>上报方决定服务端把请求发到哪里</b>。上报接口本身要凭证，但凭证一旦
 * 泄露（或仍是种子默认口令），这条链路就变成一个"由调用方指定目标"的出站请求原语。</p>
 *
 * <p>策略刻意<b>不</b>禁止内网与回环地址：工具实例本来就跑在内网、常常与服务端同机，
 * 一刀切会让产品的正常用法失效。真正要拦住的是"没有任何业务理由"的那几类：</p>
 *
 * <ul>
 *   <li>非 http/https scheme（{@code file:}、{@code gopher:} 之类）；</li>
 *   <li>带 {@code user:pass@} 的地址（借主机名解析做混淆）；</li>
 *   <li>通配地址 {@code 0.0.0.0} / {@code ::}（多数系统上等价于连本机）；</li>
 *   <li>链路本地地址（{@code 169.254.0.0/16}、{@code fe80::/10}）与云元数据别名——
 *       这是 SSRF 最有价值的目标：一个 POST 就能读走实例凭证；</li>
 *   <li>配置了 {@code domainplane.tool.endpoint.allowed-hosts} 时，不在白名单里的主机。</li>
 * </ul>
 *
 * <p>要"只能连指定几台实例"的部署，把白名单配上即可（支持精确主机与 {@code *.suffix}）。
 * 白名单为空是默认值，语义是"不限主机，仅按上面的规则兜底"。</p>
 *
 * <p><b>不做 DNS 解析</b>：本方法在每次心跳与每次工具调用的线程上执行，阻塞解析会拖慢热路径，
 * 而且解析结果与真正连接时的结果可能不同（DNS rebinding）。要更强约束请用白名单。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ToolEndpointPolicy {

    /**
     * 云元数据服务的主机名别名：任何业务工具都不该把请求打到这些名字上。
     */
    private static final List<String> METADATA_ALIASES = List.of(
            "metadata", "metadata.google.internal", "instance-data", "metadata.azure.com");

    /**
     * 云元数据的固定 IP：169.254.169.254 已被链路本地规则覆盖，这里补两家跑在"非链路本地"段的。
     */
    private static final List<String> METADATA_IPS = List.of(
            "100.100.100.200",   // 阿里云
            "fd00:ec2::254");    // AWS IPv6 IMDS

    /** 主机白名单（小写、已去空白）；空 = 不限主机 */
    private final List<String> allowedHosts;

    public ToolEndpointPolicy() {
        this("");
    }

    @Autowired
    public ToolEndpointPolicy(@Value("${domainplane.tool.endpoint.allowed-hosts:}") String allowedHostsCsv) {
        List<String> parsed = new ArrayList<>();
        if (allowedHostsCsv != null) {
            for (String piece : allowedHostsCsv.split(",")) {
                String host = piece.trim().toLowerCase(Locale.ROOT);
                if (host.isEmpty()) {
                    continue;
                }
                if (!host.startsWith("*.") && !host.matches("[a-z0-9._:\\[\\]-]+")) {
                    log.warn("[工具地址] 白名单条目 '{}' 看起来不是主机名，仍按字面量匹配", host);
                }
                parsed.add(host);
            }
        }
        this.allowedHosts = List.copyOf(parsed);
        if (this.allowedHosts.isEmpty()) {
            log.info("[工具地址] 未配置 domainplane.tool.endpoint.allowed-hosts：回流地址允许内网与回环"
                    + "（工具实例通常就在内网），仅拦截非法 scheme / 通配地址 / 链路本地与云元数据");
        } else {
            log.info("[工具地址] 回流地址白名单 {} 项：{}", this.allowedHosts.size(), this.allowedHosts);
        }
    }

    /**
     * 校验地址，不通过直接抛 {@link IllegalArgumentException}。
     *
     * <p>注册入口用它：异常由 {@code ToolRegistrationController} 统一翻成 400，
     * 与"manifest 不合法"走同一条出口。</p>
     */
    public void requireAllowed(String endpoint) {
        String reason = rejectReason(endpoint);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
    }

    /**
     * @return 拒绝原因（给运维看的一句话）；{@code null} = 通过
     */
    public String rejectReason(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "缺少 endpoint（工具调用回流地址）";
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            return "endpoint 不是合法 URL：" + e.getMessage();
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            return "endpoint 必须带 scheme（形如 http://10.0.0.5:8081/domainplane/invoke）";
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "endpoint 只允许 http/https，实际是 " + scheme;
        }
        if (uri.getUserInfo() != null) {
            return "endpoint 不允许携带 user:pass 信息（会掩盖真实目标主机）";
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "endpoint 缺少主机名";
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            return "endpoint 端口非法：" + port;
        }

        String normalized = normalize(host);
        if ("0.0.0.0".equals(normalized) || "::".equals(normalized)) {
            return "endpoint 指向通配地址（0.0.0.0 / ::），实际连到的是本机";
        }
        if (isLinkLocal(normalized)) {
            return "endpoint 指向链路本地地址（169.254.0.0/16 或 fe80::/10）：云元数据端点在此段内";
        }
        if (METADATA_IPS.contains(normalized) || METADATA_ALIASES.contains(normalized)) {
            return "endpoint 指向云元数据服务（" + normalized + "）";
        }
        if (!allowedHosts.isEmpty() && !allowed(normalized)) {
            return "endpoint 主机 " + normalized
                    + " 不在 domainplane.tool.endpoint.allowed-hosts 白名单内";
        }
        return null;
    }

    /** 是否受白名单约束（供配置自检与日志用） */
    public boolean hasAllowlist() {
        return !allowedHosts.isEmpty();
    }

    /** 当前白名单快照（只读） */
    public List<String> allowedHosts() {
        return allowedHosts;
    }

    // ==================== 内部 ====================

    private boolean allowed(String host) {
        for (String pattern : allowedHosts) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);   // ".example.com"
                if (host.endsWith(suffix)) {
                    return true;
                }
            } else if (pattern.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** 去方括号、小写；IPv6 的 {@code [::1]} 与 {@code ::1} 归一成同一个字面量 */
    private static String normalize(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }

    /**
     * 链路本地：IPv4 的 {@code 169.254.0.0/16}（含 169.254.169.254 元数据）与
     * IPv6 的 {@code fe80::/10}。IPv4-mapped 形式（{@code ::ffff:169.254.x.x}）一并按前缀判。
     */
    private static boolean isLinkLocal(String host) {
        return host.startsWith("169.254.")
                || host.startsWith("fe80:")
                || host.startsWith("::ffff:169.254.");
    }
}
