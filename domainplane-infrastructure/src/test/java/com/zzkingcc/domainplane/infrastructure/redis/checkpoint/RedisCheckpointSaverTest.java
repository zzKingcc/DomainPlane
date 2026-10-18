package com.zzkingcc.domainplane.infrastructure.redis.checkpoint;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #20：检查点的"读改写"没有互斥、单键永不裁剪、SET 与 EXPIRE 非原子。
 *
 * <p>三条后果分别对应：并发 put 丢检查点（后写覆盖先写，走完一步 resume 找不到断点）、
 * 长会话撑成大 key 且写放大 O(n²)、崩在 SET 与 EXPIRE 之间留下永不过期的键。</p>
 *
 * <p>本测试用内存桩 Redis 把三条契约钉死。</p>
 *
 * @author zzkingcc
 */
@DisplayName("RedisCheckpointSaver：并发不丢、上限裁剪、TTL 原子写入")
class RedisCheckpointSaverTest {

    private static final String THREAD = "default.sales|s-1";

    /** 内存 Redis 桩：只实现本测试用得到的 get / set / delete / scan */
    private static final class StubTemplate extends StringRedisTemplate {
        final Map<String, String> store = new HashMap<>();
        /** 走过"带 TTL 的 SET"重载的键 */
        final List<String> ttlWrites = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger getCalls = new AtomicInteger();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    StubTemplate.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> {
                            getCalls.incrementAndGet();
                            yield store.get(args[0]);
                        }
                        case "set" -> {
                            if (args != null && args.length >= 3) {
                                ttlWrites.add((String) args[0]);
                            }
                            store.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "toString" -> "stub-value-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 ValueOperations 方法：" + method.getName());
                    });
        }
    }

    private static AgentStateFactory<AgentState> factory() {
        return AgentState::new;
    }

    private static RedisCheckpointSaver saver(StubTemplate template, Duration ttl) {
        return new RedisCheckpointSaver(template,
                new ObjectStreamStateSerializer<>(factory()), ttl);
    }

    private static RunnableConfig config() {
        return RunnableConfig.builder().threadId(THREAD).build();
    }

    private static Checkpoint checkpoint(String id) {
        return Checkpoint.builder().id(id).nodeId("n-" + id).nextNodeId("next")
                .state(Map.of("k", "v")).build();
    }

    @Test
    @DisplayName("并发 put 不丢检查点：N 个线程各写一条，最后必须都在")
    void concurrentPutsDoNotLoseCheckpoints() throws Exception {
        StubTemplate template = new StubTemplate();
        RedisCheckpointSaver saver = saver(template, Duration.ofHours(24));

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            int idx = i;
            new Thread(() -> {
                try {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    saver.put(config(), checkpoint("c" + idx));
                } catch (Throwable t) {
                    errors.add(t);
                }
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        TimeUnit.MILLISECONDS.sleep(800);

        assertTrue(errors.isEmpty(), "并发 put 不应抛错：" + errors);
        List<Checkpoint> all = new java.util.ArrayList<>(saver.list(config()));
        assertEquals(threads, all.size(),
                "读改写无互斥时会互相覆盖：后写的那次把先写的检查点整条丢掉");
    }

    @Test
    @DisplayName("超过上限只留最近 200 条：砍头留尾")
    void trimKeepsOnlyTheMostRecent() throws Exception {
        StubTemplate template = new StubTemplate();
        RedisCheckpointSaver saver = saver(template, Duration.ofHours(24));

        int total = 205;
        for (int i = 0; i < total; i++) {
            saver.put(config(), checkpoint("c" + i));
        }

        List<Checkpoint> all = new java.util.ArrayList<>(saver.list(config()));
        assertEquals(200, all.size(), "单键只保留最近的 200 个断点");
        // list 返回的是"最新在前"，所以第 0 条是最后写入的 c204
        assertEquals("c204", all.get(0).getId(), "必须留最新的");
        assertEquals("c5", all.get(all.size() - 1).getId(), "最旧的 5 条应被砍掉");
    }

    @Test
    @DisplayName("TTL 走带过期时间的 SET：不再分成两步")
    void ttlIsWrittenAtomically() throws Exception {
        StubTemplate template = new StubTemplate();
        RedisCheckpointSaver saver = saver(template, Duration.ofHours(24));

        saver.put(config(), checkpoint("c1"));

        String key = "domainplane:graph:checkpoint:" + THREAD;
        assertTrue(template.ttlWrites.contains(key),
                "必须用带 TTL 的 SET 重载：先 SET 再 EXPIRE 的话，崩在两步之间就留下永不过期的键");
    }

    @Test
    @DisplayName("TTL 为 null / 零时按永久处理，且不误走带 TTL 的重载")
    void zeroTtlMeansPermanent() throws Exception {
        StubTemplate template = new StubTemplate();
        RedisCheckpointSaver saver = saver(template, null);

        saver.put(config(), checkpoint("c1"));

        assertTrue(template.ttlWrites.isEmpty(), "未配置 TTL 时不能传 null 进带 TTL 的重载");
        assertEquals("c1", saver.get(config()).orElseThrow().getId(),
                "写进去的断点必须读得出来");
    }
}
