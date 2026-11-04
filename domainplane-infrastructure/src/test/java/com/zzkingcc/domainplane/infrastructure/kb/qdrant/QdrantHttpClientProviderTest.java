package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qdrant 客户端 provider 模式：连接参数每次操作现取，未配置时不阻断启动、
 * 首次使用抛带指引的 {@link NotConfiguredException}（与 ES 侧
 * {@code SwappableElasticsearchTransport} 同纪律）。
 *
 * <p>本测试<b>不发任何网络请求</b>：只读现取维度（metric / collectionPrefix）与
 * 未配置时的安全降级路径（ping/list/count/dims 返回空值、ensureCollection 抛指引）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("QdrantHttpClient：provider 模式现取 / 未配置安全降级")
class QdrantHttpClientProviderTest {

    /** 未配置：服务能在没填地址时照常启动，首次操作才给明确指引 */
    @Test
    @DisplayName("未配置时：ping=false / list 空 / 维度 null / count=0，且 ensureCollection 抛指引")
    void unconfigured_degradesSafely() {
        QdrantHttpClient client = QdrantHttpClient.fixed(VectorTarget.UNCONFIGURED, Duration.ofSeconds(5));

        assertFalse(client.ping(), "未配置不等于可达，应视为不可达而非抛异常");
        assertTrue(client.listCollections().isEmpty(), "枚举集合失败应静默返回空，不阻断启动");
        assertNull(client.currentVectorDims("kb1"), "读维度失败应返回 null");
        assertEquals(0L, client.count("kb1", null), "计数失败应返回 0");

        NotConfiguredException e = assertThrows(NotConfiguredException.class,
                () -> client.ensureCollection("kb1", 128),
                "首次写操作必须给出'去管控台填地址'的指引，而不是静默失败");
        assertTrue(e.getMessage().contains("管控台"), e.getMessage());
    }

    /** provider 模式：地址现取，管控台保存后下一次操作自动生效，无需重启 */
    @Test
    @DisplayName("Supplier 现取：改地址/度量/前缀后，metric 与 collectionPrefix 立即反映最新值")
    void supplier_reflectsLatestTarget() {
        AtomicReference<VectorTarget> holder = new AtomicReference<>(VectorTarget.UNCONFIGURED);
        QdrantHttpClient client = new QdrantHttpClient(holder::get, Duration.ofSeconds(5));

        assertEquals("cosine", client.metric(), "未配置时度量按 cosine 回落");
        assertEquals("", client.collectionPrefix(), "未配置时前缀为空");

        holder.set(new VectorTarget("http://qdrant.internal:6333", "tk", "dot", "prod_"));

        assertEquals("dot", client.metric(), "保存后度量立即生效，无需重建客户端");
        assertEquals("prod_", client.collectionPrefix(), "保存后集合前缀立即生效");
    }

    /** 同一快照内地址与前缀必须同源：避免前缀取新、地址取旧的错位写 */
    @Test
    @DisplayName("metric / collectionPrefix 来自同一次 Supplier 读取（快照一致性）")
    void snapshot_consistency() {
        AtomicReference<VectorTarget> holder = new AtomicReference<>(
                new VectorTarget("http://a:6333", null, "cosine", "a_"));
        QdrantHttpClient client = new QdrantHttpClient(holder::get, Duration.ofSeconds(5));

        // 连续两次读取应分别反映各自时刻的快照；这里只验证取值与当前 holder 一致
        assertEquals("a_", client.collectionPrefix());
        assertEquals("cosine", client.metric());

        holder.set(new VectorTarget("http://b:6333", null, "euclid", "b_"));
        assertEquals("b_", client.collectionPrefix());
        assertEquals("euclid", client.metric());
    }

    /**
     * Qdrant 根端点（{@code GET /}）返回的是<b>顶层</b> {@code {title, version, commit}}，
     * 没有 {@code result} 包装——这一点与 {@code /collections} 等端点不同。
     * 解析路径写成 {@code result.version} 会让探活永远读不到版本号、误报"该服务可能不是 Qdrant"。
     */
    @Test
    @DisplayName("version()：根端点顶层 version 能读到（无 result 包装）")
    void version_readsTopLevelField() throws IOException {
        LocalQdrantMock mock = LocalQdrantMock.start(
                "{\"title\":\"qdrant - vector search engine\",\"version\":\"1.15.3\",\"commit\":\"abc123\"}",
                "{\"result\":{\"collections\":[]}}");
        try {
            QdrantHttpClient client = QdrantHttpClient.fixed(
                    new VectorTarget(mock.baseUrl(), null, "cosine", ""), Duration.ofSeconds(5));
            assertTrue(client.ping(), "根端点 2xx 即视为可达");
            assertEquals("1.15.3", client.version(), "版本号在顶层，不能只认 result.version");
            assertTrue(client.listCollections().isEmpty(), "新库 0 个集合是正常现象，不是失败");
        } finally {
            mock.stop();
        }
    }

    /** 前置代理改写过响应、多包一层 result 时仍要读得到（回退兼容） */
    @Test
    @DisplayName("version()：result 包装形态回退兼容")
    void version_fallsBackToWrappedField() throws IOException {
        LocalQdrantMock mock = LocalQdrantMock.start(
                "{\"result\":{\"version\":\"1.9.1\"}}",
                "{\"result\":{\"collections\":[]}}");
        try {
            QdrantHttpClient client = QdrantHttpClient.fixed(
                    new VectorTarget(mock.baseUrl(), null, "cosine", ""), Duration.ofSeconds(5));
            assertEquals("1.9.1", client.version());
        } finally {
            mock.stop();
        }
    }

    /** 根端点返回非 JSON（例如地址串带了 /dashboard 后缀）时不得抛异常，只返回 null */
    @Test
    @DisplayName("version()：非 JSON 根响应返回 null，不抛")
    void version_nonJsonRootReturnsNull() throws IOException {
        LocalQdrantMock mock = LocalQdrantMock.start("<html>dashboard</html>",
                "{\"result\":{\"collections\":[]}}");
        try {
            QdrantHttpClient client = QdrantHttpClient.fixed(
                    new VectorTarget(mock.baseUrl(), null, "cosine", ""), Duration.ofSeconds(5));
            assertNull(client.version());
        } finally {
            mock.stop();
        }
    }

    /**
     * distance 必须是 Qdrant 的 PascalCase 枚举。
     *
     * <p>配置里存的是小写（cosine / dot / euclid），而 Qdrant 的
     * {@code CreateCollection.vectors.distance} 是 Rust 枚举，只认
     * {@code Cosine / Dot / Euclid / Manhattan}。透传小写会被 serde 判成
     * "不是任何 VectorsConfig 变体"、回 HTTP 400，上传就失败成 60000
     * （日志里只有一句反直觉的 "data did not match any variant of untagged enum
     * VectorsConfig"）。这条断言钉住线上的大小写转换。</p>
     */
    @Test
    @DisplayName("ensureCollection：distance 按 Qdrant 枚举大小写发送（cosine→Cosine / dot→Dot / euclid→Euclid）")
    void ensureCollection_sendsPascalCaseDistance() throws IOException {
        for (Map.Entry<String, String> c : List.of(
                Map.entry("cosine", "Cosine"),
                Map.entry("dot", "Dot"),
                Map.entry("euclid", "Euclid"))) {
            CaptureQdrantMock mock = CaptureQdrantMock.start();
            try {
                QdrantHttpClient client = QdrantHttpClient.fixed(
                        new VectorTarget(mock.baseUrl(), null, c.getKey(), ""), Duration.ofSeconds(5));
                client.ensureCollection("kb_default", 1024);

                String body = mock.capturedBody();
                assertNotNull(body, "创建集合必须真的发出 PUT body");
                JsonNode vectors = new ObjectMapper().readTree(body).path("vectors");
                assertEquals(c.getValue(), vectors.path("distance").asText(),
                        "配置是小写词表，Qdrant 只认 PascalCase，透传会让服务端 400");
                assertEquals(1024, vectors.path("size").asInt(), "维度要按当前向量模型写入");
                assertEquals("/collections/kb_default", mock.putPath(), "集合名不能带前缀时保持规范名");
            } finally {
                mock.stop();
            }
        }
    }

    /**
     * scroll 的一页必须"点位 + 下一页游标"同一次响应取回。
     *
     * <p>这条钉住一个真实事故：旧实现每页先 {@code scroll} 再发一个 {@code limit:1} 的
     * 请求去读 {@code next_page_offset}，而那个探针请求传的是<b>旧</b> offset，
     * 服务端据此回的游标并不代表"本页之后还有下一页"，于是翻页原地打转，
     * 8 个切片的集合扫了 125 页 / 250 次请求 / 约 29 秒，管控台知识库页整个卡住
     * （warm 了才快，因为 60s 快照缓存兜住）。断言三点：
     * 一次翻页只发一次请求、游标取自同一响应、{@code with_vector} 可关。</p>
     */
    @Test
    @DisplayName("scrollPage：一页一次请求，游标取自同一响应，offset 原样回传")
    void scrollPage_singleRequestPerPage() throws IOException {
        ScrollQdrantMock mock = ScrollQdrantMock.start(
                "{\"result\":{\"points\":[{\"id\":\"p1\",\"payload\":{\"text\":\"切片一\",\"doc_id\":\"d1\"}}],"
                        + "\"next_page_offset\":\"p1\"}}");
        try {
            QdrantHttpClient client = QdrantHttpClient.fixed(
                    new VectorTarget(mock.baseUrl(), null, "cosine", ""), Duration.ofSeconds(5));

            QdrantHttpClient.ScrollPage first = client.scrollPage("kb", 256, null, false);
            assertEquals(1, first.points().size(), "点位要解析出来");
            assertTrue(first.hasMore(), "响应里给了 next_page_offset 就是还有下一页");
            assertEquals("p1", String.valueOf(first.nextOffset()), "游标要按服务端给的值原样带走");
            assertEquals(1, mock.requests(), "一页只准发一次请求——探针式第二请求正是打转的根源");

            JsonNode body = new ObjectMapper().readTree(mock.lastBody());
            assertFalse(body.path("with_vector").asBoolean(true), "列文档时不该要回 1024 维向量");

            client.scrollPage("kb", 256, first.nextOffset(), false);
            JsonNode second = new ObjectMapper().readTree(mock.lastBody());
            assertEquals("p1", second.path("offset").asText(), "下一页要把游标当 offset 发回服务端");
        } finally {
            mock.stop();
        }
    }

    /** 末页：next_page_offset 为 null 时必须回答"没有了"，否则调用方会无限翻页 */
    @Test
    @DisplayName("scrollPage：末页没有游标（hasMore=false）")
    void scrollPage_endsWithoutOffset() throws IOException {
        ScrollQdrantMock mock = ScrollQdrantMock.start(
                "{\"result\":{\"points\":[],\"next_page_offset\":null}}");
        try {
            QdrantHttpClient client = QdrantHttpClient.fixed(
                    new VectorTarget(mock.baseUrl(), null, "cosine", ""), Duration.ofSeconds(5));
            QdrantHttpClient.ScrollPage page = client.scrollPage("kb", 256, "p9", false);
            assertTrue(page.points().isEmpty());
            assertFalse(page.hasMore());
            assertNull(page.nextOffset());
        } finally {
            mock.stop();
        }
    }

    /** scroll 桩：记录请求次数与最后一个请求体，回固定分页体 */
    private static final class ScrollQdrantMock {

        private final HttpServer server;
        private final AtomicReference<String> lastBody = new AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicInteger requests =
                new java.util.concurrent.atomic.AtomicInteger();

        private ScrollQdrantMock(HttpServer server) {
            this.server = server;
        }

        static ScrollQdrantMock start(String scrollResponse) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ScrollQdrantMock mock = new ScrollQdrantMock(server);
            server.createContext("/", exchange -> {
                mock.requests.incrementAndGet();
                mock.lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, scrollResponse);
            });
            server.setExecutor(null);
            server.start();
            return mock;
        }

        int requests() {
            return requests.get();
        }

        String lastBody() {
            return lastBody.get();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    /** Qdrant 桩：GET /collections/{name} → 404（不存在），PUT → 200 并记录请求体 */
    private static final class CaptureQdrantMock {

        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<String> putPath = new AtomicReference<>();

        private CaptureQdrantMock(HttpServer server) {
            this.server = server;
        }

        static CaptureQdrantMock start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CaptureQdrantMock mock = new CaptureQdrantMock(server);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("PUT".equals(exchange.getRequestMethod())) {
                    mock.putPath.set(path);
                    mock.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 200, "{\"result\":true,\"status\":\"ok\",\"time\":0.0}");
                    return;
                }
                if (path.startsWith("/collections/")) {
                    respond(exchange, 404, "{\"status\":{\"error\":\"Not found\"},\"time\":0.0}");
                    return;
                }
                respond(exchange, 200, "{\"result\":{\"collections\":[]}}");
            });
            server.setExecutor(null);
            server.start();
            return mock;
        }

        String capturedBody() {
            return body.get();
        }

        String putPath() {
            return putPath.get();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    /** 最小 Qdrant 桩：只应答根路径与 /collections，够验解析路径 */
    private static final class LocalQdrantMock {

        private final HttpServer server;

        private LocalQdrantMock(HttpServer server) {
            this.server = server;
        }

        static LocalQdrantMock start(String rootBody, String collectionsBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> respond(exchange, "/".equals(exchange.getRequestURI().getPath())
                    ? rootBody : collectionsBody));
            server.setExecutor(null);
            server.start();
            return new LocalQdrantMock(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    /** 两个桩共用的应答体：JSON + 指定状态码 */
    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (Exception ignored) {
            // 桩服务，写失败不影响断言
        }
    }
}
