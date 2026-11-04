package com.zzkingcc.domainplane.domain.rag.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扇出写 store：同一份切片必须同时落进 keyword 后端与 vector 后端，
 * 否则另一条召回路静默查不到（不报错，只是没结果）。
 *
 * <p>三类会静默毁数据的走偏：① 只写了一个后端（vector 路漏写 → kNN 查不到）；
 * ② target 里把 primary 重复列了导致写入翻倍；
 * ③ 一个后端写失败却没冒泡（"写了一半"留孤儿）。本测试钉这三类。</p>
 *
 * <p><b>第二组（读路径）钉的是"两台 ES"这个形态</b>：切片在两台各写一份，
 * 于是两台都可能持有对方没有的文档。旧实现读路径只问 primary，一台丢了文档就
 * 显示成"这份文档不存在"——看着空、其实有。这里钉住"逐台合并 + 取最大值不求和 +
 * 按 docId 去重"。</p>
 *
 * @author zzkingcc
 */
@DisplayName("FanOutStore：双写扇出 / 读逐后端合并 / 失败冒泡")
class FanOutStoreTest {

    @Test
    @DisplayName("write：同一批切片同时落到 primary 与 target（各一次，不翻倍）")
    void write_fansOutToPrimaryAndTarget() {
        RecordingStore primary = new RecordingStore("es");
        RecordingStore target = new RecordingStore("qdrant");
        FanOutStore fan = new FanOutStore(primary, List.of(primary, target)); // primary 重复列，应去重

        KbChunk c = new KbChunk("c1", "原文", Map.of("doc_id", "d1"), null);
        fan.write("kb1", List.of(c));

        assertEquals(1, primary.writes.size(), "primary 只写一次（去重）");
        assertEquals(1, target.writes.size(), "target 写一次");
        assertEquals(2, fan.targets().size(), "去重后写入目标数 = 2");
    }

    @Test
    @DisplayName("ensureCollection / deleteCollection / deleteByDocId 都扇出到全部目标")
    void lifecycleOperations_fanOut() {
        RecordingStore primary = new RecordingStore("es");
        RecordingStore target = new RecordingStore("qdrant");
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        fan.ensureCollection("kb1", 128);
        fan.deleteByDocId("kb1", "d1");
        boolean deleted = fan.deleteCollection("kb1");

        assertTrue(deleted);
        assertEquals(List.of("kb1:128"), primary.ensured);
        assertEquals(List.of("kb1:128"), target.ensured);
        assertEquals(List.of("d1"), primary.deletedDocIds);
        assertEquals(List.of("d1"), target.deletedDocIds);
        assertEquals(List.of("kb1"), primary.deletedCollections);
        assertEquals(List.of("kb1"), target.deletedCollections);
    }

    @Test
    @DisplayName("listCollections：取全部后端的并集（任一台的孤儿集合不会被藏起来）")
    void listCollections_unionsAllTargets() {
        RecordingStore primary = new RecordingStore("es-a");
        RecordingStore target = new RecordingStore("es-b");
        primary.collections = List.of("kb1", "kb2");
        target.collections = List.of("kb2", "kb3");
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        assertEquals(List.of("kb1", "kb2", "kb3"), fan.listCollections());
    }

    @Test
    @DisplayName("count / countByDocId：取最大值不求和（扇出写入会让同一份切片在两台各存一份）")
    void counts_takeMaxNotSum() {
        RecordingStore primary = new RecordingStore("es-a");
        RecordingStore target = new RecordingStore("es-b");
        primary.total = 7;
        target.total = 7;
        primary.docTotal = 3;
        target.docTotal = 3;
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        assertEquals(7, fan.count("kb1"), "双写各 7 片，答案仍是 7 而不是 14");
        assertEquals(3, fan.countByDocId("kb1", "d1"));
    }

    @Test
    @DisplayName("documents：按 docId 合并两台（另一台独有的文档不会被漏掉）")
    void documents_mergesAcrossTargets() {
        RecordingStore primary = new RecordingStore("es-a");
        RecordingStore target = new RecordingStore("es-b");
        primary.docs = List.of(new DocumentAgg("d1", "a.pdf", "default", 2),
                new DocumentAgg("d2", "b.pdf", "default", 1));
        target.docs = List.of(new DocumentAgg("d1", "a.pdf", "default", 2),
                new DocumentAgg("d9", "only-on-b.pdf", "default", 4));
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        List<DocumentAgg> merged = fan.documents("kb1", 100);
        assertEquals(3, merged.size(), "d1/d2/d9 三条，d1 不重复");
        assertTrue(merged.stream().anyMatch(d -> "d9".equals(d.docId())),
                "只存在于第二台的文档必须出现");
    }

    @Test
    @DisplayName("readByDocId：权威那台返回空时继续问下一台")
    void readByDocId_fallsThroughToNextTarget() {
        RecordingStore primary = new RecordingStore("es-a");
        RecordingStore target = new RecordingStore("es-b");
        target.chunks = List.of(new KbChunk("c1", "只在 b 上的切片", Map.of("doc_id", "d9"), null));
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        List<KbChunk> got = fan.readByDocId("kb1", "d9");
        assertEquals(1, got.size());
        assertEquals("只在 b 上的切片", got.get(0).text());
    }

    @Test
    @DisplayName("scan：两台串流，按 docId 整组去重（同文档不出现两次，也不拼成半份）")
    void scan_mergesAndDedupesByDocId() {
        RecordingStore primary = new RecordingStore("es-a");
        RecordingStore target = new RecordingStore("es-b");
        primary.scans = List.of(
                List.of(chunk("c1", "d1"), chunk("c2", "d1")),
                List.of(chunk("c3", "d2")));
        target.scans = List.of(
                List.of(chunk("c1", "d1"), chunk("c2", "d1")),
                List.of(chunk("c9", "d9")));
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        List<String> docIds = new ArrayList<>();
        Iterator<List<KbChunk>> it = fan.scan("kb1", 100);
        while (it.hasNext()) {
            for (KbChunk c : it.next()) {
                docIds.add(c.metadata().get("doc_id"));
            }
        }
        assertEquals(List.of("d1", "d1", "d2", "d9"), docIds,
                "d1 只从第一台出现一次（整组），第二台独有的 d9 仍然要出现");
    }

    @Test
    @DisplayName("单台后端时读路径与原行为逐字一致")
    void singleTarget_readsIdentical() {
        RecordingStore primary = new RecordingStore("es");
        primary.collections = List.of("kb1");
        primary.total = 5;
        primary.docs = List.of(new DocumentAgg("d1", "a.pdf", "default", 5));
        FanOutStore fan = new FanOutStore(primary, List.of());

        assertEquals(List.of("kb1"), fan.listCollections());
        assertEquals(5, fan.count("kb1"));
        assertEquals(1, fan.documents("kb1", 10).size());
        assertTrue(fan.targets().size() == 1, "只有 primary 一个写入目标");
    }

    @Test
    @DisplayName("任一后端写入失败即整体失败（调用方据此回滚，不留孤儿）")
    void write_failurePropagates() {
        RecordingStore primary = new RecordingStore("es");
        RecordingStore target = new RecordingStore("qdrant");
        target.failWrite = true;
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        assertThrows(StoreAccessException.class,
                () -> fan.write("kb1", List.of(new KbChunk("c1", "x", Map.of(), null))),
                "一个后端写失败必须冒泡，否则'写了一半'留孤儿");
        assertEquals(1, primary.writes.size(), "primary 已写（回滚走 deleteByDocId 扇出去掉）");
    }

    @Test
    @DisplayName("读路径一台抛异常：跳过它，好的那台照常返回")
    void reads_failOpenPerTarget() {
        RecordingStore primary = new RecordingStore("es-a");
        RecordingStore target = new RecordingStore("es-b");
        primary.failRead = true;          // 权威那台坏了
        target.collections = List.of("kb1");   // 另一台好好的
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        assertEquals(List.of("kb1"), fan.listCollections(), "坏的那台跳过，好的那台照常");
        assertEquals(List.of(), fan.documents("kb1", 10), "两台都拿不到文档时返回空而不是抛");
    }

    // ==================== 桩 ====================

    private static KbChunk chunk(String id, String docId) {
        return new KbChunk(id, "text-" + id, Map.of("doc_id", docId), null);
    }

    private static final class RecordingStore implements KnowledgeStore {

        final String name;
        final List<List<KbChunk>> writes = new ArrayList<>();
        final List<String> ensured = new ArrayList<>();
        final List<String> deletedDocIds = new ArrayList<>();
        final List<String> deletedCollections = new ArrayList<>();
        List<String> collections = new ArrayList<>();
        List<DocumentAgg> docs = List.of();
        List<KbChunk> chunks = List.of();
        List<List<KbChunk>> scans = List.of();
        long total;
        long docTotal;
        boolean failWrite;
        boolean failRead;

        RecordingStore(String name) {
            this.name = name;
        }

        @Override
        public StoreCapabilities capabilities() {
            return new StoreCapabilities(true, true, true, true, true);
        }

        @Override
        public boolean exists(String collection) {
            return collections.contains(collection);
        }

        @Override
        public void ensureCollection(String collection, int dims) {
            ensured.add(collection + ":" + dims);
        }

        @Override
        public boolean deleteCollection(String collection) {
            deletedCollections.add(collection);
            return true;
        }

        @Override
        public List<String> listCollections() {
            if (failRead) {
                throw new IllegalStateException("模拟枚举失败");
            }
            return collections;
        }

        @Override
        public Integer currentVectorDims(String collection) {
            return null;
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public void diagnose(String collection) {
        }

        @Override
        public void write(String collection, List<KbChunk> chunks) {
            if (failWrite) {
                throw new StoreAccessException("写入[" + name + "]失败：模拟后端不可用");
            }
            writes.add(chunks);
        }

        @Override
        public int deleteByDocId(String collection, String docId) {
            deletedDocIds.add(docId);
            return 1;
        }

        @Override
        public Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes) {
            return Set.of();
        }

        @Override
        public long count(String collection) {
            if (failRead) {
                throw new IllegalStateException("模拟计数失败");
            }
            return total;
        }

        @Override
        public long countByDocId(String collection, String docId) {
            return docTotal;
        }

        @Override
        public List<KbChunk> readByDocId(String collection, String docId) {
            return chunks;
        }

        @Override
        public List<DocumentAgg> documents(String collection, int limit) {
            if (failRead) {
                throw new IllegalStateException("模拟文档列表失败");
            }
            return docs;
        }

        @Override
        public Iterator<List<KbChunk>> scan(String collection, int batch) {
            return scans.iterator();
        }
    }
}
