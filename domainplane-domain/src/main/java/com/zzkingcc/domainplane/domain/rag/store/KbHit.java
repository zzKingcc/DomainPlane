package com.zzkingcc.domainplane.domain.rag.store;

import java.util.Map;

/**
 * 一路检索的命中：{@link KeywordSearcher} / {@link VectorSearcher} 的出口类型。
 *
 * 分数契约（设计第 5 节，整份改造里最容易做错的一条）：
 * {@code score} 只承诺**越大越好、在同一后端同一模态内可比**；
 * **不承诺 [0,1]，不承诺跨后端可比**。
 *
 * 被否方案是把分数统一归一化到 [0,1]：BM25 无上界，压区间只能走 {@code x/(1+x)}
 * 这类非线性变换——融合层的分数制对每路做 min-max，分布被改后结果静默漂移，
 * 而 RRF 轨不受影响，于是"同一份数据、同一套配置"在两种融合下给出不同答案。
 * 统一分区间是伪需求。
 *
 * 阈值参数不属于本类型，是后端自己的配置项：ES 的 {@code minScore} 是原始余弦
 * （适配器内部 {@code +1.0} 偏移），换向量库后由该后端自己的配置解释，
 * 不会出现"同一个 0.35 在两家语义不同"的静默漂移。
 *
 * @param chunkId  切片 ID
 * @param text     切片正文；桥接层据此组装 {@code Content}
 * @param metadata 切片元数据（白名单键，见适配器的 codec）
 * @param score    该后端该模态下的原始分
 *
 * @author zzkingcc
 */
public record KbHit(String chunkId, String text, Map<String, String> metadata, double score) {

    public KbHit {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
