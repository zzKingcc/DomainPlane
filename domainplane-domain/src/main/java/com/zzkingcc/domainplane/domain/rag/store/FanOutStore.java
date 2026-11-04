package com.zzkingcc.domainplane.domain.rag.store;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 扇出写 store：把一次写入同时落到多个后端。
 *
 * <p>P1 引入——<b>keyword 路留在 ES、vector 路切到向量库</b>时，同一份切片
 * 必须同时写进两个后端：ES 那边要有文本才能被 BM25 命中，向量库那边要有向量
 * 才能被 kNN 命中。只写一边的话，另一条路静默召回不到（不报错，只是没结果）。
 * 今天同一个场景还多了一种形态：关键字槽和向量槽各绑一台<b>不同的 ES</b>。</p>
 *
 * <p><b>primary 与 targets 的分工</b>：</p>
 * <ul>
 *   <li>{@code primary} = 写入侧的<b>行为权威</b>：{@link #ping} / {@link #diagnose} /
 *       建集合时的维度校验以它为准——"这份数据能不能写进去"看它。</li>
 *   <li>{@code targets} = 需要接收写入的全部后端（primary 也在内）。</li>
 * </ul>
 *
 * <p><b>读路径按"谁有这条数据"逐后端合并，不是只看 primary</b>：</p>
 * <p>早先这里一律读 primary（关键字后端），理由是"文档登记视角只有一个"。
 * 那在两路同实例时成立，在两路<b>分属两台机器</b>时不成立：切片是各写各的，
 * 两台都可能持有对方没有的文档（比如域换过知识体、或某一台上少了某次上传）。
 * 出错的形态是"看着空、其实有"，最难排查。所以这里改为：</p>
 * <ul>
 *   <li>{@link #documents} / {@link #scan} —— 全部后端<b>按 docId 合并</b>（先到先得，
 *       同 docId 的切片数与后端无关，取谁那条都一样）；</li>
 *   <li>{@link #listCollections} —— 全部后端<b>取并集</b>；</li>
 *   <li>{@link #count} / {@link #countByDocId} —— 全部后端<b>取最大值</b>。取最大值而不是
 *       求和：扇出写入会让同一份切片在两台各存一份，求和就是翻倍；取最大值在单台时与
 *       旧行为逐字一致，在双写下仍然是"这份文档实际有几片"；</li>
 *   <li>{@link #readByDocId} / {@link #currentVectorDims} —— primary 优先，取不到再问下一台。</li>
 * </ul>
 *
 * <p><b>去重（{@link #existingDedupKeys}）只问 primary</b>：去重的目的是"别把同一份内容
 * 再写一遍"，问一台就够；问两台会把"另一台上恰好有同内容"也算成重复，反而漏灌。</p>
 *
 * <p><b>失败语义</b>：写入任一后端失败即整体失败（抛 {@link StoreAccessException}），
 * 调用方据此回滚——而回滚走 {@link #deleteByDocId}，它同样扇出到全部后端，
 * 所以"写了一半"不会留下孤儿。读路径相反：任一后端抛就返回空/0（与 SPI 的
 * fail-open 同一姿态），一页少显示几条好过整页打不开。</p>
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
     * @param primary 写入侧行为权威（ping / diagnose / 维度校验都问它）
     * @param targets 需要接收写入的全部后端（含 primary；重复的按首次出现去重）
     */
    public FanOutStore(KnowledgeStore primary, List<KnowledgeStore> targets) {
        if (primary == null) {
            throw new IllegalArgumentException("扇出写的权威后端不能为空");
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

    /** 写入侧行为权威 */
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
        // 建集合失败要让调用方看见（与写入同一姿态）：某台没建成，那台的路就静默查不到
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

    // ==================== 去重：问权威一台就够 ====================

    @Override
    public Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes) {
        return primary.existingDedupKeys(collection, contentHashes);
    }

    // ==================== 读路径：逐后端合并 ====================

    /**
     * 能力取<b>并集</b>：扇出写入要求每个后端都能收下这份数据，所以这里回答的是
     * "这些后端合起来能做什么"。单个后端的能力判定仍然走它自己的 {@code capabilities()}。
     */
    @Override
    public StoreCapabilities capabilities() {
        boolean keyword = false;
        boolean vector = false;
        boolean wildcard = false;
        boolean storesText = false;
        boolean reindexInPlace = false;
        for (KnowledgeStore target : targets) {
            StoreCapabilities c = target.capabilities();
            if (c == null) {
                continue;
            }
            keyword |= c.keyword();
            vector |= c.vector();
            wildcard |= c.wildcard();
            storesText |= c.storesText();
            reindexInPlace |= c.reindexInPlace();
        }
        return new StoreCapabilities(keyword, vector, wildcard, storesText, reindexInPlace);
    }

    @Override
    public boolean exists(String collection) throws StoreAccessException {
        // fail-closed 方法：只问权威，但任一台的失败要如实抛——
        // 逐台 OR 会把"某台抛"和"某台没有"混成一个布尔值
        return primary.exists(collection);
    }

    @Override
    public List<String> listCollections() {
        // 并集：两台各持有一部分的场景（换过知识体、单台残留）才不会被漏显示
        Set<String> names = new LinkedHashSet<>();
        for (KnowledgeStore target : targets) {
            try {
                names.addAll(target.listCollections());
            } catch (RuntimeException e) {
                // 只读展示路径：一台枚举失败按"这台没有"处理，不让它拖垮整页
            }
        }
        return List.copyOf(names);
    }

    @Override
    public Integer currentVectorDims(String collection) {
        // 任一后端给出的维度即全局值（维度由唯一的向量模型决定）；问权威优先
        Integer dims = safeDims(primary, collection);
        if (dims != null) {
            return dims;
        }
        for (KnowledgeStore target : targets) {
            dims = safeDims(target, collection);
            if (dims != null) {
                return dims;
            }
        }
        return null;
    }

    private static Integer safeDims(KnowledgeStore store, String collection) {
        try {
            return store.currentVectorDims(collection);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean ping() {
        // 写入侧行为权威：它通了才说明"这份数据写得进去"
        return primary.ping();
    }

    @Override
    public void diagnose(String collection) {
        for (KnowledgeStore target : targets) {
            target.diagnose(collection);
        }
    }

    /** @see #count 口径说明（取最大值，不求和） */
    @Override
    public long count(String collection) {
        long max = 0;
        for (KnowledgeStore target : targets) {
            try {
                max = Math.max(max, target.count(collection));
            } catch (RuntimeException e) {
                // fail-open：跳到下一台
            }
        }
        return max;
    }

    @Override
    public long countByDocId(String collection, String docId) {
        long max = 0;
        for (KnowledgeStore target : targets) {
            try {
                max = Math.max(max, target.countByDocId(collection, docId));
            } catch (RuntimeException e) {
                // fail-open：跳到下一台
            }
        }
        return max;
    }

    @Override
    public List<KbChunk> readByDocId(String collection, String docId) {
        // 权威那台先答；它返回空（而不是抛）才问别的后端——"这台没有"与"这台坏了"
        // 在 SPI 里都是空列表，所以顺序就是优先级的全部含义
        for (KnowledgeStore target : targets) {
            try {
                List<KbChunk> chunks = target.readByDocId(collection, docId);
                if (chunks != null && !chunks.isEmpty()) {
                    return chunks;
                }
            } catch (RuntimeException e) {
                // fail-open：跳到下一台
            }
        }
        return List.of();
    }

    @Override
    public List<DocumentAgg> documents(String collection, int limit) {
        // 按 docId 合并：同 docId 在两台各有一份时只显示一条（先到先得）
        Map<String, DocumentAgg> byDocId = new LinkedHashMap<>();
        for (KnowledgeStore target : targets) {
            try {
                for (DocumentAgg doc : target.documents(collection, limit)) {
                    if (doc != null && doc.docId() != null) {
                        byDocId.putIfAbsent(doc.docId(), doc);
                    }
                }
            } catch (RuntimeException e) {
                // fail-open：这台跳过
            }
        }
        return List.copyOf(byDocId.values());
    }

    @Override
    public Iterator<List<KbChunk>> scan(String collection, int batch) {
        // 合并所有后端的批次流，按 doc_id 去重：切片数按 docId 与后端无关，先到先得。
        // 单台时行为与"直接转交"逐字一致（只是多包一层去重）。
        List<Iterator<List<KbChunk>>> iterators = new ArrayList<>(targets.size());
        for (KnowledgeStore target : targets) {
            iterators.add(target.scan(collection, batch));
        }
        return new MergingScan(iterators);
    }

    /**
     * 多个后端的 scan 串成一条流，按 {@code doc_id} 整组去重。
     *
     * <p>去重粒度是<b>文档</b>不是切片：同一 docId 的切片若一半来自 A 台、一半来自 B 台，
     * 拼起来的文档会缺片而看不出缺——所以见到已发过的 docId 就整批跳过（两台上这份文档
     * 完全相同，跳过不会丢内容）。</p>
     */
    private static final class MergingScan implements Iterator<List<KbChunk>> {

        private final List<Iterator<List<KbChunk>>> iterators;
        private final Set<String> seenDocs = new LinkedHashSet<>();
        private int cursor;
        private List<KbChunk> nextBatch;

        MergingScan(List<Iterator<List<KbChunk>>> iterators) {
            this.iterators = iterators;
        }

        @Override
        public boolean hasNext() {
            if (nextBatch == null) {
                nextBatch = pull();
            }
            return nextBatch != null;
        }

        @Override
        public List<KbChunk> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            List<KbChunk> out = nextBatch;
            nextBatch = null;
            return out;
        }

        private List<KbChunk> pull() {
            while (cursor < iterators.size()) {
                Iterator<List<KbChunk>> it = iterators.get(cursor);
                if (!it.hasNext()) {
                    cursor++;
                    continue;
                }
                List<KbChunk> batch = it.next();
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                if (covered(batch)) {
                    continue;
                }
                return batch;
            }
            return null;
        }

        /** 这一批里的文档是不是全都已经发过了 */
        private boolean covered(List<KbChunk> batch) {
            Set<String> docIds = new LinkedHashSet<>();
            for (KbChunk chunk : batch) {
                String docId = chunk == null ? null
                        : chunk.metadata() == null ? null
                        : String.valueOf(chunk.metadata().get(ChunkMetadataKeys.DOC_ID));
                docIds.add(docId);
            }
            if (docIds.contains(null)) {
                // 拿不到 docId 的批次无从判断重复，原样放出：宁可多几条也不静默吞数据
                return false;
            }
            if (seenDocs.containsAll(docIds)) {
                return true;
            }
            seenDocs.addAll(docIds);
            return false;
        }
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
