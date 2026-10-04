package com.zzkingcc.domainplane.infrastructure.ingestion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.DocumentProcessStrategy;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.DocumentProcessStrategyFactory;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.IngestReport;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 知识库文档导入工具（策略模式调度层）
 *
 * @author zzkingcc
 */
@Slf4j
public class DocumentIngestor {

    private DocumentIngestor() {}

    /**
     * 导入外部提交的文档（上传接口的唯一入口）。
     *
     * @param documents      待导入文档（文本类已统一字符集，二进制类持原始字节；metadata 需带 file_name / doc_id）
     * @param esClient
     * @param indexName
     * @param embeddingStore
     * @param embeddingModel
     * @return 处理报告（文档数 + 切片诊断计数）
     */
    public static IngestReport ingestExternalDocuments(List<IngestSource> documents,
                                                       ElasticsearchClient esClient,
                                                       String indexName,
                                                       EmbeddingStore embeddingStore,
                                                       EmbeddingModel embeddingModel) {
        if (documents == null || documents.isEmpty()) {
            return IngestReport.EMPTY;
        }
        return doIngest(documents, esClient, indexName, embeddingStore, embeddingModel, "外部");
    }

    /**
     * 策略分发
     *
     * @param documents      原始文档列表（可能混合多种文件类型）
     * @param esClient
     * @param indexName
     * @param embeddingStore
     * @param embeddingModel
     * @param sourceTag      来源标签（"外部"）
     * @return 处理报告
     */
    private static IngestReport doIngest(List<IngestSource> documents,
                                         ElasticsearchClient esClient,
                                         String indexName,
                                         EmbeddingStore embeddingStore,
                                         EmbeddingModel embeddingModel,
                                         String sourceTag) {
        int docCount = documents.size();

        // 1、按扩展名分组到对应策略
        Map<DocumentProcessStrategy, List<IngestSource>> group =
                DocumentProcessStrategyFactory.groupByStrategy(documents);

        // 2、打印分组统计
        StringBuilder summary = new StringBuilder();
        summary.append("[知识库导入-").append(sourceTag).append("] 按文件类型分组：")
                .append("共 ").append(docCount).append(" 个文档 → ");
        int idx = 0;
        for (Map.Entry<DocumentProcessStrategy, List<IngestSource>> e : group.entrySet()) {
            if (idx++ > 0) summary.append(", ");
            summary.append(e.getKey().strategyName())
                    .append('=').append(e.getValue().size());
        }
        log.info(summary.toString());

        // 3、依次调用每个策略
        IngestReport total = IngestReport.EMPTY;
        for (Map.Entry<DocumentProcessStrategy, List<IngestSource>> e : group.entrySet()) {
            DocumentProcessStrategy strategy = e.getKey();
            List<IngestSource> docsOfStrategy = e.getValue();
            try {
                IngestReport report = strategy.process(
                        docsOfStrategy, esClient, indexName, embeddingStore, embeddingModel, sourceTag);
                total = total.plus(report);
                log.debug("[知识库导入-{}] 策略[{}]处理完成：{} 个文档",
                        sourceTag, strategy.strategyName(), report.documents());
            } catch (Exception ex) {
                // 失败必须上抛，不能"跳过该组"了事：吞掉会让本次导入返回一个非零计数，
                // 调用方据此判成功并跳过失败回滚，留下"半个文档"且占住文件名。
                log.error("[知识库导入-{}] 策略[{}]处理失败：{}",
                        sourceTag, strategy.strategyName(), ex.getMessage(), ex);
                throw ex;
            }
        }

        log.info("[知识库导入-{}] 全部分组处理结束，总处理文档数：{}，标题 {} 个，删噪 {} 行",
                sourceTag, total.documents(), total.sections(), total.droppedLines());
        return total;
    }
}
