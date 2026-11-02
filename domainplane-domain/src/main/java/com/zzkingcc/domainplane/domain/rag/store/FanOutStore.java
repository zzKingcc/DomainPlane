package com.zzkingcc.domainplane.domain.rag.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 扇出写 store：把一次写入同时落到多个后端。
 *
 * <p>P1 引入——<b>keyword 路留在 ES、vector 路切到向量库</b>时，同一份切片
 * 必须同时写进两个后端：ES 那边要有文本才能被 BM25 命中，向量库那边要有向量
 * 才能被 kNN 命中。只写一边的话，另一条路静默召回不到（不报错，只是没结果）。</p>
 *
 * <p><b>primary 与 targets 的分工</b>：</p>
 * <ul>
 *   <li>{@code primary} = <b>登记权威</b>（keyword 后端）。读路径（列表 / 计数 /
 *       文档明细 / 读回切片）一律以它为准——"有哪些文档"是登记视角，不是检索视角
 *       （一审修订 B4）；去重也问它。</li>
 *   <li>{@code targets} = 需要接收写入的全部后端（primary 也在内）。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：写入任一后端失败即整体失败（抛 {@link StoreAccessException}），
 * 调用方据此回滚——而回滚走 {@link #deleteByDocId}，它同样扇出到全部后端，
 * 所以"写了一半"不会留下孤儿。</p>
 *
 * <p><b>不负责的事</b>：向量维度的读数不走本类（那是 vector 后端的唯一读数源，
 * 见 {@code RetrievalPlan#vectorStore()}）；通配检索能力也不走本类
 * （各后端的 wildcard 能力由各自的能力声明负责）。</p>
 *
 * @author zzkingcc
 */
public final class FanOutStore implements KnowledgeStore, DedupLookup {

    private final KnowledgeStore primary;
    private final List<KnowledgeStore> targets;

    /**
     * @param primary 登记权威后端（读路径与去重都问它）
     * @param targets 需要接收写入的全部后端（含 primary；重复的按首次出现去重）
     */
    public FanOutStore(KnowledgeStore primary, List<KnowledgeStore> targets) {
        if (primary == null) {
            throw new IllegalArgumentException("扇出写的登记权威后端不能为空");
        }
        this.primary = primary;
        Set<KnowledgeStore> unique = new LinkedHashSet<>();
        unique.add(primary);
        if (targets != null) {
            unique.addAll(targets);
        }
        this.targets = List.copyOf(unique);
    }

    /** 全部写入目标（primary 在首位） */
    public List<KnowledgeStore> targets() {
        return targets;
    }

    /** 登记权威后端 */
    public KnowledgeStore primary() {
        return primary;
    }

    // ==================== 写入：扇出 ====================

    @Override
    public void write(String collection, List<KbChunk> chunks) throws StoreAccessException {
        for (KnowledgeStore target : targets) {
            target.write(collection, chunks);
        }
    }

    @Override
    public int deleteByDocId(String collection, String docId) throws StoreAccessException {
        int deleted = 0;
        for (KnowledgeStore target : targets) {
            deleted += target.deleteByDocId(collection, docId);
        }
        return deleted;
    }

    @Override
    public void ensureCollection(String collection, int dims) throws StoreAccessException {
        for (KnowledgeStore target : targets) {
            target.ensureCollection(collection, dims);
        }
    }

    @Override
    public boolean deleteCollection(String collection) throws StoreAccessException {
        // 任一后端删不掉即整体失败：漏删的那个后端会留下孤儿集合，
        // 而集合名是域路径的哈希，域重建后同名集合会把旧知识原样复活
        boolean deleted = false;
        for (KnowledgeStore target : targets) {
            deleted |= target.deleteCollection(collection);
        }
        return deleted;
    }

    // ==================== 去重：问登记权威 ====================

    @Override
    public Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes) {
        return primary.existingDedupKeys(collection, contentHashes);
    }

    // ==================== 读路径：一律以登记权威为准 ====================

    @Override
    public StoreCapabilities capabilities() {
        return primary.capabilities();
    }

    @Override
    public boolean exists(String collection) throws StoreAccessException {
        return primary.exists(collection);
    }

    @Override
    public List<String> listCollections() {
        return primary.listCollections();
    }

    @Override
    public Integer currentVectorDims(String collection) {
        return primary.currentVectorDims(collection);
    }

    @Override
    public boolean ping() {
        return primary.ping();
    }

    @Override
    public void diagnose(String collection) {
        for (KnowledgeStore target : targets) {
            target.diagnose(collection);
        }
    }

    @Override
    public long count(String collection) {
        return primary.count(collection);
    }

    @Override
    public long countByDocId(String collection, String docId) {
        return primary.countByDocId(collection, docId);
    }

    @Override
    public List<KbChunk> readByDocId(String collection, String docId) {
        return primary.readByDocId(collection, docId);
    }

    @Override
    public List<DocumentAgg> documents(String collection, int limit) {
        return primary.documents(collection, limit);
    }

    @Override
    public java.util.Iterator<List<KbChunk>> scan(String collection, int batch) {
        return primary.scan(collection, batch);
    }

    /**
     * 全部写入目标的快照（排障用：看这次写到底会落到哪几个后端）。
     */
    public List<String> targetNames() {
        List<String> names = new ArrayList<>();
        for (KnowledgeStore target : targets) {
            names.add(target.getClass().getSimpleName());
        }
        return names;
    }
}
