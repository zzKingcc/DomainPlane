package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import java.util.Map;

/**
 * Qdrant 点位的写入形态：id + 正文 + metadata + 向量。
 *
 * <p>与 {@code domain.rag.store.KbChunk} 一一对应，但在适配器内换了个名字——
 * 避免让 Qdrant 的概念（point / payload）渗进 SPI 契约。</p>
 *
 * @param id       点位 id（= 切片 id）
 * @param text     切片正文
 * @param metadata 切片元数据（扁平的字符串键值）
 * @param vector   向量（可为 null：keyword-only 场景）
 * @author zzkingcc
 */
public record QdrantPoint(String id, String text, Map<String, String> metadata, float[] vector) {
}
