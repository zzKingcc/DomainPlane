package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.IngestTarget;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
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
import com.zzkingcc.domainplane.server.settings.InfraSettings;
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
 * 知识库服务：文档上传 / 列表 / 删除 / 状态，并承担<b>知识体</b>索引的生命周期。
 *
 * <p>结构性事实（v2）：<b>一个知识体 = 一个索引</b>，索引名由知识体唯一名派生、创建时冻结
 * （{@link com.zzkingcc.domainplane.server.settings.InfraSettings.KnowledgeBody#effectiveIndex()}）。
 * 域只是消费者：多个域可以绑同一个体、查同一份数据；删域只解绑，不动这里的任何东西。
 * 于是本类的所有写/读/重建动作都以 <b>bodyId</b> 为参数，没有一个方法再问"域"。</p>
 *
 * <p>检索侧的边界由「查哪些索引」保证：检索域 D 时查 D 自身与祖先链上各域<b>绑定的体</b>的索引，
 * 因此"文档属于谁"变成"它被上传到哪个体"。{@code metadata.domain} 记的是体 id，
 * 只用于管控台展示与排查，不参与过滤。</p>
 *
 * <p>存储全部经 SPI（{@link KnowledgeStore}）：本类不认识 {@code ElasticsearchClient}。
 * 原文同步进 {@link ChunkArchive}（BlobStore 权威源），换模型重灌走 {@link VectorReindexer}
 * 的三阶段（archive 读原文 → 重建 → 重灌），索引是可重建的派生数据。</p>
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
     * 慢操作日志阈值（毫秒）：只读统计链路（枚举索引 / 读文档列表）超过它就打 WARN。
     * 管控台"知识库页加载慢"的定位入口。
     */
    private static final long SLOW_OP_MS = 1_500L;

    /** 「看文档」一次最多回多少片：切片数动辄上千，弹层里一次全塞给浏览器既慢也没人看得完 */
    private static final int PREVIEW_MAX_CHUNKS = 200;

    /**
     * 删索引前拉取该索引全部文档 docId 的上限（与旧实现的 search size 一致）。
     */
    private static final int EXPORT_CLEANUP_MAX_SIZE = 10_000;

    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;
    private final LlmModelHolder modelHolder;
    private final ChunkExporter chunkExporter;
    /**
     * 检索计划：写入与生命周期的 store 经它按<b>知识体</b>取
     * （{@link RetrievalPlan#writeStoreOfBody(String)}）。本类不认识任何后端类型。
     */
    private final RetrievalPlan retrievalPlan;
    /** 知识体解析：bodyId → 体定义 / 冻结索引名；管理链路只认它，不再问域 */
    private final KnowledgeBodyResolver bodyResolver;
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
                                RetrievalPlan retrievalPlan,
                                KnowledgeBodyResolver bodyResolver,
                                ChunkArchive chunkArchive,
                                ChunkArchiveReconciler archiveReconciler) {
        this.embeddingModel = embeddingModel;
        this.ragProperties = ragProperties;
        this.modelHolder = modelHolder;
        this.chunkExporter = chunkExporter;
        this.retrievalPlan = retrievalPlan;
        this.bodyResolver = bodyResolver;
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
     * @param body         落到的知识体 id
     * @param encoding     实际识别到的源文件编码（全链路统一 UTF-8 后，这个值只作诊断）
     * @param sections     识别到的标题数
     * @param droppedLines 清洗阶段删掉的行数
     * @param exportPath   切片预览 txt 的落盘路径（导出失败时为 null）
     */
    public record UploadResult(String docId, String fileName, long size, int chunks, String body,
                               int sections, int droppedLines, String encoding, String exportPath) {
    }

    /**
     * 索引内已入库的文档条目。
     *
     * @param body 文档落的知识体 id（来自切片 metadata；早期文档可能是空）
     */
    public record DocumentItem(String docId, String fileName, String fileNameLower,
                               int chunks, String body, String exportPath) {
    }

    /**
     * 「看文档」里的一片。
     *
     * @param seq   切片序号（metadata 的 {@code chunk_seq}，缺失按 0）
     * @param text  切片正文（未入库前切出来的样子，去重跳过的不在此列）
     * @param chars 正文字数——弹层里给"这片多长"一个直观数字，不用自己去数
     */
    public record PreviewChunk(int seq, String text, int chars) {
    }

    /**
     * 一个文档的切片预览（管控台「看文档」）。
     *
     * @param total       该文档在档案/索引里的切片总数
     * @param fromArchive true = 读的原文档案（权威源）；false = 档案缺失，回落到索引读
     * @param chunks      最多 {@link #PREVIEW_MAX_CHUNKS} 片
     */
    public record Preview(String docId, String index, long total, boolean fromArchive,
                          List<PreviewChunk> chunks) {
    }

    // ==================== 知识体 → 索引 ====================

    /**
     * 知识体 → 它的冻结索引名；体不存在直接抛（写入侧不能猜）。
     *
     * <p>这是全平台唯一的换算点（{@link KnowledgeBodyResolver#indexOf}）：新体按唯一名派生，
     * 迁移体带着旧名。索引名不随改名 / 换落点变化。</p>
     */
    public String indexOf(String bodyId) {
        String index = bodyResolver.indexOf(bodyId);
        if (index == null) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "知识体不存在：" + bodyId + "（可能刚被删除，请刷新知识库页）");
        }
        return index;
    }

    /**
     * 当前已存在的全部知识库索引（按名排序）。
     *
     * <p>取全部后端的并集：体的数据可能落在不同机器上（关键字与向量分两台、或体换过落点），
     * 只看平台默认那一台会漏掉挂别的连接上的体。</p>
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
                long start = System.nanoTime();
                try {
                    for (String collection : store.listCollections()) {
                        owners.computeIfAbsent(collection, name -> new ArrayList<>()).add(backend.id());
                    }
                } catch (RuntimeException e) {
                    log.warn("[知识库] 枚举后端 {} 的索引失败（按空处理）: {}", backend.id(), e.getMessage());
                }
                logSlow("枚举后端 " + backend.id() + " 的索引清单", start);
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
     * 建/确认这个知识体的索引（<b>带体</b>）：落在它自己的两路连接上，而不是平台默认那台。
     *
     * <p>上传走这条。用平台默认会在"这个体只配了 Qdrant"时把索引建到 ES 上，
     * 紧接着的写入又写到 Qdrant——那边没有集合，写入报错；或者更糟，集合自动建出来
     * 但维度与 mapping 不对，检索时静默召回不到。</p>
     */
    public void ensureBodyIndex(String bodyId, String indexName) {
        try {
            writeStore(bodyId).ensureCollection(indexName,
                    modelHolder.effectiveEmbeddingDimension());
        } catch (StoreAccessException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "确保知识体 " + bodyId + " 的索引[" + indexName + "]存在失败："
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
     * 删除一个知识体的索引（唯一会毁数据的入口，管理员显式触发、需输名确认）。
     *
     * <p><b>删域不走这里</b>：删域只解绑，体的索引与原文都留着（见 {@code DomainPurgeService}）。
     * 这条路径只服务于"我真的不要这批知识了"或"换落点前先腾空"。</p>
     *
     * <p>fail-closed：集合删不掉（存储不可达 / 权限）立即抛，绝不"跳过继续"。留一个删不掉的
     * 集合，界面显示已删除、数据还在，是最难排查的一种不一致。</p>
     *
     * <p>执行顺序硬要求：先清旁挂产物（切片预览 txt、原文档案），再删集合。反过来的话集合一删，
     * docId 清单就没了着落，档案会留下带着完整知识内容的孤儿（以后重灌还会把已删文档复活）。</p>
     *
     * @param bodyId  知识体 id
     * @param keepRaw {@code true} = 只删索引、保留原文档案与预览 txt（以后可从原文重建）；
     *                {@code false} = 连原文与预览一起删
     * @return 删除结果（被删的索引名、是否真的删掉了集合、清理了多少文档的旁挂产物）
     * @throws KnowledgeBaseException 知识体不存在 / 存储不可达 / 集合删除失败
     */
    public BodyDeleteResult deleteBody(String bodyId, boolean keepRaw) {
        String index = indexOf(bodyId);
        int docs = 0;
        try {
            docs = mergeDocuments(index, EXPORT_CLEANUP_MAX_SIZE).size();
            if (keepRaw) {
                log.info("[知识库] 删知识体 {} 时保留原文档案（{} 个文档），只删索引 {}", bodyId, docs, index);
            } else {
                // 原文档案是"权威源"，删它要留一条可追溯的日志：这是本类唯一销毁原文的地方
                deleteExportFiles(index);
                log.info("[知识库] 删知识体 {} 时一并删除原文档案与切片预览（{} 个文档）", bodyId, docs);
            }
            boolean deleted = false;
            for (KnowledgeStore store : stores()) {
                deleted |= store.deleteCollection(index);
            }
            if (!deleted) {
                log.info("[知识库] 知识体 {} 的索引 {} 在任何后端上都不存在（可能从未上传过文档）",
                        bodyId, index);
            }
            return new BodyDeleteResult(bodyId, index, deleted, keepRaw, docs);
        } catch (StoreAccessException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "知识体 " + bodyId + " 的索引[" + index + "]未能删除，已中止（知识体仍在）："
                            + e.getMessage() + "。若存储只是临时不可达，恢复后重试即可；"
                            + "若确实删不掉，需先在存储侧处理该索引再重试", e);
        }
    }

    /**
     * 删知识体的结果。
     *
     * @param index         该体冻结的索引名
     * @param deleted       是否真的删掉了集合（false = 本来就没有）
     * @param rawKept       是否保留了原文档案
     * @param documents     涉及的文档数（删档前从索引读到的）
     */
    public record BodyDeleteResult(String bodyId, String index, boolean deleted,
                                   boolean rawKept, int documents) {
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
     * 按知识体重建索引：<b>默认从原文重建</b>，不动原文、不需要重新上传。
     *
     * <p>这是知识体卡片上的「重建索引」唯一实现（关键字 / 向量 / 两者）。语义分两种：</p>
     * <ul>
     *   <li>{@code fromArchive=true}（默认）：对账 → 从 {@link ChunkArchive} 捞出该体的全部切片 →
     *       删集合 → 按当前维度重建 → 用当前向量模型重灌。换向量模型导致的维度不符靠它修，
     *       用户不需要重传任何文档；</li>
     *   <li>{@code fromArchive=false}：只删了重建（空集合），数据需重新上传。仅在原文也丢了、
     *       或用户明确要"清空这个体"时选它——它不是"修复"手段，是"丢弃"手段。</li>
     * </ul>
     *
     * <p>只动这一个体的索引：同一个后台上可能有几十个体，全量重建的代价是分钟级停机。</p>
     *
     * @param bodyId      知识体 id
     * @param dimensions  重建用的向量维度（来自当前向量模型）
     * @param fromArchive 是否从原文重灌
     * @return 重建结果（索引名、文档数、写回切片数、是否从原文）
     */
    public BodyRebuildResult rebuildBody(String bodyId, int dimensions, boolean fromArchive) {
        String index = indexOf(bodyId);
        // 先确认存储可达：重建是破坏性操作，黑洞地址上"删了建不出来"等于把索引弄丢
        assertStoreReachable();
        KnowledgeStore writer = writeStore(bodyId);
        List<KnowledgeStore> holders = holdersOf(index, writer);
        int documents = mergeDocuments(index, LIST_MAX_SIZE).size();
        int[] written = {0};
        if (fromArchive) {
            // 捞完再删：顺序与全量重灌一致，中途失败不留"部分新维度"的混合索引
            List<KbChunk> chunks = VectorReindexer.loadCollection(
                    chunkArchive, archiveReconciler, writer, index);
            log.info("[知识库] 重建知识体 {}（索引 {}）：从原文取回 {} 条切片、{} 个文档，开始删旧重建",
                    bodyId, index, chunks.size(), documents);
            recreate(holders, index, dimensions);
            written[0] = VectorReindexer.reindexCollection(writer, embeddingModel, index, chunks);
        } else {
            log.warn("[知识库] 重建知识体 {}（索引 {}）：用户选择\"只删了重建\"，索引将被清空，"
                    + "原有 {} 个文档需重新上传", bodyId, index, documents);
            recreate(holders, index, dimensions);
        }
        log.info("[知识库] 知识体 {} 重建完成：索引 {}，写回 {} 条切片，documents={}",
                bodyId, index, written[0], documents);
        return new BodyRebuildResult(bodyId, index, documents, written[0], fromArchive);
    }

    /** 重建结果 */
    public record BodyRebuildResult(String bodyId, String index, int documents,
                                    int chunksWritten, boolean fromArchive) {
    }

    // ==================== 换落点（P3） ====================

    /** 不分批 embed 时一次 write 的条数（ES bulk / Qdrant upsert 都远吃得下这个量级） */
    private static final int PLAIN_WRITE_BATCH = 200;

    /**
     * 换落点预演：这份数据迁到 {@code target} 会发生什么。<b>只读</b>。
     *
     * <p>刻意不逐片读档案：几万片的读取要几十秒，而用户此刻只需要判断三件事——
     * 有多少文档、目标能不能写、缺不缺档。切片数在页面统计里已经有。</p>
     *
     * @param bodyId             知识体 id
     * @param kind               {@code keyword} / {@code vector}
     * @param targetConnectionId 这一路要换到的连接
     * @return 预演结果；{@code ready=false} 时 {@code reason} 说明为什么不能迁
     */
    public BodyRoutePlan planRoute(String bodyId, String kind, String targetConnectionId) {
        String index = indexOf(bodyId);
        String routeKind = requireRouteKind(kind);
        InfraSettings.KnowledgeBody body = bodyResolver.body(bodyId);
        String current = connectionOf(body, routeKind);
        String target = trimToNull(targetConnectionId);
        int documents = mergeDocuments(index, LIST_MAX_SIZE).size();
        int archived = chunkArchive.docIdsOf(index).size();
        Integer modelDims = modelHolder.isEmbeddingConfigured()
                ? modelHolder.effectiveEmbeddingDimension() : null;

        if (target == null) {
            return BodyRoutePlan.blocked(bodyId, index, routeKind, current, null,
                    documents, archived, modelDims, "没选目标连接");
        }
        if (target.equals(current)) {
            return BodyRoutePlan.blocked(bodyId, index, routeKind, current, target, documents, archived,
                    modelDims, "这一路已经在连接「" + target + "」上，没有要迁的东西");
        }
        KnowledgeStore targetStore;
        try {
            targetStore = retrievalPlan.storeOf(target);
        } catch (RuntimeException e) {
            return BodyRoutePlan.blocked(bodyId, index, routeKind, current, target, documents, archived,
                    modelDims, "目标连接不可用：" + e.getMessage());
        }
        if (!targetStore.ping()) {
            return BodyRoutePlan.blocked(bodyId, index, routeKind, current, target, documents, archived,
                    modelDims, "目标连接不可达：先在「存储配置」页点「测试连接」");
        }
        boolean withVectors = needsVectors(body, routeKind, target);
        if (withVectors && modelDims == null) {
            return BodyRoutePlan.blocked(bodyId, index, routeKind, current, target, documents, archived,
                    null, "这一路迁过去要重算向量，但没配向量模型：先在「模型设置」启用 Embedding 模型");
        }
        return new BodyRoutePlan(bodyId, index, routeKind, current, target, true, null,
                documents, archived, Math.max(0, documents - archived), withVectors,
                modelDims, dimsOf(targetStore, index), null);
    }

    /**
     * 把体的某一路迁到另一台连接：<b>先在新连接上写满，再切指针</b>。
     *
     * <p>顺序是这条链路的关键。新集合写完之前，体的指针仍指旧连接，检索照常可用——
     * 索引名固定，所以新旧两台可以短暂并存同名集合，不需要计划稿里写的"期间检索不可用"。
     * 写完校验条数 → 调用方落盘切指针 → 旧集合留作遗留。</p>
     *
     * <p><b>旧连接上的同名集合不删</b>：那是运维动作（旧机器上可能还有别的东西在用它），
     * 而且删错了就没有回退依据。结果里的 {@code legacyConnectionId} 让页面提示"旧连接上
     * 还留着一份"。</p>
     *
     * @param bodyId             知识体 id
     * @param kind               {@code keyword} / {@code vector}
     * @param targetConnectionId 目标连接 id
     * @param dimensions         目标集合的向量维度（由调用方按"三处同源"取值）
     * @return 迁移结果
     * @throws NotConfiguredException 需要重算向量但向量模型未配置
     */
    public BodyRouteMigration migrateRoute(String bodyId, String kind, String targetConnectionId,
                                           int dimensions) {
        String index = indexOf(bodyId);
        String routeKind = requireRouteKind(kind);
        InfraSettings.KnowledgeBody body = bodyResolver.body(bodyId);
        String current = connectionOf(body, routeKind);
        String target = trimToNull(targetConnectionId);
        if (target == null) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER, "没选目标连接");
        }
        if (target.equals(current)) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "这一路已经在连接「" + target + "」上，不需要迁移");
        }
        boolean withVectors = needsVectors(body, routeKind, target);
        if (withVectors && !modelHolder.isEmbeddingConfigured()) {
            throw new NotConfiguredException("迁这一路要重算向量，但没配向量模型；"
                    + "请先在「模型设置」页启用 Embedding 模型");
        }
        try {
            // 与上传 / 重灌共用串行锁：迁移在写目标连接，不能和导入并发把同一批切片写两遍
            if (!ingestPermit.tryAcquire(ragProperties.getIngestLockWaitSeconds(), TimeUnit.SECONDS)) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                        "知识库正忙（导入或重灌进行中）：等待超过 "
                                + ragProperties.getIngestLockWaitSeconds() + " 秒仍未拿到导入锁");
            }
            try {
                KnowledgeStore writer = writeStore(bodyId);
                List<KbChunk> chunks = VectorReindexer.loadCollection(
                        chunkArchive, archiveReconciler, writer, index);
                if (chunks.isEmpty()) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                            "知识体 " + bodyId + " 没有可用原文：换落点靠原文重建，"
                                    + "没档案就只能重新上传文档（原落点未改动）");
                }
                KnowledgeStore targetStore = retrievalPlan.storeOf(target);
                if (!targetStore.ping()) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                            "目标连接「" + target + "」不可达，已中止（原落点未改动）");
                }
                /* 目标上可能留着上一次迁移中断的半成品：同名集合必须删了重建，
                   否则 ensureCollection 见"已存在"直接跳过，接着 write 会往旧 mapping 里灌数据 */
                if (exists(targetStore, index)) {
                    targetStore.deleteCollection(index);
                    log.warn("[知识库] 目标连接 {} 上已有同名集合 {}（多半是上次迁移中断的残留），已删除重建",
                            target, index);
                }
                targetStore.ensureCollection(index, dimensions);
                int written = withVectors
                        ? VectorReindexer.reindexCollection(targetStore, embeddingModel, index, chunks)
                        : writeChunks(targetStore, index, chunks);
                if (written != chunks.size()) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                            "迁到 " + target + " 时写入不完整：原文 " + chunks.size()
                                    + " 条、写入 " + written + " 条，已中止（原落点未改动）");
                }
                log.info("[知识库] 知识体 {} 的 {} 路已从 {} 迁到 {}：{} 条切片、{} 个文档{}",
                        bodyId, routeKind, current, target, written, documentCount(chunks),
                        withVectors ? "（已重算向量）" : "（沿用原文，未重算向量）");
                return new BodyRouteMigration(bodyId, index, routeKind, current, target,
                        written, documentCount(chunks), withVectors, current);
            } finally {
                ingestPermit.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR, "迁移落点被中断", e);
        }
    }

    /**
     * 换原文落点的预演：只数对象，不动任何东西。
     *
     * <p>报"有多少个对象"而不是字节数：{@code listKeys} 拿不到大小，为了一个预演数字
     * 去 HEAD 每个对象，代价远大于收益（页面上的容量统计在别处）。</p>
     */
    public BodyRawPlan planRaw(String bodyId, String targetLocation) {
        String index = indexOf(bodyId);
        InfraSettings.KnowledgeBody body = bodyResolver.body(bodyId);
        String current = body == null ? InfraSettings.RAW_LOCAL : body.rawLocation();
        String target = trimToNull(targetLocation);
        if (target == null) {
            return new BodyRawPlan(bodyId, index, current, null, 0, 0, false, "没选目标落点");
        }
        if (target.equals(current)) {
            return new BodyRawPlan(bodyId, index, current, target, 0, 0, false,
                    "原文已经落在这个位置，没有要搬的东西");
        }
        try {
            com.zzkingcc.domainplane.runtime.storage.BlobStore from = chunkArchive.storeAt(current);
            int chunks = from.listKeys(ChunkArchive.PREFIX + index + "/").size();
            int files = from.listKeys(ChunkArchive.FILES_PREFIX + index + "/").size();
            chunkArchive.storeAt(target);   // 目标不可用会在这里抛
            return new BodyRawPlan(bodyId, index, current, target, chunks, files, true, null);
        } catch (RuntimeException e) {
            return new BodyRawPlan(bodyId, index, current, target, 0, 0, false,
                    "落点不可用：" + e.getMessage());
        }
    }

    /**
     * 换原文落点：<b>先搬后切</b>。
     *
     * <p>顺序硬要求：把该体的全部对象（切片档案 + 原始文件）复制到新落点，
     * <b>逐个校验、数量对上</b>之后才由调用方切 {@code raw} 字段。反过来先切指针，
     * 等于让"读原文"立刻指向一个空目录，而旧落点的数据既查不到也不会被清理。</p>
     *
     * <p>旧落点上的副本<b>不删</b>：与索引换落点同一条纪律（删是运维动作，
     * 且删错了就没有回退依据）。原文键带索引前缀，不可能属于别的体，将来清理是安全的。</p>
     *
     * @return 搬迁结果
     */
    public BodyRawMigration migrateRaw(String bodyId, String targetLocation) {
        String index = indexOf(bodyId);
        InfraSettings.KnowledgeBody body = bodyResolver.body(bodyId);
        if (body == null) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        String current = body.rawLocation();
        String target = trimToNull(targetLocation);
        if (target == null) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER, "没选目标落点");
        }
        if (target.equals(current)) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "原文已经落在这个位置（" + current + "），不需要迁移");
        }
        com.zzkingcc.domainplane.runtime.storage.BlobStore from;
        com.zzkingcc.domainplane.runtime.storage.BlobStore to;
        try {
            from = chunkArchive.storeAt(current);
            to = chunkArchive.storeAt(target);
        } catch (RuntimeException e) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "原文落点不可用：" + e.getMessage());
        }
        List<String> keys = new ArrayList<>();
        keys.addAll(from.listKeys(ChunkArchive.PREFIX + index + "/"));
        keys.addAll(from.listKeys(ChunkArchive.FILES_PREFIX + index + "/"));

        long bytes = 0;
        int copied = 0;
        try {
            for (String key : keys) {
                java.util.Optional<java.io.InputStream> in = from.open(key);
                if (in.isEmpty()) {
                    continue;
                }
                byte[] data;
                try (java.io.InputStream stream = in.get()) {
                    data = stream.readAllBytes();
                }
                to.put(key, new java.io.ByteArrayInputStream(data), data.length);
                bytes += data.length;
                copied++;
            }
        } catch (java.io.IOException | RuntimeException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "搬原文到 " + target + " 失败（已搬 " + copied + "/" + keys.size()
                            + " 个对象），已中止（原落点未改动）：" + e.getMessage(), e);
        }

        /* 校验"目标上真有这些对象"再让调用方切指针。
           对象数对不上说明中途有对象没写成功（S3 的最终一致性 / 权限策略都可能造成），
           此时切指针会得到"档案缺一部分"的体——那比重来一次糟得多。 */
        int atTarget = to.listKeys(ChunkArchive.PREFIX + index + "/").size()
                + to.listKeys(ChunkArchive.FILES_PREFIX + index + "/").size();
        if (atTarget < copied) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "搬完校验不一致：源 " + keys.size() + " 个对象、复制 " + copied
                            + " 个、目标上只有 " + atTarget + " 个，已中止（原落点未改动）");
        }
        log.info("[原文档案] 知识体 {} 的原文已从 {} 搬到 {}：{} 个对象、{} 字节（旧落点副本保留未删）",
                bodyId, current, target, copied, bytes);
        return new BodyRawMigration(bodyId, index, current, target, copied, bytes);
    }

    /**
     * 换原文落点的预演结果。
     *
     * @param chunksObjects 切片档案对象数
     * @param fileObjects   原始文件对象数
     */
    public record BodyRawPlan(String bodyId, String index, String from, String to,
                              int chunksObjects, int fileObjects, boolean ready, String reason) {
    }

    /** 换原文落点的执行结果 */
    public record BodyRawMigration(String bodyId, String index, String from, String to,
                                   int objects, long bytes) {
    }

    /**
     * 换落点的预演结果。
     *
     * @param ready             能不能迁；false 时看 {@code reason}
     * @param missingArchive    索引里有、原文档案里没有的文档数——这些迁不过去
     * @param recomputeVectors  迁过去要不要重算向量（keyword 路通常不用）
     * @param targetDimension   目标上已存在的同名集合的维度（没有该集合时为 null）
     */
    public record BodyRoutePlan(String bodyId, String index, String kind, String from, String to,
                                boolean ready, String reason, int documents, int archivedDocuments,
                                int missingArchive, boolean recomputeVectors,
                                Integer modelDimension, Integer targetDimension, String note) {

        static BodyRoutePlan blocked(String bodyId, String index, String kind, String from, String to,
                                     int documents, int archived, Integer modelDimension, String reason) {
            return new BodyRoutePlan(bodyId, index, kind, from, to, false, reason,
                    documents, archived, Math.max(0, documents - archived),
                    false, modelDimension, null, null);
        }
    }

    /**
     * 换落点的执行结果。
     *
     * @param legacyConnectionId 旧连接 id：它上面还留着同名的集合，需要时由运维侧手工清理
     */
    public record BodyRouteMigration(String bodyId, String index, String kind, String from, String to,
                                     int chunksWritten, int documents, boolean vectorsRecomputed,
                                     String legacyConnectionId) {
    }

    /**
     * 迁这一路要不要重算向量。
     *
     * <p>向量路必算（档案里没有向量）。keyword 路通常不用——但有一种例外：目标连接与
     * <b>另一路</b>是同一份物理数据（同一台 ES 上的同一个索引），此时两条路共用一个集合，
     * 写成无向量会把另一路已有的向量抹掉。判据用 {@code location()}，不看连接 id。</p>
     */
    private boolean needsVectors(InfraSettings.KnowledgeBody body, String routeKind, String targetId) {
        if (InfraSettings.KIND_VECTOR.equals(routeKind)) {
            return true;
        }
        String other = InfraSettings.KIND_KEYWORD.equals(routeKind) ? body.vectorId() : body.keywordId();
        if (other == null) {
            return false;
        }
        String left = locationOf(targetId);
        String right = locationOf(other);
        return left != null && left.equals(right);
    }

    private String locationOf(String connectionId) {
        for (KnowledgeBackend backend : retrievalPlan.backends()) {
            if (backend.id().equals(connectionId)) {
                return backend.location();
            }
        }
        return null;
    }

    /** 不带向量的写入（keyword 路迁移用）：分批 write，失败即抛，绝不留半份 */
    private static int writeChunks(KnowledgeStore store, String collection, List<KbChunk> chunks) {
        int written = 0;
        for (int start = 0; start < chunks.size(); start += PLAIN_WRITE_BATCH) {
            int end = Math.min(start + PLAIN_WRITE_BATCH, chunks.size());
            List<KbChunk> batch = chunks.subList(start, end);
            try {
                store.write(collection, batch);
                MetricsRegistry.kbStoreWrite();
            } catch (StoreAccessException e) {
                MetricsRegistry.kbStoreWriteFailure();
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                        "写入 collection[" + collection + "]失败（已写 " + written + "/"
                                + chunks.size() + " 条）：" + e.getMessage(), e);
            }
            written += batch.size();
        }
        return written;
    }

    private static int documentCount(List<KbChunk> chunks) {
        Set<String> docIds = new LinkedHashSet<>();
        for (KbChunk chunk : chunks) {
            String docId = chunk.metadata() == null ? null
                    : chunk.metadata().get(ChunkMetadataKeys.DOC_ID);
            if (docId != null) {
                docIds.add(docId);
            }
        }
        return docIds.size();
    }

    private static String requireRouteKind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!InfraSettings.KIND_KEYWORD.equals(k) && !InfraSettings.KIND_VECTOR.equals(k)) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "落点类型只能是 keyword 或 vector（原文落点要等对象存储接入后才有可选项）");
        }
        return k;
    }

    private static String connectionOf(InfraSettings.KnowledgeBody body, String routeKind) {
        if (body == null) {
            return null;
        }
        return InfraSettings.KIND_KEYWORD.equals(routeKind) ? body.keywordId() : body.vectorId();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 目标上有没有同名集合；查询失败按"有"处理（随后会走删除，失败会如实抛） */
    private static boolean exists(KnowledgeStore store, String collection) {
        try {
            return store.exists(collection);
        } catch (StoreAccessException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "查询目标上的集合[" + collection + "]是否存在失败：" + e.getMessage(), e);
        }
    }

    private static Integer dimsOf(KnowledgeStore store, String collection) {
        try {
            return store.currentVectorDims(collection);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 该索引真实落在哪几台后端上（历史遗留可能多台同名）；一台都没有时退回给定的 writer */
    private List<KnowledgeStore> holdersOf(String index, KnowledgeStore fallback) {
        List<KnowledgeStore> holders = new ArrayList<>();
        for (KnowledgeStore store : stores()) {
            try {
                if (store.listCollections().contains(index)) {
                    holders.add(store);
                }
            } catch (RuntimeException e) {
                log.debug("[知识库] 枚举后端索引失败（按无此索引处理）: {}", e.getMessage());
            }
        }
        return holders.isEmpty() ? List.of(fallback) : holders;
    }

    /**
     * 删了重建这一组后端上的同名集合。
     *
     * <p>删不掉必须停：继续 ensureCollection 会撞上"索引已存在且维度一致 → 跳过创建"，
     * 用户点了重建却什么都没重建，界面显示成功、维度仍是旧的。</p>
     */
    private void recreate(List<KnowledgeStore> holders, String index, int dimensions) {
        for (KnowledgeStore store : holders) {
            store.diagnose(index);
            try {
                store.deleteCollection(index);
            } catch (StoreAccessException e) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                        "重建时删除索引[" + index + "]失败，已中止：" + e.getMessage(), e);
            }
            store.ensureCollection(index, dimensions);
        }
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
     * 写入 / 删除用的 store（<b>带体</b>）：写这个知识体自己的两路落点。
     *
     * <p>一个体正常只落一台机器；关键字在 ES、向量在 Qdrant 时
     * {@link RetrievalPlan#writeStoreOfBody(String)} 会扇出成两份，两边都拿到这份切片——
     * 否则另一条召回路静默查不到。</p>
     *
     * <p>体不存在 / 两路都没配时<b>直接抛</b>（不回落平台默认）：回落会把 A 体的知识写进
     * "第一个体那台机器"，看起来成功、实际检索不到，而且没有任何报错。</p>
     */
    private KnowledgeStore writeStore(String bodyId) {
        return retrievalPlan.writeStoreOfBody(bodyId);
    }

    /** 读路径用的 store（带体）：该体自己那份存储；体没配时抛（与写入侧同口径） */
    private KnowledgeStore readStore(String bodyId) {
        return retrievalPlan.readStoreOfBody(bodyId);
    }

    /** 平台默认写入 store：没有域上下文的对账 / 诊断 / 内部维护用 */
    private KnowledgeStore writeStore() {
        return retrievalPlan.writeStore();
    }

    /** 平台默认读 store：keyword 后端（两路不同实例时的写入行为权威） */
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
     * 同步上传一个文档到指定<b>知识体</b>。
     *
     * <p>谁能不能查到这份文档，不再由"归属域"决定，而由"哪些域绑了这个体"决定：
     * 域 D 绑了体 B，D 及其祖先链上的域查 B 的索引；B 被多个域绑 = 那些域共用同一批文档。
     * 这正是 v2 与 v1 最大的行为差别（v1 里一个域一份索引，共用体并不共用文档）。</p>
     *
     * <p>上传<b>不校验域</b>（体不需要域就能存在）：体是数据，域是消费者，互不依赖。</p>
     *
     * @param content  文件字节
     * @param fileName 原始文件名（含扩展名）
     * @param replace  {@code true} = 该体索引内已存在同名文档时先删旧再写入；{@code false} = 直接拒绝
     * @param bodyId   落到哪个知识体（必须存在，且至少一路落点可用）
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace, String bodyId) {
        String name = requireSupported(fileName);
        if (content == null || content.length == 0) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文件内容为空");
        }
        long max = ragProperties.getMaxFileSize().toBytes();
        if (content.length > max) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "文件 " + name + " 超过大小上限：" + content.length + " 字节，上限 " + max + " 字节");
        }
        if (bodyId == null || bodyId.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "必须指定知识体（文档落在知识体里，域只是消费者）");
        }
        String body = bodyId.trim();
        InfraSettings.KnowledgeBody definition = bodyResolver.body(body);
        if (definition == null) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                    "知识体不存在：" + body + "（可能刚被删除，请刷新知识库页）");
        }

        // 向量模型必须先配好：上传链路无论走哪条策略都要 embedAll，模型没配时
        // 解析、切片全都白跑，最后在向量化一步炸成 60002——那是 5xx，detail 被隐藏，
        // 用户只看得到一句"文档没能导入"，把排查方向带到沟里。
        // 前置拒掉并单开 60009：对外文案直接点名缺的是向量模型，4xx 让 detail 可达。
        if (!modelHolder.isEmbeddingConfigured()) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED,
                    "向量模型未配置，文档无法向量化：请先到管控台「模型设置」页的「向量模型」段"
                            + "启用一个 Embedding 模型（服务商地址 / API Key / 模型名 / 维度），保存后立即生效");
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
        log.info("[知识库] 收到上传请求：文件={}，大小={} 字节，类型={}，编码={}，replace={}，知识体={}",
                name, content.length, route.strategyName(), encoding, replace, body);

        Metadata metadata = Metadata.from(ChunkMetadataKeys.FILE_NAME, name);
        metadata.put("file_name_lower", lower(name));
        // metadata.domain 记体 id：它是文档的真实归属，检索隔离仍由"查哪个索引"保证
        metadata.put(ChunkMetadataKeys.DOMAIN, body);
        IngestSource source = route.binary()
                ? IngestSource.ofBinary(content, name, metadata)
                : IngestSource.ofText(text, content, name, metadata, encoding);

        Future<UploadResult> future = submit(source, content.length, name, replace, body);
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
                                        String fileName, boolean replace, String bodyId) {
        try {
            return ingestExecutor.submit(() -> ingest(source, size, fileName, replace, bodyId));
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
            long start = System.nanoTime();
            all.addAll(listIndex(index));
            logSlow("读取索引[" + index + "]的文档条目", start);
        }
        return all;
    }

    /**
     * 单文档切片预览：管控台「看文档」的数据源。
     *
     * <p>只读展示路径，fail-open：读不到就给空列表并记日志——"看不到切片"不该让整页报错。
     * 优先读原文档案（{@link ChunkArchive}：写入时按"索引里真正入库的切片"归档，去重跳过
     * 哪片档案里就少哪片），缺档（早期文档 / 对账尚未补写）再回落到索引读。档案在本地
     * BlobStore 里，比走一趟远程存储快得多。</p>
     *
     * @param limit 最多回多少片（收敛到 1..{@link #PREVIEW_MAX_CHUNKS}）
     */
    public Preview previewChunks(String docId, String bodyId, int limit) {
        String index = indexOf(bodyId);
        int cap = Math.max(1, Math.min(limit, PREVIEW_MAX_CHUNKS));

        List<KbChunk> chunks = List.of();
        boolean fromArchive = false;
        if (docId != null && !docId.isBlank()) {
            try {
                java.util.Optional<List<KbChunk>> archived = chunkArchive.read(index, docId);
                if (archived.isPresent()) {
                    chunks = archived.get();
                    fromArchive = true;
                }
            } catch (RuntimeException e) {
                log.warn("[知识库] 读原文档案失败（docId={}，index={}），回落到索引读取: {}",
                        docId, index, e.getMessage());
            }
            if (!fromArchive) {
                try {
                    chunks = readStore(bodyId).readByDocId(index, docId);
                } catch (RuntimeException e) {
                    log.warn("[知识库] 读索引切片失败（docId={}，index={}），按空预览处理: {}",
                            docId, index, e.getMessage());
                    chunks = List.of();
                }
            }
        }

        List<KbChunk> ordered = new ArrayList<>(chunks);
        return previewOf(docId, index, ordered, fromArchive, cap);
    }

    /**
     * 把切片整理成预览：按 {@code chunk_seq} 排序、截到 cap、丢掉向量。
     *
     * <p>抽成纯函数是为了可测：排序口径（序号缺失按 0、稳定）与截断是「看文档」唯一的
     * 逻辑，不需要为了测它去搭一整套存储替身。</p>
     */
    static Preview previewOf(String docId, String index, List<KbChunk> chunks, boolean fromArchive, int cap) {
        List<KbChunk> ordered = new ArrayList<>(chunks);
        // 档案与索引都不保证顺序：按 chunk_seq 排，缺序号的按 0 稳定排在最前
        ordered.sort(java.util.Comparator.comparingInt(KnowledgeBaseService::seqOf));
        List<PreviewChunk> items = new ArrayList<>(Math.min(cap, ordered.size()));
        for (KbChunk chunk : ordered) {
            if (items.size() >= cap) {
                break;
            }
            String text = chunk.text() == null ? "" : chunk.text();
            items.add(new PreviewChunk(seqOf(chunk), text, text.length()));
        }
        return new Preview(docId, index, ordered.size(), fromArchive, items);
    }

    /** 切片序号：metadata 里是字符串，缺失/非数字按 0 */
    private static int seqOf(KbChunk chunk) {
        String raw = chunk.metadata() == null ? null : chunk.metadata().get(ChunkMetadataKeys.CHUNK_SEQ);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 读某文档的原始文件字节（「下载原文」）。
     *
     * <p>没有归档过原始文件时返回空——v2 之前上传的存量文档只有切片 JSON，
     * 而切片 JSON 还原不出原始字节（解析与切分都是有损的）。</p>
     *
     * @return 字节；未归档时为空
     */
    public java.util.Optional<byte[]> readRawFile(String bodyId, String docId) {
        String index = indexOf(bodyId);
        java.util.Optional<java.io.InputStream> in = chunkArchive.openFile(index, docId);
        if (in.isEmpty()) {
            return java.util.Optional.empty();
        }
        try (java.io.InputStream stream = in.get()) {
            return java.util.Optional.of(stream.readAllBytes());
        } catch (java.io.IOException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "读取原始文件失败（docId=" + docId + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 删除一个知识体里的某个文档（全部切片 + 原文档案 + 预览 txt），并释放其文件名。
     *
     * <p>与 v1 的 {@code delete(docId)} 不同：这里<b>必须带体 id</b>。docId 全局唯一，
     * 但"在哪个索引里删"决定了会不会误删别人的文档——扫描全部索引虽然能命中，
     * 却让一次点击变成 O(全部索引) 的远程枚举，且两个体里出现同 docId（迁移粘贴）时会删错。</p>
     *
     * @return 是否命中并删除
     */
    public boolean delete(String bodyId, String docId) {
        if (docId == null || docId.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER, "docId 不能为空");
        }
        String index = indexOf(bodyId);
        if (countByDocId(index, docId) <= 0) {
            return false;
        }
        deleteByDocId(index, docId);
        chunkArchive.delete(index, docId);
        chunkExporter.deleteByDocId(docId);
        log.info("[知识库] 已删除文档 docId={}（知识体 {}，索引 {}；原文档案与预览文件一并清除）",
                docId, bodyId, index);
        return true;
    }

    /**
     * 知识库状态：索引数、文档数、切片总数，以及每个索引的明细（体 → 索引 → 文档/切片）。
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
            row.put("body", bodyResolver.bodyIdOfIndex(index));
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
                                boolean replace, String bodyId) throws InterruptedException {
        // 串行锁在任务内部获取：排队等待不占用额外池线程，池大小可保持很小
        if (!ingestPermit.tryAcquire(ragProperties.getIngestLockWaitSeconds(), TimeUnit.SECONDS)) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "知识库导入繁忙：等待超过 " + ragProperties.getIngestLockWaitSeconds()
                            + " 秒仍未拿到导入锁，请稍后重试");
        }
        String docId = null;
        String index = indexOf(bodyId);
        try {
            ensureBodyIndex(bodyId, index);
            String lowerName = lower(fileName);
            List<DocumentItem> existing = listIndex(index).stream()
                    .filter(d -> lowerName.equals(d.fileNameLower()))
                    .toList();
            if (!existing.isEmpty() && !replace) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_DOCUMENT_DUPLICATE,
                        "知识体 " + bodyId + " 下已存在同名文档：" + fileName
                                + "（同名判定不区分大小写）。"
                                + "如需替换请带 replace=true，或先删除原文档");
            }
            /* 同名旧文档的删除刻意推迟到新文档确认写入成功之后（见下方）：先删后写时，
               新文档任一环节失败都会让这次"覆盖更新"变成"只删不写"，旧文档永久丢失。 */

            docId = UUID.randomUUID().toString();
            source.metadata().put(ChunkMetadataKeys.DOC_ID, docId);
            source.metadata().put("upload_time", Instant.now().toString());
            /* 写入目标 = 这个知识体自己的两路落点（扇出到全部写入后端：keyword 与 vector
               两份都要拿到这份切片，否则对应的那条召回路静默查不到）。去重问登记权威，
               扇出 store 内部就是这么委派的——ingest 侧只认 SPI，不认识任何后端类型。

               注意这里必须是<b>带体</b>的那份：用平台默认会把 A 体的知识写进 B 体那台机器，
               上传成功、检索为空，且没有任何报错。 */
            KnowledgeStore target = writeStore(bodyId);
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
            chunkArchive.write(index, docId, readStore(bodyId).readByDocId(index, docId));

            /* 原始文件字节：切片 JSON 是"切完之后"的粒度，改解析器或改切片策略时
               只有原始字节能真正重切，所以默认一并归档（体上 archiveRaw=false 可关，
               代价是占用接近翻倍）。写失败只告警——上传已经成功，缺原始文件不影响检索。 */
            InfraSettings.KnowledgeBody archiveBody = bodyResolver.body(bodyId);
            if (archiveBody == null || archiveBody.archiveRawFiles()) {
                chunkArchive.writeFile(index, docId, source.content());
            }

            /* 新文档已确认落库，此刻才清理同名旧文档。顺序不能换：反过来的话，新文档任一
               环节失败都会让这次"覆盖更新"变成"只删不写"，旧文档永久消失。

               清理失败单独吞掉（只记日志），绝不能让它冒泡到下面的 catch，那里会
               rollback 掉新 docId，而此时旧文档可能已经删掉了一部分，结果是新旧两份都没了。
               吞掉的代价只是同名文档暂时并存，用户再删一次即可。 */
            for (DocumentItem old : existing) {
                try {
                    deleteByDocId(index, old.docId());
                    chunkArchive.delete(index, old.docId());
                    chunkExporter.deleteByDocId(old.docId());
                } catch (Exception e) {
                    log.error("[知识库] 覆盖更新时清除旧文档失败（旧 docId={}），新旧两份均保留: {}",
                            old.docId(), e.getMessage());
                }
            }
            if (!existing.isEmpty()) {
                log.info("[知识库] 覆盖更新：已清除知识体 {} 下同名旧文档 {} 个", bodyId, existing.size());
            }

            // 切片预览：读回索引里真正入库的切片写成 txt，管控台只展示这个路径。
            // 导出失败不算上传失败，它只是给人核对用的辅助产物。
            java.nio.file.Path exported = chunkExporter.export(index, docId, fileName, bodyId);
            String exportPath = exported == null ? null : exported.toAbsolutePath().toString();

            log.info("[知识库] 上传完成：文件={}，知识体={}，索引={}，docId={}，切片={}，标题={}，删噪={} 行，编码={}",
                    fileName, bodyId, index, docId, chunks, report.sections(), report.droppedLines(),
                    source.encoding());
            return new UploadResult(docId, fileName, size, chunks, bodyId,
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
                    doc.chunks(), doc.body(), exportPathOf(fileName, doc.docId())));
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
            long start = System.nanoTime();
            List<DocumentAgg> found = store.documents(index, limit);
            logSlow("读取索引[" + index + "]的文档列表（后端 " + store.getClass().getSimpleName() + "）", start);
            for (DocumentAgg doc : found) {
                if (doc.docId() != null) {
                    byDocId.putIfAbsent(doc.docId(), doc);
                }
            }
        }
        return List.copyOf(byDocId.values());
    }

    /**
     * 慢操作日志：只在超过阈值时打，避免把 INFO 刷满。
     *
     * <p>管控台"知识库页加载慢"的排查第一步就是看这几条——它直接给出
     * "哪台后端、哪个索引、花了多久"，而不是让人对着整页猜。</p>
     */
    private static void logSlow(String what, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        if (ms >= SLOW_OP_MS) {
            log.warn("[知识库] 慢操作：{} 耗时 {} ms（管控台加载慢先看这条）", what, ms);
        }
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
