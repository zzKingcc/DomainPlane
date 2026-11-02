/**
 * 知识库存储后端的 ES 适配器：把 {@code domain.rag.store} 的四个 SPI 落到 Elasticsearch。
 *
 * 这是 P0 的唯一后端实现。包内纪律（设计第 8 节 + 第 5 节红线）：
 *
 *   • **mapping 字面量一字不改**——{@link EsMapping#build} 是 {@code EsIndexManager.buildIkMapping}
 *     的原文平移，IK + {@code text.standard} + {@code dense_vector} 全量保留；
 *     {@code EsMetadataMappingTest} 逐字比对继续有效。
 *   • **分数逐字节等价**——向量路 {@code cosineSimilarity + 1.0}、{@code minScore + 1.0} 偏移、
 *     {@code size} / {@code _source} 过滤字段、"text 为空白则丢弃"四条红线一个数都不许改，
 *     否则改造前后基线比对必失败，而失败会被误读成"融合算法被改坏"。
 *   • **异常全部接住转 {@code StoreAccessException}**——ES 的 {@code IOException} 与
 *     {@code ElasticsearchException}（4xx/5xx）都接，不接就会绕过 ErrorCode 分类。
 *   • **ES 方言不外泄**——{@code cosine+1.0} 偏移、{@code minimumShouldMatch} 的 40%~50%
 *     有效区间都是本包内部事，SPI 入参里看不到它们。
 *
 * @author zzkingcc
 */
package com.zzkingcc.domainplane.infrastructure.kb.es;
