package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import java.util.Map;

/**
 * Qdrant 的命中结果：id + 正文 + metadata + 分数（+ 向量，扫描路才带）。
 *
 * <p>分数语义：Qdrant 的相似度分（cosine 下越大越相似；euclid 下越小越近，
 * 适配器内部会做归一化，见 {@code QdrantVectorSearcher}）。只承诺
 * "同一后端同一模态内可比"，不承诺跨后端可比——SPI 分数契约。</p>
 *
 * @param id       点位 id
 * @param text     切片正文
 * @param metadata 切片元数据
 * @param score    相似度分
 * @param vector   向量（扫描路带回，检索路为 null）
 * @author zzkingcc
 */
public record QdrantScored(String id, String text, Map<String, String> metadata,
                            double score, float[] vector) {
}
