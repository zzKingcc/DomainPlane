package com.zzkingcc.domainplane.runtime.stream;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流注册表的"注销先于收尾"不变量。
 *
 * <p>为什么这条重要：{@code complete()} 会同步触发 sink 的 {@code onDispose}，而 onDispose 里靠
 * {@link StreamSinkRegistry#isCurrent} 判断"这是不是我这一轮"。若在注销前就 complete，
 * {@code isCurrent} 仍为 true，于是把停止标志打到<b>同一个 key 上正在执行的那一轮</b>身上：
 * 一个被正常拒绝的请求，亲手摧毁了一个正在跑的合法请求。</p>
 */
class StreamSinkRegistryTest {

    private static final String KEY = "default|s1";

    /**
     * 捕获 {@code Flux.create} 给出的真 {@link FluxSink}，并挂上 onDispose 回调。
     *
     * <p>onDispose 是<b>注册在 sink 上</b>的，与是否已订阅无关；complete() 会同步触发它：
     * 这一点正是被测行为的前提。</p>
     */
    private static final class SinkHolder {
        private FluxSink<AgentEvent> sink;

        void wire(Disposable onDispose) {
            Flux.<AgentEvent>create(s -> {
                this.sink = s;
                s.onDispose(onDispose);
            }).subscribe();
        }
    }

    @Test
    void 先注销再complete不会误判成当前轮() {
        StreamSinkRegistry registry = new StreamSinkRegistry();
        AtomicBoolean stopRequested = new AtomicBoolean(false);

        // 模拟"正在跑"的上轮上下文（它才是被误伤的对象，这里只占位）
        SinkHolder running = new SinkHolder();
        running.wire(() -> {
        });
        registry.register(KEY, running.sink, "default");

        // 本轮：被并发闸门拒绝的那一份上下文
        SinkHolder rejected = new SinkHolder();
        StreamContext[] self = new StreamContext[1];
        rejected.wire(() -> {
            if (registry.isCurrent(KEY, self[0])) {
                stopRequested.set(true);
            }
        });
        StreamContext mine = registry.register(KEY, rejected.sink, "default");
        self[0] = mine;

        // 修复后的顺序：先注销，再收尾
        registry.unregister(KEY, mine);
        mine.complete();

        assertFalse(stopRequested.get(),
                "注销后再 complete，onDispose 里 isCurrent 应为 false，不该打到别人身上");
    }

    @Test
    void 先complete再注销会把停止标志打到同key的其他轮次上() {
        // 复现旧顺序的后果：证明上一条不是"怎么写都过"
        StreamSinkRegistry registry = new StreamSinkRegistry();
        AtomicBoolean stopRequested = new AtomicBoolean(false);

        SinkHolder holder = new SinkHolder();
        StreamContext[] self = new StreamContext[1];
        holder.wire(() -> {
            if (registry.isCurrent(KEY, self[0])) {
                stopRequested.set(true);
            }
        });
        StreamContext ctx = registry.register(KEY, holder.sink, "default");
        self[0] = ctx;

        // 旧顺序：先 complete（同步触发 onDispose，此时还没注销 → isCurrent 仍 true）
        ctx.complete();

        assertTrue(stopRequested.get(),
                "这正是缺陷本身：注销前 complete 会让 onDispose 判定成'还是当前轮'，误置停止标志");
    }
}