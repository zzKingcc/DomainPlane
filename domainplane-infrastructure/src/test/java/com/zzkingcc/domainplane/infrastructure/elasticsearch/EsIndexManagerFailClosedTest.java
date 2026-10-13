package com.zzkingcc.domainplane.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-03：索引存在性查询必须 fail-closed。
 *
 * <p>这组测试守的是一个具体的静默错误：{@code exists} 查询失败时若返回 {@code false}，
 * {@link EsIndexManager#deleteIndex} 会认为"没有东西可删"直接跳过，删域流程照样往下走并
 * 报告成功，域没了索引还在。而索引名是<b>域路径的哈希</b>，同一路径域将来重建时索引名相同，
 * 这份本该删掉的知识内容会原样复活，全程零报错。</p>
 *
 * <p>对照组是 {@link EsIndexManager#listIndices}：它<b>必须保持 fail-open</b>。
 * 同一批改动不能顺手把它也改成抛异常，那是管控台列表页，ES 抖一下代价是多显示一行空，
 * 抛异常的代价是页面整个打不开。两种处理方式并存，正是"看接收方是人还是机器"的判据。</p>
 *
 * @author zzkingcc
 */
@DisplayName("I-03 索引存在性查询：破坏性路径 fail-closed，只读展示 fail-open")
class EsIndexManagerFailClosedTest {

    // ==================== 桩 ====================

    /**
     * 可编程的 indices 客户端桩。
     *
     * <p>{@code exists(ExistsRequest)} / {@code delete(DeleteIndexRequest)} / {@code get(GetIndexRequest)}
     * 三个非 final 方法在生产代码里正是被直接调用的那三个（Function 版是 final 的桥接），
     * 所以覆盖它们能真实命中 {@link EsIndexManager} 的执行路径。</p>
     */
    private static class StubIndices extends ElasticsearchIndicesClient {

        /** 存在性查询的答案；为 null 时改为抛异常（模拟 ES 不可达 / 超时 / 集群 red） */
        Boolean existsAnswer;
        IOException existsFailure;
        /** 删除操作的失败原因；null 表示删除成功 */
        IOException deleteFailure;
        /** 枚举索引返回的索引名；null 表示抛异常 */
        List<String> listAnswer;
        IOException listFailure;

        final AtomicInteger existsCalls = new AtomicInteger();
        final AtomicInteger deleteCalls = new AtomicInteger();
        final AtomicInteger listCalls = new AtomicInteger();
        final List<String> deletedIndices = new ArrayList<>();

        StubIndices() {
            super((ElasticsearchTransport) null);
        }

        @Override
        public BooleanResponse exists(ExistsRequest request) throws IOException {
            existsCalls.incrementAndGet();
            if (existsFailure != null) {
                throw existsFailure;
            }
            return new BooleanResponse(Boolean.TRUE.equals(existsAnswer));
        }

        @Override
        public DeleteIndexResponse delete(DeleteIndexRequest request) throws IOException {
            deleteCalls.incrementAndGet();
            // ES 客户端的 DeleteIndexRequest.index() 是 List（单删也用列表传）
            deletedIndices.addAll(request.index());
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            // DeleteIndexResponse 只有 acknowledged/shards 字段，索引名在 request 上
            return DeleteIndexResponse.of(b -> b.acknowledged(true));
        }

        @Override
        public GetIndexResponse get(GetIndexRequest request) throws IOException {
            listCalls.incrementAndGet();
            if (listFailure != null) {
                throw listFailure;
            }
            Map<String, co.elastic.clients.elasticsearch.indices.IndexState> indices = new LinkedHashMap<>();
            for (String name : listAnswer) {
                indices.put(name, null);
            }
            return GetIndexResponse.of(b -> b.indices(indices));
        }
    }

    /** 只把 {@code indices()} 换成桩，其余保持真实客户端形态 */
    private static class StubClient extends ElasticsearchClient {

        private final ElasticsearchIndicesClient stub;

        StubClient(ElasticsearchIndicesClient stub) {
            super((ElasticsearchTransport) null);
            this.stub = stub;
        }

        @Override
        public ElasticsearchIndicesClient indices() {
            return stub;
        }
    }

    private static ElasticsearchClient client(StubIndices stub) {
        return new StubClient(stub);
    }

    // ==================== exists：查询失败必须抛 ====================

    @Test
    @DisplayName("exists 查询失败时抛 EsAccessException，绝不返回 false")
    void existsThrowsWhenQueryFails() {
        StubIndices stub = new StubIndices();
        stub.existsFailure = new IOException("Connection refused");

        EsAccessException e = assertThrows(EsAccessException.class,
                () -> EsIndexManager.exists(client(stub), "domainplane_kb_abc123"));

        // 文案必须点明"无法确认 ≠ 不存在"，否则排障时会误判成索引本来就没有
        assertTrue(e.getMessage().contains("无法确认"), "文案要说明这不是'不存在'：" + e.getMessage());
        assertTrue(e.getMessage().contains("domainplane_kb_abc123"), "文案要带上索引名：" + e.getMessage());
        assertNotNull(e.getCause(), "原始异常要保留，否则无法判断是超时还是连接被拒");
        assertEquals("Connection refused", e.getCause().getMessage());
    }

    @Test
    @DisplayName("exists 查询失败时是抛异常，不是返回 false（显式对照）")
    void existsDoesNotSilentlyReturnFalse() {
        StubIndices stub = new StubIndices();
        stub.existsFailure = new IOException("timeout");

        // 这一条是整组测试的核心断言：把"抛"改成"返回 false"时，本测试必须红
        assertThrows(EsAccessException.class,
                () -> EsIndexManager.exists(client(stub), "idx"),
                "查询失败被当成'不存在'会让删域静默跳过索引删除");
    }

    @Test
    @DisplayName("exists 正常返回 true / false 时如实透传")
    void existsPassesThroughRealAnswers() {
        StubIndices stub = new StubIndices();
        stub.existsAnswer = true;
        assertTrue(EsIndexManager.exists(client(stub), "idx"));

        stub.existsAnswer = false;
        assertFalse(EsIndexManager.exists(client(stub), "idx"));

        assertEquals(2, stub.existsCalls.get(), "每次调用只该问一次");
    }

    // ==================== deleteIndex：任一步失败都必须中止 ====================

    @Test
    @DisplayName("deleteIndex：存在性查询失败时中止，且不发出删除请求")
    void deleteIndexAbortsWhenExistsQueryFails() {
        StubIndices stub = new StubIndices();
        stub.existsAnswer = true;                 // 假设索引确实在
        stub.existsFailure = new IOException("cluster red");

        assertThrows(EsAccessException.class,
                () -> EsIndexManager.deleteIndex(client(stub), "domainplane_kb_abc123"));

        // 关键：不能在"没问到答案"的情况下还发删除，那是把决策建立在未知上
        assertEquals(0, stub.deleteCalls.get(), "存在性没确认前不得发出删除请求");
    }

    @Test
    @DisplayName("deleteIndex：删除本身失败时抛 EsAccessException")
    void deleteIndexAbortsWhenDeleteFails() {
        StubIndices stub = new StubIndices();
        stub.existsAnswer = true;
        stub.deleteFailure = new IOException("index locked");

        EsAccessException e = assertThrows(EsAccessException.class,
                () -> EsIndexManager.deleteIndex(client(stub), "domainplane_kb_abc123"));

        assertTrue(e.getMessage().contains("删除索引"), "文案要能区分'查失败'与'删失败'：" + e.getMessage());
        assertEquals(1, stub.deleteCalls.get());
        assertEquals(List.of("domainplane_kb_abc123"), stub.deletedIndices);
    }

    @Test
    @DisplayName("deleteIndex：确认不存在时返回 false（不抛，这是真的'没东西可删'）")
    void deleteIndexReturnsFalseWhenConfirmedAbsent() {
        StubIndices stub = new StubIndices();
        stub.existsAnswer = false;

        assertFalse(EsIndexManager.deleteIndex(client(stub), "idx"));
        assertEquals(0, stub.deleteCalls.get(), "不存在时不该发删除请求");
    }

    @Test
    @DisplayName("deleteIndex：正常路径返回 true")
    void deleteIndexReturnsTrueOnSuccess() {
        StubIndices stub = new StubIndices();
        stub.existsAnswer = true;

        assertTrue(EsIndexManager.deleteIndex(client(stub), "domainplane_kb_abc123"));
        assertEquals(1, stub.deleteCalls.get());
    }

    // ==================== listIndices：必须保持 fail-open ====================

    @Test
    @DisplayName("listIndices：ES 异常时返回空列表而不是抛异常（只读展示 fail-open）")
    void listIndicesStaysFailOpen() {
        StubIndices stub = new StubIndices();
        stub.listFailure = new IOException("Connection refused");

        // 与 exists 刻意相反：这里返回空列表是对的
        assertEquals(List.of(), EsIndexManager.listIndices(client(stub), "domainplane_kb_*"));
        assertEquals(1, stub.listCalls.get());
    }

    @Test
    @DisplayName("listIndices：正常时返回按字典序排好的索引名")
    void listIndicesReturnsSortedNames() {
        StubIndices stub = new StubIndices();
        stub.listAnswer = List.of("domainplane_kb_c", "domainplane_kb_a", "domainplane_kb_b");

        assertEquals(List.of("domainplane_kb_a", "domainplane_kb_b", "domainplane_kb_c"),
                EsIndexManager.listIndices(client(stub), "domainplane_kb_*"));
    }

    // ==================== 异常类型契约 ====================

    @Test
    @DisplayName("EsAccessException 是 RuntimeException，捕获它的地方必须当成失败")
    void esAccessExceptionIsUncheckedButFailClosed() {
        EsAccessException e = new EsAccessException("msg");
        assertSame(RuntimeException.class, EsAccessException.class.getSuperclass(),
                "继承 unchecked 只是为了不污染调用链签名，不代表可以忽略");
        assertEquals("msg", e.getMessage());
        assertEquals("cause", new EsAccessException("msg", new IllegalStateException("cause")).getCause().getMessage());
    }
}
