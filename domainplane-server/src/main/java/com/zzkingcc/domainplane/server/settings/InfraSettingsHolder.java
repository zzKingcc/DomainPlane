package com.zzkingcc.domainplane.server.settings;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.infrastructure.kb.qdrant.QdrantHttpClient;
import com.zzkingcc.domainplane.infrastructure.kb.qdrant.VectorTarget;
import com.zzkingcc.domainplane.server.config.ElasticsearchProperties;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLException;

/**
 * 基础设施存储配置持有者 —— 合并 yaml、落盘、热替换、探活。
 *
 * <p><b>合并口径（一条就够）</b>：管控台配过连接（{@code connections} 非空）就以管控台为准；
 * 一条都没配时，用 yaml 的 {@code domainplane.knowledge.backends} 合成默认连接与默认知识体，
 * 让"yaml 配了就开箱可用、管控台填了就覆盖"两件事同时成立。Redis 不参与这个开关，
 * 始终按字段回落 yaml——它是平台基础设施，不存在"多个连接"这回事。</p>
 *
 * <p>为什么不按连接逐条合并 yaml：那会让"删掉一条连接"变成删不掉（yaml 里那条又冒出来），
 * 用户永远说不清表里那一行到底是谁的。开关式口径只有一个方向，排障时不需要推理。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class InfraSettingsHolder {

    /** 解析原始 JSON 用；这里刻意不经过 ES 客户端的模型层（见 {@link #probeProduct}） */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ElasticsearchProperties yamlEs;
    private final RedisProperties yamlRedis;
    private final KnowledgeProperties yamlKnowledge;
    private final InfraSettingsStore store;

    /** 管控台那一份（未合并），用于判断配置来源 */
    private volatile InfraSettings stored;

    /** 合并后的生效配置 */
    private volatile InfraSettings settings;

    /** 注册进来的可热替换实现（ES / Redis 代理与后端注册表在构造时自注册） */
    private final List<InfraSwappable> swappables = new CopyOnWriteArrayList<>();

    public InfraSettingsHolder(ElasticsearchProperties yamlEs,
                               RedisProperties yamlRedis,
                               KnowledgeProperties yamlKnowledge,
                               InfraSettingsStore store) {
        this.yamlEs = yamlEs;
        this.yamlRedis = yamlRedis;
        this.yamlKnowledge = yamlKnowledge;
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

    /** 向量用途是否至少有一个可用知识体（ES / Qdrant 都算） */
    public boolean isVectorSlotConfigured() {
        return current().isVectorKindUsable();
    }

    /** 该值当前从哪来：管控台 / yaml（含环境变量）/ 未配置 */
    public String esSource() {
        InfraSettings s = stored;
        if (s != null && s.connection(s.platformConnectionId(InfraSettings.KIND_KEYWORD)) != null) {
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

    /** 向量用途来源：只有管控台一个（yaml 侧无 Qdrant 地址键） */
    public String vectorSlotSource() {
        InfraSettings s = stored;
        if (s != null && s.connection(s.platformConnectionId(InfraSettings.KIND_VECTOR)) != null) {
            return "管控台";
        }
        return "未配置";
    }

    // ===== 保存并立即生效 =====

    /**
     * 保存设置并立即生效。
     *
     * <p>顺序是有意的：先落盘再审生效值，最后才热替换。反过来（先生效后落盘）在写盘失败时
     * 会留下"这台机器生效了新配置、重启后回退"的错位——排障时最费劲的那种。</p>
     */
    public synchronized void apply(InfraSettings incoming) {
        fillBlankSecrets(incoming);
        rejectInvalid(incoming);
        // 先标记"管控台配过"，再落盘与合并：merge() 靠这个位决定要不要用 yaml 兜底
        incoming.setConfigured(true);
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
     * 口令留空 = "不修改"，用当前生效值按<b>连接 id</b>补齐。
     *
     * <p>按 id 而不是按"第一个 ES"：同一台实例可以被关键字槽和向量槽各引用一次，
     * 补口令时两边都该补上；而不同实例之间绝不能互相串口令。</p>
     */
    public void fillBlankSecrets(InfraSettings incoming) {
        if (incoming == null) {
            return;
        }
        InfraSettings cur = current();
        if (incoming.getRedis() == null) {
            incoming.setRedis(new InfraSettings.Redis());
        }
        if (!hasText(incoming.getRedis().getPassword())) {
            incoming.getRedis().setPassword(cur.getRedis().getPassword());
        }
        if (incoming.getConnections() == null) {
            return;
        }
        for (Map.Entry<String, InfraSettings.Connection> entry : incoming.getConnections().entrySet()) {
            InfraSettings.Connection cand = entry.getValue();
            if (cand == null) {
                continue;
            }
            InfraSettings.Connection old = cur.connection(cand.getId() != null ? cand.getId() : entry.getKey());
            if (old == null) {
                continue;
            }
            if (cand.getEs() != null && !hasText(cand.getEs().getPassword())) {
                cand.getEs().setPassword(old.getEs() == null ? null : old.getEs().getPassword());
            }
            if (cand.getVector() != null && !hasText(cand.getVector().getToken())) {
                cand.getVector().setToken(old.getVector() == null ? null : old.getVector().getToken());
            }
            /* 对象存储：AK / SK 留空 = 沿用已存的那份（与 ES 口令、Qdrant token 同口径）。
               两个都要处理——只补 SK 的话，编辑页面只回填 AK 时会把 SK 冲成空。 */
            if (cand.getS3() != null) {
                if (!hasText(cand.getS3().getAccessKey())) {
                    cand.getS3().setAccessKey(old.getS3() == null ? null : old.getS3().getAccessKey());
                }
                if (!hasText(cand.getS3().getSecretKey())) {
                    cand.getS3().setSecretKey(old.getS3() == null ? null : old.getS3().getSecretKey());
                }
            }
        }
    }

    /**
     * 只替换知识体表（知识库页的增删改走这条），连接与 Redis 原样保留。
     *
     * <p>与 {@link #apply} 同一条落盘 + 热替换路径：知识体是"数据身份"，加一个体就要
     * 让 {@code RetrievalPlan} / 后端注册表立刻看到它，不能等重启。</p>
     *
     * <p>拷一份当前生效的连接与 Redis 再提交：{@code apply} 会整份落盘，少传一项就等于
     * 把它清空。共享连接对象的引用是安全的——这里从不修改它们。</p>
     */
    public synchronized void replaceBodies(Map<String, InfraSettings.KnowledgeBody> bodies) {
        InfraSettings currentSettings = current();
        InfraSettings next = new InfraSettings();
        next.setConfigured(true);
        next.setRedis(currentSettings.getRedis());
        next.setConnections(currentSettings.connectionsOrEmpty());
        next.setBodies(new LinkedHashMap<>(bodies));
        apply(next);
    }

    /**
     * 合并：管控台填写优先，缺失字段回落到 yaml / 环境变量。
     */
    private InfraSettings merge(InfraSettings ui) {
        InfraSettings merged = new InfraSettings();

        InfraSettings.Redis ur = ui == null || ui.getRedis() == null
                ? new InfraSettings.Redis() : ui.getRedis();
        merged.getRedis().setHost(pick(ur.getHost(), yamlRedis.getHost()));
        merged.getRedis().setPort(ur.getPort() != null ? ur.getPort() : yamlRedis.getPort());
        merged.getRedis().setPassword(pick(ur.getPassword(), yamlRedis.getPassword()));
        merged.getRedis().setDatabase(ur.getDatabase() != null
                ? ur.getDatabase() : yamlRedis.getDatabase());

        if (ui != null && (ui.isConfigured()
                || (ui.getConnections() != null && !ui.getConnections().isEmpty()))) {
            merged.setConfigured(true);
            merged.setConnections(copy(ui.getConnections()));
            merged.setBodies(ui.getBodies() == null ? new LinkedHashMap<>() : copyBodies(ui.getBodies()));
            return merged;
        }
        synthesizeFromYaml(merged);
        return merged;
    }

    /**
     * 用 yaml 的后端清单合成连接与默认知识体。
     *
     * <p>ES 连接吃 {@code domainplane.elasticsearch.*}；Qdrant 连接在 yaml 里没有地址键，
     * 合成出来是"类型对、地址空"，等管控台填——与改造前"向量库地址只能管控台填"一致。</p>
     */
    private void synthesizeFromYaml(InfraSettings target) {
        Map<String, KnowledgeProperties.Backend> backends = yamlKnowledge.getBackends();
        if (backends == null || backends.isEmpty()) {
            return;
        }
        Map<String, InfraSettings.Connection> connections = new LinkedHashMap<>();
        backends.forEach((id, backend) -> {
            InfraSettings.Connection c = new InfraSettings.Connection();
            c.setId(id);
            c.setName(id);
            String type = backend == null || backend.getType() == null || backend.getType().isBlank()
                    ? InfraSettings.TYPE_ELASTICSEARCH
                    : backend.getType().trim().toLowerCase();
            c.setType(type);
            if (InfraSettings.TYPE_ELASTICSEARCH.equals(type)) {
                c.setEs(yamlEs());
            } else if (InfraSettings.TYPE_QDRANT.equals(type)) {
                c.setVector(new InfraSettings.VectorStore());
            }
            connections.put(id, c);
        });
        target.setConnections(connections);

        /* yaml 的两个角色位合成<b>一个双路知识体</b>：关键字槽指向 keyword-backend、
           向量槽指向 vector-backend。这正是"yaml 配了就开箱可用"的等价物——
           改造前它们藏在同一个存储集的两个槽里，只是那时候域没得选。 */
        InfraSettings.KnowledgeBody body = new InfraSettings.KnowledgeBody();
        body.setId("default");
        body.setName("默认知识体");
        body.setKeyword(yamlKnowledge.getKeywordBackend());
        body.setVector(yamlKnowledge.getVectorBackend());
        Map<String, InfraSettings.KnowledgeBody> bodies = new LinkedHashMap<>();
        if (body.configured()) {
            bodies.put(body.getId(), body);
        }
        target.setBodies(bodies);
    }

    /** yaml 侧的 ES 连接参数 */
    private InfraSettings.Elasticsearch yamlEs() {
        InfraSettings.Elasticsearch es = new InfraSettings.Elasticsearch();
        es.setHost(yamlEs.getHost());
        es.setPort(yamlEs.getPort());
        es.setScheme(yamlEs.getScheme());
        es.setUsername(yamlEs.getUsername());
        es.setPassword(yamlEs.getPassword());
        es.setConnectTimeout(yamlEs.getConnectTimeout());
        es.setSocketTimeout(yamlEs.getSocketTimeout());
        return es;
    }

    private static Map<String, InfraSettings.Connection> copy(Map<String, InfraSettings.Connection> src) {
        return JSON.convertValue(src, new TypeReference<LinkedHashMap<String, InfraSettings.Connection>>() {
        });
    }

    private static Map<String, InfraSettings.KnowledgeBody> copyBodies(
            Map<String, InfraSettings.KnowledgeBody> src) {
        return JSON.convertValue(src,
                new TypeReference<LinkedHashMap<String, InfraSettings.KnowledgeBody>>() {
                });
    }

    private void logStatus() {
        InfraSettings s = current();
        StringBuilder sb = new StringBuilder();
        s.connectionsOrEmpty().forEach((id, c) -> {
            if (c != null && sb.length() > 0) {
                sb.append("；");
            }
            if (c != null) {
                sb.append(id).append("=").append(c.describe()).append("（").append(c.effectiveType()).append("）");
            }
        });
        StringBuilder bodySb = new StringBuilder();
        s.bodiesOrEmpty().forEach((id, b) -> {
            if (b != null && bodySb.length() > 0) {
                bodySb.append("；");
            }
            if (b != null) {
                bodySb.append(id).append("=");
                if (!b.configured()) {
                    bodySb.append("一路都没配");
                    return;
                }
                if (b.hasKeyword()) {
                    bodySb.append("关键字@").append(b.keywordId());
                }
                if (b.hasVector()) {
                    bodySb.append(b.hasKeyword() ? " + " : "").append("向量@").append(b.vectorId());
                }
            }
        });
        log.info("[存储配置] 生效配置：Redis = {}（来源：{}）；连接 = {}；知识体 = {}",
                s.getRedis().describe(), redisSource(),
                sb.length() == 0 ? "无" : sb,
                bodySb.length() == 0 ? "无" : bodySb);
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
        // 两个都要纳入 try-with-resources：RestClientTransport 构造失败时，上面刚建好的连接池
        // 没有任何人关，而 testEs 是管控台上可以反复点击的接口：每点一次就泄一个池。
        // 关闭顺序由内到外（transport 先关），RestClient 重复 close 是空操作。
        try (RestClient restClient = SwappableElasticsearchTransport.buildRestClient(candidate);
             RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper())) {
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
     * 向量库探活结果。
     *
     * @param version       服务端版本（读不到为 null）
     * @param metric        距离度量
     * @param collectionCount 已有集合数
     * @param note          能力提示（读不到就如实说读不到）
     */
    public record VectorStoreProbe(String version, String metric, int collectionCount, String note) {
    }

    /**
     * 用候选参数探活向量库：ping + 读版本 + 枚举集合。
     *
     * <p>与 ES / Redis 的探活同族：<b>用完即关、不接触生效链路</b>。
     * 这里新建一个临时客户端（不复用装配期那个），所以不会把生效中的
     * 连接参数改掉——探活失败不该有副作用。</p>
     */
    public VectorStoreProbe testVectorStore(InfraSettings.VectorStore candidate) {
        if (candidate == null || !candidate.isUsable()) {
            throw new NotConfiguredException("请先填写向量库地址");
        }
        rejectInvalid(candidate.validate(), "向量库");
        // 探活用一个"固定目标"的临时客户端：用完即关，不接触生效链路。
        // 不能复用装配期那个 provider 客户端——那会把候选参数当成生效值用。
        QdrantHttpClient client = QdrantHttpClient.fixed(
                new VectorTarget(candidate.getUri(), candidate.getToken(),
                        candidate.effectiveMetric(), candidate.effectiveCollectionPrefix()),
                Duration.ofSeconds(10));
        try {
            if (!client.ping()) {
                throw new IllegalStateException("向量库 ping 失败（地址不可达或鉴权不通过）");
            }
            String version = client.version();
            List<String> collections = client.listCollections();
            return new VectorStoreProbe(version, candidate.effectiveMetric(), collections.size(),
                    vectorNote(version, collections.isEmpty()));
        } catch (Exception e) {
            throw new IllegalStateException(describe(e), e);
        }
    }

    /**
     * 向量库侧的能力提示。
     *
     * <p>版本号读不到有两种成因，分开说：集合也读不到（{@code /collections} 返回空）
     * 极可能是探到了别的 HTTP 服务；集合能读到说明确实是 Qdrant，
     * 只是地址带了路径前缀（例如 {@code http://host:6333/dashboard}）导致根响应不是 JSON。</p>
     */
    private static String vectorNote(String version, boolean collectionsEmpty) {
        if (version == null) {
            if (collectionsEmpty) {
                return "连接正常，但未读到版本号与集合列表（该地址可能不是 Qdrant 服务）。";
            }
            return "向量库连接正常：未读到版本号（已读到集合列表）。地址请填根路径，不要带 /dashboard 等路径后缀。";
        }
        return "向量库连接正常：版本 " + version
                + (collectionsEmpty ? "，当前 0 个集合（新库正常现象）。" : "，集合可正常枚举。");
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
