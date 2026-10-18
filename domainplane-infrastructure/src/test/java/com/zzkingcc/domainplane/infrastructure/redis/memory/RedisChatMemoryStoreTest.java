package com.zzkingcc.domainplane.infrastructure.redis.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-R5：记忆存储侧的三条"不必读全量"的命令：尾部读、取长度、截尾。
 *
 * <p>Redis 侧用内存桩，但 {@code LRANGE} 的<b>负下标语义</b>与 {@code LTRIM}
 * 的剪法都按真实行为实现，这里锁的正是"负下标取尾"这件事，
 * 桩如果把它简化成正下标，测出来的就不是生产行为了。</p>
 *
 * @author zzkingcc
 */
@DisplayName("M4-R5 记忆存储：尾部读 / 取长度 / 截尾")
class RedisChatMemoryStoreTest {

    private static final String KEY = "domainplane:chat:memory:default|s1";

    /** 内存版 StringRedisTemplate：只桩 opsForList 与 delete */
    private static class StubRedis extends StringRedisTemplate {

        final Map<String, List<String>> lists = new LinkedHashMap<>();
        /** 每次 range 的 (start, end)，用来证明"取尾"用的是负下标 */
        final List<long[]> rangeArgs = new ArrayList<>();
        int trimCalls;
        int deleteCalls;

        @Override
        @SuppressWarnings("unchecked")
        public ListOperations<String, String> opsForList() {
            return (ListOperations<String, String>) Proxy.newProxyInstance(
                    StubRedis.class.getClassLoader(),
                    new Class<?>[]{ListOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "range" -> {
                            rangeArgs.add(new long[]{(Long) args[1], (Long) args[2]});
                            yield lrange(lists.get((String) args[0]), (Long) args[1], (Long) args[2]);
                        }
                        case "size" -> (long) size((String) args[0]);
                        case "trim" -> {
                            trimCalls++;
                            trim((String) args[0], (Long) args[1], (Long) args[2]);
                            yield null;
                        }
                        case "rightPush" -> {
                            lists.computeIfAbsent((String) args[0], k -> new ArrayList<>())
                                    .add((String) args[1]);
                            yield 1L;
                        }
                        case "rightPushAll" -> {
                            List<String> target = lists.computeIfAbsent((String) args[0],
                                    k -> new ArrayList<>());
                            Collection<?> values = args[1] instanceof Collection<?> c
                                    ? c : List.of((Object[]) args[1]);
                            for (Object v : values) {
                                target.add((String) v);
                            }
                            yield (long) values.size();
                        }
                        case "toString" -> "stub-list-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 ListOperations 方法：" + method.getName());
                    });
        }

        @Override
        public Boolean delete(String key) {
            deleteCalls++;
            return lists.remove(key) != null;
        }

        private int size(String key) {
            List<String> list = lists.get(key);
            return list == null ? 0 : list.size();
        }

        /** 忠实复刻 LRANGE 的负下标语义 */
        private List<String> lrange(List<String> list, long start, long end) {
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            int size = list.size();
            long from = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
            long to = end < 0 ? size + end : Math.min(end, size - 1L);
            if (from > to || to < 0) {
                return List.of();
            }
            return new ArrayList<>(list.subList((int) from, (int) to + 1));
        }

        /** 忠实复刻 LTRIM：只保留 [start, end] */
        private void trim(String key, long start, long end) {
            List<String> list = lists.get(key);
            if (list == null) {
                return;
            }
            List<String> kept = lrange(list, start, end);
            list.clear();
            list.addAll(kept);
        }
    }

    private static RedisChatMemoryStore store(StubRedis redis) {
        // ttl=null：本类测的是读/剪，不测过期
        return new RedisChatMemoryStore(redis, null);
    }

    private static void seed(StubRedis redis, int turns) {
        RedisChatMemoryStore store = store(redis);
        for (int i = 0; i < turns; i++) {
            store.appendMessage("default|s1", UserMessage.from("问" + i));
            store.appendMessage("default|s1", AiMessage.from("答" + i));
        }
    }

    // ==================== tailMessages ====================

    @Test
    @DisplayName("tailMessages：用负下标一次取尾，条数与顺序都对")
    void tailReadsFromTheEnd() {
        StubRedis redis = new StubRedis();
        seed(redis, 5);

        List<ChatMessage> tail = store(redis).tailMessages("default|s1", 4);

        assertEquals(4, tail.size());
        assertEquals("问3", ((UserMessage) tail.get(0)).singleText(),
                "5 轮 = 问0..问4，最后 4 条是 问3 答3 问4 答4");
        assertEquals("答4", ((AiMessage) tail.get(3)).text());
        assertEquals(1, redis.rangeArgs.size());
        assertEquals(-4L, redis.rangeArgs.get(0)[0], "起点必须是负下标（从尾数），不是 0");
        assertEquals(-1L, redis.rangeArgs.get(0)[1]);
    }

    @Test
    @DisplayName("tailMessages：窗口比历史长时给全部；limit<=0 一个命令都不发")
    void tailHandlesBounds() {
        StubRedis redis = new StubRedis();
        seed(redis, 2);

        assertEquals(4, store(redis).tailMessages("default|s1", 99).size(), "窗口比历史长 → 全部");
        assertTrue(store(redis).tailMessages("default|s1", 0).isEmpty());
        assertTrue(store(redis).tailMessages("default|s1", -1).isEmpty());
        assertEquals(1, redis.rangeArgs.size(), "无意义的窗口不该打扰 Redis");
    }

    @Test
    @DisplayName("tailMessages：空会话返回空，不抛")
    void tailOnEmptySession() {
        StubRedis redis = new StubRedis();
        assertTrue(store(redis).tailMessages("default|nobody", 10).isEmpty());
    }

    // ==================== sizeOf ====================

    @Test
    @DisplayName("sizeOf：走 LLEN 语义，空会话是 0")
    void sizeReturnsLength() {
        StubRedis redis = new StubRedis();
        seed(redis, 3);
        assertEquals(6L, store(redis).sizeOf("default|s1"), "3 轮 = 6 条");
        assertEquals(0L, store(redis).sizeOf("default|nobody"));
    }

    // ==================== truncate ====================

    @Test
    @DisplayName("truncate：只保留前 N 条，删掉的是尾部")
    void truncateKeepsHead() {
        StubRedis redis = new StubRedis();
        seed(redis, 3);

        store(redis).truncate("default|s1", 2);

        assertEquals(1, redis.trimCalls, "走 LTRIM");
        List<ChatMessage> left = store(redis).tailMessages("default|s1", 10);
        assertEquals(2, left.size());
        assertEquals("问0", ((UserMessage) left.get(0)).singleText(), "留的是最前面那两条");
        assertEquals("答0", ((AiMessage) left.get(1)).text());
    }

    @Test
    @DisplayName("truncate(0)：退化为整键删除，不留空 List 键")
    void truncateToZeroDeletesKey() {
        StubRedis redis = new StubRedis();
        seed(redis, 2);

        store(redis).truncate("default|s1", 0);

        assertEquals(1, redis.deleteCalls);
        assertEquals(0L, store(redis).sizeOf("default|s1"));
        assertEquals(0, redis.trimCalls, "删键就不必再 LTRIM");
    }

    @Test
    @DisplayName("truncate：keep 大于现有条数时一条都不剪")
    void truncateWithLargeKeepKeepsAll() {
        StubRedis redis = new StubRedis();
        seed(redis, 2);

        store(redis).truncate("default|s1", 99);

        assertEquals(4L, store(redis).sizeOf("default|s1"));
    }

    @Test
    @DisplayName("追加与取尾能对得上：落盘的是可反序列化的 JSON，不是原文")
    void appendThenTailRoundTrips() {
        StubRedis redis = new StubRedis();
        RedisChatMemoryStore store = store(redis);

        store.appendMessage("default|s1", UserMessage.from("你好"));

        List<ChatMessage> tail = store.tailMessages("default|s1", 1);
        assertEquals(1, tail.size());
        assertTrue(tail.get(0) instanceof UserMessage);
        assertEquals("你好", ((UserMessage) tail.get(0)).singleText());
        assertTrue(redis.lists.get(KEY).get(0).contains("你好"), "落盘的是 JSON 元素");
    }
}
