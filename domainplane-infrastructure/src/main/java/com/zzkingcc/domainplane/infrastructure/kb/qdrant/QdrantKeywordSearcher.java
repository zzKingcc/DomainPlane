package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.zzkingcc.domainplane.common.util.LogSanitizer;
import com.zzkingcc.domainplane.domain.rag.store.KbHit;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Qdrant 版 {@link KeywordSearcher}：payload 全文检索。
 *
 * <p>与 ES 适配器的 BM25 路同层同契约（SPI 分数契约：同后端同模态内可比，
 * 不承诺跨后端可比）。差别：ES 走 IK 分词的 multi_match（四字段加权），
 * Qdrant 走 payload 上的全文索引 match——分词由服务端负责，
 * 字段加权在本适配器里不做（Qdrant 的 match 不支持字段权重）。</p>
 *
 * <p>这个能力存在的意义是<b>证明关键词路也能离开 ES</b>：配置成
 * {@code keyword-backend: qdrant-1} 时，整条关键词召回不经过 ES。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class QdrantKeywordSearcher implements KeywordSearcher {

    private final QdrantHttpClient client;

    public QdrantKeywordSearcher(QdrantHttpClient client) {
        this.client = client;
    }

    @Override
    public List<KbHit> search(String collection, String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        try {
            List<QdrantScored> scored = client.searchByText(collection, queryText, topK);
            List<KbHit> out = new ArrayList<>();
            for (QdrantScored s : scored) {
                out.add(new KbHit(s.id(), s.text(), s.metadata(), s.score()));
            }
            // queryText 是用户提问原文，只记长度不记内容
            log.info("[Qdrant关键词检索] 查询完成，查询{}，设定{}条，命中{}条",
                    LogSanitizer.describeUserText(queryText), topK, out.size());
            return out;
        } catch (Exception e) {
            log.error("[Qdrant关键词检索] 查询失败: {}", e.getMessage(), e);
            throw new StoreAccessException("Qdrant 关键词检索失败: " + e.getMessage(), e);
        }
    }
}
