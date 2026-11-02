package com.zzkingcc.domainplane.domain.rag.retriever;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/**
 * 向量通道专用检索器：查询向量由编排层（{@link CompositeRetriever}）统一算好后
 * 按本接口分发，一轮一个向量。
 *
 * 为什么需要它：向量化上移到编排层（{@link QueryVectorResolver}）后，"把向量送进
 * 检索器"必须有过墙的通道。{@code ContentRetriever.retrieve(Query)} 只带文本，
 * 而 langchain4j 的 {@code Query} 没有挂载数组的位子；把向量塞进检索器实例又会让
 * 缓存复用的检索器在并发轮次之间共享可变状态。用单独的方法参数传：一轮一个向量，
 * 检索器实例保持无状态，并发天然安全。
 *
 * 失败语义（一审修订 A2）：embed 失败时编排层根本不会调用本接口——本轮全部向量
 * 通道按"该路失败"降级，关键词通道照常执行；只有全部通道失败才向上抛。
 *
 * @author zzkingcc
 */
@FunctionalInterface
public interface VectorContentRetriever extends ContentRetriever {

    /**
     * 无向量入口：只有绕过编排层直接调用时才可能走到这里。
     *
     * 向量通道没有向量可用就没有意义，直接抛而不是猜一个空向量——
     * 空向量会让 cosineSimilarity 对全库算出同一个分数，检索结果变成"按入库顺序"
     * 这种看起来正常实则全错的东西。
     */
    @Override
    default List<Content> retrieve(Query query) {
        throw new UnsupportedOperationException("向量通道检索器必须由编排层提供查询向量");
    }

    /**
     * @param query       本轮查询（文本等）
     * @param queryVector 编排层统一算好的查询向量
     */
    List<Content> retrieve(Query query, float[] queryVector);
}
