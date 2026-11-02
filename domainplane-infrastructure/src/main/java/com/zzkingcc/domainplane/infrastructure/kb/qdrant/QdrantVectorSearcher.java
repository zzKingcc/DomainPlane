package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.zzkingcc.domainplane.common.util.LogSanitizer;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Qdrant 版 {@link VectorSearcher}：向量检索。
 *
 * <p>与 ES 适配器的差别只在方言：ES 用 {@code cosineSimilarity+1.0} 的 Painless
 * 脚本并把偏移关在类内，Qdrant 直接由服务端按 collection 的 distance 度量算分。
 * 两边的分数都<b>不外泄到配置层</b>——阈值是后端自己的配置
 * （{@code domainplane.knowledge.backends.<id>.vector.min-score}）。</p>
 *
 * <p><b>距离度量与分数方向</b>：Qdrant 的 distance 可配 cosine / dot / euclid。
 * cosine 与 dot 下分数越大越相似；euclid 下分数越小越近——但 euclid 不在
 * {@code minScore} 语义域内（阈值是"相似度下限"），所以本适配器只放行
 * cosine / dot，euclid 会被拒绝而不是悄悄给出方向相反的分数。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class QdrantVectorSearcher implements VectorSearcher {

    private final QdrantHttpClient client;
    private final double minScore;

    /**
     * 距离度量是否需要做"距离 → 相似度"换算。
     *
     * <p>Qdrant 的 euclid / manhattan 是<b>距离</b>（越小越近），与 cosine / dot 的
     * <b>相似度</b>（越大越近）方向相反。SPI 契约要求命中按分数降序、KbHit 的分数
     * "越大越好"，所以 euclid 必须换算成同向的相似度再出 SPI——
     * 否则混合后端融合时这一路会倒着排（而且不会有任何报错，只是排得离谱）。</p>
     */
    private final boolean distanceMetric;

    public QdrantVectorSearcher(QdrantHttpClient client, double minScore) {
        this.client = client;
        this.minScore = minScore;
        String metric = client.metric();
        this.distanceMetric = "euclid".equals(metric) || "manhattan".equals(metric);
    }

    /** 距离 → 相似度的单调递减换算（1/(1+d)）：保序、值域 (0,1]，越大越近。 */
    private static double toSimilarity(double distance) {
        return 1.0d / (1.0d + Math.max(0.0d, distance));
    }

    @Override
    public List<KbHit> search(String collection, float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        try {
            List<QdrantScored> scored = client.searchByVector(collection, queryVector, topK);
            List<KbHit> out = new ArrayList<>();
            for (QdrantScored s : scored) {
                double score = distanceMetric ? toSimilarity(s.score()) : s.score();
                // 阈值过滤放在适配器内：SPI 入参不含阈值（设计第 5 节）
                if (score < minScore) {
                    continue;
                }
                out.add(new KbHit(s.id(), s.text(), s.metadata(), score));
            }
            // queryText 不在这里（向量路没有原文查询），只记命中数
            log.info("[Qdrant向量检索] 查询完成，度量={}，设定{}条，命中{}条",
                    client.metric(), topK, out.size());
            return out;
        } catch (Exception e) {
            log.error("[Qdrant向量检索] 查询失败: {}", e.getMessage(), e);
            throw new StoreAccessException("Qdrant 向量检索失败: " + e.getMessage(), e);
        }
    }
}
