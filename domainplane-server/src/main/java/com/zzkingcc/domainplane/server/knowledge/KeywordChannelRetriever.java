package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/**
 * 关键词通道检索器：把 SPI 的 {@link KeywordSearcher} 包成编排层认识的
 * {@link ContentRetriever}。
 *
 * 无状态（collection 与 topK 定死，检索全走入参），可被 {@code DomainChannelProvider}
 * 按 {@code backendId|collection|modality} 缓存复用。
 *
 * topK 是通用配置，每次调用随 {@code search} 传入——不在构造器里，因为同一后端
 * 的同一 collection 在通配降级等路径上会以不同 topK 复用同一个检索器实例。
 *
 * @author zzkingcc
 */
public class KeywordChannelRetriever implements ContentRetriever {

    private final KeywordSearcher searcher;
    private final String collection;
    private final int topK;

    public KeywordChannelRetriever(KeywordSearcher searcher, String collection, int topK) {
        this.searcher = searcher;
        this.collection = collection;
        this.topK = topK;
    }

    @Override
    public List<Content> retrieve(Query query) {
        return KbHitContents.toContents(searcher.search(collection, query.text(), topK));
    }
}
