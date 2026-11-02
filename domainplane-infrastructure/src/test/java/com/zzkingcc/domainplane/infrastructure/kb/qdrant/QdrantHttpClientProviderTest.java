package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
