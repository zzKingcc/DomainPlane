package com.zzkingcc.domainplane.server.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.elasticsearch.EsAccessException;
import com.zzkingcc.domainplane.infrastructure.elasticsearch.EsIndexManager;
import com.zzkingcc.domainplane.infrastructure.elasticsearch.VectorReindexer;
import com.zzkingcc.domainplane.infrastructure.ingestion.DocumentIngestor;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.DocumentProcessStrategy;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.DocumentProcessStrategyFactory;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.IngestReport;
import com.zzkingcc.domainplane.infrastructure.ingestion.txt.TxtNormalizer;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationScript;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识库服务：文档上传 / 列表 / 删除 / 状态，并承担一域一索引的生命周期。
 *
 * 结构性事实（与域树、工具、提示词同构）：一个域 = 一个 ES 索引。
 * 索引名由域路径确定性派生（{@link KbIndexes#nameOf}），在首次上传时按需创建 ——
 * 域是运行期由用户创建的，索引跟着域走，没有统一的"启动期全局索引"。
 *
 * 检索侧的域边界由「查哪些索引」保证：检索域 D 时只查 D 自身与祖先链上的索引，
 * 因此文档自带域的含义变成「它被上传到哪个域的索引」。{@code metadata.domain} 只用于
 * 管控台展示与排查，不参与过滤。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class KnowledgeBaseService {

    /**
     * 列表聚合时单个索引最多拉取的命中数。
     */
    private static final int LIST_MAX_SIZE = 1000;

    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;
    private final LlmModelHolder modelHolder;
    private final ChunkExporter chunkExporter;
    /** 域注册表：归属域必须是登记过的域（写入前校验，避免凭空造索引） */
    private final DomainRegistry domainRegistry;
    private final ThreadPoolExecutor ingestExecutor;
    private final Semaphore ingestPermit = new Semaphore(1);

    /** 索引名 → 向量存储（无状态包装，可安全复用） */
    private final Map<String, EmbeddingStore> storeCache = new ConcurrentHashMap<>();

    public KnowledgeBaseService(@Qualifier("domainplaneElasticsearchClient") ElasticsearchClient esClient,
                                @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                                RagProperties ragProperties,
                                LlmModelHolder modelHolder,
                                ChunkExporter chunkExporter,
                                DomainRegistry domainRegistry) {
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.ragProperties = ragProperties;
        this.modelHolder = modelHolder;
        this.chunkExporter = chunkExporter;
        this.domainRegistry = domainRegistry;
        AtomicLong seq = new AtomicLong();
        this.ingestExecutor = new ThreadPoolExecutor(
                ragProperties.getIngestPoolSize(),
                ragProperties.getIngestPoolSize(),
                60L, TimeUnit.SECONDS,
                // 有界队列：满即拒绝，绝不无界堆积
                new LinkedBlockingQueue<>(ragProperties.getIngestQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "domainplane-kb-ingest-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("[知识库] 导入线程池已初始化：size={}, queueCapacity={}",
                ragProperties.getIngestPoolSize(), ragProperties.getIngestQueueCapacity());
    }

    /** 进程退出时不再接受新任务，让在途导入收尾 */
    @PreDestroy
    public void shutdown() {
        ingestExecutor.shutdown();
    }

    /**
     * 上传结果。
     *
     * @param encoding     实际识别到的源文件编码（全链路统一 UTF-8 后，这个值只作诊断）
     * @param sections     识别到的标题数
     * @param droppedLines 清洗阶段删掉的行数
     * @param exportPath   切片预览 txt 的落盘路径（导出失败时为 null）
     */
    public record UploadResult(String docId, String fileName, long size, int chunks, String domain,
                               int sections, int droppedLines, String encoding, String exportPath) {
    }

    /** 索引内已入库的文档条目 */
    public record DocumentItem(String docId, String fileName, String fileNameLower,
                               int chunks, String domain, String exportPath) {
    }

    // ==================== 域 → 索引 ====================

    /**
     * 规范化并校验文档归属域 —— 与 {@code @Tool(domains = {...})} 同构：
     * 必须是从根域出发的完整路径，留空 = 挂在根域 {@code default}。
     *
     * 路径非法直接抛异常而不是静默丢弃 —— 静默会把文档写到一个树里不存在的域上，
     * 结果是永远检索不到且不报错。
     */
    public static String normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return Domains.DEFAULT;
        }
        String path = domain.trim();
        String reason = Domains.validatePath(path);
        if (reason != null) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "知识库文档的域不合法（" + reason + "）；须为从 " + Domains.DEFAULT
                            + " 出发的完整路径，如 default.sales");
        }
        return path;
    }

    /** 域 → 该域的知识库索引名 */
    public static String indexOf(String domain) {
        return KbIndexes.nameOf(normalizeDomain(domain));
    }

    /** 当前已存在的全部知识库索引（按名排序） */
    public List<String> existingIndices() {
        return EsIndexManager.listIndices(esClient, KbIndexes.WILDCARD);
    }

    /**
     * 取某个索引的向量存储（按索引名缓存 —— 存储对象本身无状态，只是 client + indexName 的包装）。
     */
    public EmbeddingStore storeFor(String indexName) {
        return storeCache.computeIfAbsent(indexName, name -> ElasticsearchEmbeddingStore.builder()
                .client(esClient)
                .indexName(name)
                .configuration(ElasticsearchConfigurationScript.builder().build())
                .build());
    }

    /**
     * 索引不存在则按当前向量维度创建（幂等）。
     */
    public void ensureIndex(String indexName) {
        EsIndexManager.createIndexWithIkMapping(esClient, indexName, modelHolder.effectiveEmbeddingDimension());
    }

    /**
     * 取任一已存在索引的向量维度；没有任何索引时返回 {@code null}。
     *
     * 维度是全局的（只有一个向量模型），所以拿哪个索引读都一样。
     */
    public Integer currentVectorDims() {
        for (String index : existingIndices()) {
            Integer dims = EsIndexManager.currentVectorDims(esClient, index);
            if (dims != null) {
                return dims;
            }
        }
        return null;
    }

    /**
     * 全部知识库索引里的文档（切片）总数。
     *
     * 用来判"有没有已灌库的内容"：索引存在不等于有内容，一排空索引不该触发换模型的二次确认。
     *
     * 读不到就返回 0（宁可少问一次确认，也不该因为一次统计失败就挡住正常切换）。
     */
    public long totalIndexedDocuments() {
        long total = 0;
        for (String index : existingIndices()) {
            try {
                total += esClient.count(c -> c.index(index)).count();
            } catch (Exception e) {
                log.warn("[知识库] 统计索引[{}]文档数失败: {}", index, e.getMessage());
            }
        }
        return total;
    }

    /**
     * 删除若干域各自的知识库索引（供删域时清理，先清索引再删域）。
     *
     * fail-closed：任何一个域的索引删不掉（ES 不可达 / 删除失败），立即抛异常中止，
     * 绝不"跳过它继续删下一个"。删域的语义是"这个域的一切都清干净"，
     * 留一个孤儿索引的后果不是"多了点垃圾"：
     * 索引名是域路径的哈希，同一路径域将来重建时索引名相同，
     * 这份本该删掉的知识内容会原样复活，且没有任何报错。
     *
     * @return 实际存在并被删除的域标识
     * @throws KnowledgeBaseException ES 不可达或索引删除失败 —— 域未被删除
     */
    public List<String> deleteIndices(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return List.of();
        }
        List<String> removed = new ArrayList<>();
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            String normalized = domain.trim();
            String index = KbIndexes.nameOf(normalized);
            // 先删切片预览文件（含文档全文）再删索引：否则 ES 一删，docId 清单就没了着落，
            // 预览目录里会留下带着完整知识内容的孤儿 txt
            deleteExportFiles(index);
            boolean deleted;
            try {
                deleted = EsIndexManager.deleteIndex(esClient, index);
            } catch (EsAccessException e) {
                // 明确区分「索引可能还在」与「查都没问到」：前者绝不能继续删域
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                        "索引[" + index + "]（域 " + normalized + "）未能删除，已中止、域未删除："
                                + e.getMessage() + "。"
                                + "若 ES 只是临时不可达，恢复后重新执行删除即可；"
                                + "若确实删不掉，需先在 ES 侧处理该索引再重试", e);
            }
            if (deleted) {
                storeCache.remove(index);
                removed.add(normalized);
            }
        }
        if (!removed.isEmpty()) {
            log.info("[知识库] 已删除 {} 个域的索引：{}", removed.size(), removed);
        }
        return List.copyOf(removed);
    }

    /**
     * 删除某索引前，先把该索引全部文档的切片预览文件清掉。
     *
     * 导出文件名里带 docId 短码，按 docId 匹配删除；查不到 / 删失败只告警，不阻断删索引。
     */
    private void deleteExportFiles(String index) {
        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(index)
                            .size(10_000)
                            .source(src -> src.filter(f -> f.includes("metadata.doc_id"))),
                    Map.class);
            java.util.Set<String> docIds = new java.util.LinkedHashSet<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                Object source = hit.source() == null ? null : hit.source().get("metadata");
                if (source instanceof Map<?, ?> metadata && metadata.get(ChunkMetadataKeys.DOC_ID) != null) {
                    docIds.add(String.valueOf(metadata.get(ChunkMetadataKeys.DOC_ID)));
                }
            }
            for (String docId : docIds) {
                chunkExporter.deleteByDocId(docId);
            }
            if (!docIds.isEmpty()) {
                log.info("[知识库] 删索引前已清理 {} 个文档的切片预览文件（索引 {}）", docIds.size(), index);
            }
        } catch (Exception e) {
            log.warn("[知识库] 清理索引 {} 的切片预览文件失败（不阻断删索引）: {}", index, e.getMessage());
        }
    }

    /**
     * 重建全部知识库索引（诊断 → 删旧 → 按新维度建 mapping → 校验）。
     *
     * 语义是「删掉重建」，因此索引会被清空，文档需重新上传。什么时候需要它：
     * 换了向量模型导致维度变化（ES 的向量维度是 mapping 参数，建好后无法修改，不重建就永远修不好），
     * 或者想一把清空知识库。一个索引都没有时，重建会建出根域的索引。
     *
     * @param dimensions 新索引的向量维度（取"三处同源"的公共取值点）
     * @return 被重建的索引名
     */
    public List<String> rebuildAll(int dimensions) {
        assertElasticsearchReachable();
        List<String> existing = existingIndices();
        List<String> targets = existing.isEmpty()
                ? List.of(KbIndexes.nameOf(Domains.DEFAULT))
                : existing;
        log.info("[知识库] 开始重建 {} 个索引（维度 {}）：{}", targets.size(), dimensions, targets);
        for (String index : targets) {
            EsIndexManager.diagnoseElasticsearch(esClient, index);
            // 删不掉就必须停在这里：继续往下 createIndexWithIkMapping 只会撞上
            // "索引已存在且维度一致 → 跳过创建"，于是用户点了"重建"却什么都没重建，
            // 界面显示成功、维度仍是旧的 —— 又是一个"不报错但没生效"
            EsIndexManager.deleteIndex(esClient, index);
            EsIndexManager.createIndexWithIkMapping(esClient, index, dimensions);
            EsIndexManager.writeAfterVerify(esClient, index);
        }
        storeCache.clear();
        log.info("[知识库] 索引重建完成：{}", targets);
        return targets;
    }

    /**
     * 换向量模型时保留原文地重建全部知识库索引。
     *
     * 与 {@link #rebuildAll(int)} 的差别就是"保不保留文档"：后者删索引了事，
     * 用户得把每个文档重新上传；而索引里{@code text} 与 {@code metadata} 本来就还在，
     * 切片是服务端算出来的，只需要用新模型把向量重算一遍即可。
     * 因此换向量模型走这条，不需要用户重新上传任何东西。
     *
     * 顺序刻意是「全部捞完 → 才开始删」：逐个索引"捞→删→重灌"的话，
     * 中途失败会留下部分索引已换新向量、部分还是老向量的混合状态 ——
     * 维度相同的两个向量模型混在一个索引里，检索不会报错，只会静默失真。
     *
     * @param dimensions 新索引的向量维度（取"三处同源"的公共取值点）
     * @return 各索引重灌入的切片条数
     */
    public Map<String, Integer> rebuildPreservingText(int dimensions) {
        assertElasticsearchReachable();
        List<String> targets = existingIndices();
        if (targets.isEmpty()) {
            log.info("[向量重建] 当前没有任何知识库索引，无需重灌");
            return Map.of();
        }
        log.info("[向量重建] 开始：{} 个索引，新维度 {}，向量模型来自 {}",
                targets.size(), dimensions, modelHolder.embeddingSource());

        // ① 先全部捞回原文。失败在这里炸，一个索引都还没删。
        Map<String, List<VectorReindexer.PreservedChunk>> preserved =
                VectorReindexer.preserveAll(esClient, targets);
        int totalChunks = preserved.values().stream().mapToInt(List::size).sum();
        log.info("[向量重建] 已保留 {} 个索引共 {} 条切片原文，开始删索引重建", targets.size(), totalChunks);

        // ② 删索引 + 按新维度重建 mapping。删不掉必须停：往下走只会撞上"已存在且维度一致 → 跳过"，
        //    于是用户看到"重建成功"而维度仍是旧的。
        for (String index : targets) {
            EsIndexManager.deleteIndex(esClient, index);
            EsIndexManager.createIndexWithIkMapping(esClient, index, dimensions);
        }
        storeCache.clear();

        // ③ 用新向量模型把原文重新向量化写回
        VectorReindexer.ReindexResult result = VectorReindexer.reindex(
                esClient, embeddingModel, preserved, this::storeFor);

        // 写回数必须与捞回数逐项相等：不等就说明有切片丢了向量，而在向量检索里
        // "没向量"等于这条知识凭空消失 —— 比维度不符更难被发现，必须当场炸。
        for (Map.Entry<String, Integer> e : result.preserved().entrySet()) {
            int written = result.reindexed().getOrDefault(e.getKey(), 0);
            if (written != e.getValue()) {
                throw new IllegalStateException("索引[" + e.getKey() + "]重灌不完整：捞回 "
                        + e.getValue() + " 条、只写回 " + written + " 条，其余切片将无法被向量检索命中");
            }
        }

        log.info("[向量重建] 完成：{}", result.reindexed());
        return result.reindexed();
    }

    /**
     * 删掉全部知识库索引（换向量模型时的另一个选项：不要旧内容）。
     *
     * 与 {@link #rebuildAll(int)} 的区别是不再重建空索引 —— 用户既然选择"丢掉旧知识"，
     * 留一堆空索引没有意义，只会在知识库页显示成一排空域。
     *
     * @return 被删除的索引名
     */
    public List<String> purgeAll() {
        assertElasticsearchReachable();
        List<String> targets = existingIndices();
        if (targets.isEmpty()) {
            return List.of();
        }
        log.info("[向量重建] 按用户选择丢弃全部知识库内容：{} 个索引", targets.size());
        for (String index : targets) {
            // 预览 txt 里是完整文档全文，索引删了它就成孤儿，必须一起清
            deleteExportFiles(index);
            EsIndexManager.deleteIndex(esClient, index);
            storeCache.remove(index);
        }
        log.info("[向量重建] 已清空 {} 个知识库索引", targets.size());
        return targets;
    }

    private void assertElasticsearchReachable() {
        try {
            Boolean ok = esClient.ping().value();
            if (ok == null || !ok) {
                throw new IllegalStateException("ES ping 返回 false");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "Elasticsearch 不可达：" + e.getMessage()
                            + "（请检查管控台「存储配置」页的地址与账号密码）", e);
        }
    }

    // ==================== 上传 ====================

    /**
     * 同步上传一个文档到根域 {@code default} 的索引（按累加语义对全树可见）。
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace) {
        return upload(content, fileName, replace, Domains.DEFAULT);
    }

    /**
     * 同步上传一个文档，并指定它归属的域（即落到该域的索引里）。
     *
     * 域决定哪些对话能检索到这份文档：检索域 D 时只查 D 及其祖先链上的索引，
     * 所以挂在某域 = 该域及其全部后代域都能检索到。检索工具本身仍由部署方用
     * {@code @Tool} 暴露到哪些域，两层是叠加的。
     *
     * @param content  文件字节
     * @param fileName 原始文件名（含扩展名）
     * @param replace  {@code true} = 该域索引内已存在同名文档时先删旧再写入；{@code false} = 直接拒绝
     * @param domain   归属域（完整路径；留空 → 根域）
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace, String domain) {
        String name = requireSupported(fileName);
        if (content == null || content.length == 0) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文件内容为空");
        }
        long max = ragProperties.getMaxFileSize().toBytes();
        if (content.length > max) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "文件 " + name + " 超过大小上限：" + content.length + " 字节，上限 " + max + " 字节");
        }
        String effective = normalizeDomain(domain);
        // 归属域必须是被登记过的域：否则会给一个树里不存在的域凭空建出索引、写进文档，
        // 而那个索引既检索不到（没有域的祖先链包含它）又要靠手工清（删域接口只认注册表）
        if (!domainRegistry.contains(effective)) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "域不存在：" + effective + "（已登记的域: " + domainRegistry.ids() + "）；"
                            + "请先在管控台「域空间」创建该域，或把文档传到已存在的域");
        }

        // ===== 类型分叉 =====
        // 文本类：在这里一次性转成 UTF-8 语义的文本。放在提交任务之前，是为了让"编码判不出"
        // 这类问题在拿导入锁之前就被拒掉。
        // 二进制类：绝不解码。二进制走统一字符集只有两种结果 —— 恰好能通过严格 UTF-8 就
        // 静默灌进一堆乱码知识（最糟），或者报一句用户永远解决不了的"编码错误"。
        DocumentProcessStrategy route = DocumentProcessStrategyFactory.resolve(name);
        String encoding;
        String text;
        if (route.binary()) {
            encoding = IngestSource.BINARY_ENCODING;
            text = null;
        } else {
            TxtNormalizer.Decoded decoded = TxtNormalizer.decode(content);
            encoding = decoded.encoding();
            text = decoded.text();
        }
        log.info("[知识库] 收到上传请求：文件={}，大小={} 字节，类型={}，编码={}，replace={}，域={}",
                name, content.length, route.strategyName(), encoding, replace, effective);

        Metadata metadata = Metadata.from(ChunkMetadataKeys.FILE_NAME, name);
        metadata.put("file_name_lower", lower(name));
        metadata.put(EsIndexManager.DOMAIN_FIELD, effective);
        IngestSource source = route.binary()
                ? IngestSource.ofBinary(content, name, metadata)
                : IngestSource.ofText(text, content, name, metadata, encoding);

        Future<UploadResult> future = submit(source, content.length, name, replace, effective);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR, "上传被中断", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof KnowledgeBaseException kbe) {
                throw kbe;
            }
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                    "知识库文档导入失败：" + cause.getMessage(), cause);
        }
    }

    /** 提交导入任务；队列满时明确拒绝，而不是无界堆积 */
    private Future<UploadResult> submit(IngestSource source, long size,
                                        String fileName, boolean replace, String domain) {
        try {
            return ingestExecutor.submit(() -> ingest(source, size, fileName, replace, domain));
        } catch (RejectedExecutionException e) {
            log.warn("[知识库] 导入队列已满（容量 {}），拒绝本次上传：{}",
                    ragProperties.getIngestQueueCapacity(), fileName);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "知识库导入繁忙：队列已满（容量 " + ragProperties.getIngestQueueCapacity()
                            + "），请稍后重试");
        }
    }

    // ==================== 列表 / 删除 / 状态 ====================

    /** 全部索引上的已入库文档（按 doc_id 聚合切片数；超出 {@link #LIST_MAX_SIZE} 的切片不计入展示） */
    public List<DocumentItem> list() {
        List<DocumentItem> all = new ArrayList<>();
        for (String index : existingIndices()) {
            all.addAll(listIndex(index));
        }
        return all;
    }

    /**
     * 删除一个文档的全部切片，并释放其文件名（删除后同名可以再次上传）。
     *
     * docId 里看不出它在哪个域，因此逐个索引找；命中即删并返回。
     *
     * @return 是否命中并删除
     */
    public boolean delete(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER, "docId 不能为空");
        }
        for (String index : existingIndices()) {
            if (countByDocId(index, docId) > 0) {
                deleteByDocId(index, docId);
                chunkExporter.deleteByDocId(docId);
                log.info("[知识库] 已删除文档 docId={}（索引 {}，文件名随之释放，切片预览文件一并清除）", docId, index);
                return true;
            }
        }
        return false;
    }

    /**
     * 知识库状态：索引数、文档数、切片总数，以及每个索引的明细（域 → 索引 → 文档/切片）。
     */
    public Map<String, Object> status() {
        List<Map<String, Object>> indices = new ArrayList<>();
        int documents = 0;
        int chunks = 0;
        for (String index : existingIndices()) {
            List<DocumentItem> items = listIndex(index);
            int chunkCount = countAll(index);
            documents += items.size();
            chunks += chunkCount;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index);
            row.put("domain", items.isEmpty() ? null : items.get(0).domain());
            row.put("documents", items.size());
            row.put("chunks", chunkCount);
            indices.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexCount", indices.size());
        result.put("documents", documents);
        result.put("chunks", chunks);
        result.put("indices", indices);
        return result;
    }

    // ==================== 内部实现 ====================

    private UploadResult ingest(IngestSource source, long size, String fileName,
                                boolean replace, String domain) throws InterruptedException {
        // 串行锁在任务内部获取：排队等待不占用额外池线程，池大小可保持很小
        if (!ingestPermit.tryAcquire(ragProperties.getIngestLockWaitSeconds(), TimeUnit.SECONDS)) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "知识库导入繁忙：等待超过 " + ragProperties.getIngestLockWaitSeconds()
                            + " 秒仍未拿到导入锁，请稍后重试");
        }
        String docId = null;
        String index = indexOf(domain);
        try {
            ensureIndex(index);
            String lowerName = lower(fileName);
            List<DocumentItem> existing = listIndex(index).stream()
                    .filter(d -> lowerName.equals(d.fileNameLower()))
                    .toList();
            if (!existing.isEmpty()) {
                if (!replace) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_DOCUMENT_DUPLICATE,
                            "域 " + domain + " 下已存在同名文档：" + fileName
                                    + "（同名判定不区分大小写）。"
                                    + "如需替换请带 replace=true，或先删除原文档");
                }
                for (DocumentItem old : existing) {
                    deleteByDocId(index, old.docId());
                    chunkExporter.deleteByDocId(old.docId());
                }
                log.info("[知识库] 覆盖更新：已清除域 {} 下同名旧文档 {} 个", domain, existing.size());
            }

            docId = UUID.randomUUID().toString();
            source.metadata().put(ChunkMetadataKeys.DOC_ID, docId);
            source.metadata().put("upload_time", Instant.now().toString());
            IngestReport report = DocumentIngestor.ingestExternalDocuments(
                    List.of(source), esClient, index, storeFor(index), embeddingModel);
            if (report.documents() <= 0) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                        "文档未处理成功：" + fileName);
            }
            int chunks = countByDocId(index, docId);
            if (chunks <= 0) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                        "导入后未写入任何片段，可能文档内容为空或向量化失败：" + fileName);
            }
            // 切片预览：读回索引里真正入库的切片写成 txt，管控台只展示这个路径。
            // 导出失败不算上传失败 —— 它只是给人核对用的辅助产物。
            java.nio.file.Path exported = chunkExporter.export(index, docId, fileName, domain);
            String exportPath = exported == null ? null : exported.toAbsolutePath().toString();

            log.info("[知识库] 上传完成：文件={}，域={}，索引={}，docId={}，切片={}，标题={}，删噪={} 行，编码={}",
                    fileName, domain, index, docId, chunks, report.sections(), report.droppedLines(),
                    source.encoding());
            return new UploadResult(docId, fileName, size, chunks, domain,
                    report.sections(), report.droppedLines(), source.encoding(), exportPath);
        } catch (KnowledgeBaseException e) {
            rollback(index, docId);
            throw e;
        } catch (Exception e) {
            rollback(index, docId);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                    "知识库文档导入失败：" + e.getMessage(), e);
        } finally {
            ingestPermit.release();
        }
    }

    /**
     * 读取单个索引里的文档条目（按 doc_id 聚合切片数）。
     *
     * 这里是唯一允许对 {@code exists} fail-open 的地方：它是管控台文档列表的读取路径，
     * ES 抖一下的代价只是这一页少显示几个文档，而抛异常会让整个页面报错。
     * 与 {@link #deleteIndices} 的差别正是"破坏性操作 fail-closed、只读展示 fail-open"。
     */
    private List<DocumentItem> listIndex(String index) {
        boolean exists;
        try {
            exists = EsIndexManager.exists(esClient, index);
        } catch (EsAccessException e) {
            log.warn("[知识库] 读取索引[{}]文档列表失败（ES 不可达，按空列表处理）: {}", index, e.getMessage());
            return List.of();
        }
        if (!exists) {
            return List.of();
        }
        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(index)
                    .size(LIST_MAX_SIZE)
                    .query(Query.of(q -> q.matchAll(m -> m)))
                    .source(src -> src.filter(f -> f.includes("metadata"))), Map.class);

            Map<String, String> idToName = new LinkedHashMap<>();
            Map<String, Integer> idToChunks = new LinkedHashMap<>();
            Map<String, String> idToDomain = new LinkedHashMap<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                Map<String, Object> md = metadataOf(hit.source());
                if (md == null) {
                    continue;
                }
                String docId = text(md.get(ChunkMetadataKeys.DOC_ID));
                if (docId == null) {
                    continue;
                }
                idToName.putIfAbsent(docId, text(md.get(ChunkMetadataKeys.FILE_NAME)));
                idToChunks.merge(docId, 1, Integer::sum);
                idToDomain.putIfAbsent(docId, domainOf(md));
            }
            List<DocumentItem> items = new ArrayList<>();
            idToName.forEach((id, fileName) ->
                    items.add(new DocumentItem(id, fileName, lower(fileName),
                            idToChunks.getOrDefault(id, 0),
                            idToDomain.getOrDefault(id, Domains.DEFAULT),
                            exportPathOf(fileName, id))));
            return items;
        } catch (Exception e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "读取知识库索引[" + index + "]文档列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 失败回滚：清掉本次已写入的片段。
     */
    private void rollback(String index, String docId) {
        if (index == null || docId == null) {
            return;
        }
        try {
            deleteByDocId(index, docId);
            log.warn("[知识库] 导入失败已回滚，清除 docId={} 的残留片段", docId);
        } catch (Exception e) {
            log.error("[知识库] 回滚失败（docId={}），残留片段需手工清理: {}", docId, e.getMessage());
        }
    }

    /** 该文档的切片预览文件路径；文件不存在时返回 {@code null}（管控台只展示真实存在的路径） */
    private String exportPathOf(String fileName, String docId) {
        try {
            java.nio.file.Path path = chunkExporter.pathOf(fileName, docId);
            return java.nio.file.Files.exists(path) ? path.toAbsolutePath().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取切片元数据里的归属域；字段缺失或为空时按根域算（历史数据与"未声明"同义）。
     */
    static String domainOf(Map<String, Object> md) {
        Object raw = md.get(EsIndexManager.DOMAIN_FIELD);
        if (raw != null && !raw.toString().isBlank()) {
            return raw.toString();
        }
        return Domains.DEFAULT;
    }

    /** 校验扩展名在白名单内，返回去空白的文件名 */
    private String requireSupported(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文件名不能为空");
        }
        String name = fileName.trim();
        String ext = extensionOf(name);
        if (ext == null || !ragProperties.getAllowedExtensions()
                .contains(ext.toLowerCase(Locale.ROOT))) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "不支持的文件类型：" + name + "；当前白名单 " + ragProperties.getAllowedExtensions());
        }
        return name;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1);
    }

    private void deleteByDocId(String index, String docId) {
        try {
            esClient.deleteByQuery(d -> d.index(index)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId)))));
            esClient.indices().refresh(r -> r.index(index));
        } catch (Exception e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "删除知识库文档失败：" + e.getMessage(), e);
        }
    }

    private int countByDocId(String index, String docId) {
        try {
            return (int) esClient.count(c -> c.index(index)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId))))).count();
        } catch (Exception e) {
            log.warn("[知识库] 统计 docId={} 的片段数失败: {}", docId, e.getMessage());
            return 0;
        }
    }

    private int countAll(String index) {
        try {
            return (int) esClient.count(c -> c.index(index)).count();
        } catch (Exception e) {
            log.warn("[知识库] 统计索引总片段数失败（index={}）: {}", index, e.getMessage());
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataOf(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object md = source.get("metadata");
        return md instanceof Map ? (Map<String, Object>) md : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
