package com.zzkingcc.domainplane.server.settings;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.config.ElasticsearchProperties;
import com.zzkingcc.domainplane.server.config.RedisProperties;
import com.zzkingcc.domainplane.server.config.SwappableElasticsearchTransport;
import com.zzkingcc.domainplane.server.config.SwappableRedisConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLException;

/**
 * 基础设施存储配置持有者 —— 合并 yaml、落盘、热替换、探活。
 * @author zzkingcc
 */
@Slf4j
@Component
public class InfraSettingsHolder {

    /** 解析原始 JSON 用；这里刻意不经过 ES 客户端的模型层（见 {@link #probeProduct}） */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ElasticsearchProperties yamlEs;
    private final RedisProperties yamlRedis;
    private final InfraSettingsStore store;

    /** 管控台那一份（未合并），用于判断配置来源 */
    private volatile InfraSettings stored;

    /** 合并后的生效配置 */
    private volatile InfraSettings settings;

    /** 注册进来的可热替换实现（两个代理类在构造时自注册） */
    private final List<InfraSwappable> swappables = new CopyOnWriteArrayList<>();

    public InfraSettingsHolder(ElasticsearchProperties yamlEs,
                               RedisProperties yamlRedis,
                               InfraSettingsStore store) {
        this.yamlEs = yamlEs;
        this.yamlRedis = yamlRedis;
        this.store = store;
        this.stored = store.load();
        this.settings = merge(this.stored);
        logStatus();
    }

    public void register(InfraSwappable swappable) {
        swappables.add(swappable);
    }

    // ===== 读取 =====

    /** 合并后的生效配置（永远非 null） */
    public InfraSettings current() {
        InfraSettings s = settings;
        return s == null ? new InfraSettings() : s;
    }

    /** 管控台保存的那一份（未合并），用于判断来源 */
    public InfraSettings storedSettings() {
        return stored;
    }

    public boolean isEsConfigured() {
        return current().isEsUsable();
    }

    public boolean isRedisConfigured() {
        return current().isRedisUsable();
    }

    /** 该值当前从哪来：管控台 / yaml（含环境变量）/ 未配置 */
    public String esSource() {
        InfraSettings s = stored;
        if (s != null && s.getEs() != null && hasText(s.getEs().getHost())) {
            return "管控台";
        }
        return hasText(yamlEs.getHost()) ? "yaml / 环境变量" : "未配置";
    }

    public String redisSource() {
        InfraSettings s = stored;
        if (s != null && s.getRedis() != null && hasText(s.getRedis().getHost())) {
            return "管控台";
        }
        return hasText(yamlRedis.getHost()) ? "yaml / 环境变量" : "未配置";
    }

    // ===== 保存并立即生效 =====

    /**
     * 保存设置并<b>立即生效</b>
     */
    public synchronized void apply(InfraSettings incoming) {
        fillBlankSecrets(incoming);
        rejectInvalid(incoming);
        store.save(incoming);
        this.stored = incoming;
        this.settings = merge(incoming);
        logStatus();
        for (InfraSwappable swappable : swappables) {
            try {
                swappable.swap();
            } catch (Exception e) {
                log.error("[存储配置] 热替换失败（配置已保存，重启可恢复）: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 口令留空 = "不修改"，用当前生效值补齐
     */
    public void fillBlankSecrets(InfraSettings incoming) {
        InfraSettings cur = current();
        if (incoming == null) {
            return;
        }
        if (incoming.getEs() == null) {
            incoming.setEs(new InfraSettings.Elasticsearch());
        }
        if (incoming.getRedis() == null) {
            incoming.setRedis(new InfraSettings.Redis());
        }
        if (!hasText(incoming.getEs().getPassword())) {
            incoming.getEs().setPassword(cur.getEs().getPassword());
        }
        if (!hasText(incoming.getRedis().getPassword())) {
            incoming.getRedis().setPassword(cur.getRedis().getPassword());
        }
    }

    /**
     * 合并：管控台填写优先，缺失字段回落到 yaml / 环境变量。
     */
    private InfraSettings merge(InfraSettings ui) {
        InfraSettings.Elasticsearch ue = ui == null || ui.getEs() == null ? new InfraSettings.Elasticsearch() : ui.getEs();
        InfraSettings.Redis ur = ui == null || ui.getRedis() == null
                ? new InfraSettings.Redis() : ui.getRedis();

        InfraSettings merged = new InfraSettings();
        merged.getEs().setHost(pick(ue.getHost(), yamlEs.getHost()));
        merged.getEs().setPort(ue.getPort() != null ? ue.getPort() : yamlEs.getPort());
        merged.getEs().setScheme(pick(ue.getScheme(), yamlEs.getScheme()));
        merged.getEs().setUsername(pick(ue.getUsername(), yamlEs.getUsername()));
        merged.getEs().setPassword(pick(ue.getPassword(), yamlEs.getPassword()));
        merged.getEs().setConnectTimeout(ue.getConnectTimeout() != null
                ? ue.getConnectTimeout() : yamlEs.getConnectTimeout());
        merged.getEs().setSocketTimeout(ue.getSocketTimeout() != null
                ? ue.getSocketTimeout() : yamlEs.getSocketTimeout());

        merged.getRedis().setHost(pick(ur.getHost(), yamlRedis.getHost()));
        merged.getRedis().setPort(ur.getPort() != null ? ur.getPort() : yamlRedis.getPort());
        merged.getRedis().setPassword(pick(ur.getPassword(), yamlRedis.getPassword()));
        merged.getRedis().setDatabase(ur.getDatabase() != null
                ? ur.getDatabase() : yamlRedis.getDatabase());
        return merged;
    }

    private void logStatus() {
        InfraSettings s = current();
        log.info("[存储配置] 生效配置：Elasticsearch = {}（来源：{}）；Redis = {}（来源：{}）",
                s.getEs().describe(), esSource(), s.getRedis().describe(), redisSource());
    }

    // ===== 连通性测试（临时客户端，用完即关，不接触生效链路） =====

    /**
     * ES 探活结果。
     */
    public record EsProbe(String clusterName, String version, String buildFlavor,
                          EsCompatibility.Verdict compatibility,
                          Boolean ikAvailable, String ikNote) {
    }

    /**
     * Redis 探活结果。
     */
    public record RedisProbe(String version, String mode, int database, Integer databases,
                             Long dbSize, String note) {
    }

    /**
     * 用候选参数探活 Elasticsearch：{@code ping} + 产品身份 + IK 分词器
     *
     * @param candidate 候选配置（口令留空时调用方应先补齐）
     */
    public EsProbe testEs(InfraSettings.Elasticsearch candidate) {
        if (candidate == null || !candidate.isUsable()) {
            throw new NotConfiguredException("请先填写 Elasticsearch 地址");
        }
        rejectInvalid(candidate.validate(), "Elasticsearch");
        RestClient restClient = SwappableElasticsearchTransport.buildRestClient(candidate);
        try (RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            Boolean ok = client.ping().value();
            if (ok == null || !ok) {
                throw new IllegalStateException("Elasticsearch ping 返回 false");
            }
            ProductInfo product = probeProduct(restClient);
            Boolean ikAvailable = probeIk(restClient);
            return new EsProbe(product.clusterName(), product.version(), product.flavor(),
                    EsCompatibility.judge(product.version(), product.flavor(), product.distribution()),
                    ikAvailable, ikNote(ikAvailable));
        } catch (Exception e) {
            throw new IllegalStateException(describe(e), e);
        }
    }

    /** 从 {@code GET /} 解出的对端身份 */
    private record ProductInfo(String clusterName, String version, String flavor, String distribution) {
    }

    /**
     * 读 {@code GET /} 辨认对端身份：集群名、版本、发行版、以及"到底是不是 Elasticsearch"
     */
    private static ProductInfo probeProduct(RestClient restClient) throws IOException {
        Response response = restClient.performRequest(new Request("GET", "/"));
        JsonNode root = JSON.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
        JsonNode version = root.path("version");
        return new ProductInfo(
                root.path("cluster_name").asText(null),
                version.path("number").asText(null),
                version.path("build_flavor").asText(null),
                version.path("distribution").asText(null));
    }

    /**
     * 探测 IK 中文分词器是否可用
     */
    private static Boolean probeIk(RestClient restClient) {
        try {
            Request request = new Request("POST", "/_analyze");
            request.setJsonEntity("{\"analyzer\":\"ik_max_word\",\"text\":\"中文分词探测\"}");
            Response response = restClient.performRequest(request);
            return response.getStatusLine().getStatusCode() == 200;
        } catch (ResponseException e) {
            // 400 = "analyzer [ik_max_word] not found" → 确实没装。
            // 其余状态码（403 / 404 / …）一律算"没探到"，不能算"没有"。
            return e.getResponse().getStatusLine().getStatusCode() == 400 ? Boolean.FALSE : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** IK 探测结论翻成人话；三态各有各的说法 */
    private static String ikNote(Boolean ikAvailable) {
        if (Boolean.TRUE.equals(ikAvailable)) {
            return "IK 中文分词器可用，中文分词与检索走 IK。";
        }
        if (Boolean.FALSE.equals(ikAvailable)) {
            return "未安装 IK 分词器：建索引时 IK mapping 会失败并回退到默认分词器"
                    + "（索引照样建、灌库照样成功，只有中文检索质量下降且不报错）。"
                    + "装上 analysis-ik 插件后，到「知识库」页重建一次索引即可恢复。";
        }
        return "未能探测 IK 分词器（账号权限不足或对方未开放 /_analyze）。"
                + "这不影响使用，可以忽略；若中文检索效果不理想，请让 DBA 确认 analysis-ik 插件是否已安装。";
    }

    /**
     * 用候选参数探活 Redis：建连 + {@code PING} + 读版本、拓扑、库大小
     */
    public RedisProbe testRedis(InfraSettings.Redis candidate, RedisProperties yaml) {
        if (candidate == null || !candidate.isUsable()) {
            throw new NotConfiguredException("请先填写 Redis 地址");
        }
        rejectInvalid(candidate.validate(), "Redis");
        LettuceConnectionFactory factory = SwappableRedisConnectionFactory.build(candidate, yaml);
        try {
            factory.afterPropertiesSet();
            RedisConnection connection = factory.getConnection();
            try {
                connection.ping();
                Properties server = connection.serverCommands().info("server");
                String version = server == null ? null : server.getProperty("redis_version");
                String mode = server == null ? null : server.getProperty("redis_mode");
                Integer databases = server == null ? null : intOrNull(server.getProperty("databases"));
                int database = candidate.effectiveDatabase();
                Long dbSize = connection.serverCommands().dbSize();
                return new RedisProbe(version, mode, database, databases, dbSize,
                        redisNote(mode, database, databases));
            } finally {
                connection.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException(describe(e), e);
        } finally {
            try {
                factory.destroy();
            } catch (Exception ignored) {
                // 临时工厂清理失败不影响结论
            }
        }
    }

    /**
     * Redis 侧的能力提示 —— 只看拓扑与库号范围，不看版本
     */
    private static String redisNote(String mode, int database, Integer databases) {
        if (mode != null && mode.toLowerCase().contains("cluster") && database != 0) {
            return "该实例运行在集群模式，集群模式通常只提供 0 号库；"
                    + "请把库号改成 0，否则取连接会失败。";
        }
        if (databases != null && database >= databases) {
            return "该实例只提供 " + databases + " 个库（0 ~ " + (databases - 1) + "），"
                    + "库号 " + database + " 超出范围，取连接会失败。";
        }
        return null;
    }

    /** 解析 {@code INFO server} 里的整数字段；缺失或非数字一律返回 null（不猜） */
    private static Integer intOrNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ===== 失败原因翻译（让配错的用户自己看得出问题） =====

    /**
     * 把底层异常翻成一句中文原因
     */
    public static String describe(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String text = collectMessages(e);

        // 认证类：两种中间件都以文字形式给出线索
        if (containsAny(text, "security_exception", "unable to authenticate", "Unauthorized",
                "authentication_exception")) {
            return "认证失败：账号或密码不正确，或该账号无权限";
        }
        if (containsAny(text, "WRONGPASS", "NOAUTH", "invalid username-password",
                "Client sent AUTH")) {
            return "认证失败：Redis 密码错误，或该实例要求密码而未提供";
        }

        // 网络层按异常类型判断
        if (findCause(e, UnknownHostException.class) != null) {
            return "无法解析主机名（地址拼写错误或 DNS 不可用）";
        }
        if (findCause(e, ConnectException.class) != null) {
            return "无法连接（端口不通，或对方未监听、防火墙拦截）";
        }
        if (findCause(e, SocketTimeoutException.class) != null
                || containsAny(text, "ConnectTimeoutException", "Connection timed out")) {
            return "连接超时（地址可达但无响应，或网络延迟过高）";
        }
        if (findCause(e, SSLException.class) != null) {
            return "TLS/SSL 握手失败（检查协议 scheme 与端口是否匹配）";
        }

        String raw = rootMessage(e);
        return raw == null ? e.getClass().getSimpleName() : limit(raw);
    }

    private static String collectMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int guard = 0;
        while (t != null && guard++ < 10) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" | ");
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable e) {
        String message = null;
        Throwable t = e;
        int guard = 0;
        while (t != null && guard++ < 10) {
            if (hasText(t.getMessage())) {
                message = t.getMessage();
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return message;
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        Throwable t = e;
        int guard = 0;
        while (t != null && guard++ < 10) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return null;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String limit(String text) {
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    private static String pick(String primary, String fallback) {
        return hasText(primary) ? primary : fallback;
    }

    /**
     * 保存前拦下"填了但不可能连上"的配置。
     */
    private static void rejectInvalid(InfraSettings settings) {
        if (settings != null) {
            rejectInvalid(settings.validate(), "存储配置");
        }
    }

    private static void rejectInvalid(List<String> problems, String what) {
        if (problems != null && !problems.isEmpty()) {
            throw new IllegalArgumentException(what + "填写有误：" + String.join("；", problems));
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
