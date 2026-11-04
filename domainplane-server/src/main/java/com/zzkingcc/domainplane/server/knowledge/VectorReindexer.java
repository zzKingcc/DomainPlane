package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 换向量模型 / 换后端时的重灌：原文不进索引，索引从原文重建（设计第 11 节）。
 *
 * 与旧实现的根本差别是原文来源：旧实现从**索引**捞回原文（{@code preserveAll}），
 * 于是"保原文重灌"是 ES 的隐性福利——纯向量库存不下原文，这条路直接断；
 * 新实现从 {@link ChunkArchive}（BlobStore 承载的权威源）读，索引降格为
 * 可重建的派生数据，"换后端"从数据迁移变成一次重灌。
 *
 * 三阶段，失败语义照搬 fail-closed：
 *   1. **全捞**：先对每个 collection 跑一轮双向对账（补缺档、删孤儿——
 *      孤儿不清掉的话，已删除的文档会被原样重灌复活），再从 archive 读全部原文。
 *      archive 全失时兜底从 {@code store.scan()} 读（scan 的存在理由之二）。
 *      读不全立即抛、不往下走。
 *   2. **重建**：逐个 {@code deleteCollection → ensureCollection(newDims)}。
 *   3. **重灌**：25 条/批 embed 后 {@code write}；批内向量条数不符直接抛
 *      （不能留下"有些切片有向量、有些没有"的半成品）。
 *
 * 先全部捞完再开始删：一个 collection 一个地"捞→删→重灌"，中途失败会留下
 * 部分已换新向量、部分还是老向量的混合态，而维度相同的两个模型混在一个索引里，
 * 检索不报错、只会静默失真。
 *
 * @author zzkingcc
 */
@Slf4j
public class VectorReindexer {

    /** 向量化单批行数上限（百炼 text-embedding-v2 限制 25 行；与摄取链路同值不同源，两边各自钉死） */
    private static final int EMBEDDING_BATCH = 25;

    private VectorReindexer() {
    }

    /**
     * 重建结果。
     *
     * @param preserved 各 collection 捞回来的条数
     * @param reindexed 各 collection 实际写回的条数
     * @implNote 两者必须逐项相等；不等就意味着丢切片（捞取被截断、或写回中途失败），
     * 而向量缺失的切片在向量检索里等于凭空消失：所以刻意把它作为可核对的返回值暴露出去。
     */
    public record ReindexResult(Map<String, Integer> preserved, Map<String, Integer> reindexed) {
    }

    /**
     * 面向 SPI 的三阶段重灌（同后端：换向量模型）。
     *
     * @param archive         原文权威源
     * @param reconciler      对账（重灌前的清理复用同一套双向逻辑）
     * @param store           目标后端（P0 唯一实现是 ES 适配器）
     * @param embeddingModel  新向量模型
     * @param newDims         新维度（与 embeddingModel 输出一致，由调用方"三处同源"取值）
     */
    public static ReindexResult reindex(ChunkArchive archive,
                                        ChunkArchiveReconciler reconciler,
                                        KnowledgeStore store,
                                        EmbeddingModel embeddingModel,
                                        int newDims) {
        return reindex(archive, reconciler, store, store, embeddingModel, newDims);
    }

    /**
     * 面向 SPI 的三阶段重灌（跨后端：换后端）。
     *
     * <p>「换后端」与「换向量模型」的区别只在<b>目标后端</b>：前者从
     * {@code source} 读旧数据（或直接用 archive 的原文），写进 {@code target}；
     * 后者在同一个后端里删了重建。</p>
     *
     * <p>关键纪律：<b>先全部捞完再动任何一个后端</b>。跨后端重灌时若中途失败，
     * 源后端的数据必须完好——它是"再来一次"的依据，删了就没了。</p>
     *
     * @param archive         原文权威源（换后端时的首选数据来源：原文与后端无关）
     * @param reconciler      对账（重灌前的清理复用同一套双向逻辑）
     * @param source          源后端（读：scan 兜底取旧数据）
     * @param target          目标后端（写：删旧 → 重建 → 重灌）
     * @param embeddingModel  新向量模型
     * @param newDims         新维度
     */
    public static ReindexResult reindex(ChunkArchive archive,
                                        ChunkArchiveReconciler reconciler,
                                        KnowledgeStore source,
                                        KnowledgeStore target,
                                        EmbeddingModel embeddingModel,
                                        int newDims) {
        // collection 清单取源后端：目标后端可能是空的（刚建的新库），
        // 按它的列表重灌会什么都不做——而用户要的是"把源里的东西搬过去"
        List<String> collections = source.listCollections();
        if (collections.isEmpty()) {
            log.info("[向量重灌] 源后端当前没有任何 collection，无需重灌");
            return new ReindexResult(Map.of(), Map.of());
        }
        log.info("[向量重灌] 开始：{} 个 collection，新维度 {}，源后端={}，目标后端={}",
                collections.size(), newDims, source.getClass().getSimpleName(),
                target.getClass().getSimpleName());

        // ===== ① 全捞（对账 → 读 archive；archive 全失兜底 scan） =====
        Map<String, List<KbChunk>> all = new LinkedHashMap<>();
        for (String collection : collections) {
            List<KbChunk> chunks = loadCollection(archive, reconciler, source, collection);
            all.put(collection, chunks);
        }
        int totalChunks = all.values().stream().mapToInt(List::size).sum();
        log.info("[向量重灌] 已取回 {} 个 collection 共 {} 条切片原文，开始删旧重建", all.size(), totalChunks);

        // ===== ② 重建（只动目标后端） =====
        for (String collection : collections) {
            try {
                target.deleteCollection(collection);
                target.ensureCollection(collection, newDims);
            } catch (StoreAccessException e) {
                throw new IllegalStateException("重建 collection[" + collection + "]失败，已中止（其余 collection 未处理）："
                        + e.getMessage(), e);
            }
        }

        // ===== ③ 重灌 =====
        Map<String, Integer> reindexed = new LinkedHashMap<>();
        for (Map.Entry<String, List<KbChunk>> entry : all.entrySet()) {
            String collection = entry.getKey();
            List<KbChunk> chunks = entry.getValue();
            int written = reindexCollection(target, embeddingModel, collection, chunks);
            reindexed.put(collection, written);
        }

        Map<String, Integer> preservedCounts = new LinkedHashMap<>();
        all.forEach((collection, list) -> preservedCounts.put(collection, list.size()));

        for (Map.Entry<String, Integer> e : preservedCounts.entrySet()) {
            int written = reindexed.getOrDefault(e.getKey(), 0);
            if (written != e.getValue()) {
                throw new IllegalStateException("collection[" + e.getKey() + "]重灌不完整：捞回 "
                        + e.getValue() + " 条、只写回 " + written + " 条，其余切片将无法被向量检索命中");
            }
        }
        log.info("[向量重灌] 完成：{}", reindexed);
        return new ReindexResult(preservedCounts, reindexed);
    }

    /**
     * 单个 collection 的原文装载：对账 → archive 读；archive 全失兜底 scan。
     *
     * <p>公开给"按知识体重建"用：那条路径只处理一个体自己的索引，不能走
     * {@link #reindex(ChunkArchive, ChunkArchiveReconciler, KnowledgeStore, EmbeddingModel, int)}
     * ——它会遍历源后端的全部 collection。</p>
     *
     * <p>对账传 {@link Duration#ZERO} 关护栏：用户点了"重建"，意图明确，
     * 不该被"文件太新"挡下；孤儿更必须在重灌前清掉（否则复活已删文档）。</p>
     */
    public static List<KbChunk> loadCollection(ChunkArchive archive,
                                               ChunkArchiveReconciler reconciler,
                                               KnowledgeStore store,
                                               String collection) {
        reconciler.reconcileCollection(collection, Duration.ZERO);

        Set<String> docIds = new LinkedHashSet<>(archive.docIdsOf(collection));
        if (docIds.isEmpty()) {
            // archive 全失（或从来没写过）：兜底从索引 scan。
            // scan 出来的切片顺手补写 archive——补完之后未来重灌就有档了，
            // 不必每次换模型都扫一遍全库
            List<KbChunk> scanned = scanAll(store, collection);
            if (!scanned.isEmpty()) {
                backfillByDoc(archive, collection, scanned);
                log.info("[向量重灌] collection[{}] 无档案，已按索引 scan 兜底取回 {} 条并补写档案",
                        collection, scanned.size());
            }
            return scanned;
        }

        List<KbChunk> chunks = new ArrayList<>();
        for (String docId : docIds) {
            // read 失败（档案损坏 / 存储故障）立即抛：读不全就往下走，
            // 等于把"这篇文档"从重灌里静默丢掉
            chunks.addAll(archive.read(collection, docId)
                    .orElseThrow(() -> new IllegalStateException(
                            "原文档案缺失（collection=" + collection + "，docId=" + docId
                                    + "），对账应已补写；仍缺失说明读取失败，不能继续重灌")));
        }
        return chunks;
    }

    /** 从索引全量 scan 一个 collection 的切片（兜底路径） */
    private static List<KbChunk> scanAll(KnowledgeStore store, String collection) {
        List<KbChunk> out = new ArrayList<>();
        java.util.Iterator<List<KbChunk>> it = store.scan(collection, 500);
        while (it.hasNext()) {
            out.addAll(it.next());
        }
        return out;
    }

    /** 按 doc_id 分组补写 archive（兜底 scan 的产物） */
    private static void backfillByDoc(ChunkArchive archive, String collection, List<KbChunk> chunks) {
        Map<String, List<KbChunk>> byDoc = new LinkedHashMap<>();
        for (KbChunk chunk : chunks) {
            String docId = chunk.metadata().get(
                    com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys.DOC_ID);
            if (docId != null) {
                byDoc.computeIfAbsent(docId, k -> new ArrayList<>()).add(chunk);
            }
        }
        byDoc.forEach((docId, list) -> archive.write(collection, docId, list));
    }

    /** 单个 collection 的重灌：25 条/批 embed 后整批 write */
    public static int reindexCollection(KnowledgeStore store, EmbeddingModel embeddingModel,
                                         String collection, List<KbChunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        if (embeddingModel == null) {
            throw new IllegalStateException("向量模型不可用，无法重灌 collection[" + collection + "]");
        }
        int written = 0;
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH) {
            int end = Math.min(start + EMBEDDING_BATCH, chunks.size());
            List<KbChunk> batch = chunks.subList(start, end);

            List<TextSegment> segments = new ArrayList<>(batch.size());
            for (KbChunk chunk : batch) {
                segments.add(segmentOf(chunk));
            }
            Response<List<Embedding>> resp = embeddingModel.embedAll(segments);
            List<Embedding> embeddings = resp.content();
            if (embeddings == null || embeddings.size() != batch.size()) {
                // 不能只写一半：那样索引里会留下"有些切片有向量、有些没有"的半成品，
                // 而向量缺失的切片在向量检索里等于凭空消失
                throw new IllegalStateException("向量模型返回的条数与请求不符（请求 "
                        + batch.size() + " 条，返回 " + (embeddings == null ? 0 : embeddings.size()) + " 条）");
            }
            List<KbChunk> withVectors = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                KbChunk src = batch.get(i);
                withVectors.add(new KbChunk(src.id(), src.text(), src.metadata(),
                        embeddings.get(i).vector()));
            }
            try {
                store.write(collection, withVectors);
                MetricsRegistry.kbStoreWrite();
                MetricsRegistry.kbReindexChunks(withVectors.size());
            } catch (StoreAccessException e) {
                MetricsRegistry.kbStoreWriteFailure();
                throw new IllegalStateException("collection[" + collection + "]重灌写入失败（已写入 "
                        + written + "/" + chunks.size() + " 条）：" + e.getMessage(), e);
            }
            written += batch.size();
            log.info("[向量重灌] collection[{}] 进度: {}/{}", collection, written, chunks.size());
        }
        return written;
    }

    /**
     * {@link KbChunk} → {@link TextSegment}（embedAll 的入参）。
     *
     * metadata 值统一按字符串落：langchain4j 的 embedAll 只读 text，metadata 只是
     * 搭车；数值键的收窄在写回路由由适配器负责（与摄取链路同口径）。
     */
    private static TextSegment segmentOf(KbChunk chunk) {
        TextSegment segment = TextSegment.from(chunk.text());
        chunk.metadata().forEach(segment.metadata()::put);
        return segment;
    }
}
