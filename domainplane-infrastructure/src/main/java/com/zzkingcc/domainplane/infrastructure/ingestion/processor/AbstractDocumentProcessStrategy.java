package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.domain.rag.store.DedupKey;
import com.zzkingcc.domainplane.domain.rag.store.IngestTarget;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestLimits;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文档处理策略抽象基类（模板方法模式）
 *
 * 封装分片后的通用处理流程：content_hash 生成 → 批次内去重 → 后端去重 → 分批向量化写入。
 * 子类只需实现 {@link #splitDocuments(List)} 提供差异化的分片逻辑。
 *
 * 写入经 SPI（{@link IngestTarget#store()}）："谁做 embed"钉死在调用侧（本类），
 * 后端只管存已带向量的切片。每批 embed 后组装成 {@link KbChunk} 一次
 * {@code write}——bulk 任一条失败即整体失败，由上层按 docId 回滚兜底，
 * 不会留下"部分切片没有向量"的中间态。
 *
 * @author zzkingcc
 */
@Slf4j
public abstract class AbstractDocumentProcessStrategy implements DocumentProcessStrategy {

    /** 百炼 text-embedding-v2 模型限制的单次请求最大行数 */
    private static final int EMBEDDING_BATCH_SIZE = 25;

    @Override
    public final IngestReport process(List<IngestSource> documents,
                                      IngestTarget target,
                                      EmbeddingModel embeddingModel,
                                      String sourceTag) {
        int docCount = documents.size();
        String collection = target.collection();
        log.info("[分片写入-{}][{}] 开始处理 {} 个文档（collection={}）",
                sourceTag, strategyName(), docCount, collection);

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
        // 一次上传就能把导入队列堵住，在动手向量化之前先挡掉
        guardChunkLimit(documents, allSegments);

        if (allSegments.isEmpty()) {
            log.warn("[分片写入-{}][{}] 分片结果为空，跳过后续流程", sourceTag, strategyName());
            return report;
        }

        // 2. 生成去重键：正文哈希 **与 file_name 一起** 构成去重身份
        //
        //    只用正文哈希会造成跨文档误伤：一次上传可能包含多个文件，而两个文件里
        //    出现同一句话（合同的标准条款、多份文件共用的表头、政策里的通用表述）
        //    是常态。纯正文哈希会让后一个文件的这条切片被当成"已存在"而丢弃：
        //    报告仍按文档数返回、日志仍写"写入完成"，用户完全看不出来有内容没入库。
        //
        //    加了 file_name 之后：同一文档内的重复段落仍会去重（真正想做的），
        //    而"改了文件名重传同一份文档"不再是同一身份，这是可接受的，
        //    因为文档管理侧本来就有同名校验与 replace 覆盖。
        for (TextSegment seg : allSegments) {
            seg.metadata().put(ChunkMetadataKeys.CONTENT_HASH, computeContentHash(seg.text()));
            seg.metadata().put("dedup_key", computeDedupKey(
                    seg.metadata().getString(ChunkMetadataKeys.CONTENT_HASH),
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

        // 4. 后端去重：先按正文哈希召回候选，再在内存里按 (哈希 + 文件名) 配对
        //
        //    分两步是有意的：后端的 terms 查询没法表达「哈希 A 且文件名 X」这种配对条件，
        //    而索引里也没有 dedup_key 字段（新增字段要重建存量索引，代价太大）。
        //    先用 terms 取回候选文档的 (content_hash, file_name)，在内存里算去重键比对：
        //    存量索引无需迁移，行为也正确。
        Set<String> candidateHashes = new LinkedHashSet<>();
        for (TextSegment seg : uniqueSegments.values()) {
            candidateHashes.add(seg.metadata().getString(ChunkMetadataKeys.CONTENT_HASH));
        }
        Set<DedupKey> existingKeys = target.dedup().existingDedupKeys(collection, candidateHashes);
        List<TextSegment> newSegments = new ArrayList<>();
        for (TextSegment seg : uniqueSegments.values()) {
            String hash = seg.metadata().getString(ChunkMetadataKeys.CONTENT_HASH);
            String fileName = seg.metadata().getString(ChunkMetadataKeys.FILE_NAME);
            if (!existingKeys.contains(new DedupKey(hash, fileName))) {
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
                /* 条数必须逐一对上：模型少返回一条时下面的 get(i) 会越界，而越界发生在
                   批次写入中途，索引里会留下"部分切片没有向量"的中间态（没有向量的切片
                   在向量通道里等于消失）。宁可整批不写，让上层按失败回滚。 */
                if (embeddings == null || embeddings.size() != batch.size()) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                            "向量模型返回条数与请求不一致（请求 " + batch.size() + " 条，返回 "
                                    + (embeddings == null ? 0 : embeddings.size()) + " 条）");
                }
                // 组装成 SPI 的 KbChunk 一次写出：bulk 任一条失败即整批失败，
                // 与"逐条 add、失败时前面的已写入"相比，失败面更小、回滚口径更简单
                List<KbChunk> chunks = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    TextSegment seg = batch.get(i);
                    chunks.add(new KbChunk(
                            UUID.randomUUID().toString(),
                            seg.text(),
                            metadataOf(seg),
                            embeddings.get(i).vector()));
                }
                target.store().write(collection, chunks);
                written += batch.size();
                log.info("[分片写入-{}][{}] 向量化进度: {}/{}", sourceTag, strategyName(), written, total);
            }
            log.info("[分片写入-{}][{}] 写入完成，新增 {} 个片段", sourceTag, strategyName(), newSegments.size());
        } catch (StoreAccessException e) {
            // 后端写入失败按导入失败上抛：吞掉后仍返回 docCount（非 0），调用方会当成
            // "导入成功"并跳过回滚，留下半个文档且占住文件名
            log.error("[分片写入-{}][{}] 写入后端失败: {}", sourceTag, strategyName(), e.getMessage(), e);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                    "写入知识库存储失败：" + e.getMessage(), e);
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
     * TextSegment 的 metadata → SPI 的 {@code Map<String,String>}。
     *
     * 值统一 toString：{@code KbChunk} 跨后端中立，不认 langchain4j 的装箱类型；
     * 数值键（{@code chunk_seq} 等）由 ES 适配器的写回路还原成 integer，
     * 与旧写入路径（切片侧 Integer 原样进 _source）同形。
     */
    private static Map<String, String> metadataOf(TextSegment seg) {
        Map<String, String> out = new LinkedHashMap<>();
        if (seg.metadata() == null) {
            return out;
        }
        seg.metadata().toMap().forEach((key, value) ->
                out.put(key, value == null ? null : value.toString()));
        return out;
    }

    /**
     * 子类实现：将文档列表切分为文本片段，并带回切片诊断计数
     */
    protected abstract SplitResult splitDocuments(List<IngestSource> documents);

    /**
     * 单文件切片数上限（防雪崩，不是限制功能）。
     *
     * 几百页的 PDF / 超长 docx 一次就能产出几千片，向量化要分批请求模型：
     * 一次上传就能把导入队列堵死。超限直接拒绝，并告诉用户怎么做（拆成几个小文件）。
     */
    protected void guardChunkLimit(List<IngestSource> documents, List<TextSegment> segments) {
        int limit = IngestLimits.maxChunksPerDocument();
        if (limit <= 0 || segments == null || segments.isEmpty()) {
            return;
        }
        // 必须按文件名分组再判：一次上传可以是多个文件，把"总片数"当成"单文件片数"
        // 会误伤正常上传；而且报错还把总数安在第一个文件头上，用户按提示去拆那个文件，
        // 拆完再传依旧报同样的错。
        java.util.Map<String, Integer> perFile = new java.util.LinkedHashMap<>();
        for (TextSegment segment : segments) {
            String name = segment.metadata() == null
                    ? null : segment.metadata().getString(ChunkMetadataKeys.FILE_NAME);
            if (name == null || name.isBlank()) {
                name = "(unknown)";
            }
            perFile.merge(name, 1, Integer::sum);
        }
        for (java.util.Map.Entry<String, Integer> entry : perFile.entrySet()) {
            if (entry.getValue() > limit) {
                // 切片没带文件名（老数据 / 上游没写 metadata）时分组键是 "(unknown)"；
                // 单文件上传这种情况下总数就等于该文件的片数，直接报那个文件名，
                // 否则用户看到 "(unknown)" 完全不知道该拆哪个文件。
                String label = entry.getKey();
                if ("(unknown)".equals(label) && documents != null && documents.size() == 1) {
                    String only = documents.get(0).fileName();
                    if (only != null && !only.isBlank()) {
                        label = only;
                    }
                }
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                        "文件 " + label + " 切出 " + entry.getValue() + " 片，超过单文件上限 " + limit
                                + " 片；请把它拆成几个小文件分别上传");
            }
        }
    }

    /**
     * 计算 content_hash：SHA-256(切片正文)。
     *
     * 只算正文，不含文件名与标题，正文里已经带了 section_path，所以重复内容一定同哈希；
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
     * 分隔符用 {@code \n}，文件名在入库时已被规范化，不含控制字符，不会与内容混淆。
     */
    protected static String computeDedupKey(String contentHash, String fileName) {
        return (contentHash == null ? "" : contentHash) + "\n" + (fileName == null ? "" : fileName);
    }

    /**
     * 文本类策略用：把 {@link IngestSource} 还原成 langchain4j 的 {@code Document}。
     *
     * 只有文本类能走这一步，二进制载体 {@link IngestSource#text()} 为 {@code null}，
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
