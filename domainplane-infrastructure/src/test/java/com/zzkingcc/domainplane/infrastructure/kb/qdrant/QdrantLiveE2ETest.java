package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qdrant <b>真实实例</b>端到端（F05 P2-b）。
 *
 * <p>设计文档把"真实向量库实例的检索 / 重灌验证"列为 P2 未完成项，原因就是本机起不了
 * 实例（无 Docker）。这里把它做成<b>环境门控</b>测试：给了地址就真打，没给就跳过——
 * 于是"待有实例环境补跑"不再是一句备注，而是一个随时能跑的用例。</p>
 *
 * <p>启用方式（二选一）：</p>
 * <pre>
 *   mvn -o test -Ddomainplane.qdrant.url=http://127.0.0.1:6333 -Dtest=QdrantLiveE2ETest
 *   QDRANT_URL=http://127.0.0.1:6333 mvn -o test -Dtest=QdrantLiveE2ETest
 * </pre>
 *
 * <p>本机（无实例）的默认行为是 <b>skip</b>，不影响构建。请求体级别的契约已由
 * {@link QdrantKnowledgeStoreKeywordOnlyTest} 用假客户端在本地验证过了，
 * 这个用例验证的是"真实 Qdrant 真的接受这套请求"。</p>
 *
 * @author zzkingcc
 */
@DisplayName("Qdrant 真实实例端到端（P2-b，无实例则跳过）")
class QdrantLiveE2ETest {

    private static final String URL_PROP = "domainplane.qdrant.url";
    private static final String URL_ENV = "QDRANT_URL";
    private static final int DIMS = 4;
    private static final String PREFIX = "p2e2e_";

    private static final float[] V1 = new float[]{0.1f, 0.2f, 0.3f, 0.4f};

    @Test
    @DisplayName("纯 keyword 后端：建无向量集合 → 写 payload-only → 关键词检索命中；带向量后端：写向量 → 向量检索命中")
    void live_keywordOnlyAndVectorBackends() {
        String url = System.getProperty(URL_PROP);
        if (url == null || url.isBlank()) {
            url = System.getenv(URL_ENV);
        }
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 -D" + URL_PROP + " 或环境变量 " + URL_ENV + " → 跳过（本机无向量库实例）");

        QdrantHttpClient client =
                QdrantHttpClient.fixed(new VectorTarget(url, null, "cosine", PREFIX),
                        Duration.ofSeconds(10));
        Assumptions.assumeTrue(client.ping(), "Qdrant 不可达：" + url);

        // ---------- 纯 keyword 后端 ----------
        QdrantKnowledgeStore keywordOnly = new QdrantKnowledgeStore(client, false);
        assertFalse(keywordOnly.capabilities().vector(), "纯 keyword 后端声明 vector=false");
        assertFalse(keywordOnly.capabilities().reindexInPlace(), "不存向量 → 不能作为重灌目标");

        keywordOnly.deleteCollection("kw");
        keywordOnly.ensureCollection("kw", DIMS); // 走无向量分支
        // 切片带着向量进来：纯 keyword 后端应当只落 payload
        keywordOnly.write("kw", List.of(new KbChunk("kw-1", "专项标记词Alpha",
                Map.of("doc_id", "d1"), V1)));

        List<KbHit> hits = new QdrantKeywordSearcher(client).search("kw", "Alpha", 10);
        assertTrue(hits.stream().anyMatch(h -> h.text().contains("Alpha")),
                "写入的正文应能被关键词检索命中（集合无向量、点位无向量）");

        // ---------- 带向量后端（对照）----------
        QdrantKnowledgeStore full = new QdrantKnowledgeStore(client, true);
        assertTrue(full.capabilities().vector());

        full.deleteCollection("vec");
        full.ensureCollection("vec", DIMS);
        full.write("vec", List.of(new KbChunk("vec-1", "专项标记词Beta",
                Map.of("doc_id", "d2"), V1)));

        List<QdrantScored> vecHits = client.searchByVector("vec", V1, 10);
        assertTrue(vecHits.stream().anyMatch(s -> s.text().contains("Beta")),
                "带向量后端应能被向量检索命中");
    }
}
