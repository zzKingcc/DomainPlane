package com.zzkingcc.domainplane.domain.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆<b>只增不淘汰</b>；{@code capacityFor} 的判定口径只作自检，主线不再用它拒绝开轮。
 *
 * <p>判定只约束"存了多少"，不约束"这轮给模型看多少"；最终回答永远允许写入，
 * 否则会留下有问无答的孤立提问。</p>
 */
class DualConstraintChatMemoryTest {

    /** 内存版 store（一期记忆只增不减，一个 List 就够） */
    private static final class InMemoryStore implements ChatMemoryStore {

        private final Map<Object, List<ChatMessage>> data = new LinkedHashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return new ArrayList<>(data.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.put(memoryId, messages == null ? new ArrayList<>() : new ArrayList<>(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(memoryId);
        }
    }

    private static DualConstraintChatMemory memory(int maxMessages, int maxTokens) {
        return new DualConstraintChatMemory("default|s1", maxMessages, maxTokens, new InMemoryStore());
    }

    /** 40 个汉字 ≈ 60 tokens（CJK 按 1.5/字估算），便于精确构造超限场景 */
    private static final String LONG_TEXT = "甲".repeat(40);

    @Test
    void 超过条数上限也不再淘汰旧消息() {
        DualConstraintChatMemory memory = memory(4, 100_000);

        for (int i = 0; i < 6; i++) {
            memory.add(UserMessage.from("第" + i + "问"));
        }

        assertEquals(6, memory.messages().size(), "一期不再淘汰：旧消息必须原样留着");
        assertEquals("第0问", ((UserMessage) memory.messages().get(0)).singleText(), "最旧的那条不能被挤掉");
    }

    @Test
    void 空记忆可以开新一轮() {
        assertTrue(memory(100, 30_000).capacityFor("你好").canAccept());
    }

    @Test
    void 还能塞下整轮时放行_塞不下整轮就拒绝() {
        DualConstraintChatMemory memory = memory(4, 100_000);

        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));

        // 2 条 + 本轮 2 条 = 4 ≤ 上限 → 放行
        assertTrue(memory.capacityFor("你好").canAccept());

        memory.add(UserMessage.from("再问"));
        memory.add(AiMessage.from("再答"));

        // 4 条 + 本轮 2 条 = 6 > 上限 → 拒绝
        DualConstraintChatMemory.Capacity capacity = memory.capacityFor("你好");
        assertFalse(capacity.canAccept());
        assertTrue(capacity.detail().contains("更换 sessionId"), "拒绝原因必须带上可操作的动作：" + capacity.detail());
    }

    @Test
    void 拒绝是粘性的_满了之后每次都拒绝() {
        DualConstraintChatMemory memory = memory(2, 100_000);
        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));

        DualConstraintChatMemory.Capacity first = memory.capacityFor("你好");
        DualConstraintChatMemory.Capacity second = memory.capacityFor("你好");

        assertFalse(first.canAccept());
        assertFalse(second.canAccept(), "记忆只增不减 → 判定天然粘性，不需要额外记'已封顶'标记位");
        assertEquals(first.detail(), second.detail());
    }

    @Test
    void token用完也拒绝() {
        DualConstraintChatMemory memory = memory(1000, 30);
        memory.add(UserMessage.from(LONG_TEXT));   // 60 + 4 = 64 tokens，已超上限

        DualConstraintChatMemory.Capacity capacity = memory.capacityFor("你好");

        assertFalse(capacity.canAccept());
        assertTrue(capacity.detail().contains("tokens"), capacity.detail());
    }

    @Test
    void 单条提问本身超上限时给出专门文案() {
        DualConstraintChatMemory memory = memory(1000, 10);

        DualConstraintChatMemory.Capacity capacity = memory.capacityFor(LONG_TEXT);

        assertFalse(capacity.canAccept());
        assertTrue(capacity.detail().contains("单条提问"), capacity.detail());
    }

    @Test
    void 出口不受上限约束_最终回答永远能写入() {
        DualConstraintChatMemory memory = memory(2, 30);
        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));
        assertFalse(memory.capacityFor("你好").canAccept(), "入口已拒绝");

        memory.add(AiMessage.from(LONG_TEXT));

        assertEquals(3, memory.messages().size(), "最终回答必须写得进去，否则会留下有问无答的孤立提问");
    }

    @Test
    void 上限放行时detail为空() {
        assertNull(memory(100, 30_000).capacityFor("你好").detail());
    }

    // ==================== D-04：预留条数必须等于真实写入量 ====================

    /**
     * 「视为拒绝」那条路径一轮写 <b>3 条</b>：占位回答 + 提问 + 最终回答。
     * 若入口按 2 条预留，{@code maxMessages - 2} 时就会放行、写完变成 {@code maxMessages + 1}。
     */
    @Test
    void 有待审批断点时按三条预留_否则会写破上限() {
        DualConstraintChatMemory memory = memory(6, 100_000);
        memory.add(UserMessage.from("q1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("q2"));
        memory.add(AiMessage.from("a2"));

        // 4 条：正常轮次 4 + 2 = 6 ≤ 6 → 放行
        assertTrue(memory.capacityFor("你好", false).canAccept());
        // 4 条：带占位 4 + 3 = 7 > 6 → 必须拒绝
        DualConstraintChatMemory.Capacity capacity = memory.capacityFor("你好", true);
        assertFalse(capacity.canAccept(),
                "这一轮会补占位回答（3 条），按 2 条预留就会写破上限");
        assertTrue(capacity.detail().contains("本轮 3 条"),
                "拒绝原因要说明本轮实际占用几条：" + capacity.detail());
    }

    /**
     * 不变式：<b>任何余量下，模拟完整一轮之后总条数都不超过上限</b>。
     *
     * <p>逐个余量扫过去，是因为漏洞只在特定窗口出现：
     * {@code maxMessages - 2} 时"按 2 预留"判定为放行，而实际写入 3 条。
     * 只测一个余量会漏掉这个窗口，只测"判定返回 false 就断言没写"则是同义反复
     * （判定与写入用同一个开关，天然不可能超）。</p>
     */
    @Test
    void 任何余量下跑完一轮都不超上限() {
        int maxMessages = 20;
        for (int slack = 0; slack <= 5; slack++) {
            DualConstraintChatMemory memory = memory(maxMessages, 100_000);
            int before = maxMessages - slack;
            while (memory.messages().size() < before) {
                memory.add(UserMessage.from("q" + memory.messages().size()));
                memory.add(AiMessage.from("a" + memory.messages().size()));
            }
            // 截断到精确条数
            while (memory.messages().size() > before) {
                memory.restore(memory.messages().subList(0, memory.messages().size() - 1));
            }
            int startSize = memory.messages().size();

            // 模拟「视为拒绝」整轮：入口按 3 条判定 → 放行才写
            boolean pending = true;
            if (memory.capacityFor("新问题", pending).canAccept()) {
                if (pending) {
                    memory.add(AiMessage.from("（已取消）"));
                }
                memory.add(UserMessage.from("新问题"));
                memory.add(AiMessage.from("新回答"));
            }

            int endSize = memory.messages().size();
            assertTrue(endSize <= maxMessages,
                    "余量 " + slack + " 条时写到了 " + endSize + " 条，超出上限 " + maxMessages
                            + "（起始 " + startSize + "）—— 记忆只增不淘汰，破了限就永久拒绝");

            // 且判定与实际写入必须一致：放行就一定写满 3 条，不放行就一条不动
            if (endSize > startSize) {
                assertEquals(startSize + 3, endSize, "放行时应恰好写入 3 条");
            }
        }
    }

    @Test
    void 无待审批断点时只按两条预留_不浪费容量() {
        DualConstraintChatMemory memory = memory(6, 100_000);
        memory.add(UserMessage.from("q1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("q2"));
        memory.add(AiMessage.from("a2"));

        // 4 + 2 = 6 ≤ 6 → 放行。若这里也按 3 预留，会白白少开一轮
        assertTrue(memory.capacityFor("你好", false).canAccept());
    }

    @Test
    void 单参重载等价于不补占位() {
        DualConstraintChatMemory memory = memory(6, 100_000);
        memory.add(UserMessage.from("q1"));

        assertEquals(memory.capacityFor("你好", false).detail(), memory.capacityFor("你好").detail());
        assertEquals(memory.capacityFor("你好", false).canAccept(), memory.capacityFor("你好").canAccept());
    }
}
