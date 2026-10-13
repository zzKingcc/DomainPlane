package com.zzkingcc.domainplane.server.config;

import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.Endpoint;
import co.elastic.clients.transport.TransportOptions;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSwappable;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 可热替换的 ES 通信层 —— {@link ElasticsearchTransport} 的委派代理。
 *
 * @author zzkingcc
 */
@Slf4j
public class SwappableElasticsearchTransport implements ElasticsearchTransport, InfraSwappable {

    /**
     * JsonpMapper 全程共用一份：{@code ElasticsearchClient} 在构造时会取一次
     * {@code transport.jsonpMapper()}，若每次替换都换新实例，客户端与 transport
     * 会拿到两个 mapper，序列化行为不再一致。
     */
    private static final JsonpMapper MAPPER = new JacksonJsonpMapper();

    /** 旧实现延迟关闭的宽限期（秒）——留出在途请求跑完的时间 */
    private static final long GRACE_SECONDS = 30;

    private final InfraSettingsHolder holder;

    /** 当前真实实现；替换是原子引用替换 */
    private volatile RestClientTransport delegate;

    public SwappableElasticsearchTransport(InfraSettingsHolder holder) {
        this.holder = holder;
        this.delegate = build(holder.current().getEs());
        log.info("[存储配置] Elasticsearch 通信层就绪：{}", holder.current().getEs().describe());
        // 先建好 delegate 再注册，避免注册后立刻被并发 swap 覆盖
        holder.register(this);
    }

    /**
     * 用一份候选配置构建低层 HTTP 客户端
     */
    public static RestClient buildRestClient(InfraSettings.Elasticsearch es) {
        // 未配置时给一个不指向任何真实服务的占位地址：只要能构建出对象即可。
        // 真实请求由 requireConfigured() 拦下，不会打到这里来。
        String host = es != null && es.isUsable() ? es.getHost() : "localhost";
        int port = es == null ? 9200 : es.effectivePort();
        String scheme = es == null ? "http" : es.effectiveScheme();

        HttpHost httpHost = new HttpHost(host, port, scheme);
        RestClientBuilder builder = RestClient.builder(httpHost)
                .setRequestConfigCallback(cb -> cb
                        .setConnectTimeout(es == null ? 5000 : es.effectiveConnectTimeout())
                        .setSocketTimeout(es == null ? 10000 : es.effectiveSocketTimeout()));

        if (es != null && hasText(es.getUsername()) && hasText(es.getPassword())) {
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(es.getUsername(), es.getPassword()));
            builder.setHttpClientConfigCallback(hcb -> hcb.setDefaultCredentialsProvider(cp));
        }
        return builder.build();
    }

    private static RestClientTransport build(InfraSettings.Elasticsearch es) {
        return new RestClientTransport(buildRestClient(es), MAPPER);
    }

    @Override
    public synchronized void swap() {
        InfraSettings.Elasticsearch cfg = holder.current().getEs();
        RestClientTransport fresh = build(cfg);
        RestClientTransport old = this.delegate;
        this.delegate = fresh;
        log.info("[存储配置] Elasticsearch 通信层已切换：{}", cfg.describe());
        scheduleClose(old);
    }

    private RestClientTransport current() {
        RestClientTransport d = this.delegate;
        if (d == null) {
            // 理论不可达（构造时必定赋值），保底避免 NPE
            synchronized (this) {
                if (this.delegate == null) {
                    this.delegate = build(holder.current().getEs());
                }
                return this.delegate;
            }
        }
        return d;
    }

    /**
     * 未配置守卫：把"压根没配"和"配了但连不上"在调用点就分开。
     */
    private void requireConfigured() {
        if (!holder.isEsConfigured()) {
            throw new NotConfiguredException("Elasticsearch 尚未配置，请先在管控台"
                    + "「存储配置」页填写地址并保存（保存后立即生效，无需重启）");
        }
    }

    // ===== Transport 接口：4 个抽象方法 + close =====

    @Override
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(RequestT request,
                                                                 Endpoint<RequestT, ResponseT, ErrorT> endpoint,
                                                                 TransportOptions options) throws IOException {
        requireConfigured();
        return current().performRequest(request, endpoint, options);
    }

    @Override
    public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
        requireConfigured();
        return current().performRequestAsync(request, endpoint, options);
    }

    @Override
    public JsonpMapper jsonpMapper() {
        return current().jsonpMapper();
    }

    @Override
    public TransportOptions options() {
        return current().options();
    }

    /** Bean 销毁入口（{@code @Bean(destroyMethod = "close")}） */
    @Override
    public void close() throws IOException {
        RestClientTransport d = this.delegate;
        this.delegate = null;
        if (d != null) {
            d.close();
        }
        // 宽限期内还没被关掉的旧实例也要收：它们是 RestClient 的连接池（线程非守护），
        // 而调度线程是守护线程：进程退出时不会等它，那些池线程就无人回收，挂住退出。
        for (RestClientTransport pending : PENDING_CLOSE) {
            closeQuietly(pending);
        }
        DELAYED_CLOSE.shutdownNow();
    }

    /**
     * 延迟关闭专用调度器：固定单线程 + 守护线程。
     */
    private static final java.util.concurrent.ScheduledExecutorService DELAYED_CLOSE =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "domainplane-es-close");
                t.setDaemon(true);
                return t;
            });

    /**
     * 宽限期内待关闭的旧实例。
     *
     * 调度线程本身是守护线程，JVM 退出不会等它：但被它延后关闭的 {@link RestClient}
     * 内部的连接池线程不是守护线程。宽限期内退出进程时，这些实例没人关，池线程无人回收，
     * 进程挂在那里退出不掉。所以销毁入口必须把待关闭的实例一并收掉。
     */
    private static final java.util.Set<RestClientTransport> PENDING_CLOSE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void scheduleClose(RestClientTransport old) {
        if (old == null) {
            return;
        }
        PENDING_CLOSE.add(old);
        DELAYED_CLOSE.schedule(() -> closeQuietly(old), GRACE_SECONDS, TimeUnit.SECONDS);
    }

    private static void closeQuietly(RestClientTransport transport) {
        PENDING_CLOSE.remove(transport);
        try {
            transport.close();
        } catch (Exception e) {
            log.debug("[存储配置] 关闭旧的 ES 通信层时出错（可忽略）: {}", e.getMessage());
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
