package com.zzkingcc.domainplane.domain.rag.store;

/**
 * 文档级聚合：管控台文档列表的一行（docId + 文件名 + 归属知识体 + 切片数）。
 *
 * 由 {@link KnowledgeStore#documents} 产出；聚合口径（按哪些字段分组）归后端，
 * SPI 只约定输出形态。
 *
 * @param docId    文档 ID
 * @param fileName 文件名
 * @param body     文档落的知识体 id（索引名无法反查体时仍能读出归属）
 * @param chunks   该文档在当前 collection 内的切片数
 *
 * @author zzkingcc
 */
public record DocumentAgg(String docId, String fileName, String body, int chunks) {
}
