package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #53：模型客户端缓存从"先 get、没有就 build、再 evict+put"改成 {@code computeIfAbsent}。
 *
 * <p>改之前，冷启动的并发首请求会各建一个实例，随后的 evict 互相把对方清掉：
 * 被清掉的那个连接池没有人关，每次并发都漏一份孤儿连接（OkHttp 的线程池 + 连接池）。</p>
 *
 * <p>本测试把三条契约钉死：同指纹复用同一实例、指纹变了才换、并发首建只建一份。</p>
 *
 * @author zzkingcc
 */
@DisplayName("ModelClientFactory：同指纹复用，并发首建不产生孤儿实例")
class ModelClientFactoryConcurrentTest {

    private final ModelClientFactory factory =
            new ModelClientFactory(new ModelAdapterRegistry(new DefaultResourceLoader()));

    private static ModelProfile profile(String alias, String apiKey) {
        return new ModelProfile(
                alias, List.of(ModelProfile.EP_CHAT), List.of("text"), List.of("text"),
                "https://example.test/v1", apiKey, "test-model",
                null, null, null, null, null, List.of(), Map.of());
    }

    @Test
    @DisplayName("同一档案重复取用：返回同一个实例，不重建")
    void sameFingerprintReusesInstance() {
        ModelProfile p = profile("alias-a", "key-a");
        assertSame(factory.streamingChat(p), factory.streamingChat(p), "流式客户端必须被缓存复用");
        assertSame(factory.chat(p), factory.chat(p), "非流式客户端必须被缓存复用");
    }

    @Test
    @DisplayName("指纹变化（换 Key / 换地址）：必须拿到新实例，且新实例稳定")
    void fingerprintChangeBuildsNewInstance() {
        ModelProfile a = profile("alias-b", "key-a");
        ModelProfile b = profile("alias-b", "key-b");

        assertNotSame(factory.streamingChat(a), factory.streamingChat(b),
                "Key 变了必须换客户端，否则改过的 Key 不生效");
        assertSame(factory.streamingChat(b), factory.streamingChat(b), "新指纹同样要能缓存");
    }

    @Test
    @DisplayName("并发首建：N 个线程同时取同一档案，只应得到一个实例")
    void concurrentFirstBuildProducesSingleInstance() throws Exception {
        ModelProfile p = profile("alias-c", "key-c");
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<dev.langchain4j.model.chat.StreamingChatModel> got =
                Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS), "起跑门没放行");
                    got.add(factory.streamingChat(p));
                } catch (Throwable t) {
                    errors.add(t);
                }
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "线程没就绪");
        start.countDown();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> { /* 占位，避免误读 */ }).join(1);
        }
        TimeUnit.MILLISECONDS.sleep(500);

        assertTrue(errors.isEmpty(), "并发取用不应抛错：" + errors);
        assertEquals(threads, got.size(), "每个线程都该拿到一个实例");
        dev.langchain4j.model.chat.StreamingChatModel first = got.get(0);
        for (dev.langchain4j.model.chat.StreamingChatModel m : got) {
            assertSame(first, m, "并发首建必须只建一份：出现第二个实例就意味着有一个会被 evict 掉、连接池没人关");
        }
    }
}
