package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.DedupKey;
import com.zzkingcc.domainplane.domain.rag.store.DedupLookup;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Qdrant 版 {@link KnowledgeStore}：写入与生命周期。
 *
 * <p>与 ES 适配器同契约、同失败姿态（见 {@code domain.rag.store} 包文档的
 * 异常姿态表），差别只在方言：</p>
 *
 * <table>
 *   <caption>两后端的能力差异</caption>
 *   <tr><th>能力</th><th>ES</th><th>Qdrant</th></tr>
 *   <tr><td>keyword</td><td>BM25（IK 分词）</td><td>payload 全文 match（BM25）</td></tr>
 *   <tr><td>vector</td><td>dense_vector + 脚本余弦</td><td>命名/无名向量 + distance</td></tr>
 *   <tr><td>wildcard</td><td>索引模式 {@code domainplane_kb_*}</td><td><b>无</b>（只能逐集合检索）</td></tr>
 *   <tr><td>storesText</td><td>是</td><td>是（payload 存正文）</td></tr>
 * </table>
 *
 * <p><b>wildcard=false 是刻意的能力声明</b>：Qdrant 没有索引模式，
 * 管控台的"未绑域全库预览"走不通配——由 {@code RetrievalPlan} 统一降级
 * （枚举集合逐个检索关键词路，向量路跳过并告警）。这不是缺陷，是两个后端的
 * 真实差异，capabilities 就是用来如实表达它的。</p>
 *
 * <p>同时实现 {@link DedupLookup}（ingest 侧的窄依赖），与 ES 适配器同构。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class QdrantKnowledgeStore implements KnowledgeStore, DedupLookup {

    /** 扫描与文档列表的单次拉取上限。 */
    private static final int SCROLL_PAGE = 256;

    /** 文档列表的拉取上限（与 ES 适配器的 LIST_MAX_SIZE 对齐）。 */
    private static final int LIST_MAX_SIZE = 1000;

    /** 去重查询的拉取上限。 */
    private static final int DEDUP_FETCH_CAP = 2000;

    /** 按 docId 读回切片的条数上限（与 ES 适配器的 READ_BY_DOC_MAX 对齐）。 */
    private static final int READ_BY_DOC_MAX = 2000;

    private final QdrantHttpClient client;

    /** 是否承载向量：false = 纯 keyword 后端（只写 payload、不接向量检索、不可作重灌目标） */
    private final boolean vectorEnabled;

    public QdrantKnowledgeStore(QdrantHttpClient client) {
        this(client, true);
    }

    public QdrantKnowledgeStore(QdrantHttpClient client, boolean vectorEnabled) {
        this.client = client;
        this.vectorEnabled = vectorEnabled;
    }

    @Override
    public StoreCapabilities capabilities() {
        if (vectorEnabled) {
            // Qdrant：关键词（payload 全文）+ 向量都占；不支持通配（无索引模式）
            return new StoreCapabilities(true, true, false, true, true);
        }
        // 纯 keyword：不存向量、不能作为重灌目标（重灌要写向量，keyword-only 收不下）
        return new StoreCapabilities(true, false, false, true, false);
    }

    // ==================== 生命周期 ====================

    @Override
    public boolean exists(String collection) throws StoreAccessException {
        return client.exists(collection);
    }

    @Override
    public void ensureCollection(String collection, int dims) throws StoreAccessException {
        if (vectorEnabled) {
            client.ensureCollection(collection, dims);
        } else {
            // 纯 keyword：建无向量集合（dims 与 keyword-only 无关，落不下向量就不该按维度建）
            client.ensureCollection(collection);
        }
    }

    @Override
    public boolean deleteCollection(String collection) throws StoreAccessException {
        return client.deleteCollection(collection);
    }

    @Override
    public List<String> listCollections() {
        return client.listCollections();
    }

    @Override
    public Integer currentVectorDims(String collection) {
        return client.currentVectorDims(collection);
    }

    @Override
    public boolean ping() {
        return client.ping();
    }

    @Override
    public void diagnose(String collection) {
        try {
            String version = client.version();
            log.info("=================== Qdrant 诊断开始 ===================");
            log.info("[Qdrant] 服务版本: {}", version == null ? "未知" : version);
            log.info("[Qdrant] 距离度量: {}", client.metric());
            boolean exists = client.exists(collection);
            log.info("[Qdrant] 集合[{}] 是否存在: {}", collection, exists);
            if (exists) {
                Integer dims = client.currentVectorDims(collection);
                log.info("[Qdrant] 集合[{}] 向量维度: {}", collection, dims == null ? "N/A" : dims);
                log.info("[Qdrant] 集合[{}] 点位数: {}", collection, client.count(collection, null));
            }
            log.info("=================== Qdrant 诊断结束 ===================");
        } catch (Exception e) {
            log.error("[Qdrant] 诊断失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 写入 ====================

    @Override
    public void write(String collection, List<KbChunk> chunks) throws StoreAccessException {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<QdrantPoint> points = new ArrayList<>(chunks.size());
        for (KbChunk chunk : chunks) {
            // 纯 keyword 后端丢弃向量：写 payload-only 点位（QdrantPoint.vector 可为 null，
            // client.upsert 会跳过 vector 数组）。切片本体本来就带向量（摄取侧统一 embed），
            // "往不下向量的后端塞向量"要在这里掐断，而不是等 Qdrant 报维度错误
            float[] vector = vectorEnabled ? chunk.vector() : null;
            points.add(new QdrantPoint(chunk.id(), chunk.text(), chunk.metadata(), vector));
        }
        // wait=true：写完立即可见（Qdrant 默认是异步写，等于 ES 的 refresh 竞态）
        client.upsert(collection, points);
    }

    @Override
    public int deleteByDocId(String collection, String docId) throws StoreAccessException {
        return client.deleteByDocId(collection, docId);
    }

    @Override
    public Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes) {
        if (contentHashes == null || contentHashes.isEmpty()) {
            return Set.of();
        }
        Set<DedupKey> existing = new HashSet<>();
        try {
            // Qdrant 没有 terms 查询，用 scroll 扫 metadata 里的 content_hash。
            // 扫描量受 DEDUP_FETCH_CAP 限制：触顶时告警，宁可少判一次重复，
            // 也别悄悄写重（与 ES 适配器同纪律）。
            int scanned = 0;
            Object scrollId = null;
            while (scanned < DEDUP_FETCH_CAP) {
                List<QdrantScored> page = client.scroll(collection, SCROLL_PAGE, scrollId);
                if (page.isEmpty()) {
                    break;
                }
                for (QdrantScored s : page) {
                    String hash = s.metadata().get(ChunkMetadataKeys.CONTENT_HASH);
                    if (hash != null && contentHashes.contains(hash)) {
                        existing.add(new DedupKey(hash, s.metadata().get(ChunkMetadataKeys.FILE_NAME)));
                    }
                }
                scanned += page.size();
                scrollId = client.nextScrollId(collection, scrollId);
                if (scrollId == null) {
                    break;
                }
            }
            if (scanned >= DEDUP_FETCH_CAP) {
                log.warn("[Qdrant] 去重查询扫描达到上限 {} 条，剩余点位未参与判定，"
                        + "本次导入可能写入重复切片", DEDUP_FETCH_CAP);
            }
        } catch (Exception e) {
            // fail-open + error 级日志：后果是重复写入而不是错数据
            log.error("[Qdrant] 去重查询失败（集合可能不存在），"
                    + "本次导入将跳过去重，可能写入重复切片: {}", e.getMessage());
        }
        return existing;
    }

    // ==================== 读取（管控台 / 重灌 / 对账）====================

    @Override
    public long count(String collection) {
        return client.count(collection, null);
    }

    @Override
    public long countByDocId(String collection, String docId) {
        return client.count(collection, docId);
    }

    @Override
    public List<KbChunk> readByDocId(String collection, String docId) {
        try {
            List<KbChunk> out = new ArrayList<>();
            Object scrollId = null;
            while (out.size() < READ_BY_DOC_MAX) {
                List<QdrantScored> page = client.scroll(collection, SCROLL_PAGE, scrollId);
                if (page.isEmpty()) {
                    break;
                }
                for (QdrantScored s : page) {
                    if (docId != null && !docId.equals(s.metadata().get(ChunkMetadataKeys.DOC_ID))) {
                        continue;
                    }
                    KbChunk chunk = toChunk(s);
                    if (chunk != null) {
                        out.add(chunk);
                    }
                }
                scrollId = client.nextScrollId(collection, scrollId);
                if (scrollId == null) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            // fail-open：读不到按缺档处置（对账补写 / archive 记 missing）
            log.warn("[Qdrant] 读回 docId={} 的切片失败（集合={}）: {}", docId, collection, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<DocumentAgg> documents(String collection, int limit) {
        try {
            if (!exists(collection)) {
                return List.of();
            }
            Map<String, String> idToName = new LinkedHashMap<>();
            Map<String, Integer> idToChunks = new LinkedHashMap<>();
            Map<String, String> idToDomain = new LinkedHashMap<>();
            Object scrollId = null;
            int scanned = 0;
            while (scanned < Math.max(1, limit)) {
                List<QdrantScored> page = client.scroll(collection, SCROLL_PAGE, scrollId);
                if (page.isEmpty()) {
                    break;
                }
                for (QdrantScored s : page) {
                    Map<String, String> md = s.metadata();
                    String docId = md.get(ChunkMetadataKeys.DOC_ID);
                    if (docId == null) {
                        continue;
                    }
                    idToName.putIfAbsent(docId, md.get(ChunkMetadataKeys.FILE_NAME));
                    idToChunks.merge(docId, 1, Integer::sum);
                    idToDomain.putIfAbsent(docId, domainOf(md));
                }
                scanned += page.size();
                scrollId = client.nextScrollId(collection, scrollId);
                if (scrollId == null) {
                    break;
                }
            }
            List<DocumentAgg> items = new ArrayList<>();
            idToName.forEach((id, fileName) ->
                    items.add(new DocumentAgg(id, fileName, idToDomain.getOrDefault(id, Domains.DEFAULT),
                            idToChunks.getOrDefault(id, 0))));
            return items;
        } catch (Exception e) {
            // fail-open：管控台只读展示路径
            log.warn("[Qdrant] 读取集合[{}]文档列表失败（按空列表处理）: {}", collection, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Iterator<List<KbChunk>> scan(String collection, int batch) {
        return new ScanIterator(collection, Math.max(1, batch));
    }

    /**
     * 全量扫描迭代器：Qdrant 的 scroll 协议（自带 next_page_offset 游标）。
     *
     * <p>与 ES 适配器同纪律：<b>读到一半停住会丢切片</b>，所以翻页失败必须抛，
     * 不能静默返回"扫完了"。</p>
     */
    private final class ScanIterator implements Iterator<List<KbChunk>> {

        private final String collection;
        private final int batch;
        private List<KbChunk> pending;
        private Object scrollId;
        private boolean done;

        private ScanIterator(String collection, int batch) {
            this.collection = collection;
            this.batch = batch;
        }

        @Override
        public boolean hasNext() {
            while (!done && (pending == null || pending.isEmpty())) {
                fetchPage();
            }
            // done 后 pending 可能已被 next() 清空（置 null）：必须回答"没有了"，
            // 不能直接 pending.isEmpty()——标准打法 while(hasNext()) next() 会 NPE
            return pending != null && !pending.isEmpty();
        }

        @Override
        public List<KbChunk> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            List<KbChunk> out = pending;
            pending = null;
            return out;
        }

        private void fetchPage() {
            try {
                List<QdrantScored> page = client.scroll(collection, batch, scrollId);
                List<KbChunk> chunks = new ArrayList<>(page.size());
                for (QdrantScored s : page) {
                    KbChunk c = toChunk(s);
                    if (c != null) {
                        chunks.add(c);
                    }
                }
                pending = chunks;
                Object next = client.nextScrollId(collection, scrollId);
                if (page.isEmpty() || next == null) {
                    done = true;
                    return;
                }
                scrollId = next;
            } catch (Exception e) {
                throw new StoreAccessException("扫描集合[" + collection + "]失败："
                        + e.getMessage(), e);
            }
        }
    }

    // ==================== 内部 ====================

    /** Qdrant 命中 → {@link KbChunk}（扫描路：要向量）。 */
    private static KbChunk toChunk(QdrantScored s) {
        if (s.text() == null || s.text().isBlank()) {
            return null;
        }
        return new KbChunk(s.id(), s.text(), s.metadata(), s.vector());
    }

    /** 读取切片元数据里的归属域；缺失或为空时按根域算（与 ES 适配器同口径）。 */
    private static String domainOf(Map<String, String> md) {
        String raw = md.get(ChunkMetadataKeys.DOMAIN);
        if (raw != null && !raw.isBlank()) {
            return raw;
        }
        return Domains.DEFAULT;
    }
}
