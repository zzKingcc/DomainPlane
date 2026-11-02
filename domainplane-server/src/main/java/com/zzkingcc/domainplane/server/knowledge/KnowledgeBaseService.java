package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.IngestTarget;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
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
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * 结构性事实（与域树、工具、提示词同构）：一个域 = 一个知识库索引。
 * 索引名由域路径确定性派生（{@link KbIndexes#nameOf}），在首次上传时按需创建，
 * 域是运行期由用户创建的，索引跟着域走，没有统一的"启动期全局索引"。
 *
 * 检索侧的域边界由「查哪些索引」保证：检索域 D 时只查 D 自身与祖先链上的索引，
 * 因此文档自带域的含义变成「它被上传到哪个域的索引」。{@code metadata.domain} 只用于
 * 管控台展示与排查，不参与过滤。
 *
 * 存储全部经 SPI（{@link KnowledgeStore}，P0 唯一实现是 ES 适配器）：本类不认识
 * {@code ElasticsearchClient}。原文同步进 {@link ChunkArchive}（BlobStore 权威源），
 * 换模型重灌走 {@link VectorReindexer} 的三阶段（archive 读原文 → 重建 → 重灌），
 * 索引是可重建的派生数据。
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

    /**
     * 删索引前拉取该索引全部文档 docId 的上限（与旧实现的 search size 一致）。
     */
    private static final int EXPORT_CLEANUP_MAX_SIZE = 10_000;

    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;
    private final LlmModelHolder modelHolder;
    private final ChunkExporter chunkExporter;
    /** 域注册表：归属域必须是登记过的域（写入前校验，避免凭空造索引） */
    private final DomainRegistry domainRegistry;
    /**
     * 检索计划：写入与生命周期的 store 经它取（{@link RetrievalPlan#keywordStore()}，
     * keyword 后端为文档登记视角的权威——一审修订 B4）。本类不认识任何后端类型，
     * 也不认识 {@code ElasticsearchClient}。
     */
    private final RetrievalPlan retrievalPlan;
    /** 切片原文档案：ingest 成功后归档，重灌时读回 */
    private final ChunkArchive chunkArchive;
    /** 档案对账：重灌前的清理（补缺档、删孤儿）复用同一套双向逻辑 */
    private final ChunkArchiveReconciler archiveReconciler;
    private final ThreadPoolExecutor ingestExecutor;
    private final Semaphore ingestPermit = new Semaphore(1);

    public KnowledgeBaseService(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                                RagProperties ragProperties,
                                LlmModelHolder modelHolder,
                                ChunkExporter chunkExporter,
                                DomainRegistry domainRegistry,
                                RetrievalPlan retrievalPlan,
                                ChunkArchive chunkArchive,
                                ChunkArchiveReconciler archiveReconciler) {
        this.embeddingModel = embeddingModel;
        this.ragProperties = ragProperties;
        this.modelHolder = modelHolder;
        this.chunkExporter = chunkExporter;
        this.domainRegistry = domainRegistry;
        this.retrievalPlan = retrievalPlan;
        this.chunkArchive = chunkArchive;
        this.archiveReconciler = archiveReconciler;
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
     * 规范化并校验文档归属域，与 {@code @Tool(domains = {...})} 同构：
     * 必须是从根域出发的完整路径，留空 = 挂在根域 {@code default}。
     *
     * 路径非法直接抛异常而不是静默丢弃，静默会把文档写到一个树里不存在的域上，
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

    /**
     * 当前已存在的全部知识库索引（按名排序）。
     *
     * <p>取全部后端的并集：索引属于域，域的东西可能落在不同机器上（一个域一个知识体），
     * 只看平台默认那一台会漏掉挂在别的连接上的域。</p>
     */
    public List<String> existingIndices() {
        Set<String> names = new LinkedHashSet<>();
        for (KnowledgeStore store : stores()) {
            names.addAll(store.listCollections());
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }

    /**
     * 索引名 → <b>真实存在这个索引的后端 id 清单</b>（一次列全）。
     *
     * <p>管控台知识库页要回答"这份数据在哪几台机器上"，而索引名只由域派生、可以被写在
     * 多台上（扇出写入、或域换过知识体后旧机器的残留），所以这是唯一的事实来源：
     * 逐台 {@code listCollections()} 拿到自己的清单后按索引名归并。</p>
     *
     * <p><b>与 {@link #existingIndices()} 的关系</b>：两者的名字集合完全相同
     * （后者是前者的 key 排序视图）。统计层用这个方法一次拿全，避免"枚举一遍拿名字、
     * 再枚举一遍拿归属"——存储黑洞时那就是两次 connectTimeout。</p>
     *
     * <p>只读展示路径：单台枚举抛异常按"这台没有"处理（与 SPI 的 fail-open 同口径），
     * 不让一台坏后端把整页拖垮。</p>
     */
    public Map<String, List<String>> indexOwners() {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (KnowledgeBackend backend : retrievalPlan.backends()) {
            backend.store().ifPresent(store -> {
                try {
                    for (String collection : store.listCollections()) {
                        owners.computeIfAbsent(collection, name -> new ArrayList<>()).add(backend.id());
                    }
                } catch (RuntimeException e) {
                    log.warn("[知识库] 枚举后端 {} 的索引失败（按空处理）: {}", backend.id(), e.getMessage());
                }
            });
        }
        return owners;
    }

    /**
     * 索引不存在则按当前向量维度创建（幂等）。<b>平台默认</b>版本：没有域上下文时用。
     *
     * <p>扇出到该域的全部写入后端：keyword 后端缺集合会 BM25 查不到，
     * vector 后端缺集合会 kNN 查不到——两边都得建。</p>
     */
    public void ensureIndex(String indexName) {
        try {
            writeStore().ensureCollection(indexName, modelHolder.effectiveEmbeddingDimension());
        } catch (StoreAccessException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "确保知识库索引[" + indexName + "]存在失败：" + e.getMessage(), e);
        }
    }

    /**
     * 建该域自己的索引（<b>带域</b>）：落在它绑的知识体上，而不是平台默认那台。
     *
     * <p>上传走这条。用平台默认会在"这个域挂在 Qdrant"时，把索引建到 ES 上，
     * 紧接着的写入又写到 Qdrant——那边没有集合，写入报错；或者更糟，集合自动建出来
     * 但维度与 mapping 不对，检索时静默召回不到。</p>
     */
    public void ensureIndex(String domain, String indexName) {
        try {
            writeStore(domain).ensureCollection(indexName,
                    modelHolder.effectiveEmbeddingDimension());
        } catch (StoreAccessException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "确保域 " + domain + " 的知识库索引[" + indexName + "]存在失败："
                            + e.getMessage(), e);
        }
    }

    /**
     * 取任一已存在索引的向量维度；没有任何索引时返回 {@code null}。
     *
     * <p>维度是全局的（只有一个向量模型），所以拿哪个索引读都一样。</p>
     *
     * <p>读数源是<b>向量能力</b>（一审修订 B4）：keyword-only 后端没有维度概念，
     * 遍历全部后端会把第一个有值的当全局值。多后端之后仍按"谁能给维度就问谁"，
     * 只是问的范围从"平台默认那一台"扩到全部——域可能挂在不同连接上。</p>
     */
    public Integer currentVectorDims() {
        for (KnowledgeStore vectorStore : stores()) {
            for (String index : vectorStore.listCollections()) {
                Integer dims;
                try {
                    dims = vectorStore.currentVectorDims(index);
                } catch (RuntimeException e) {
                    // 这台后端不认维度（纯 keyword 实现）：问下一台，不打断整个统计
                    continue;
                }
                if (dims != null) {
                    return dims;
                }
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
            total += countAll(index);
        }
        return total;
    }

    /**
     * 删除若干域各自的知识库索引（供删域时清理，先清索引再删域）。
     *
     * fail-closed：任何一个域的索引删不掉（存储不可达 / 删除失败），立即抛异常中止，
     * 绝不"跳过它继续删下一个"。删域的语义是"这个域的一切都清干净"，
     * 留一个孤儿索引的后果不是"多了点垃圾"：
     * 索引名是域路径的哈希，同一路径域将来重建时索引名相同，
     * 这份本该删掉的知识内容会原样复活，且没有任何报错。
     *
     * @return 实际存在并被删除的域标识
     * @throws KnowledgeBaseException 存储不可达或索引删除失败，域未被删除
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
            // 先删切片预览文件（含文档全文）再删索引：否则索引一删，docId 清单就没了着落，
            // 预览目录里会留下带着完整知识内容的孤儿 txt
            deleteExportFiles(index);
            boolean deleted;
            try {
                // 逐台后端删同名 collection：索引名按域唯一，所以"在每台上都删一次"是幂等的；
                // 而且必须逐台删——域换过知识体时，旧数据还在上一台的机器上，
                // 只删当前那台会留下一个永远不会被查到的孤儿索引（同名域重建就复活了）
                deleted = false;
                for (KnowledgeStore store : stores()) {
                    deleted |= store.deleteCollection(index);
                }
            } catch (StoreAccessException e) {
                // 明确区分「索引可能还在」与「查都没问到」：前者绝不能继续删域
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                        "索引[" + index + "]（域 " + normalized + "）未能删除，已中止、域未删除："
                                + e.getMessage() + "。"
                                + "若存储只是临时不可达，恢复后重新执行删除即可；"
                                + "若确实删不掉，需先在存储侧处理该索引再重试", e);
            }
            if (deleted) {
                removed.add(normalized);
            }
        }
        if (!removed.isEmpty()) {
            log.info("[知识库] 已删除 {} 个域的索引：{}", removed.size(), removed);
        }
        return List.copyOf(removed);
    }

    /**
     * 删除某索引前，先把该索引的旁挂产物清掉：切片预览文件 + 原文档案。
     *
     * 顺序硬要求（一审修订 B3）：先删 exporter 预览文件与 archive，再删 collection。
     * 反过来的话 collection 一删，docId 清单就没了着落——archive 里会留下
     * 带着完整知识内容的孤儿（重灌时还会把已删文档复活），预览目录里留下孤儿 txt。
     *
     * 两者删失败都只告警：真正 fail-closed 的是随后的 deleteCollection。
     */
    private void deleteExportFiles(String index) {
        try {
            List<String> docIds = mergeDocuments(index, EXPORT_CLEANUP_MAX_SIZE).stream()
                    .map(DocumentAgg::docId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            for (String docId : docIds) {
                chunkExporter.deleteByDocId(docId);
            }
            if (!docIds.isEmpty()) {
                log.info("[知识库] 删索引前已清理 {} 个文档的切片预览文件（索引 {}）", docIds.size(), index);
            }
        } catch (Exception e) {
            log.warn("[知识库] 清理索引 {} 的切片预览文件失败（不阻断删索引）: {}", index, e.getMessage());
        }
        // 原文档案整域清除：与预览文件同族，漏删的键由对账当孤儿回收
        chunkArchive.deleteCollection(index);
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
        assertStoreReachable();
        List<String> existing = existingIndices();
        List<String> targets = existing.isEmpty()
                ? List.of(KbIndexes.nameOf(Domains.DEFAULT))
                : existing;
        Map<String, List<KnowledgeStore>> owners = ownersByIndex();
        log.info("[知识库] 开始重建 {} 个索引（维度 {}）：{}", targets.size(), dimensions, targets);
        for (String index : targets) {
            // 只重建"这个索引真实存在的那几台"：盲目在每台上都 ensureCollection 会
            // 给每台机器都建出一排空索引，知识库页于是显示一堆零文档的域
            List<KnowledgeStore> holders = owners.get(index);
            if (holders == null || holders.isEmpty()) {
                holders = List.of(writeStore());
            }
            for (KnowledgeStore store : holders) {
                store.diagnose(index);
                // 删不掉就必须停在这里：继续往下 ensureCollection 只会撞上
                // "索引已存在且维度一致 → 跳过创建"，于是用户点了"重建"却什么都没重建，
                // 界面显示成功、维度仍是旧的，又是一个"不报错但没生效"
                try {
                    store.deleteCollection(index);
                } catch (StoreAccessException e) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                            "重建时删除索引[" + index + "]失败，已中止：" + e.getMessage(), e);
                }
                store.ensureCollection(index, dimensions);
            }
            /* 旧实现在这里还有一次 writeAfterVerify（refresh + count + mapping 日志）。
               重建后的索引为空，count 恒 0，mapping 信息 diagnose 已经打过一遍，
               刷新与否对空索引无意义；真正写入时的刷新由 SPI 的 write() 负责。 */
        }
        log.info("[知识库] 索引重建完成：{}", targets);
        return targets;
    }
    /**
     * 换向量模型时保留原文地重建全部知识库索引。
     *
     * 与 {@link #rebuildAll(int)} 的差别就是"保不保留文档"：后者删索引了事，
     * 用户得把每个文档重新上传；而原文档案（{@link ChunkArchive}）里存着全部切片，
     * 只需要用新模型把向量重算一遍即可。因此换向量模型走这条，不需要用户重新上传任何东西。
     *
     * 顺序刻意是「全部捞完 → 才开始删」：逐个索引"捞→删→重灌"的话，
     * 中途失败会留下部分索引已换新向量、部分还是老向量的混合状态，
     * 维度相同的两个向量模型混在一个索引里，检索不会报错，只会静默失真。
     *
     * @param dimensions 新索引的向量维度（取"三处同源"的公共取值点）
     * @return 各索引重灌入的切片条数
     */
    public Map<String, Integer> rebuildPreservingText(int dimensions) {
        assertStoreReachable();
        log.info("[向量重建] 开始（三阶段：档案对账 → 读原文 → 重建 → 重灌），新维度 {}，向量模型来自 {}",
                dimensions, modelHolder.embeddingSource());

        /* 重灌按<b>每一台后端原地重灌</b>：知识体把数据摊在多台机器上，而 archive 是
           全局的（按索引名归档），所以"对每台各自跑一遍三阶段"就等价于"把全部索引都换新
           向量"，不需要先算出"哪个索引在哪台"。索引名按域唯一，因此各台的 collection
           清单天然不重叠；换过知识体的域若在两台都留了同名 collection，两次都会各自
           重灌成新维度，不会互相踩。 */
        Map<String, Integer> reindexed = new LinkedHashMap<>();
        int preservedTotal = 0;
        for (KnowledgeStore store : stores()) {
            VectorReindexer.ReindexResult result = VectorReindexer.reindex(
                    chunkArchive, archiveReconciler, store, store, embeddingModel, dimensions);
            result.reindexed().forEach(reindexed::put);
            preservedTotal += result.preserved().values().stream().mapToInt(Integer::intValue).sum();
        }

        // 写回数必须与捞回数逐项相等：不等就说明有切片丢了向量，而在向量检索里
        // "没向量"等于这条知识凭空消失，比维度不符更难被发现，必须当场炸
        // （ReindexResult 内部已逐 collection 核对，这里再兜一层总量说明）
        log.info("[向量重建] 完成：{} 个索引、共 {} 条切片（捞回 {} 条）",
                reindexed.size(), reindexed.values().stream().mapToInt(Integer::intValue).sum(),
                preservedTotal);
        return reindexed;
    }

    /**
     * 删掉全部知识库索引（换向量模型时的另一个选项：不要旧内容）。
     *
     * 与 {@link #rebuildAll(int)} 的区别是不再重建空索引，用户既然选择"丢掉旧知识"，
     * 留一堆空索引没有意义，只会在知识库页显示成一排空域。
     *
     * @return 被删除的索引名
     */
    public List<String> purgeAll() {
        assertStoreReachable();
        List<String> targets = existingIndices();
        if (targets.isEmpty()) {
            return List.of();
        }
        log.info("[向量重建] 按用户选择丢弃全部知识库内容：{} 个索引", targets.size());
        for (String index : targets) {
            // 预览 txt 里是完整文档全文，索引删了它就成孤儿，必须一起清
            deleteExportFiles(index);
            try {
                // 每台都删：清空语义是"这些索引一个字节都别留"，包括换过知识体后
                // 残留在旧机器上的那份
                for (KnowledgeStore store : stores()) {
                    store.deleteCollection(index);
                }
            } catch (StoreAccessException e) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                        "清空知识库时删除索引[" + index + "]失败：" + e.getMessage(), e);
            }
        }
        log.info("[向量重建] 已清空 {} 个知识库索引", targets.size());
        return targets;
    }

    /**
     * 把当前全部知识库内容跨后端重灌到指定目标后端（换后端 = 从 archive 重灌到新后端，设计第 11 节）。
     *
     * 源是 keyword 后端（文档登记权威，archive 兜底 scan 也走它），目标是界面指定的后端。
     * 目标后端可以是当前生效的 vector 后端（等价于「换向量模型保留原文重建」），
     * 也可以是另一个已注册、具备向量存储能力的后端（等价于把数据搬到新后端）。
     *
     * 维度不可由调用方指定——它只能来自当前生效的向量模型（"三处同源"的唯一点），
     * 否则会出现"模型输出维度 ≠ 重灌维度"的静默错配。向量模型未配置则无法重灌。
     *
     * @param targetBackendId 目标后端 id（必须已注册且具备向量存储能力）
     * @return 各 collection 重灌入的切片条数
     * @throws KnowledgeBaseException 目标未注册 / 不具备存储或向量能力（60007）、导入锁超时（已拒绝）
     * @throws NotConfiguredException 向量模型未配置（由全局处理器转 503 指引管控台）
     */
    public Map<String, Integer> reindexToBackend(String targetBackendId) {
        if (targetBackendId == null || targetBackendId.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER, "targetBackendId 不能为空");
        }
        try {
            // 串行锁复用到上传：重灌写目标后端，不能与上传并发写坏数据
            if (!ingestPermit.tryAcquire(ragProperties.getIngestLockWaitSeconds(), TimeUnit.SECONDS)) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                        "知识库重灌繁忙：等待超过 " + ragProperties.getIngestLockWaitSeconds()
                                + " 秒仍未拿到导入锁，请稍后重试");
            }
            try {
                // 目标后端必须已注册且提供 store 能力；且重灌写向量，必须具备向量能力
                KnowledgeBackend targetBackend = retrievalPlan.backends().stream()
                        .filter(b -> b.id().equals(targetBackendId))
                        .findFirst()
                        .orElseThrow(() -> new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                                "知识库后端 " + targetBackendId + " 未注册（可用后端: "
                                        + retrievalPlan.backends().stream().map(KnowledgeBackend::id)
                                        .collect(java.util.stream.Collectors.joining(", ")) + "）"));
                KnowledgeStore target = targetBackend.store().orElseThrow(() -> new KnowledgeBaseException(
                        ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                        "知识库后端 " + targetBackendId + " 未提供存储能力（应被启动期校验拦截）"));
                if (!targetBackend.capabilities().vector()) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                            "目标后端 " + targetBackendId + " 不具备向量存储能力（capabilities.vector=false），"
                                    + "不能作为重灌目标");
                }
                // 向量模型必须已配置：否则连维度都取不到
                if (!modelHolder.isEmbeddingConfigured()) {
                    throw new NotConfiguredException("向量模型未配置，无法重灌到后端 [" + targetBackendId
                            + "]；请在管控台「模型设置」的「向量模型」段启用 Embedding 模型后再试");
                }
                int dims = modelHolder.effectiveEmbeddingDimension();
                /* 源 = 每一台后端各自（知识体的数据摊在多台机器上，只搬"平台默认那台"
                   会漏掉挂别的连接上的域）；目标 = 指定后端。
                   collection 名按域唯一、各台清单天然不重叠，所以逐台搬不会互相覆盖：
                   每一轮只删目标上"这一台源里有的"那几个 collection。
                   源恰好就是目标时走"原地重灌"那个重载（先删后建同一台）。 */
                Map<String, Integer> reindexed = new LinkedHashMap<>();
                for (KnowledgeStore source : stores()) {
                    VectorReindexer.ReindexResult result = source == target
                            ? VectorReindexer.reindex(chunkArchive, archiveReconciler,
                                    target, embeddingModel, dims)
                            : VectorReindexer.reindex(chunkArchive, archiveReconciler,
                                    source, target, embeddingModel, dims);
                    reindexed.putAll(result.reindexed());
                }
                log.info("[向量重灌] 重灌到后端 [{}] 完成：{}", targetBackendId, reindexed);
                return reindexed;
            } finally {
                ingestPermit.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR, "重灌被中断", e);
        }
    }

    /**
     * 已注册的知识库后端清单（含能力声明）：管控台「重灌到新后端」下拉用。
     */
    public List<Map<String, Object>> listBackends() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeBackend b : retrievalPlan.backends()) {
            StoreCapabilities c = b.capabilities();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("capabilities", Map.of(
                    "keyword", c.keyword(),
                    "vector", c.vector(),
                    "wildcard", c.wildcard(),
                    "storesText", c.storesText(),
                    "reindexInPlace", c.reindexInPlace()));
            m.put("hasStore", b.store().isPresent());
            out.add(m);
        }
        return out;
    }

    private void assertStoreReachable() {
        // 每一台都探：域可能挂在不同连接上，"默认那台活着"不代表别人的数据安全。
        // 任一台不可达就整体拒绝——这些调用方全是破坏性操作（重建 / 清空 / 重灌）
        for (KnowledgeStore store : stores()) {
            if (!store.ping()) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                        "知识库存储不可达（请检查管控台「存储配置」页的地址与账号密码）");
            }
        }
    }

    /**
     * 写入 / 删除用的 store（<b>带域</b>）：写这个域自己绑的知识体。
     *
     * <p>知识体是一个域一个，所以正常只落一台机器；万一是旧的两角色绑定（关键字在 ES、
     * 向量在 Qdrant），{@link RetrievalPlan#writeStore(String)} 会扇出成两份，两边都拿到
     * 这份切片——否则另一条召回路静默查不到。</p>
     *
     * <p>域没绑知识体时<b>直接抛</b>（不回落平台默认）：回落会把 A 域的知识写进"第一个
     * 知识体那台机器"，看起来成功、实际检索不到，而且没有任何报错。</p>
     */
    private KnowledgeStore writeStore(String domain) {
        return retrievalPlan.writeStore(domain);
    }

    /** 读路径用的 store（带域）：该域自己那份存储；没绑时回落平台默认（只读场景可容忍） */
    private KnowledgeStore readStore(String domain) {
        return retrievalPlan.readStore(domain);
    }

    /** 平台默认写入 store：没有域上下文的对账 / 诊断 / 内部维护用 */
    private KnowledgeStore writeStore() {
        return retrievalPlan.writeStore();
    }

    /** 平台默认读 store：keyword 后端（文档登记视角的权威，B4） */
    private KnowledgeStore store() {
        return retrievalPlan.keywordStore();
    }

    /**
     * 索引名 → 真实存在这个索引的后端（一次列全，避免每索引每后端各查一遍）。
     *
     * <p>重建 / 清空用它来"只动真的有数据的那几台"。</p>
     */
    private Map<String, List<KnowledgeStore>> ownersByIndex() {
        Map<String, List<KnowledgeStore>> owners = new LinkedHashMap<>();
        for (KnowledgeStore store : stores()) {
            for (String collection : store.listCollections()) {
                owners.computeIfAbsent(collection, name -> new ArrayList<>()).add(store);
            }
        }
        return owners;
    }

    /**
     * 全部已注册后端里能提供存储的实例（按对象身份去重）。
     *
     * <p>读路径为什么要看全量：知识体是"一种用途 + 一台连接"，A 域可能挂在 ES、
     * B 域挂在 Qdrant。只读平台默认那一台，B 域在知识库页永远显示"零文档"，而数据
     * 好端端躺在另一台机器上——这类"看着空、其实有"最费排查时间。</p>
     *
     * <p>清单页 / 状态页 / 清空这类<b>平台级</b>视图据此汇总；单域写入与写入后的回读
     * 一律走 {@link #writeStore(String)} / {@link #readStore(String)}，按域精确落点。</p>
     */
    private List<KnowledgeStore> stores() {
        Set<KnowledgeStore> distinct =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (KnowledgeBackend backend : retrievalPlan.backends()) {
            backend.store().ifPresent(distinct::add);
        }
        if (distinct.isEmpty()) {
            // 一个后端都没注册时退回平台默认：让调用方拿到"未配置"的那句标准报错，
            // 而不是在这里抛一个空列表导致的 NPE
            return List.of(store());
        }
        return List.copyOf(distinct);
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
        // 二进制类：绝不解码。二进制走统一字符集只有两种结果，恰好能通过严格 UTF-8 就
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
        metadata.put(ChunkMetadataKeys.DOMAIN, effective);
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
        return list(existingIndices());
    }

    /**
     * 指定索引上的已入库文档。
     *
     * <p>给已经枚举过索引的调用方用（统计层用 {@link #indexOwners()} 一次拿全，
     * 再来这里取文档）——否则 {@code list()} 内部会再枚举一遍全部后端，
     * 存储不可达时等于白烧第二个 connectTimeout。</p>
     */
    public List<DocumentItem> list(Collection<String> indices) {
        if (indices == null || indices.isEmpty()) {
            return List.of();
        }
        List<DocumentItem> all = new ArrayList<>();
        for (String index : indices) {
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
            ensureIndex(domain, index);
            String lowerName = lower(fileName);
            List<DocumentItem> existing = listIndex(index).stream()
                    .filter(d -> lowerName.equals(d.fileNameLower()))
                    .toList();
            if (!existing.isEmpty() && !replace) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_DOCUMENT_DUPLICATE,
                        "域 " + domain + " 下已存在同名文档：" + fileName
                                + "（同名判定不区分大小写）。"
                                + "如需替换请带 replace=true，或先删除原文档");
            }
            /* 同名旧文档的删除刻意推迟到新文档确认写入成功之后（见下方）：先删后写时，
               新文档任一环节失败都会让这次"覆盖更新"变成"只删不写"，旧文档永久丢失。 */

            docId = UUID.randomUUID().toString();
            source.metadata().put(ChunkMetadataKeys.DOC_ID, docId);
            source.metadata().put("upload_time", Instant.now().toString());
            /* 写入目标 = 这个域自己绑的知识体（扇出到该域的全部写入后端：keyword 与 vector
               两份都要拿到这份切片，否则对应的那条召回路静默查不到）。去重问登记权威，
               扇出 store 内部就是这么委派的——ingest 侧只认 SPI，不认识任何后端类型。

               注意这里必须是<b>带域</b>的那份：用平台默认会把 A 域的知识写进 B 域那台机器，
               上传成功、检索为空，且没有任何报错。 */
            KnowledgeStore target = writeStore(domain);
            IngestTarget ingestTarget = new IngestTarget(index, target, target::existingDedupKeys);
            IngestReport report = DocumentIngestor.ingest(List.of(source), ingestTarget, embeddingModel);
            if (report.documents() <= 0) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                        "文档未处理成功：" + fileName);
            }
            int chunks = countByDocId(index, docId);
            if (chunks <= 0) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                        "导入后未写入任何片段，可能文档内容为空或向量化失败：" + fileName);
            }

            /* 原文归档：索引已确认落库，把"刚写进去的切片"收进 ChunkArchive
               （权威源，换模型重灌的输入）。经 readByDocId 读回而不是复用策略层
               的 segments——归档的必须是"索引里真正入库的切片"（去重跳过了哪片，
               档案里就如实反映），与 ChunkExporter 同一条"所见即所存"口径。
               写失败只告警（ChunkArchive 内部处理），缺档由对账补写。 */
            chunkArchive.write(index, docId, readStore(domain).readByDocId(index, docId));

            /* 新文档已确认落库，此刻才清理同名旧文档。顺序不能换：反过来的话，新文档任一
               环节失败都会让这次"覆盖更新"变成"只删不写"，旧文档永久消失。

               清理失败单独吞掉（只记日志），绝不能让它冒泡到下面的 catch，那里会
               rollback 掉新 docId，而此时旧文档可能已经删掉了一部分，结果是新旧两份都没了。
               吞掉的代价只是同名文档暂时并存，用户再删一次即可。 */
            for (DocumentItem old : existing) {
                try {
                    deleteByDocId(index, old.docId());
                    chunkExporter.deleteByDocId(old.docId());
                } catch (Exception e) {
                    log.error("[知识库] 覆盖更新时清除旧文档失败（旧 docId={}），新旧两份均保留: {}",
                            old.docId(), e.getMessage());
                }
            }
            if (!existing.isEmpty()) {
                log.info("[知识库] 覆盖更新：已清除域 {} 下同名旧文档 {} 个", domain, existing.size());
            }

            // 切片预览：读回索引里真正入库的切片写成 txt，管控台只展示这个路径。
            // 导出失败不算上传失败，它只是给人核对用的辅助产物。
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
     * 只读展示路径，fail-open：SPI 的 {@code documents} 在存储不可达时返回空列表
     * （设计 4.6）——一页少显示几个文档好过整页打不开。与 {@link #deleteIndices}
     * 的差别正是"破坏性操作 fail-closed、只读展示 fail-open"。
     */
    private List<DocumentItem> listIndex(String index) {
        List<DocumentAgg> docs = mergeDocuments(index, LIST_MAX_SIZE);
        List<DocumentItem> items = new ArrayList<>(docs.size());
        for (DocumentAgg doc : docs) {
            String fileName = doc.fileName();
            items.add(new DocumentItem(doc.docId(), fileName, lower(fileName),
                    doc.chunks(), doc.domain(), exportPathOf(fileName, doc.docId())));
        }
        return items;
    }

    /**
     * 单个索引的文档聚合：<b>跨后端合并</b>，按 docId 去重。
     *
     * <p>为什么要合并：扇出写入（同一域关键字与向量分两台）会在两台各留一份同 docId 的
     * 切片；换过知识体的域则可能在旧机器上还有老文档。合并 + 按 docId 去重既不会把一份
     * 文档显示成两条，也不会让老机器上的内容在页面上凭空消失。</p>
     */
    private List<DocumentAgg> mergeDocuments(String index, int limit) {
        Map<String, DocumentAgg> byDocId = new LinkedHashMap<>();
        for (KnowledgeStore store : stores()) {
            for (DocumentAgg doc : store.documents(index, limit)) {
                if (doc.docId() != null) {
                    byDocId.putIfAbsent(doc.docId(), doc);
                }
            }
        }
        return List.copyOf(byDocId.values());
    }

    /**
     * 失败回滚：清掉本次已写入的片段（连同原文档案——否则索引回滚了、
     * 档案还留着，重灌会把这份没入库的文档复活）。
     */
    private void rollback(String index, String docId) {
        if (index == null || docId == null) {
            return;
        }
        try {
            deleteByDocId(index, docId);
            chunkArchive.delete(index, docId);
            log.warn("[知识库] 导入失败已回滚，清除 docId={} 的残留片段与档案", docId);
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
            // 逐台都删：扇出写入时两台各有一份；域换过知识体时旧机器上还有残留。
            // 只删"当前那台"会留下孤儿切片（向量路仍能命中它，而管控台看不到这份文档）
            for (KnowledgeStore store : stores()) {
                store.deleteByDocId(index, docId);
            }
        } catch (StoreAccessException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "删除知识库文档失败：" + e.getMessage(), e);
        }
    }

    /**
     * 该索引里这个 docId 的切片数：<b>跨后端取最大值</b>，不是求和。
     *
     * <p>求和会把扇出写入算成两倍（同一份切片在两台各一份）；取最大值在单台部署下
     * 与旧行为完全一致，在扇出部署下也仍然是"这份文档实际有几片"。真正的存在性判断
     * （{@code > 0}）两种口径都对，但展示与核对只认对的那个。</p>
     */
    private int countByDocId(String index, String docId) {
        int max = 0;
        for (KnowledgeStore store : stores()) {
            max = Math.max(max, (int) store.countByDocId(index, docId));
        }
        return max;
    }

    /** 该索引的切片总数：口径同上，跨后端取最大值（不求和，避免扇出翻倍） */
    private int countAll(String index) {
        int max = 0;
        for (KnowledgeStore store : stores()) {
            max = Math.max(max, (int) store.count(index));
        }
        return max;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
