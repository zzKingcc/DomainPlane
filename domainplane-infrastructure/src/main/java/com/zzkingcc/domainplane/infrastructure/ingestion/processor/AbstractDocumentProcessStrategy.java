package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestLimits;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档处理策略抽象基类（模板方法模式）
 *
 * 封装分片后的通用处理流程：content_hash 生成 → 批次内去重 → ES 去重 → 分批向量化写入。
 * 子类只需实现 {@link #splitDocuments(List)} 提供差异化的分片逻辑。
 * @author zzkingcc
 */
@Slf4j
public abstract class AbstractDocumentProcessStrategy implements DocumentProcessStrategy {

    /** ES terms 查询单次最大条数限制 */
    private static final int ES_TERMS_BATCH_SIZE = 10000;

    /** 百炼 text-embedding-v2 模型限制的单次请求最大行数 */
    private static final int EMBEDDING_BATCH_SIZE = 25;

    @Override
    public final IngestReport process(List<IngestSource> documents,
                                      ElasticsearchClient esClient,
                                      String indexName,
                                      EmbeddingStore embeddingStore,
                                      EmbeddingModel embeddingModel,
                                      String sourceTag) {
        int docCount = documents.size();
        log.info("[分片写入-{}][{}] 开始处理 {} 个文档", sourceTag, strategyName(), docCount);

        // 1. 分片（子类实现差异化逻辑）
        SplitResult split = splitDocuments(documents);
        List<TextSegment> allSegments = split.segments();
        log.info("[分片写入-{}][{}] 分片完成：{} 个文档 → {} 个 TextSegment，" +
                        "最小字符 {}，最大字符 {}，平均字符 {}",
                sourceTag, strategyName(), docCount, allSegments.size(),
                allSegments.stream().mapToInt(s -> s.text().length()).min().orElse(0),
                allSegments.stream().mapToInt(s -> s.text().length()).max().orElse(0),
                allSegments.stream().mapToInt(s -> s.text().length()).average().orElse(0d));

        IngestReport report = new IngestReport(docCount, split.sections(), split.droppedLines());

        // 切片数上限：几百页的文档一次就能产出几千片，向量化是分批请求模型的，
        // 一次上传就能把导入队列堵住 —— 在动手向量化之前先挡掉
        guardChunkLimit(documents, allSegments);

        if (allSegments.isEmpty()) {
            log.warn("[分片写入-{}][{}] 分片结果为空，跳过后续流程", sourceTag, strategyName());
            return report;
        }

        // 2. 生成去重键：正文哈希 **与 file_name 一起** 构成去重身份
        //
        //    只用正文哈希会造成跨文档误伤：一次上传可能包含多个文件，而两个文件里
        //    出现同一句话（合同的标准条款、多份文件共用的表头、政策里的通用表述）
        //    是常态。纯正文哈希会让后一个文件的这条切片被当成"已存在"而丢弃 ——
        //    报告仍按文档数返回、日志仍写"写入完成"，用户完全看不出来有内容没入库。
        //
        //    加了 file_name 之后：同一文档内的重复段落仍会去重（真正想做的），
        //    而"改了文件名重传同一份文档"不再是同一身份 —— 这是可接受的，
        //    因为文档管理侧本来就有同名校验与 replace 覆盖。
        for (TextSegment seg : allSegments) {
            seg.metadata().put("content_hash", computeContentHash(seg.text()));
            seg.metadata().put("dedup_key", computeDedupKey(
                    seg.metadata().getString("content_hash"),
                    seg.metadata().getString(ChunkMetadataKeys.FILE_NAME)));
        }

        // 3. 批次内去重（同一批次内跨文档也要各算各的）
        Map<String, TextSegment> uniqueSegments = new LinkedHashMap<>();
        for (TextSegment seg : allSegments) {
            uniqueSegments.putIfAbsent(seg.metadata().getString("dedup_key"), seg);
        }
        int batchDupCount = allSegments.size() - uniqueSegments.size();
        if (batchDupCount > 0) {
            log.info("[分片写入-{}][{}] 批次内去重：{} → {}，移除 {} 个重复片段",
                    sourceTag, strategyName(), allSegments.size(), uniqueSegments.size(), batchDupCount);
        }

        // 4. ES 去重：先按正文哈希召回候选，再在内存里按 (哈希 + 文件名) 配对
        //
        //    分两步是有意的：ES 的 terms 查询没法表达「哈希 A 且文件名 X」这种配对条件，
        //    而 mapping 里也没有 dedup_key 字段（新增字段要重建存量索引，代价太大）。
        //    先用 terms 取回候选文档的 (content_hash, file_name)，在内存里算去重键比对 ——
        //    存量索引无需迁移，行为也正确。
        Set<String> candidateHashes = new LinkedHashSet<>();
        for (TextSegment seg : uniqueSegments.values()) {
            candidateHashes.add(seg.metadata().getString("content_hash"));
        }
        Set<String> existingKeys = queryExistingKeys(esClient, indexName, candidateHashes);
        List<TextSegment> newSegments = new ArrayList<>();
        for (TextSegment seg : uniqueSegments.values()) {
            if (!existingKeys.contains(seg.metadata().getString("dedup_key"))) {
                newSegments.add(seg);
            }
        }
        int esDupCount = uniqueSegments.size() - newSegments.size();
        if (esDupCount > 0) {
            log.info("[分片写入-{}][{}] ES去重：跳过 {} 个已存在片段", sourceTag, strategyName(), esDupCount);
        }

        if (newSegments.isEmpty()) {
            log.info("[分片写入-{}][{}] 去重后无新增片段，跳过写入", sourceTag, strategyName());
            return report;
        }

        // 5. 分批向量化 + 写入（受 text-embedding-v2 单次请求最大 25 行限制）
        log.info("[分片写入-{}][{}] 开始向量化写入（共 {} 个片段，每批 {} 个）",
                sourceTag, strategyName(), newSegments.size(), EMBEDDING_BATCH_SIZE);
        try {
            int total = newSegments.size();
            int written = 0;
            for (int start = 0; start < total; start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(start + EMBEDDING_BATCH_SIZE, total);
                List<TextSegment> batch = newSegments.subList(start, end);

                Response<List<Embedding>> embedResp = embeddingModel.embedAll(batch);
                List<Embedding> embeddings = embedResp.content();
                for (int i = 0; i < batch.size(); i++) {
                    embeddingStore.add(embeddings.get(i), batch.get(i));
                }
                written += batch.size();
                log.info("[分片写入-{}][{}] 向量化进度: {}/{}", sourceTag, strategyName(), written, total);
            }
            log.info("[分片写入-{}][{}] 写入完成，新增 {} 个片段", sourceTag, strategyName(), newSegments.size());
        } catch (Exception e) {
            // 不能吞：吞掉后方法仍返回 docCount（非 0），调用方会当成"导入成功"，
            // 而实际一个片段都没写进去。失败必须往上抛，让上层回滚并如实报错。
            log.error("[分片写入-{}][{}] 向量化写入失败: {}", sourceTag, strategyName(), e.getMessage(), e);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                    "向量化写入失败：" + e.getMessage(), e);
        }

        return report;
    }

    /**
     * 子类实现：将文档列表切分为文本片段，并带回切片诊断计数
     */
    protected abstract SplitResult splitDocuments(List<IngestSource> documents);

    /**
     * 单文件切片数上限（防雪崩，不是限制功能）。
     *
     * 几百页的 PDF / 超长 docx 一次就能产出几千片，向量化要分批请求模型 ——
     * 一次上传就能把导入队列堵死。超限直接拒绝，并告诉用户怎么做（拆成几个小文件）。
     */
    protected void guardChunkLimit(List<IngestSource> documents, List<TextSegment> segments) {
        int limit = IngestLimits.maxChunksPerDocument();
        if (limit <= 0 || segments.size() <= limit) {
            return;
        }
        String name = documents == null || documents.isEmpty() ? "(unknown)" : documents.get(0).fileName();
        throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                "文件 " + name + " 切出 " + segments.size() + " 片，超过单文件上限 " + limit
                        + " 片；请把它拆成几个小文件分别上传");
    }

    /**
     * 计算 content_hash：SHA-256(切片正文)。
     *
     * 只算正文，不含文件名与标题 —— 正文里已经带了 section_path，所以重复内容一定同哈希；
     * 而"改了文件名重传"不会因此变成新内容，去重才有意义。
     */
    protected String computeContentHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_DEDUP_ERROR, "SHA-256 哈希计算失败，去重逻辑不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 去重键 = 正文哈希 + 文件名。
     *
     * 加文件名是为了把去重限制在「同一份文档内」：同一文档里的重复段落仍会被去掉
     * （这是去重本来要解决的问题），但不会把另一个文件里相同的句子误判成重复内容。
     * 分隔符用 {@code \n} —— 文件名在入库时已被规范化，不含控制字符，不会与内容混淆。
     */
    protected static String computeDedupKey(String contentHash, String fileName) {
        return (contentHash == null ? "" : contentHash) + "\n" + (fileName == null ? "" : fileName);
    }

    /**
     * 分批 terms 查询索引中已存在的去重键。
     * 查询失败返回空集，不阻塞导入。
     *
     * @param hashesToCheck 本次要检查的正文哈希（不是去重键）
     * @return 已存在的 {@code (哈希 + 文件名)} 去重键集合
     */
    protected Set<String> queryExistingKeys(ElasticsearchClient esClient,
                                             String indexName,
                                             Set<String> hashesToCheck) {
        if (hashesToCheck == null || hashesToCheck.isEmpty()) {
            return Set.of();
        }

        Set<String> existing = new HashSet<>();
        List<String> hashList = new ArrayList<>(hashesToCheck);

        for (int start = 0; start < hashList.size(); start += ES_TERMS_BATCH_SIZE) {
            int end = Math.min(start + ES_TERMS_BATCH_SIZE, hashList.size());
            List<String> chunk = hashList.subList(start, end);

            try {
                List<FieldValue> fieldValues = new ArrayList<>();
                for (String h : chunk) {
                    fieldValues.add(FieldValue.of(h));
                }

                Query termsQuery = new Query.Builder()
                        .terms(t -> t
                                .field("metadata.content_hash")
                                .terms(tf -> tf.value(fieldValues)))
                        .build();

                SearchResponse<Map> resp = esClient.search(s -> s
                                .index(indexName)
                                .size(chunk.size())
                                .source(src -> src.filter(f -> f.includes("metadata")))
                                .query(termsQuery),
                        Map.class);

                resp.hits().hits().forEach(h -> {
                    if (h.source() == null) {
                        return;
                    }
                    Object metadataObj = h.source().get("metadata");
                    if (!(metadataObj instanceof Map)) {
                        return;
                    }
                    Map<?, ?> meta = (Map<?, ?>) metadataObj;
                    Object hash = meta.get("content_hash");
                    if (hash == null) {
                        return;
                    }
                    Object name = meta.get(ChunkMetadataKeys.FILE_NAME);
                    existing.add(computeDedupKey(hash.toString(), name == null ? null : name.toString()));
                });
            } catch (Exception e) {
                log.warn("[分片去重-{}] 查询ES已有content_hash失败（索引可能不存在或无content_hash字段），"
                        + "跳过ES去重: {}", strategyName(), e.getMessage());
            }
        }

        return existing;
    }

    /**
     * 文本类策略用：把 {@link IngestSource} 还原成 langchain4j 的 {@code Document}。
     *
     * 只有文本类能走这一步 —— 二进制载体 {@link IngestSource#text()} 为 {@code null}，
     * 这里会跳过并告警（二进制策略应该取 {@link IngestSource#content()}）。
     */
    protected static List<Document> asDocuments(List<IngestSource> sources) {
        List<Document> documents = new ArrayList<>(sources.size());
        for (IngestSource source : sources) {
            String text = source.text();
            if (text == null || text.isBlank()) {
                log.warn("[分片] 文件[{}]没有可用文本（二进制格式走了文本管线？），跳过", source.fileName());
                continue;
            }
            documents.add(Document.from(text, source.metadata()));
        }
        return documents;
    }

    /**
     * 取文件名（写日志 / 报错提示用）
     */
    protected String safeFileName(IngestSource doc) {
        return doc.fileName();
    }
}
