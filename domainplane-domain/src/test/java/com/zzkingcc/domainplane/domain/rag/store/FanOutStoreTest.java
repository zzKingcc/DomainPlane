package com.zzkingcc.domainplane.domain.rag.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * @author zzkingcc
 */
@DisplayName("FanOutStore：双写扇出 / 读走登记权威 / 失败冒泡")
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
    @DisplayName("读路径一律以登记权威(primary)为准，不碰 target")
    void reads_goToPrimaryOnly() {
        RecordingStore primary = new RecordingStore("es");
        RecordingStore target = new RecordingStore("qdrant");
        primary.collections = List.of("kb1", "kb2");
        FanOutStore fan = new FanOutStore(primary, List.of(target));

        assertEquals(List.of("kb1", "kb2"), fan.listCollections(), "集合清单来自 primary");
        assertTrue(target.reads == 0, "读不应落到 target");
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

    // ==================== 桩 ====================

    private static final class RecordingStore implements KnowledgeStore {

        final String name;
        final List<List<KbChunk>> writes = new ArrayList<>();
        final List<String> ensured = new ArrayList<>();
        final List<String> deletedDocIds = new ArrayList<>();
        final List<String> deletedCollections = new ArrayList<>();
        List<String> collections = new ArrayList<>();
        int reads;
        boolean failWrite;

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
            return 0;
        }

        @Override
        public long countByDocId(String collection, String docId) {
            return 0;
        }

        @Override
        public List<KbChunk> readByDocId(String collection, String docId) {
            reads++;
            return List.of();
        }

        @Override
        public List<DocumentAgg> documents(String collection, int limit) {
            reads++;
            return List.of();
        }

        @Override
        public Iterator<List<KbChunk>> scan(String collection, int batch) {
            reads++;
            return List.<List<KbChunk>>of().iterator();
        }
    }
}
