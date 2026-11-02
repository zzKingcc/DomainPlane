package com.zzkingcc.domainplane.domain.rag.store;

import java.util.List;

/**
 * 向量检索：与关键词检索分开的独立能力。
 *
 * **只收向量，不收文本**：向量化上移到编排层（{@link QueryVectorResolver}），
 * 一轮检索一次 embed 供全部向量通道复用——现状每条向量通道各 embed 一次，
 * 域链 5 层 = 5 次相同 query 的嵌入调用。
 *
 * 失败语义同 {@link KeywordSearcher}：抛 {@link StoreAccessException}，
 * 按"该路失败"降级。
 *
 * @author zzkingcc
 */
public interface VectorSearcher {

    /**
     * @param collection  目标 collection
     * @param queryVector 查询向量（编排层已算好）
     * @param topK        最多返回多少条
     * @return 命中列表，按该后端分数降序；分数契约见 {@link KbHit}
     */
    List<KbHit> search(String collection, float[] queryVector, int topK);
}
