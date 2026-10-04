package com.zzkingcc.domainplane.runtime.stream;

import com.zzkingcc.domainplane.api.agent.CallerContext;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本轮上下文必须<b>随图执行传下去</b>，不能按 key 回查注册表。
 *
 * <p>为什么这条不能靠"读注释"保证：注册表里存的是"当前那一轮"，而节点跑的是"发起它的那一轮"。
 * 两个请求重叠时（网关重试、前端双击、同一会话并发 resume），注册表已经指向后者，
 * 按 key 回查会让前者的节点拿到后者的上下文 —— 工具调用记到别人的租户名下、
 * 模型的 token 流进别人的 SSE 流。这条测试用"注册表指向 B、但配置携带 A"来复现该场景。</p>
 */
class GraphContextCarryingTest {

    private static final String KEY = "default|s1";

    private static StreamContext register(StreamSinkRegistry registry) {
        FluxSink<AgentEvent>[] holder = new FluxSink[1];
        Flux.<AgentEvent>create(s -> {
            holder[0] = s;
            s.onDispose(() -> {
            });
        }).subscribe();
        return registry.register(KEY, holder[0], CallerContext.of("default"));
    }

    @Test
    void 携带在配置里的上下文不会被注册表里的替换掉() {
        StreamSinkRegistry registry = new StreamSinkRegistry();

        // A 请求发起，携带 A 的上下文进图
        StreamContext a = register(registry);
        String traceA = a.traceId();
        RunnableConfig configA = RunnableConfig.builder()
                .threadId(KEY)
                .addMetadata("domainplane.streamContext", a)
                .build();

        // A 还在跑图时，B 来了 —— register 会覆盖注册表并结束 A 的流
        StreamContext b = register(registry);

        assertNotSame(a, b, "两个请求必须是不同的上下文");
        assertSame(b, registry.get(KEY), "注册表现在指向 B —— 这正是串号的前提");
        assertNotSame(traceA, b.traceId());

        // A 的节点从配置里取，取到的必须仍然是 A 自己
        Object carried = configA.metadata("domainplane.streamContext").orElseThrow();
        assertSame(a, carried,
                "A 的节点必须拿到 A 的上下文；拿到 B 就意味着 A 的调用被记到了 B 的租户名下");
        assertEquals(traceA, ((StreamContext) carried).traceId());
    }

    @Test
    void 没有携带上下文时配置文件读得到空Optional() {
        RunnableConfig config = RunnableConfig.builder().threadId(KEY).build();

        assertTrue(config.metadata("domainplane.streamContext").isEmpty(),
                "未携带时应为空，让调用方能识别并告警，而不是拿到一个错位的上下文");
    }

    /** 冒烟：metadata 能承载任意对象（本轮上下文不是 String，序列化走的是对象本身） */
    @Test
    void metadata可承载任意类型的值() {
        AtomicReference<Object> seen = new AtomicReference<>();
        RunnableConfig config = RunnableConfig.builder()
                .threadId(KEY)
                .addMetadata("k", new Object())
                .build();

        seen.set(config.metadata("k").orElse(null));
        assertTrue(seen.get() instanceof Object);
    }
}