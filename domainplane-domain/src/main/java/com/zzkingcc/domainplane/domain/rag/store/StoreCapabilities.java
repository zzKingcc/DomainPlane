package com.zzkingcc.domainplane.domain.rag.store;

/**
 * 后端能力声明：装配期（启动 + 管控台保存配置）据此校验召回策略是否可满足，
 * 运行期据此做通配降级。
 *
 * **能力不满足必须是启动失败，不是运行时空结果**——运行时空结果会让"配错了"和
 * "知识库是空的"长得一模一样，用户面对空结果只会反复换问法，不会去查配置。
 *
 * @param keyword        具备关键词（BM25 类）检索能力 → 应提供 {@link KeywordSearcher}
 * @param vector         具备向量检索能力 → 应提供 {@link VectorSearcher}
 * @param wildcard       支持通配 collection 检索（{@code KbIndexes.WILDCARD}，管控台
 *                       全库预览 / 诊断用）。向量库通常不支持：该路径降级为
 *                       {@link KnowledgeStore#listCollections()} 枚举后逐 collection
 *                       检索关键词路，向量路跳过并告警
 * @param storesText     索引内存储切片原文。{@code false}（纯向量库）时换向量模型
 *                       重灌必须依赖 ChunkArchive 原文权威源，不能从索引捞回原文
 * @param reindexInPlace 支持原地重建（{@code deleteCollection} + {@code ensureCollection}
 *                       在同一后端完成），重灌第二阶段的前提
 *
 * @author zzkingcc
 */
public record StoreCapabilities(boolean keyword, boolean vector, boolean wildcard,
                                boolean storesText, boolean reindexInPlace) {
}
