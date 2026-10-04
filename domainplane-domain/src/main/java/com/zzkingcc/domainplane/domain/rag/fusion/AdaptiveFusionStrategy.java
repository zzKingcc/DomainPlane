package com.zzkingcc.domainplane.domain.rag.fusion;

import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import dev.langchain4j.rag.content.Content;

import java.util.List;

/**
 * 双轨融合策略：按<b>本次召回实际有命中的来源数</b>自动选择算法。
 *
 * <ul>
 *   <li><b>单来源</b>（有命中的来源 ≤ 1）→ {@link DefaultFusionStrategy}（分数制）；</li>
 *   <li><b>多来源</b>（有命中的来源 ≥ 2）→ {@link RrfFusionStrategy}（排名制），规避 BM25 跨索引不可比。</li>
 * </ul>
 *
 * <p>判据是"<b>有命中</b>"而不是"通道里出现过几个来源"：通道是按域链无条件解析出来的
 * （祖先域没上传过文档时它根本没有索引），若按通道数判，则任何非根域都会被判成多来源、
 * 一律走 RRF —— 分数制只在根域生效，等于白留一轨。按有命中的来源数判，才符合本意：
 * 结果实际来自同一索引时，分数制（同索引内 BM25 可比）是更精确的选择。</p>
 *
 * @author zzkingcc
 */
public class AdaptiveFusionStrategy implements FusionStrategy {

    private final FusionStrategy singleTrack;
    private final FusionStrategy multiTrack;

    public AdaptiveFusionStrategy() {
        this(new DefaultFusionStrategy(), new RrfFusionStrategy());
    }

    public AdaptiveFusionStrategy(FusionStrategy singleTrack, FusionStrategy multiTrack) {
        this.singleTrack = singleTrack;
        this.multiTrack = multiTrack;
    }

    @Override
    public List<Content> fuse(String queryText, List<RankedList> lists, FusionConfig fusion) {
        long sources = lists == null ? 0 : lists.stream()
                .filter(l -> l != null && !l.isEmpty())
                .map(RankedList::sourceId)
                .distinct()
                .count();
        FusionStrategy picked = sources >= 2 ? multiTrack : singleTrack;
        return picked.fuse(queryText, lists, fusion);
    }
}
