package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.domain.memory.DualConstraintChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版 {@link ChatMemoryStore}（测试台专供）。
 *
 * <p>刻意<b>不实现</b>任何"可选能力"方法（{@code appendMessage} / {@code tailMessages} /
 * {@code sizeOf} / {@code truncate}）：{@code DualConstraintChatMemory} 是靠方法名探测这些能力的，
 * 走不到就退全量路径。测试台要保证的是"同一套生产代码在纯 JVM 里跑得通"，
 * 而不是"复刻 Redis 的每条 fast path"——那些 fast path 有自己的专项测试。</p>
 */
final class MapChatMemoryStore implements ChatMemoryStore, ChatMemoryProvider {

    private final Map<Object, List<ChatMessage>> data = Collections.synchronizedMap(new HashMap<>());

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return new ArrayList<>(data.getOrDefault(memoryId, List.of()));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        data.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        data.remove(memoryId);
    }

    @Override
    public ChatMemory get(Object memoryId) {
        if (failuresLeft.getAndDecrement() > 0) {
            throw new IllegalStateException("模拟会话记忆读取失败");
        }
        return new DualConstraintChatMemory(memoryId, Integer.MAX_VALUE, Integer.MAX_VALUE, this);
    }

    /**
     * 让接下来 {@code n} 次 {@code get} 抛异常。
     *
     * <p>用来复现"异常发生在长度测量之前"这一场景 —— 那是 {@code memorySizeBefore}
     * 哨兵必须是 {@code null} 而不是 0 的唯一理由：那一刻它还没被赋过值。</p>
     */
    void failNextGets(int n) {
        failuresLeft.set(n);
    }

    private final java.util.concurrent.atomic.AtomicInteger failuresLeft =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 直接读某个会话当前的记忆内容（绕过 {@link DualConstraintChatMemory} 的累计值缓存） */
    List<ChatMessage> raw(Object memoryId) {
        return data.getOrDefault(memoryId, List.of());
    }
}
