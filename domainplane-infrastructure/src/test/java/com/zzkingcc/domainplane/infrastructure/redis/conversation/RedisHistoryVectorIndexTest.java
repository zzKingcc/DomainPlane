package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.HistoryVectorIndex;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.Cursor.CursorId;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F04-b 归档轮次的向量索引：一会话一 Hash，应用内余弦排序。
 *
 * <p>这里锁四条：</p>
 * <ul>
 *   <li><b>相似度高的在前</b>：语义路的价值全在这一个次序上；</li>
 *   <li><b>维度对不上要跳过</b>：换过向量模型之后旧向量是噪声，让它自然被裁；</li>
 *   <li><b>有上限</b>：老轮次的语义召回价值低，却占着最贵的存储；</li>
 *   <li><b>删域 fail-closed</b>：与关键词索引同一口径，漏删等于删域后还能被勾回来。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("F04-b 历史向量索引：余弦排序 / 维度护栏 / 上限裁剪")
class RedisHistoryVectorIndexTest {

    private static final String SESSION = "default.sales|s-1";

    private static final class StubTemplate extends StringRedisTemplate {
        final Map<String, String> hash = new LinkedHashMap<>();
        final List<String> deletedKeys = new ArrayList<>();
        final List<String> scanKeys = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public HashOperations<String, Object, Object> opsForHash() {
            return (HashOperations<String, Object, Object>) Proxy.newProxyInstance(
                    StubTemplate.class.getClassLoader(),
                    new Class<?>[]{HashOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "put" -> {
                            hash.put(String.valueOf(args[1]), String.valueOf(args[2]));
                            yield null;
                        }
                        case "entries" -> new HashMap<>(hash);
                        case "size" -> (long) hash.size();
                        case "keys" -> Set.copyOf(hash.keySet());
                        case "delete" -> {
                            Object[] victims = args[1] instanceof Object[] arr
                                    ? arr : new Object[]{args[1]};
                            int removed = 0;
                            for (Object v : victims) {
                                if (hash.remove(String.valueOf(v)) != null) {
                                    removed++;
                                }
                            }
                            yield (long) removed;
                        }
                        case "toString" -> "stub-hash-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 HashOperations 方法：" + method.getName());
                    });
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            return true;
        }

        @Override
        public Boolean delete(String key) {
            deletedKeys.add(key);
            return true;
        }

        @Override
        public Long delete(Collection<String> keys) {
            deletedKeys.addAll(keys);
            return (long) keys.size();
        }

        @Override
        public Cursor<String> scan(ScanOptions options) {
            return new ListCursor(scanKeys);
        }
    }

    private static final class ListCursor implements Cursor<String> {
        private final Iterator<String> delegate;

        ListCursor(Collection<String> keys) {
            this.delegate = keys.iterator();
        }

        @Override public boolean hasNext() { return delegate.hasNext(); }
        @Override public String next() {
            if (!delegate.hasNext()) {
                throw new NoSuchElementException();
            }
            return delegate.next();
        }
        @Override public void close() { }
        @Override public CursorId getId() { return CursorId.of(0L); }
        @Override public long getCursorId() { return 0L; }
        @Override public boolean isClosed() { return false; }
        @Override public long getPosition() { return 0L; }
    }

    private static float[] vec(float... values) {
        return values;
    }

    @Test
    @DisplayName("检索按余弦相似度降序：语义更接近的轮次在前")
    void searchRanksByCosine() {
        StubTemplate template = new StubTemplate();
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);

        index.index(SESSION, 1L, vec(1f, 0f, 0f));
        index.index(SESSION, 2L, vec(0f, 1f, 0f));
        index.index(SESSION, 3L, vec(0.9f, 0.1f, 0f));

        List<Long> hits = index.search(SESSION, vec(1f, 0f, 0f), 3);
        assertEquals(List.of(1L, 3L), hits,
                "第 1 轮与查询同向，第 3 轮略偏；第 2 轮正交（余弦 0）属噪声，被最低相似度闸掉");
    }

    @Test
    @DisplayName("维度对不上就跳过：换过向量模型之后的旧向量不能参与排序")
    void mismatchedDimensionIsSkipped() {
        StubTemplate template = new StubTemplate();
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);

        index.index(SESSION, 1L, vec(1f, 0f));            // 旧模型：2 维
        index.index(SESSION, 2L, vec(1f, 0f, 0f));        // 新模型：3 维

        List<Long> hits = index.search(SESSION, vec(1f, 0f, 0f), 5);
        assertEquals(List.of(2L), hits, "旧维度的向量不是噪声，是错数据：必须跳过");
    }

    @Test
    @DisplayName("超过上限裁最老的：老轮次的语义价值低，不该占着最贵的存储")
    void trimsTheOldest() {
        StubTemplate template = new StubTemplate();
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);

        // 裁剪带迟滞（攒够 20 条富余才动一次手），所以要多写满一个迟滞带才会触发
        int total = HistoryVectorIndex.MAX_TURNS_PER_SESSION + 21;
        for (int i = 1; i <= total; i++) {
            index.index(SESSION, (long) i, vec(1f, 0f));
        }

        assertEquals(HistoryVectorIndex.MAX_TURNS_PER_SESSION, index.size(SESSION));
        List<Long> hits = index.search(SESSION, vec(1f, 0f), HistoryVectorIndex.MAX_TURNS_PER_SESSION);
        assertTrue(hits.contains((long) total), "最新的必须还在");
        assertTrue(hits.stream().noneMatch(id -> id <= 21), "最老的 21 条应被裁掉");
    }

    @Test
    @DisplayName("空入参与空索引都返回空，不抛")
    void emptyInputsAreSafe() {
        StubTemplate template = new StubTemplate();
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);

        assertTrue(index.search(SESSION, vec(1f), 5).isEmpty());
        assertTrue(index.search("", vec(1f), 5).isEmpty());
        assertTrue(index.search(SESSION, null, 5).isEmpty());
        index.index(SESSION, 1L, new float[0]);   // 空向量不该写进去
        assertEquals(0, index.size(SESSION));
    }

    @Test
    @DisplayName("删域 fail-closed：空域拒绝，正常域清键")
    void deleteByDomainIsFailClosed() {
        StubTemplate template = new StubTemplate();
        template.scanKeys.add("domainplane:conv:vec:default|s-1");
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);

        assertThrows(ChatMemoryException.class, () -> index.deleteByDomain(null),
                "空域会让 pattern 退化成匹配全部域");
        assertEquals(1, index.deleteByDomain("default"));
        assertEquals(1, index.deleteBySession(SESSION));
        assertEquals(0, index.deleteBySession(""));
    }
}
