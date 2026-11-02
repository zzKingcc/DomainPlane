package com.zzkingcc.domainplane.domain.rag.store;

import java.util.Map;

/**
 * 知识库切片：写入（{@link KnowledgeStore#write}）与全量扫描（{@link KnowledgeStore#scan}）
 * 的统一数据单元，也是 ChunkArchive 重灌的输入形态。
 *
 * @param id       切片在 collection 内的唯一标识；写入侧生成，重灌时保持稳定
 * @param text     切片正文。keyword-only 后端也存正文——只有
 *                 {@link StoreCapabilities#storesText()} 为 {@code false} 的后端
 *                 （纯向量库）才允许不存
 * @param metadata 切片元数据，键名见 {@code ChunkMetadataKeys}（含 {@code content_hash}）
 * @param vector   向量；keyword-only 后端为 {@code null}
 *
 * @author zzkingcc
 */
public record KbChunk(String id, String text, Map<String, String> metadata, float[] vector) {

    public KbChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        // 数组组件必须防御性拷贝：记录不会自动克隆，调用方持有着原数组引用，
        // 一次越界写就能污染已进入写入批次的切片，而这种污染在向量检索里
        // 表现为"某条结果分数莫名其妙地差"，极难定位。
        vector = vector == null ? null : vector.clone();
    }

    /** 不带向量的简写（keyword-only 后端 / 先写正文后补向量的两段式写入）。 */
    public KbChunk(String id, String text, Map<String, String> metadata) {
        this(id, text, metadata, null);
    }

    @Override
    public float[] vector() {
        return vector == null ? null : vector.clone();
    }
}
