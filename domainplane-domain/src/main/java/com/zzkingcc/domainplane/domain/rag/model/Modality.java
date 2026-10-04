package com.zzkingcc.domainplane.domain.rag.model;

/**
 * 召回模态：知识库检索的两路来源。
 *
 * @author zzkingcc
 */
public enum Modality {

    /** 向量检索（余弦相似度） */
    VECTOR,

    /** 关键词检索（BM25） */
    KEYWORD
}
