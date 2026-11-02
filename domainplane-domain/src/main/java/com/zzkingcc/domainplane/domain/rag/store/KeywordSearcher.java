package com.zzkingcc.domainplane.domain.rag.store;

import java.util.List;

/**
 * 关键词检索（BM25 类）：与向量检索**分开的独立能力**，启用哪一路由部署方配置
 * （{@code domainplane.knowledge.strategy}）决定。
 *
 * 失败语义：抛 {@link StoreAccessException}——通道层按"该路失败"降级
 * （{@code CompositeRetriever} 既有语义：单通道失败不影响其它通道，全部失败才抛）。
 *
 * @author zzkingcc
 */
public interface KeywordSearcher {

    /**
     * @param collection 目标 collection
     * @param queryText  查询文本（原始问句，分词归后端）
     * @param topK       最多返回多少条
     * @return 命中列表，按该后端分数降序；分数契约见 {@link KbHit}
     */
    List<KbHit> search(String collection, String queryText, int topK);
}
