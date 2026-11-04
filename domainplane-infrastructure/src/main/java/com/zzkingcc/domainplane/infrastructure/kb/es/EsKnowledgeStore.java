package com.zzkingcc.domainplane.infrastructure.kb.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.IndexState;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.support.KbIndexes;
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
 * ES 版 {@link KnowledgeStore}：写入与生命周期。
 *
 * 由三处平移合成：{@code EsIndexManager}（生命周期 / mapping / 诊断）、
 * {@code KnowledgeBaseService} 的直调（count / deleteByQuery / 列表聚合 / ping）、
 * {@code AbstractDocumentProcessStrategy.queryExistingKeys}（去重查询）。
 * 失败姿态逐条对齐 {@link KnowledgeStore} 的 javadoc 表，改动点只有两处，
 * 都在 javadoc 里写明：
 *
 *   1. {@link #ensureCollection} 不再假承诺"由上层回退到默认创建方式"——
 *      旧注释这么说但实现里没有回退（一审修订 B1）。这里如实说明后果。
 *   2. {@link #write} 末尾显式 refresh——旧写入路径（langchain4j 的 store）不 refresh，
 *      "导入后立即 countByDocId"实际踩着 ES 近实时刷新（默认 1s）的竞态，
 *      旧代码靠导入间隔偶然掩盖；批量重灌时第一个索引就会撞上。
 *
 * 同时实现 {@link DedupLookup}（ingest 侧的窄依赖）：{@link #existingDedupKeys}
 * 一个方法满足两个接口——摄取链路只问去重，不给它整个 store。
 *
 * @author zzkingcc
 */
@Slf4j
public class EsKnowledgeStore implements KnowledgeStore, DedupLookup {

    /** ES terms 查询单次最大条数限制（ES 服务端上限 65536，这里取保守值） */
    private static final int ES_TERMS_BATCH_SIZE = 10000;

    /**
     * 列表聚合时单个索引最多拉取的命中数（与旧 {@code KnowledgeBaseService.listIndex} 一致）。
     */
    private static final int LIST_MAX_SIZE = 1000;

    /**
     * 去重查询的拉取上限。
     *
     * 不能拿候选哈希数当上限：一个正文哈希可能命中"同内容的多份文件"，
     * 命中数被卡在哈希个数上时，多出来的去重键读不到，同内容切片会被重复写入。
     * 留 10 倍余量并封顶；触顶时告警，宁可少判一次重复，也别悄悄写重。
     */
    private static final int DEDUP_FETCH_CAP = 2000;

    /**
     * 按 docId 读回切片的条数上限（与切片导出口径一致：单文件切片上限 2000）。
     */
    private static final int READ_BY_DOC_MAX = 2000;

    private final ElasticsearchClient esClient;

    public EsKnowledgeStore(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public StoreCapabilities capabilities() {
        // ES 三样全占：关键字（BM25）+ 向量（dense_vector）+ 通配（索引模式）
        return new StoreCapabilities(true, true, true, true, true);
    }

    // ==================== 生命周期 ====================

    @Override
    public boolean exists(String collection) throws StoreAccessException {
        try {
            return esClient.indices().exists(e -> e.index(collection)).value();
        } catch (Exception e) {
            // fail-closed：查不到不等同于不存在。调用方拿这个结果做破坏性决策，
            // ES 一抖动（超时、连接被拒、集群 red）时答"不存在"会让删域静默跳过
            // 索引删除，而同一路径域重建后索引名相同（KbIndexes.nameOf 是路径哈希），
            // 这份"本该删掉的知识内容"会原样复活，且没有任何报错。
            throw new StoreAccessException("查询 collection[" + collection + "]是否存在失败"
                    + "（无法确认，不等同于不存在）：" + e.getMessage(), e);
        }
    }

    @Override
    public void ensureCollection(String collection, int dims) throws StoreAccessException {
        boolean exists;
        try {
            exists = esClient.indices().exists(e -> e.index(collection)).value();
        } catch (Exception e) {
            // 旧实现在这一步把异常吞进"创建降级"告警：ES 整个不可达时只看到一条
            // 降级警告，后续写入才以 index_not_found 失败。按契约改为 fail-closed。
            throw new StoreAccessException("查询 collection[" + collection + "]是否存在失败："
                    + e.getMessage(), e);
        }
        if (exists) {
            Integer current = currentVectorDims(collection);
            if (current != null && current != dims) {
                log.error("[ES] collection[{}]已存在且向量维度不一致：现有 mapping dims={}，当前配置需要 {}。"
                                + "写入会因维度不符被拒绝，需删除后重建（管控台「知识库」页点「触发重建」）。",
                        collection, current, dims);
            } else {
                log.info("[ES] collection[{}]已存在（mapping dims={}），跳过创建 IK mapping", collection, current);
            }
            return;
        }

        String mappingJson = EsMapping.build(dims);
        try {
            esClient.indices().create(c -> c
                    .index(collection)
                    .withJson(new java.io.StringReader(mappingJson)));
            log.info("[ES] collection[{}]创建成功（IK分词器 mapping，dims={}）", collection, dims);
        } catch (Exception e) {
            // 刻意不按报文关键字去分类异常（"是不是 IK 没装"）：判据靠关键字在多版本、
            // 多发行版的 ES 上并不可靠，而错误分类会把用户引向错误方向。
            //
            // 一审修订 B1：旧注释承诺"由上层回退到默认创建方式"，但上层并没有回退，
            // 这是假承诺。如实说明后果，不把谎言搬进新代码：
            log.warn("""
                    ================ [ES] 索引创建降级 ================
                    collection[{}] 未能按 IK 分词器 mapping 创建，该索引当前不存在。
                    影响：后续写入要么报 index_not_found，要么被 ES 的 auto_create_index
                          动态建成默认 mapping（中文检索质量下降且无任何提示）。
                          两种情况导入都不算成功，不会静默降级后照样返回成功。
                    最常见原因：该 ES 未安装 analysis-ik 插件（IK 是插件，任何版本都需单独安装）。
                    确认方式：管控台「存储配置」页点一次「测试连接」，它会明确告知 IK 是否可用。
                    原始原因：{}
                    ===============================================
                    """, collection, e.getMessage());
        }
    }

    @Override
    public boolean deleteCollection(String collection) throws StoreAccessException {
        // exists 现在会抛 StoreAccessException，而"查询失败"和"删除失败"在上层要区别对待：
        // 前者 collection 可能还在（不该删域），后者是删操作本身没成功（同样不该删域）
        if (!exists(collection)) {
            return false;
        }
        try {
            esClient.indices().delete(d -> d.index(collection));
        } catch (Exception e) {
            throw new StoreAccessException("删除 collection[" + collection + "]失败：" + e.getMessage(), e);
        }
        log.info("[ES] 已删除 collection: {}", collection);
        return true;
    }

    @Override
    public List<String> listCollections() {
        try {
            var resp = esClient.indices().get(g -> g.index(KbIndexes.WILDCARD)
                    .ignoreUnavailable(true).allowNoIndices(true));
            return resp.indices().keySet().stream().sorted().toList();
        } catch (Exception e) {
            // fail-open：只用于管控台列表展示，返回空列表的代价是多显示一行空，
            // 而抛异常的代价是页面整个打不开
            log.warn("[ES] 枚举索引[{}]失败: {}", KbIndexes.WILDCARD, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Integer currentVectorDims(String collection) {
        try {
            IndexState state = esClient.indices().get(g -> g.index(collection)).indices().get(collection);
            if (state == null || state.mappings() == null) {
                return null;
            }
            Property vector = state.mappings().properties().get("vector");
            if (vector == null || !vector.isDenseVector()) {
                return null;
            }
            return vector.denseVector().dims();
        } catch (Exception e) {
            log.debug("[ES] 读取索引[{}]向量维度失败: {}", collection, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean ping() {
        try {
            Boolean ok = esClient.ping().value();
            return ok != null && ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void diagnose(String collection) {
        try {
            var info = esClient.info();
            String version = info.version().number();
            String clusterName = info.clusterName();
            log.info("=================== ES 诊断开始 ===================");
            log.info("[ES] 集群: {}  |  服务器版本: {}", clusterName, version);

            HealthResponse h = esClient.cluster().health();
            log.info("[ES] 集群健康: status={}, 节点数={}, 活跃分片={}",
                    h.status(), h.numberOfNodes(), h.activeShards());

            boolean exists = esClient.indices().exists(e -> e.index(collection)).value();
            log.info("[ES] 索引[{}] 是否存在: {}", collection, exists);
            if (exists) {
                try {
                    SearchResponse<Map> s = esClient.search(q -> q.index(collection).size(0), Map.class);
                    log.info("[ES] 索引文档数: {}", s.hits().total() != null ? s.hits().total().value() : "N/A");
                } catch (Exception ignored) {
                }
                try {
                    IndexState state = esClient.indices().get(g -> g.index(collection)).indices().get(collection);
                    if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                        Map<String, Property> properties = state.mappings().properties();
                        log.info("[ES] Mapping 字段:");
                        properties.forEach((name, prop) -> {
                            String extra = "";
                            if (prop.isDenseVector()) {
                                extra = " (dims=" + prop.denseVector().dims() + ")";
                            } else if (prop.isObject()) {
                                extra = " (enabled=" + (prop.object().enabled() != null
                                        && Boolean.TRUE.equals(prop.object().enabled())) + ")";
                            }
                            log.info("[ES]      - {} -> {}{}", name, propertyTypeName(prop), extra);
                        });
                    }
                } catch (Exception ignored) {
                }
            }
            log.info("=================== ES 诊断结束 ===================");
        } catch (Exception e) {
            log.error("[ES] 诊断失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 写入 ====================

    @Override
    public void write(String collection, List<KbChunk> chunks) throws StoreAccessException {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            List<BulkOperation> ops = new ArrayList<>(chunks.size());
            for (KbChunk chunk : chunks) {
                ops.add(BulkOperation.of(b -> b.index(i -> i
                        .index(collection)
                        .id(chunk.id())
                        .document(EsSourceCodec.toDocument(chunk)))));
            }
            BulkResponse resp = esClient.bulk(r -> r.index(collection).operations(ops));

            if (resp.errors()) {
                // 任一条失败都抛，让调用方回滚。bulk 的部分成功由调用方的
                // deleteByDocId（按 docId 整文档删）兜底，不会留下半个文档
                StringBuilder sb = new StringBuilder();
                for (BulkResponseItem item : resp.items()) {
                    if (item.error() != null) {
                        sb.append(item.error().type()).append(": ").append(item.error().reason()).append("; ");
                    }
                }
                throw new StoreAccessException("写入 collection[" + collection + "]失败（共 "
                        + chunks.size() + " 条，任一条失败即整体失败）：" + sb);
            }

            /* refresh 让随后的 count / search 立即可见。ES 默认近实时
               （refresh_interval 1s），不 refresh 的话"导入后立即 countByDocId"
               是踩竞态：旧写入路径不 refresh，靠两次导入之间的间隔偶然掩盖，
               批量重灌时第一个索引就会撞上"写完了却数出 0 条"。 */
            esClient.indices().refresh(rf -> rf.index(collection));
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreAccessException("写入 collection[" + collection + "]失败："
                    + e.getMessage(), e);
        }
    }

    @Override
    public int deleteByDocId(String collection, String docId) throws StoreAccessException {
        try {
            var resp = esClient.deleteByQuery(d -> d.index(collection)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId)))));
            esClient.indices().refresh(r -> r.index(collection));
            long deleted = resp.deleted() == null ? 0 : resp.deleted();
            return Math.toIntExact(deleted);
        } catch (Exception e) {
            throw new StoreAccessException("删除 collection[" + collection + "]中文档["
                    + docId + "]失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes) {
        if (contentHashes == null || contentHashes.isEmpty()) {
            return Set.of();
        }

        Set<DedupKey> existing = new HashSet<>();
        List<String> hashList = new ArrayList<>(contentHashes);

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
                                .field("metadata." + ChunkMetadataKeys.CONTENT_HASH)
                                .terms(tf -> tf.value(fieldValues)))
                        .build();

                int fetchSize = Math.min(DEDUP_FETCH_CAP, Math.max(chunk.size() * 10, chunk.size()));
                SearchResponse<Map> resp = esClient.search(s -> s
                                .index(collection)
                                .size(fetchSize)
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
                    Object hash = meta.get(ChunkMetadataKeys.CONTENT_HASH);
                    if (hash == null) {
                        return;
                    }
                    Object name = meta.get(ChunkMetadataKeys.FILE_NAME);
                    existing.add(new DedupKey(hash.toString(), name == null ? null : name.toString()));
                });
            } catch (Exception e) {
                // 升到 error：这里是"本次去重整体失效"，同内容切片会被重复写入。
                // warn 级别混在常规告警里没人会注意，而后果是索引里越来越多重复条目。
                log.error("[分片去重] 查询 ES 已有 content_hash 失败（collection 可能不存在或无 content_hash 字段），"
                        + "本次导入将跳过去重，可能写入重复切片: {}", e.getMessage());
            }
        }
        return existing;
    }

    // ==================== 读取（管控台 / 重灌 / 对账）====================

    @Override
    public long count(String collection) {
        try {
            return esClient.count(c -> c.index(collection)).count();
        } catch (Exception e) {
            log.warn("[知识库] 统计索引总片段数失败（collection={}）: {}", collection, e.getMessage());
            return 0;
        }
    }

    @Override
    public long countByDocId(String collection, String docId) {
        try {
            return esClient.count(c -> c.index(collection)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId))))).count();
        } catch (Exception e) {
            log.warn("[知识库] 统计 docId={} 的片段数失败: {}", docId, e.getMessage());
            return 0;
        }
    }

    @Override
    public List<KbChunk> readByDocId(String collection, String docId) {
        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(collection)
                    .size(READ_BY_DOC_MAX)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId))))
                    .source(src -> src.filter(f -> f.includes("text", "metadata", "vector"))), Map.class);
            List<KbChunk> out = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                KbChunk chunk = EsSourceCodec.toChunk(hit.id(), hit.source());
                if (chunk != null) {
                    out.add(chunk);
                }
            }
            return out;
        } catch (Exception e) {
            // fail-open：读不到按缺档处置（对账补写 / archive 记 missing），
            // 不把"归档一个文档"升级成存储故障
            log.warn("[知识库] 读回 docId={} 的切片失败（collection={}）: {}", docId, collection, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<DocumentAgg> documents(String collection, int limit) {
        boolean exists;
        try {
            exists = exists(collection);
        } catch (StoreAccessException e) {
            log.warn("[知识库] 读取索引[{}]文档列表失败（ES 不可达，按空列表处理）: {}",
                    collection, e.getMessage());
            return List.of();
        }
        if (!exists) {
            return List.of();
        }
        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(collection)
                    .size(Math.max(1, limit))
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
                idToDomain.putIfAbsent(docId, bodyOf(md));
            }
            List<DocumentAgg> items = new ArrayList<>();
            idToName.forEach((id, fileName) ->
                    items.add(new DocumentAgg(id, fileName, idToDomain.get(id),
                            idToChunks.getOrDefault(id, 0))));
            return items;
        } catch (Exception e) {
            // 设计 4.6：documents 是管控台只读展示路径，fail-open。
            // 与旧实现的差异：旧 listIndex 在 search 失败时抛 KnowledgeBaseException，
            // 这里按契约改为空列表——一页少显示几个文档好过整页打不开
            log.warn("[知识库] 读取索引[{}]文档列表失败（按空列表处理）: {}", collection, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Iterator<List<KbChunk>> scan(String collection, int batch) {
        return new ScanIterator(collection, Math.max(1, batch));
    }

    /**
     * 全量扫描的翻页迭代器：{@code search_after} + {@code _doc} 排序，
     * 规则与旧 {@code VectorReindexer.preserveAll} 一致——
     *
     *   • 判"取完了"看 total（{@code trackTotalHits} 必须开，ES 默认只精确统计到 1 万条，
     *     不开会在索引上提前 break，而调用方紧接着就要删索引重建，截掉的部分永久丢失）；
     *   • 没取完却读不出东西 / 拿不到游标：宁可抛异常中止，也不能从头覆盖着翻。
     */
    private final class ScanIterator implements Iterator<List<KbChunk>> {

        private final String collection;
        private final int batch;
        private final long total;
        private List<FieldValue> cursor;
        private long consumed;
        private List<KbChunk> pending;
        private boolean done;

        private ScanIterator(String collection, int batch) {
            this.collection = collection;
            this.batch = batch;
            this.total = readTotalHits();
            this.cursor = null;
            this.consumed = 0;
        }

        /** 首页之前先取一次 total：后面每页按它判"取完了" */
        private long readTotalHits() {
            try {
                SearchResponse<Map> resp = esClient.search(s -> s
                        .index(collection).size(0), Map.class);
                return resp.hits().total() == null ? 0 : resp.hits().total().value();
            } catch (Exception e) {
                throw new StoreAccessException("读取 collection[" + collection + "]总数失败，无法安全扫描："
                        + e.getMessage(), e);
            }
        }

        @Override
        public boolean hasNext() {
            while (!done && (pending == null || pending.isEmpty())) {
                fetchPage();
            }
            // done 后 pending 可能已被 next() 清空（置 null）：此时必须回答"没有了"，
            // 不能直接 pending.isEmpty()——最后一页消费完再调 hasNext 会 NPE，
            // 而 scanAll 的标准打法就是 while(hasNext()) next()
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
            if (total >= 0 && consumed >= total) {
                done = true;
                pending = List.of();
                return;
            }
            try {
                final List<FieldValue> after = cursor;
                SearchResponse<Map> resp = esClient.search(s -> {
                    s.index(collection)
                            .size(batch)
                            .trackTotalHits(t -> t.enabled(true))
                            .sort(so -> so.field(f -> f.field("_doc").order(SortOrder.Asc)))
                            .source(src -> src.filter(f -> f.includes("text", "metadata", "vector")));
                    if (after != null) {
                        s.searchAfter(after);
                    }
                    return s;
                }, Map.class);

                List<Hit<Map>> hits = resp.hits().hits();
                List<KbChunk> chunks = new ArrayList<>(hits.size());
                for (Hit<Map> hit : hits) {
                    KbChunk c = EsSourceCodec.toChunk(hit.id(), hit.source());
                    if (c != null) {
                        chunks.add(c);
                    }
                }
                consumed += hits.size();
                pending = chunks;

                if (consumed >= total) {
                    done = true;
                    return;
                }
                if (hits.isEmpty()) {
                    // 没取完却已经读不出东西了：再发一次也是空转，直接中止而不是死循环
                    throw new StoreAccessException("ES 未返回数据，无法安全翻页（已取 "
                            + consumed + " 条 / 共 " + total + " 条，若就此截断会丢切片）");
                }
                List<FieldValue> next = hits.get(hits.size() - 1).sort();
                if (next == null || next.isEmpty()) {
                    // 还没取完却拿不到游标：再翻一页就会从头覆盖，丢数据；宁可中止
                    throw new StoreAccessException("ES 未返回 sort 值，无法安全翻页（已取 "
                            + consumed + " 条 / 共 " + total + " 条，若就此截断会丢切片）");
                }
                cursor = new ArrayList<>(next);
            } catch (StoreAccessException e) {
                throw e;
            } catch (Exception e) {
                throw new StoreAccessException("扫描 collection[" + collection + "]失败（已取 "
                        + consumed + " 条 / 共 " + total + " 条）：" + e.getMessage(), e);
            }
        }
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataOf(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object md = source.get("metadata");
        return md instanceof Map ? (Map<String, Object>) md : null;
    }

    /** 读取切片元数据里的知识体 id；缺失（早期文档）返回 null——体已经不是必填的历史语义了 */
    private static String bodyOf(Map<String, Object> md) {
        Object raw = md.get(ChunkMetadataKeys.DOMAIN);
        if (raw != null && !raw.toString().isBlank()) {
            return raw.toString();
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String propertyTypeName(Property p) {
        if (p == null) return "null";
        if (p.isText()) return "text";
        if (p.isKeyword()) return "keyword";
        if (p.isDenseVector()) return "dense_vector";
        if (p.isObject()) return "object";
        if (p.isNested()) return "nested";
        if (p.isInteger() || p.isLong()) return "long";
        if (p.isFloat()) return "float";
        if (p.isDouble()) return "double";
        if (p.isBoolean()) return "boolean";
        if (p.isIp()) return "ip";
        if (p.isDate()) return "date";
        if (p.isGeoPoint()) return "geo_point";
        return p._kind().name().toLowerCase();
    }
}
