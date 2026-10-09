package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzkingcc.domainplane.common.util.AtomicFiles;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 服务商连接库（{@code config/provider-connections.json}）—— 按唯一名称存一条
 * 「服务商地址 + 可选密钥」，供模型档案表单一键选用。
 *
 * 为什么地址与密钥放在一起：这两个字段从来是一起用的 —— 换一家服务商，地址和 Key
 * 同时换。分成两个库就等于让用户配两遍、还要自己对齐哪条地址配哪把 Key；合成一条之后，
 * 下拉里选一次就把表单填好，也避免了"地址是 A、密钥是 B"这种拼错的组合。
 *
 * 它只是"取连接快捷方式"，不是引用关系：选中后把地址与密钥填进档案表单，
 * 保存档案时仍按原样把明文写进 {@code models.json}。因此删除一条不会影响任何已保存
 * 档案 —— 两者本就无关联，界面必须把这一点说清楚，否则用户会以为删了就等于"从档案里撤掉了"。
 * 反过来，在档案表单里改了地址或 Key 也不会回写本库。
 *
 * 名称必须唯一：名称是下拉里唯一的标识，两个同名条目会让"选哪个"变成猜谜，
 * 而且改名 / 删除时无法指定是哪一条。唯一性在写入层判（不只控制器判），否则将来任何
 * 新的写入入口都可能绕过它。比较时按去首尾空白后的精确匹配，不折叠大小写 ——
 * 折叠大小写会误伤 {@code OpenRouter} / {@code openrouter} 这类确实想区分的名字，
 * 而"重名"这件事在报错文案里点名已有名称就足够说清了。
 *
 * 密钥可空：本地 Ollama / vLLM 这类自建端点随便填个非空占位即可，
 * 不该强迫用户为一条不存在的密钥编一个值。因此空密钥在这里是合法状态（存空串），
 * 但模型档案侧的 {@code apiKey} 仍是必填 —— 那是档案自己的约束，不是本库的。
 *
 * 地址不做强制规范化：只校验"是 http/https 的合法 URL 且有主机"，
 * 不要求也不追加 {@code /v1} —— 各家网关的路径段五花八门（{@code /v1}、
 * {@code /openai/v1}、{@code /compatible-mode/v1}），由探测环节按 404 兜底补全更合适
 * （见 {@link ModelCatalog}）。
 *
 * 单独一个文件而不是并进 models.json：这里可能有明文凭据，与档案配置的生命周期不同
 * —— 备份、授权、轮换都该能单独处理，不必连带整个档案表一起搬走。
 *
 * 明文只在两处出现：磁盘上的本文件，以及调用 {@link #resolve(String)} 那一刻的
 * 响应体。列表接口只给脱敏值（见 {@code AdminProviderConnectionController}）—— 页面每次打开
 * 就把全库明文拉进浏览器内存没有必要，而档案列表里的 Key 本来就是脱敏展示的。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ProviderConnectionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 三个字段的上限：够用且能在错误文案里原样回显，不必截断 */
    private static final int MAX_NAME = 64;
    private static final int MAX_URL = 512;
    private static final int MAX_KEY = 512;

    private final Path settingsFile;

    /** 当前内容（写操作整体替换，读操作拿不可变副本） */
    private volatile Connections connections;

    public ProviderConnectionStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("provider-connections.json");
        this.connections = load();
        log.info("[服务商连接库] 初始化：已登记 {} 条（落盘 {}）",
                connections.connections().size(), settingsFile.toAbsolutePath());
    }

    /**
     * 库中的一条连接。
     *
     * @param name     唯一名称（预设下拉里的标识）
     * @param baseUrl  服务商地址
     * @param apiKey   密钥，可为空（自建端点没有真密钥）；不要写进日志
     * @param protocol 协议类型名（{@link ProviderType#name()}）；空 = OpenAI 兼容
     */
    public record ProviderConnection(String name, String baseUrl, String apiKey, String protocol) {

        public ProviderConnection {
            name = name == null ? "" : name.trim();
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            /* 老文件里没有这个字段。缺省即 OpenAI 兼容 —— 与只有这一种协议时行为一致 */
            protocol = protocol == null || protocol.isBlank()
                    ? ProviderType.OPENAI_COMPATIBLE.name()
                    : protocol.trim();
        }

        /** 这条连接是否带密钥（决定下拉里要不要显示脱敏值） */
        public boolean hasApiKey() {
            return !apiKey.isEmpty();
        }

        /** 脱敏展示（与档案卡片上的 {@code maskedApiKey} 同一口径）；无密钥时返回空串 */
        public String maskedApiKey() {
            if (apiKey.isEmpty()) {
                return "";
            }
            if (apiKey.length() <= 8) {
                return "****";
            }
            return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
        }
    }

    /** 落盘结构（顶层一个 {@code connections} 数组） */
    public record Connections(List<ProviderConnection> connections) {

        public Connections {
            connections = connections == null ? List.of() : List.copyOf(connections);
        }
    }

    // ==================== 读 ====================

    /** 全部条目（按名称排序，不可变副本） */
    public List<ProviderConnection> entries() {
        return connections.connections().stream()
                .sorted(java.util.Comparator.comparing(ProviderConnection::name))
                .toList();
    }

    /**
     * 按名称取整条连接（含明文密钥）。
     *
     * 这是明文离开磁盘的唯一出口，只在用户于下拉里明确选中某个名称时调用一次。
     */
    public Optional<ProviderConnection> resolve(String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return connections.connections().stream()
                .filter(c -> c.name().equals(key))
                .findFirst();
    }

    // ==================== 写 ====================

    /**
     * 新增一条。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String add(String name, String baseUrl, String apiKey, String protocol) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return "名称不能为空";
        }
        if (key.length() > MAX_NAME) {
            return "名称最长 " + MAX_NAME + " 个字符，当前 " + key.length() + " 个";
        }

        String url = normalize(baseUrl);
        String urlFailure = checkBaseUrl(url);
        if (urlFailure != null) {
            return urlFailure;
        }

        String secret = normalize(apiKey);
        if (secret.length() > MAX_KEY) {
            return "API Key 最长 " + MAX_KEY + " 个字符，当前 " + secret.length() + " 个";
        }
        /* 空密钥合法（自建端点），因此这里不拦空 —— 只拦超长 */

        /* 唯一性在这里判（写入层），而不是只在控制器判：将来多一个写入入口也不会绕过它 */
        Optional<ProviderConnection> existing = find(key);
        if (existing.isPresent()) {
            return "名称已存在：" + key + "（这是「" + existing.get().baseUrl() + "」那条）。"
                    + "名称用来标识是哪一条，不允许重复；想改地址或密钥请先删除同名项再添加。";
        }

        /* 认不出来的协议名退回归 OpenAI 兼容 —— 与档案侧同一个 parse，口径一致 */
        String proto = ProviderType.parse(protocol).name();

        List<ProviderConnection> next = new ArrayList<>(connections.connections());
        next.add(new ProviderConnection(key, url, secret, proto));
        persist(new Connections(next));
        log.info("[服务商连接库] 已添加「{}」→ {}（协议 {}，{}）", key, url, proto,
                new ProviderConnection(key, url, secret, proto).maskedApiKey());
        return null;
    }

    /**
     * 按名称删除。
     *
     * 只删库里这一条：已保存档案里的地址与密钥与本库无关联，不会被牵连 ——
     * 这是"快捷填充"设计的必然结果，界面要照实说明，别让用户以为删了就等于从档案里撤掉了。
     *
     * @return 是否真的删掉了（名称不存在返回 {@code false}）
     */
    public synchronized boolean delete(String name) {
        String key = normalize(name);
        List<ProviderConnection> next = new ArrayList<>();
        boolean removed = false;
        for (ProviderConnection connection : connections.connections()) {
            if (connection.name().equals(key)) {
                removed = true;
            } else {
                next.add(connection);
            }
        }
        if (!removed) {
            return false;
        }
        persist(new Connections(next));
        log.info("[服务商连接库] 已删除「{}」（已保存的模型档案不受影响，它们各自保存的是独立副本）", key);
        return true;
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }

    // ==================== 内部 ====================

    private Optional<ProviderConnection> find(String name) {
        return connections.connections().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /**
     * 地址校验：只卡"根本不是一个可请求的 http(s) 地址"，其余一律放过。
     *
     * 刻意不校验也不追加 {@code /v1}：各家网关的路径段五花八门，强行按一种模板改写
     * 只会把合法地址改坏；漏了版本段时探测环节会按 404 兜底补一次。
     *
     * @return 失败原因；{@code null} 表示通过
     */
    static String checkBaseUrl(String baseUrl) {
        if (baseUrl.isEmpty()) {
            return "服务商地址不能为空";
        }
        if (baseUrl.length() > MAX_URL) {
            return "服务商地址最长 " + MAX_URL + " 个字符，当前 " + baseUrl.length() + " 个";
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            return "服务商地址不是合法 URL（含空格或非法字符）：" + baseUrl;
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return "服务商地址要带协议，如 https://api.example.com/v1（当前：" + baseUrl + "）";
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "服务商地址只支持 http / https（当前协议：" + scheme + "）";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "服务商地址缺少主机名：" + baseUrl;
        }
        return null;
    }

    /** 读取；文件不存在或解析失败时按空库继续（不阻断启动，可进管控台修正） */
    private Connections load() {
        if (!Files.exists(settingsFile)) {
            return new Connections(List.of());
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            Connections loaded = MAPPER.readValue(json, Connections.class);
            if (loaded == null) {
                return new Connections(List.of());
            }
            /* 手工改坏过文件时可能留下重名条目：这里按"先出现的赢"去重，
               并在下次写入时写回干净的文件 —— 否则下拉里会出现两个同名项、删不掉第二个。 */
            Set<String> seen = new LinkedHashSet<>();
            List<ProviderConnection> deduped = new ArrayList<>();
            for (ProviderConnection connection : loaded.connections()) {
                if (seen.add(connection.name())) {
                    deduped.add(connection);
                }
            }
            if (deduped.size() < loaded.connections().size()) {
                log.warn("[服务商连接库] {} 中存在重名条目，已保留每名第一条（共 {} → {}）",
                        settingsFile.toAbsolutePath(), loaded.connections().size(), deduped.size());
            }
            return new Connections(deduped);
        } catch (IOException e) {
            /* 刻意不打印异常原文：解析失败时原文里可能混着密钥片段 */
            log.error("[服务商连接库] 读取 {} 失败，按空库继续（可进管控台修正）",
                    settingsFile.toAbsolutePath());
            return new Connections(List.of());
        }
    }

    /** 写盘成功后整体替换内存态（写失败时抛异常，内存态保持原样） */
    private void persist(Connections next) {
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(next));
            this.connections = next;
        } catch (IOException e) {
            throw new IllegalStateException("服务商连接库保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }
}