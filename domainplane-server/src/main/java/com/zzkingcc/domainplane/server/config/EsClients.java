package com.zzkingcc.domainplane.server.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 具名 ES 连接的客户端池。
 *
 * <p><b>为什么需要它</b>：管控台允许关键字槽绑一台 ES、向量槽绑另一台 ES（两个不同集群）。
 * 改造前只有一个 {@code domainplaneElasticsearchClient}，装不下第二种形态。这里按连接 id
 * 建客户端，各连各的。</p>
 *
 * <p><b>但平台默认那台不重复建</b>：连接 id 等于
 * {@link InfraSettings#defaultEsConnectionId()} 时直接返回平台那个可热替换的客户端——
 * 常见形态是"关键字槽就是平台 ES"，那就只有一份连接池，不因为抽象多开销一份。</p>
 *
 * <p>指纹（scheme/host/port/账号/口令/超时）变了才重建，同一个 id 下次取到的是新客户端，
 * 旧的宽限期后关闭——留出在途请求跑完的时间，不硬切。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class EsClients implements AutoCloseable {

    private static final JsonpMapper MAPPER = new JacksonJsonpMapper();

    /** 旧实现延迟关闭的宽限期（秒） */
    private static final long GRACE_SECONDS = 30;

    private static final ScheduledExecutorService DELAYED_CLOSE =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "domainplane-es-pool-close");
                t.setDaemon(true);
                return t;
            });

    private final InfraSettingsHolder holder;

    /** 平台默认客户端（懒取，避免与 transport bean 形成构造期环） */
    private final ObjectProvider<ElasticsearchClient> platformClient;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public EsClients(InfraSettingsHolder holder,
                     @Qualifier("domainplaneElasticsearchClient")
                     ObjectProvider<ElasticsearchClient> platformClient) {
        this.holder = holder;
        this.platformClient = platformClient;
    }

    /**
     * 取某个连接 id 的客户端。
     *
     * @throws NotConfiguredException 该连接还没填地址
     * @throws IllegalArgumentException 该连接不存在，或不是 ES 类型
     */
    public ElasticsearchClient forConnection(String connectionId) {
        InfraSettings settings = holder.current();
        InfraSettings.Connection conn = settings.connection(connectionId);
        if (conn == null) {
            throw new IllegalArgumentException("ES 连接不存在: " + connectionId
                    + "，可用: " + settings.connectionsOrEmpty().keySet());
        }
        if (!conn.isElasticsearch()) {
            throw new IllegalArgumentException("连接 " + connectionId + " 不是 Elasticsearch 类型（"
                    + conn.effectiveType() + "）");
        }
        // 平台默认那台走可热替换客户端：一份连接池，且地址改了自动跟上
        if (conn.getId().equals(settings.defaultEsConnectionId())) {
            return platformClient.getObject();
        }
        if (!conn.isUsable()) {
            throw new NotConfiguredException("Elasticsearch 连接「" + conn.effectiveName()
                    + "」尚未填写地址，请先在管控台「存储配置」页补上并保存");
        }
        String fingerprint = fingerprint(conn.getEs());
        Entry cached = cache.get(conn.getId());
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.client();
        }
        synchronized (this) {
            cached = cache.get(conn.getId());
            if (cached != null && cached.fingerprint().equals(fingerprint)) {
                return cached.client();
            }
            RestClientTransport transport = new RestClientTransport(
                    SwappableElasticsearchTransport.buildRestClient(conn.getEs()), MAPPER);
            Entry fresh = new Entry(fingerprint, new ElasticsearchClient(transport), transport);
            cache.put(conn.getId(), fresh);
            if (cached != null) {
                log.info("[存储配置] ES 连接「{}」参数已变化，客户端已重建：{}",
                        conn.getId(), conn.describe());
                scheduleClose(cached.transport());
            } else {
                log.info("[存储配置] ES 连接「{}」客户端就绪：{}", conn.getId(), conn.describe());
            }
            return fresh.client();
        }
    }

    /** 该连接当前是否已有可用客户端（诊断用；不触发构建） */
    public boolean hasClient(String connectionId) {
        return cache.containsKey(connectionId);
    }

    /** 丢弃某个连接的客户端（连接被删时调用） */
    public void evict(String connectionId) {
        Entry removed = cache.remove(connectionId);
        if (removed != null) {
            scheduleClose(removed.transport());
        }
    }

    /** 只保留给定连接 id 的客户端，其余丢弃 */
    public void retainOnly(Iterable<String> connectionIds) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        connectionIds.forEach(keep::add);
        for (String id : cache.keySet()) {
            if (!keep.contains(id)) {
                evict(id);
            }
        }
    }

    private static String fingerprint(InfraSettings.Elasticsearch es) {
        if (es == null) {
            return "";
        }
        return es.effectiveScheme() + "|" + es.getHost() + "|" + es.effectivePort()
                + "|" + es.getUsername() + "|" + es.getPassword()
                + "|" + es.effectiveConnectTimeout() + "|" + es.effectiveSocketTimeout();
    }

    @Override
    public void close() {
        for (Entry entry : cache.values()) {
            closeQuietly(entry.transport());
        }
        cache.clear();
        DELAYED_CLOSE.shutdownNow();
    }

    private static void scheduleClose(RestClientTransport old) {
        if (old == null) {
            return;
        }
        DELAYED_CLOSE.schedule(() -> closeQuietly(old), GRACE_SECONDS, TimeUnit.SECONDS);
    }

    private static void closeQuietly(RestClientTransport transport) {
        try {
            transport.close();
        } catch (Exception e) {
            log.debug("[存储配置] 关闭旧的 ES 客户端时出错（可忽略）: {}", e.getMessage());
        }
    }

    private record Entry(String fingerprint, ElasticsearchClient client, RestClientTransport transport) {
    }
}
