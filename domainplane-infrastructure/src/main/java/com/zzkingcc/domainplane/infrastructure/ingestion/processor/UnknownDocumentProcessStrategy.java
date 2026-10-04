package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 未知/未识别文件类型处理策略
 * @author zzkingcc
 */
@Slf4j
public class UnknownDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final UnknownDocumentProcessStrategy INSTANCE = new UnknownDocumentProcessStrategy();

    private UnknownDocumentProcessStrategy() {
    }

    @Override
    public IngestReport process(List<IngestSource> documents,
                                ElasticsearchClient esClient,
                                String indexName,
                                EmbeddingStore embeddingStore,
                                EmbeddingModel embeddingModel,
                                String sourceTag) {
        int docCount = documents.size();
        log.info("[分片写入-{}][{}] ⚠ 共[{}]个文件的扩展名未识别或未识别，当前版本暂不支持该类型，已跳过。",
                sourceTag, strategyName(), docCount);
        return IngestReport.EMPTY;
    }

    @Override
    public List<String> supportedExtensions() {
        // 兜底策略，不注册具体扩展名
        return List.of();
    }

    @Override
    public String strategyName() {
        return "未知类型";
    }
}
