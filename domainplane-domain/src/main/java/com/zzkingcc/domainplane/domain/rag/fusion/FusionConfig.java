package com.zzkingcc.domainplane.domain.rag.fusion;

/**
 * 融合排序参数
 *
 * @param vectorWeight   向量权重（多索引时按「向量排名表个数」均摊到各表）
 * @param keywordWeight  关键词权重（多索引时按「关键词排名表个数」均摊到各表）
 * @param titleBoost     标题命中时的乘法增益：{@code 分 × (1 + titleBoost)}
 * @param fileNameBoost  文件名命中时的乘法增益：{@code 分 × (1 + fileNameBoost)}
 * @param topN           重排后返回条数
 * @param rrfK           RRF 平滑常数（多索引排名制融合用）
 * @param ancestorDecay  层级衰减系数（多索引排名制融合用）：距查询域每远一层，权重乘一次该系数。
 *                       {@code 1.0} = 不衰减（本域与祖先等权）
 *
 * @author zzkingcc
 */
public record FusionConfig(double vectorWeight,
                           double keywordWeight,
                           double titleBoost,
                           double fileNameBoost,
                           int topN,
                           int rrfK,
                           double ancestorDecay) {

    /**
     * 默认配置：向量 0.6 / 关键词 0.4，标题 ×1.15、文件名 ×1.10，
     * 返回 Top15，{@code rrf-k=10}，层级衰减 0.7。
     */
    public static FusionConfig defaults() {
        return new FusionConfig(0.6, 0.4, 0.15, 0.10, 15, 10, 0.7);
    }

    /** 不衰减的简写（等权融合）。 */
    public FusionConfig(double vectorWeight,
                        double keywordWeight,
                        double titleBoost,
                        double fileNameBoost,
                        int topN,
                        int rrfK) {
        this(vectorWeight, keywordWeight, titleBoost, fileNameBoost, topN, rrfK, 1.0);
    }
}
