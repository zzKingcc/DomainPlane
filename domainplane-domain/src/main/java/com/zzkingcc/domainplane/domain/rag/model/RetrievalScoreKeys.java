package com.zzkingcc.domainplane.domain.rag.model;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;

/**
 * 检索分数在 {@code TextSegment#metadata()} 中的 key 常量
 *
 * @author zzkingcc
 */
public final class RetrievalScoreKeys {

    /**
     * ES 原始检索分数。
     *
     * 它由 infrastructure 的检索器写入、由本模块的融合阶段读取，是跨模块契约，
     * 所以定义在 {@link ChunkMetadataKeys}；这里保留别名是因为融合/排序相关的键都归本类管。
     */
    public static final String RAW_SCORE = ChunkMetadataKeys.RAW_SCORE;

    /** 向量检索原始分 */
    public static final String VECTOR_SCORE = "_vector_score";

    /** 关键词检索原始分 */
    public static final String KEYWORD_SCORE = "_keyword_score";

    /** 向量分 min-max 归一化结果,区间 [0,1] */
    public static final String NORM_VECTOR_SCORE = "_norm_vector_score";

    /** 关键词分 min-max 归一化结果,区间 [0,1] */
    public static final String NORM_KEYWORD_SCORE = "_norm_keyword_score";

    /** 融合总分(加权求和 + 标题/文件名 boost) */
    public static final String FUSED_SCORE = "_fused_score";

    /** 融合重排后的名次,从 1 开始 */
    public static final String FUSION_RANK = "_fusion_rank";

    /** 该条结果命中的检索通道:vector / keyword / both */
    public static final String MATCH_CHANNEL = "_match_channel";

    private RetrievalScoreKeys() {
    }
}
