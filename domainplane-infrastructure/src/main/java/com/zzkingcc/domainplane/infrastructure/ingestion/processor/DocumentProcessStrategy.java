package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * 文档处理策略接口
 * @author zzkingcc
 */
public interface DocumentProcessStrategy {

    /**
     * 流程：解析 → 分片 → 去重 → 向量化写入。
     *
     * @param documents      待处理的文档列表（已按文件类型分组，全部匹配本策略）
     * @param esClient
     * @param indexName
     * @param embeddingStore
     * @param embeddingModel
     * @param sourceTag      来源标签（"本地"/"外部"）
     * @return 处理报告（文档数 + 切片诊断计数）
     */
    IngestReport process(List<IngestSource> documents,
                         ElasticsearchClient esClient,
                         String indexName,
                         EmbeddingStore embeddingStore,
                         EmbeddingModel embeddingModel,
                         String sourceTag);

    /**
     * @return 本策略支持的文件扩展名集合（小写，不含点），用于工厂匹配
     */
    List<String> supportedExtensions();

    /**
     * 是否为二进制格式（docx / pdf）。
     *
     * 决定上传入口的分叉：{@code true} 时不解码，原始字节交给 {@link IngestSource#content()}；
     * {@code false} 时入口先统一字符集，策略取 {@link IngestSource#text()}。
     */
    default boolean binary() {
        return false;
    }

    /**
     * @return 策略名称
     */
    String strategyName();
}
