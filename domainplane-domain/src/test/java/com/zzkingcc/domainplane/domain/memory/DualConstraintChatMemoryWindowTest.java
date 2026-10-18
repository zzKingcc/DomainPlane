package com.zzkingcc.domainplane.domain.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-R5：近况窗口读尾部、回滚剪尾部。
 *
 * <p>锁两条契约：</p>
 * <ul>
 *   <li><b>能走快路径就走</b>：存储提供尾部读 / 取长度 / 截断时，绝不去读全量
 *       （这正是 M4-R5 要消除的那次 O(n) 读）；</li>
 *   <li><b>走不了快路径就退回全量，结果必须一模一样</b>：单测的 Map 存储走的就是这条路，
 *       把它锁住才能保证"换存储实现不换语义"。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M4-R5 记忆窗口与回滚：尾部读 / 取长度 / 截尾")
class DualConstraintChatMemoryWindowTest {

    /** 只实现 ChatMemoryStore 基础方法的存储 —— 快路径能力一个都没有 */
    private static final class PlainStore implements ChatMemoryStore {

        final List<ChatMessage> data = new ArrayList<>();
        int fullReads;

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            fullReads++;
            return new ArrayList<>(data);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.clear();
            if (messages != null) {
                data.addAll(messages);
            }
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.clear();
        }
    }

    /**
     * 带全部快路径能力的存储（对齐 {@code RedisChatMemoryStore} 的签名）。
     *
     * <p>记调用次数是为了证明"快路径被选中"，而不只是"结果对路"。</p>
     */
    private static final class CapableStore implements ChatMemoryStore {

        final List<ChatMessage> data = new ArrayList<>();
        int fullReads;
        int tailCalls;
        int sizeCalls;
        int truncateCalls;
        int appendCalls;

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            fullReads++;
            return new ArrayList<>(data);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.clear();
            if (messages != null) {
                data.addAll(messages);
            }
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.clear();
        }

        public void appendMessage(Object memoryId, ChatMessage message) {
            appendCalls++;
            data.add(message);
        }

        public List<ChatMessage> tailMessages(Object memoryId, int limit) {
            tailCalls++;
            int from = Math.max(0, data.size() - limit);
            return new ArrayList<>(data.subList(from, data.size()));
        }

        public long sizeOf(Object memoryId) {
            sizeCalls++;
            return data.size();
        }

        public void truncate(Object memoryId, int keep) {
            truncateCalls++;
            if (keep <= 0) {
                data.clear();
                return;
            }
            if (keep < data.size()) {
                data.subList(keep, data.size()).clear();
            }
        }
    }

    private static DualConstraintChatMemory memoryOf(ChatMemoryStore store) {
        return new DualConstraintChatMemory("default|s1", 1000, 1_000_000, store);
    }

    // ==================== tail ====================

    @Test
    @DisplayName("tail：存储支持尾部读时走快路径，一次全量读都不发")
    void tailUsesFastPathWhenSupported() {
        CapableStore store = new CapableStore();
        DualConstraintChatMemory memory = memoryOf(store);
        for (int i = 0; i < 10; i++) {
            memory.add(UserMessage.from("问" + i));
            memory.add(AiMessage.from("答" + i));
        }
        int readsBefore = store.fullReads;

        List<ChatMessage> tail = memory.tail(4);

        assertEquals(4, tail.size());
        assertEquals("问8", ((UserMessage) tail.get(0)).singleText(), "取的是最后 4 条且保序");
        assertEquals("答9", ((AiMessage) tail.get(3)).text());
        assertEquals(1, store.tailCalls, "走了一次尾部读");
        assertEquals(readsBefore, store.fullReads, "绝不该为了取尾再读一次全量");
    }

    @Test
    @DisplayName("tail：存储不支持尾部读时退回全量取尾，结果一致")
    void tailFallsBackToFullRead() {
        PlainStore store = new PlainStore();
        DualConstraintChatMemory memory = memoryOf(store);
        for (int i = 0; i < 6; i++) {
            memory.add(UserMessage.from("问" + i));
            memory.add(AiMessage.from("答" + i));
        }

        List<ChatMessage> tail = memory.tail(3);

        assertEquals(3, tail.size());
        assertEquals("答5", ((AiMessage) tail.get(2)).text());
        assertTrue(store.fullReads > 0, "只能靠全量读取尾");
    }

    @Test
    @DisplayName("tail：limit 为 0 / 负数返回空；超过总条数返回全部")
    void tailHandlesBounds() {
        CapableStore store = new CapableStore();
        DualConstraintChatMemory memory = memoryOf(store);
        memory.add(UserMessage.from("只有一条"));

        assertTrue(memory.tail(0).isEmpty());
        assertTrue(memory.tail(-3).isEmpty());
        assertEquals(0, store.tailCalls, "无意义的窗口不该打扰存储");
        assertEquals(1, memory.tail(99).size(), "窗口比历史长时给全部，不报错");
    }

    // ==================== size ====================

    @Test
    @DisplayName("size：有 O(1) 长度就走它；没有才读全量数一遍")
    void sizePrefersStoreLength() {
        CapableStore capable = new CapableStore();
        DualConstraintChatMemory fast = memoryOf(capable);
        fast.add(UserMessage.from("问"));
        fast.add(AiMessage.from("答"));
        assertEquals(2, fast.size());
        assertEquals(1, capable.sizeCalls, "长度来自存储");
        assertEquals(0, capable.fullReads, "不必读全量");

        PlainStore plain = new PlainStore();
        DualConstraintChatMemory slow = memoryOf(plain);
        slow.add(UserMessage.from("问"));
        assertEquals(1, slow.size(), "退回全量数一遍，结果一致");
    }

    // ==================== truncateTo ====================

    @Test
    @DisplayName("truncateTo：剪掉尾部本轮追加的部分，只留前 N 条")
    void truncateToDropsTail() {
        CapableStore store = new CapableStore();
        DualConstraintChatMemory memory = memoryOf(store);
        memory.add(UserMessage.from("旧问"));
        memory.add(AiMessage.from("旧答"));
        int sizeBefore = memory.size();

        // 本轮追加提问 + 回答后回滚
        memory.add(UserMessage.from("本轮问"));
        memory.add(AiMessage.from("本轮答"));
        assertEquals(4, memory.size());

        memory.truncateTo(sizeBefore);

        assertEquals(2, memory.size(), "回滚到本轮提问前");
        assertEquals(1, store.truncateCalls, "走存储侧截尾");
        assertEquals("旧问", ((UserMessage) memory.messages().get(0)).singleText());
    }

    @Test
    @DisplayName("truncateTo：存储不支持截断时退回读全量 + 回写，结果一致")
    void truncateFallsBackToRewrite() {
        PlainStore store = new PlainStore();
        DualConstraintChatMemory memory = memoryOf(store);
        memory.add(UserMessage.from("旧问"));
        memory.add(UserMessage.from("本轮问"));

        memory.truncateTo(1);

        assertEquals(1, memory.messages().size());
        assertEquals("旧问", ((UserMessage) memory.messages().get(0)).singleText());
    }

    @Test
    @DisplayName("truncateTo(0)：清空；keep 不小于现有条数：不动")
    void truncateToHandlesEdges() {
        CapableStore store = new CapableStore();
        DualConstraintChatMemory memory = memoryOf(store);
        memory.add(UserMessage.from("问"));
        memory.add(AiMessage.from("答"));

        memory.truncateTo(99);
        assertEquals(2, memory.size(), "保留量大于现有条数时不该剪掉任何东西");

        memory.truncateTo(0);
        assertEquals(0, memory.size(), "截到 0 = 清空");
    }

    @Test
    @DisplayName("truncateTo 后累计值作废：容量判定按回滚后的真实条数算")
    void truncateInvalidatesCachedCounts() {
        PlainStore store = new PlainStore();
        // 上限压到很小，让"缓存的条数是否被作废"直接体现在判定结果上
        DualConstraintChatMemory memory = new DualConstraintChatMemory("default|s1", 4, 1_000_000, store);
        memory.add(UserMessage.from("问1"));
        memory.add(AiMessage.from("答1"));
        assertTrue(memory.capacityFor("再问").canAccept(), "2 + 2 = 4 条，正好到上限");

        memory.add(UserMessage.from("问2"));
        memory.add(AiMessage.from("答2"));
        assertEquals(4, memory.size());
        assertFalse(memory.capacityFor("再问").canAccept(), "4 + 2 > 4，应已满");

        // 回滚掉第二轮，缓存必须跟着作废，否则下一轮会被"假的满"挡住
        memory.truncateTo(2);
        assertEquals(2, memory.size());
        assertTrue(memory.capacityFor("再问").canAccept(), "回滚后累计值必须重算");
    }
}
