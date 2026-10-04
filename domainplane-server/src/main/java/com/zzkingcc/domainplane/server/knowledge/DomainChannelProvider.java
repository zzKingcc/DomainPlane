package com.zzkingcc.domainplane.server.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.retriever.CompositeRetriever;
import com.zzkingcc.domainplane.domain.rag.retriever.CompositeRetriever.Channel;
import com.zzkingcc.domainplane.infrastructure.elasticsearch.retriever.KeywordMatchContentRetriever;
import com.zzkingcc.domainplane.infrastructure.elasticsearch.retriever.NativeScriptScoreContentRetriever;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按<b>当前检索域</b>解析召回通道：一域一索引 → 每个「域链上的索引 × 两路模态」各一条通道。
 *
 * <p>域链 = {@code Domains.chainOf(当前域)}，即<b>自身 + 全部祖先</b>（含根域），
 * 与工具可见性、提示词的同构语义一致：挂在父域的内容，子域天然可用。</p>
 *
 * <p>不预判索引是否存在 —— 某条祖先域还没上传过任何文档时它没有索引，此时通道安静地返回空
 * （检索器开了 {@code ignoreUnavailable / allowNoIndices}），比先探测一遍省掉 N 次往返。</p>
 *
 * <p>每条通道带 <b>depth</b>（距查询域的距离，0 = 自身），供融合阶段做层级衰减：
 * 本域自有知识优先于从祖先继承来的知识。</p>
 *
 * <p>未绑定域（非对话路径：管控台预览、诊断等）走<b>通配</b>：对全部知识库索引做一次检索，
 * 保持升级前"全库检索"的行为，避免这些调用凭空查不到东西。</p>
 *
 * @author zzkingcc
 */
public class DomainChannelProvider implements CompositeRetriever.ChannelProvider {

    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;
    private final RetrievalProperties properties;

    /** 索引名 → 检索器（检索器无状态，可安全复用；避免每轮重建） */
    private final Map<String, ContentRetriever> vectorCache = new ConcurrentHashMap<>();
    private final Map<String, ContentRetriever> keywordCache = new ConcurrentHashMap<>();

    public DomainChannelProvider(ElasticsearchClient esClient,
                                 EmbeddingModel embeddingModel,
                                 RetrievalProperties properties) {
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    @Override
    public List<Channel> channels() {
        String domain = RetrievalScope.current();
        if (domain == null) {
            return List.of(
                    new Channel(KbIndexes.WILDCARD, Modality.VECTOR, vector(KbIndexes.WILDCARD)),
                    new Channel(KbIndexes.WILDCARD, Modality.KEYWORD, keyword(KbIndexes.WILDCARD)));
        }

        // chainOf 是「由根向下」的顺序：[root, ..., 查询域自身]。
        // depth 是"距查询域的距离"，0 = 自身，所以要反向算：末位是 0，越靠前越大。
        List<String> chain = Domains.chainOf(Domains.normalize(domain));
        int last = chain.size() - 1;
        List<Channel> channels = new ArrayList<>(chain.size() * 2);
        for (int i = 0; i < chain.size(); i++) {
            String index = KbIndexes.nameOf(chain.get(i));
            int depth = last - i;
            channels.add(new Channel(index, Modality.VECTOR, vector(index), depth));
            channels.add(new Channel(index, Modality.KEYWORD, keyword(index), depth));
        }
        return channels;
    }

    private ContentRetriever vector(String index) {
        return vectorCache.computeIfAbsent(index, name -> new NativeScriptScoreContentRetriever(
                esClient, name, embeddingModel, properties.getVectorTopK(), properties.getVectorMinScore()));
    }

    private ContentRetriever keyword(String index) {
        return keywordCache.computeIfAbsent(index, name -> new KeywordMatchContentRetriever(
                esClient, name, properties.getKeywordTopK(), properties.getMinimumShouldMatch()));
    }

    /**
     * 删域时清理：失效这些索引对应的检索器缓存。
     *
     * <p>不失效的话，删除的索引还会被缓存里的旧检索器继续命中（ES 层报索引缺失），
     * 且同路径域将来重建时会复用指向旧 mapping 的检索器。</p>
     */
    public void evictIndices(Collection<String> indexNames) {
        if (indexNames == null || indexNames.isEmpty()) {
            return;
        }
        for (String index : indexNames) {
            vectorCache.remove(index);
            keywordCache.remove(index);
        }
    }
}
