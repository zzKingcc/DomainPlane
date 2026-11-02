package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 keyword 后端（P2）的<b>写入形态</b>：集合不带向量、点位不带向量。
 *
 * <p>本机没有向量库实例（无 Docker），所以这里不去打真实网络，而是覆写
 * {@link QdrantHttpClient#send} 做一层假客户端：出网请求被拦下<b>记录在案</b>，
 * 断言的是"实际会发给 Qdrant 的请求体长什么样"。这是 P2 新增行为的本地契约验证——
 * 无向量集合（{@code body} 不含 {@code vectors}）与 payload-only 点位
 * （点位不含 {@code vector}）两条，正是纯 keyword 后端与 P1 全能力后端的唯一实质差异。</p>
 *
 * <p>假客户端只模拟到"够走完被测路径"：查集合返回 404（不存在 → 走到创建分支）、
 * 写集合与写点位返回成功、{@code /points/query} 返回一 canned 命中。</p>
 *
 * @author zzkingcc
 */
@DisplayName("QdrantKnowledgeStore：纯 keyword 写入形态（P2）")
class QdrantKnowledgeStoreKeywordOnlyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 关键词检索的 canned 命中（验证 payload 全文 match 的解析）。 */
    private static final String QUERY_HITS =
            "{\"result\":[{\"id\":\"p1\",\"payload\":{\"text\":\"切片正文\",\"doc_id\":\"d1\"},"
                    + "\"score\":1.75}]}";

    /** 假 Qdrant：拦下出网请求，只记录最后一条请求的方法 / 路径 / 请求体。 */
    private static final class FakeQdrantClient extends QdrantHttpClient {

        private String lastMethod;
        private String lastPath;
        private String lastBody;

        FakeQdrantClient() {
            // 指向一个不存在的本地地址：send 被覆写，永远不会真的出网
            super(() -> new VectorTarget("http://127.0.0.1:6333", null, "cosine", ""),
                    Duration.ofSeconds(1));
        }

        @Override
        HttpResponse<String> send(VectorTarget t, String method, String path, String body) {
            lastMethod = method;
            lastPath = path;
            lastBody = body;
            if ("POST".equals(method) && path.contains("/points/query")) {
                return response(200, QUERY_HITS);
            }
            // 查集合 → 404：让 ensure 走到"创建"分支（exists=false）
            if ("GET".equals(method) && path.startsWith("/collections/")) {
                return response(404, "{\"status\":\"not_found\"}");
            }
            return response(200, "{\"result\":{\"status\":\"ok\"}}");
        }

        private static HttpResponse<String> response(int status, String body) {
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public String body() {
                    return body;
                }

                @Override
                public HttpRequest request() {
                    return null;
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.<String, List<String>>of(), (a, b) -> true);
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }

                @Override
                public URI uri() {
                    return URI.create("http://127.0.0.1:6333" + "/");
                }

                @Override
                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }
            };
        }
    }

    private static JsonNode lastBodyAs(FakeQdrantClient client) throws Exception {
        return MAPPER.readTree(client.lastBody == null ? "{}" : client.lastBody);
    }

    @Test
    @DisplayName("纯 keyword 的 store 能力声明：vector=false / reindexInPlace=false")
    void capabilities_keywordOnly() {
        QdrantKnowledgeStore store = new QdrantKnowledgeStore(new FakeQdrantClient(), false);
        StoreCapabilities c = store.capabilities();
        assertTrue(c.keyword(), "关键词能力是它存在的意义");
        assertFalse(c.vector(), "不存向量");
        assertTrue(c.storesText(), "正文存在 payload 里");
        assertFalse(c.reindexInPlace(), "收不下向量 → 不能作为重灌目标");
    }

    @Test
    @DisplayName("带向量的 store 能力声明不变（P1 回归）")
    void capabilities_full() {
        QdrantKnowledgeStore store = new QdrantKnowledgeStore(new FakeQdrantClient(), true);
        StoreCapabilities c = store.capabilities();
        assertTrue(c.keyword());
        assertTrue(c.vector());
        assertTrue(c.storesText());
        assertTrue(c.reindexInPlace());
    }

    @Test
    @DisplayName("纯 keyword 建集合：请求体不含 vectors 段（无向量集合）")
    void ensureCollection_noVectorForKeywordOnly() throws Exception {
        FakeQdrantClient client = new FakeQdrantClient();
        new QdrantKnowledgeStore(client, false).ensureCollection("kb-default", 1536);

        JsonNode body = lastBodyAs(client);
        assertFalse(body.has("vectors"),
                "纯 keyword 建的是无向量集合：请求体不该带 vectors 段（带了他就得按维度存向量）");
    }

    @Test
    @DisplayName("带向量建集合：请求体含 vectors.size / distance（P1 回归）")
    void ensureCollection_withVectorForFull() throws Exception {
        FakeQdrantClient client = new FakeQdrantClient();
        new QdrantKnowledgeStore(client, true).ensureCollection("kb-default", 1536);

        JsonNode body = lastBodyAs(client);
        assertTrue(body.has("vectors"));
        assertEquals(1536, body.get("vectors").get("size").asInt());
        assertEquals("cosine", body.get("vectors").get("distance").asText());
    }

    @Test
    @DisplayName("纯 keyword 写入：点位不带 vector，正文与元数据照写（payload-only）")
    void write_dropsVectorForKeywordOnly() throws Exception {
        FakeQdrantClient client = new FakeQdrantClient();
        QdrantKnowledgeStore store = new QdrantKnowledgeStore(client, false);
        // 切片本体带着向量（摄取侧统一 embed）："该不该落"由后端能力决定，不是由切片决定
        store.write("kb-default", List.of(new KbChunk("p1", "切片正文",
                Map.of("doc_id", "d1"), new float[]{0.1f, 0.2f})));

        JsonNode point = lastBodyAs(client).get("points").get(0);
        assertFalse(point.has("vector"), "纯 keyword 后端不存向量：点位必须不带 vector");
        assertEquals("p1", point.get("id").asText());
        assertEquals("切片正文", point.get("payload").get("text").asText());
        assertEquals("d1", point.get("payload").get("doc_id").asText());
    }

    @Test
    @DisplayName("带向量写入：点位带 vector（P1 回归）")
    void write_keepsVectorForFull() throws Exception {
        FakeQdrantClient client = new FakeQdrantClient();
        QdrantKnowledgeStore store = new QdrantKnowledgeStore(client, true);
        store.write("kb-default", List.of(new KbChunk("p1", "切片正文", Map.of(),
                new float[]{0.1f, 0.2f})));

        JsonNode point = lastBodyAs(client).get("points").get(0);
        assertTrue(point.has("vector"));
        assertEquals(2, point.get("vector").size());
    }

    @Test
    @DisplayName("关键词检索走 payload 全文 match，命中正确解析回 KbHit")
    void keywordSearch_parsesTextHits() {
        FakeQdrantClient client = new FakeQdrantClient();
        List<KbHit> hits = new QdrantKeywordSearcher(client).search("kb-default", "查询词", 10);

        assertEquals(1, hits.size());
        assertEquals("切片正文", hits.get(0).text());
        assertEquals(1.75, hits.get(0).score(), 1e-6);
        assertEquals("d1", hits.get(0).metadata().get("doc_id"));
    }
}
