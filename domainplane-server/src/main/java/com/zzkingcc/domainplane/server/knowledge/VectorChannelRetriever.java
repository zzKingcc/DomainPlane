package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import com.zzkingcc.domainplane.domain.rag.retriever.VectorContentRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/**
 * 向量通道检索器：把 SPI 的 {@link VectorSearcher} 包成编排层认识的
 * {@link VectorContentRetriever}。
 *
 * 查询向量由编排层（{@code CompositeRetriever}）统一算好后经
 * {@link #retrieve(Query, float[])} 传入——一轮一个向量，全部向量通道复用；
 * 本实例自身无状态，可按 {@code backendId|collection|modality} 缓存复用。
 *
 * @author zzkingcc
 */
public class VectorChannelRetriever implements VectorContentRetriever {

    private final VectorSearcher searcher;
    private final String collection;
    private final int topK;

    public VectorChannelRetriever(VectorSearcher searcher, String collection, int topK) {
        this.searcher = searcher;
        this.collection = collection;
        this.topK = topK;
    }

    @Override
    public List<Content> retrieve(Query query, float[] queryVector) {
        return KbHitContents.toContents(searcher.search(collection, queryVector, topK));
    }
}
