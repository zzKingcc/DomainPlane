package com.zzkingcc.domainplane.domain.rag.fusion;

import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import dev.langchain4j.rag.content.Content;

import java.util.List;

/**
 * 混合检索融合策略：把多路召回的<b>排名表</b>合并、去重、排序、取 TopN。
 *
 * <p>输入是一组 {@link RankedList}（多索引时 = 索引数 × 2），由本接口的实现决定如何融合：
 * <ul>
 *   <li>{@link DefaultFusionStrategy} —— 单索引（分数制）行为；</li>
 *   <li>{@link RrfFusionStrategy} —— 多索引（排名制）行为；</li>
 *   <li>{@link AdaptiveFusionStrategy} —— 按索引数自动二选一（本项目的双轨入口）。</li>
 * </ul>
 *
 * @author zzkingcc
 */
public interface FusionStrategy {

    /**
     * 融合多路召回结果。
     *
     * @param queryText 原始查询文本（用于标题 / 文件名命中 boost 判定）
     * @param lists     各路排名表（可能为空；单路失败或被跳过时该表内容为空）
     * @param fusion    融合参数（权重 / boost / topN / rrfK）
     * @return 融合重排后的结果，已回写分项分、融合分、名次、命中通道到 metadata
     */
    List<Content> fuse(String queryText,
                       List<RankedList> lists,
                       FusionConfig fusion);
}
