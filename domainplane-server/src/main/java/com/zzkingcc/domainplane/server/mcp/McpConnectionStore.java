package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.common.util.AtomicFiles;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MCP 连接库（{@code config/mcp-connections.json}）—— 照 {@code ProviderConnectionStore} 的口径。
 *
 * 为什么"连接"与"域绑定"同文件：两者不一致就是脏状态（绑了一个不存在的连接），拆两个文件
 * 就要在两个 store 之间做一致性。结构：{@code { connections: [...], domainBindings: { "<域>": [连接名] } }}。
 *
 * 为什么绑定是"域维度"（域 → [连接]）而非"连接维度"（连接 → [域]）：与
 * {@code ModelProfileRegistry.domainBindings} 同构，删域级联天然可用（删域只需从这张 map 里
 * 摘掉该域的条目，连接本身不被删）。
 *
 * 凭据（headers 里的认证值）明文落盘，仅出口脱敏，与既有连接资源同口径；是否加密留作后续专项。
 *
 * @author zzkingcc
 */
@Component
public class McpConnectionStore {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final int MAX_NAME = 64;
    private static final int MAX_URL = 512;

    private final Path settingsFile;
    private volatile McpConnection.FileShape state;

    /**
     * 最近一次读盘是否解析失败。
     *
     * 为 {@code true} 时内存里是空态，此时任何"全量覆盖写"都会把坏文件真正改写成空文件，
     * 既有连接与域绑定因此永久消失。故置位后一律拒绝写入，等人工修复或删除该文件。
     */
    private volatile boolean loadFailed;

    public McpConnectionStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("mcp-connections.json");
        this.state = load();
        log.info("[MCP 连接库] 初始化：已登记 {} 条连接、{} 条域绑定（落盘 {}）",
                state.connections().size(), state.domainBindings().size(), settingsFile.toAbsolutePath());
    }

    // ==================== 读 ====================

    /** 全部连接（不可变副本） */
    public List<McpConnection> list() {
        return List.copyOf(state.connections());
    }

    /** 按名取连接（含完整 headers 明文） */
    public McpConnection get(String name) {
        String key = normalize(name);
        return state.connections().stream()
                .filter(c -> c.name().equals(key))
                .findFirst()
                .orElse(null);
    }

    /** 存在性 */
    public boolean exists(String name) {
        return get(name) != null;
    }

    /** 某连接当前绑定的域列表（来自 domainBindings 的反查） */
    public List<String> boundDomainsOf(String connectionName) {
        String key = normalize(connectionName);
        List<String> domains = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : state.domainBindings().entrySet()) {
            if (entry.getValue().contains(key)) {
                domains.add(entry.getKey());
            }
        }
        return domains;
    }

    /** 某域直接绑定的连接名列表（不含祖先继承） */
    public List<String> connectionsOfDomain(String domain) {
        String key = Domains.normalize(domain);
        return List.copyOf(state.domainBindings().getOrDefault(key, List.of()));
    }

    /** 全部连接名 */
    public Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        for (McpConnection c : state.connections()) {
            out.add(c.name());
        }
        return out;
    }

    /** 全量域绑定映射（域 → 直接绑定的连接名列表）；管控台展示用，副本返回 */
    public Map<String, List<String>> allDomainBindings() {
        return new LinkedHashMap<>(state.domainBindings());
    }

    // ==================== 写（整体替换 + 原子落盘） ====================

    /**
     * 新增或更新一条连接（同名即更新；不改动其绑定关系）。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String upsert(McpConnection connection) {
        String key = normalize(connection.name());
        if (key.isEmpty()) {
            return "连接名不能为空";
        }
        String nameFailure = McpConnection.validateName(key);
        if (nameFailure != null) {
            return nameFailure;
        }
        String urlFailure = checkBaseUrl(connection.url());
        if (urlFailure != null) {
            return urlFailure;
        }
        List<McpConnection> next = new ArrayList<>(state.connections().size() + 1);
        boolean replaced = false;
        for (McpConnection existing : state.connections()) {
            if (existing.name().equals(key)) {
                next.add(new McpConnection(key, connection.url(), connection.transport(), connection.headers(),
                        connection.callTimeoutMs(), connection.enabled(), connection.approvalPolicy(),
                        connection.toolAllowlist(), connection.remark(), connection.oauth()));
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(connection);
        }
        persist(new McpConnection.FileShape(next, state.domainBindings(), state.oauthTokens()));
        log.info("[MCP 连接库] 已{}连接「{}」→ {}（{}）", replaced ? "更新" : "新增", key,
                connection.url(), connection.enabled() ? "启用" : "禁用");
        return null;
    }

    /** 切换启用开关（不删配置，仅停止/恢复连接） */
    public synchronized String setEnabled(String name, boolean enabled) {
        String key = normalize(name);
        McpConnection existing = get(key);
        if (existing == null) {
            return "MCP 连接「" + key + "」不存在";
        }
        return upsert(new McpConnection(existing.name(), existing.url(), existing.transport(), existing.headers(),
                existing.callTimeoutMs(), enabled, existing.approvalPolicy(), existing.toolAllowlist(),
                existing.remark(), existing.oauth()));
    }

    /**
     * 删除连接：同时从其所有绑定关系里清掉该连接名（解绑，不删域）。
     *
     * @return 是否真的删掉了
     */
    public synchronized boolean delete(String name) {
        String key = normalize(name);
        List<McpConnection> nextConn = new ArrayList<>();
        boolean removed = false;
        for (McpConnection c : state.connections()) {
            if (c.name().equals(key)) {
                removed = true;
            } else {
                nextConn.add(c);
            }
        }
        if (!removed) {
            return false;
        }
        Map<String, List<String>> nextBind = new LinkedHashMap<>(state.domainBindings());
        for (Map.Entry<String, List<String>> entry : nextBind.entrySet()) {
            List<String> kept = new ArrayList<>();
            for (String conn : entry.getValue()) {
                if (!conn.equals(key)) {
                    kept.add(conn);
                }
            }
            if (kept.isEmpty()) {
                nextBind.remove(entry.getKey());
            } else {
                nextBind.put(entry.getKey(), kept);
            }
        }
        // 连接删除 → token 级联删除（留着就是指向不存在连接的孤儿凭据）
        Map<String, McpConnection.OAuthToken> nextTok = new LinkedHashMap<>(state.oauthTokens());
        nextTok.remove(key);
        persist(new McpConnection.FileShape(nextConn, nextBind, nextTok));
        log.info("[MCP 连接库] 已删除连接「{}」（若仍被域装配，已一并解绑）", key);
        return true;
    }

    /**
     * 设置某域直接绑定的连接集合（全量覆盖该域的条目；空列表 = 解绑该域）。
     * 只接受"确实存在"的连接名，避免绑一个不存在的连接（脏状态）。
     */
    public synchronized String bind(String domain, List<String> connectionNames) {
        String key = Domains.normalize(domain);
        String reason = Domains.validatePath(key);
        if (reason != null) {
            return "域不合法：" + reason;
        }
        Set<String> known = names();
        List<String> cleaned = new ArrayList<>();
        for (String conn : connectionNames == null ? List.<String>of() : connectionNames) {
            String c = normalize(conn);
            if (c.isEmpty()) {
                continue;
            }
            if (!known.contains(c)) {
                return "MCP 连接「" + c + "」不存在，无法绑定";
            }
            if (!cleaned.contains(c)) {
                cleaned.add(c);
            }
        }
        Map<String, List<String>> nextBind = new LinkedHashMap<>(state.domainBindings());
        if (cleaned.isEmpty()) {
            nextBind.remove(key);
        } else {
            nextBind.put(key, cleaned);
        }
        persist(new McpConnection.FileShape(state.connections(), nextBind, state.oauthTokens()));
        log.info("[MCP 连接库] 域 {} 已绑定 MCP 连接：{}", key, cleaned);
        return null;
    }

    /**
     * 删域级联：从所有绑定关系里摘掉受影响的域（连接本身不删，与模型绑定同口径）。
     */
    public synchronized void unbindDomains(Set<String> affectedDomains) {
        if (affectedDomains == null || affectedDomains.isEmpty()) {
            return;
        }
        Map<String, List<String>> nextBind = new LinkedHashMap<>(state.domainBindings());
        boolean changed = false;
        for (String domain : affectedDomains) {
            if (nextBind.remove(Domains.normalize(domain)) != null) {
                changed = true;
            }
        }
        if (changed) {
            persist(new McpConnection.FileShape(state.connections(), nextBind, state.oauthTokens()));
            log.info("[MCP 连接库] 删域级联：已从绑定关系里摘除域 {}", affectedDomains);
        }
    }

    // ==================== OAuth token ====================

    /** 取某连接的 OAuth token（不存在返回 null） */
    public McpConnection.OAuthToken tokenOf(String connectionName) {
        return state.oauthTokens().get(normalize(connectionName));
    }

    /** 存/换某连接的 OAuth token（授权成功与刷新成功后调用） */
    public synchronized void saveToken(String connectionName, McpConnection.OAuthToken token) {
        String key = normalize(connectionName);
        Map<String, McpConnection.OAuthToken> next = new LinkedHashMap<>(state.oauthTokens());
        next.put(key, token);
        persist(new McpConnection.FileShape(state.connections(), state.domainBindings(), next));
        log.info("[MCP 连接库] 连接 {} 的 OAuth token 已更新（过期时间 {}）", key, token.expiresAtEpochMs());
    }

    /** 删某连接的 OAuth token（解绑 / 清理失效凭据） */
    public synchronized void removeToken(String connectionName) {
        String key = normalize(connectionName);
        if (!state.oauthTokens().containsKey(key)) {
            return;
        }
        Map<String, McpConnection.OAuthToken> next = new LinkedHashMap<>(state.oauthTokens());
        next.remove(key);
        persist(new McpConnection.FileShape(state.connections(), state.domainBindings(), next));
        log.info("[MCP 连接库] 连接 {} 的 OAuth token 已删除", key);
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }

    // ==================== 内部 ====================

    private void persist(McpConnection.FileShape next) {
        if (loadFailed) {
            throw new IllegalStateException("MCP 连接库文件无法解析，拒绝覆盖写入以免把既有配置清空："
                    + settingsFile.toAbsolutePath() + "；请修复或删除该文件后重启服务");
        }
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(next));
            this.state = next;
            try {
                Files.setPosixFilePermissions(settingsFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows 上无 POSIX 权限；文件已落盘，权限收窄仅在支持的系统生效
            }
        } catch (IOException e) {
            throw new IllegalStateException("MCP 连接库保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    private McpConnection.FileShape load() {
        if (!Files.exists(settingsFile)) {
            return new McpConnection.FileShape(List.of(), Map.of());
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            McpConnection.FileShape loaded = MAPPER.readValue(json, McpConnection.FileShape.class);
            // 手工改坏文件可能留下重名连接：先出现的赢，下次写入写回干净文件
            Map<String, McpConnection> byName = new TreeMap<>();
            for (McpConnection c : loaded.connections()) {
                byName.putIfAbsent(c.name(), c);
            }
            if (byName.size() < loaded.connections().size()) {
                log.warn("[MCP 连接库] {} 中存在重名连接，已保留每名第一条", settingsFile.toAbsolutePath());
            }
            /* 三参构造：oauthTokens 必须原样带回来。走二参构造会把它丢成空 map，
               而下一次 persist 用 state.oauthTokens() 组装整个 FileShape 覆盖落盘，
               等于把磁盘上的 token 顺手抹掉，所有 OAuth 连接从此跨不过重启。 */
            return new McpConnection.FileShape(new ArrayList<>(byName.values()),
                    loaded.domainBindings(), loaded.oauthTokens());
        } catch (IOException e) {
            loadFailed = true;
            log.error("[MCP 连接库] 读取 {} 失败，按空库继续（已禁止写入，可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e.getMessage());
            return new McpConnection.FileShape(List.of(), Map.of());
        }
    }

    /** 地址校验：只卡"根本不是一个可请求的 http(s) 地址"，其余一律放过（照 ProviderConnectionStore） */
    static String checkBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "MCP 服务地址不能为空";
        }
        if (baseUrl.length() > MAX_URL) {
            return "MCP 服务地址最长 " + MAX_URL + " 个字符，当前 " + baseUrl.length() + " 个";
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            return "MCP 服务地址不是合法 URL（含空格或非法字符）：" + baseUrl;
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return "MCP 服务地址要带协议，如 https://mcp.example.com/mcp（当前：" + baseUrl + "）";
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "MCP 服务地址只支持 http / https（当前协议：" + scheme + "）";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "MCP 服务地址缺少主机名：" + baseUrl;
        }
        return null;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
