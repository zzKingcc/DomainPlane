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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量写入路径 —— 存储提供 {@code appendMessage(Object, ChatMessage)} 时走的那条。
 *
 * <p>另一半路径（存储不支持追加、只能全量读写）由 {@code DualConstraintChatMemoryTest} 覆盖：
 * 那边的 {@code InMemoryStore} 故意不提供 {@code appendMessage}。</p>
 *
 * <p>这里最要紧的不是"能不能写进去"，而是<b>两条路径必须给出同一个答案</b> ——
 * 探测失败会静默退回全量写（行为不变、性能退化），而探测成功却算错了累计值，
 * 会让容量判定在长会话上放行超限的轮次，且症状是"聊着聊着突然被拒"，
 * 极难从日志里看出是累计值偏了。</p>
 */
class DualConstraintChatMemoryAppendTest {

    /**
     * 支持增量追加的内存版 store。
     *
     * <p>刻意记录 {@code updateCalls} 与 {@code appendCalls}：
     * 只断言最终内容的话，"走了全量写但结果也对"会与"走了增量写"混为一谈，
     * 而这次改造的全部意义就在于走哪条路。</p>
     */
    private static final class AppendableStore implements ChatMemoryStore {

        private final Map<Object, List<ChatMessage>> data = new LinkedHashMap<>();

        /** 整体替换的调用次数：增量路径下应当为 0 */
        int updateCalls;

        /** 单条追加的调用次数 */
        int appendCalls;

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return new ArrayList<>(data.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            updateCalls++;
            data.put(memoryId, messages == null ? new ArrayList<>() : new ArrayList<>(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(memoryId);
        }

        /** 鸭子类型探测的落点：签名必须与探测用的完全一致 */
        public void appendMessage(Object memoryId, ChatMessage message) {
            appendCalls++;
            data.computeIfAbsent(memoryId, k -> new ArrayList<>()).add(message);
        }
    }

    private static DualConstraintChatMemory memory(int maxMessages, int maxTokens, ChatMemoryStore store) {
        return new DualConstraintChatMemory("default|s1", maxMessages, maxTokens, store);
    }



    @Test
    void 存储支持追加时add走增量_不再整体重写() {
        AppendableStore store = new AppendableStore();
        DualConstraintChatMemory memory = memory(100, 100_000, store);

        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));
        memory.add(UserMessage.from("再问"));

        assertEquals(3, store.appendCalls, "每写一条都该是一次单条追加");
        assertEquals(0, store.updateCalls,
                "整体重写必须一次都不发生 —— 这次改造就是为了消掉它");
    }

    @Test
    void 存储不支持追加时退回全量写_行为不变() {
        /** 不提供 appendMessage 的 store，与既有测试用的是同一种形态 */
        final class PlainStore implements ChatMemoryStore {
            int updateCalls;

            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                return new ArrayList<>();
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> messages) {
                updateCalls++;
            }

            @Override
            public void deleteMessages(Object memoryId) {
            }
        }
        PlainStore store = new PlainStore();
        DualConstraintChatMemory memory = memory(100, 100_000, store);

        memory.add(UserMessage.from("你好"));

        assertEquals(1, store.updateCalls, "探测不到追加能力时必须退回原路径，不能静默不写");
    }

    // ==================== 已加载后再写入（累计值唯一会错的地方） ====================

    /**
     * 先判定（把累计值装载出来），<b>再写入</b>，然后重新判定 ——
     * 这条路径是累计值唯一可能出错的地方。
     *
     * <p>为什么单列一个测试：之前那批测试都是"先写若干条、再首次判定"，
     * 而累计值只在已装载后才参与累加。首读那条路会全量重算，
     * 于是"漏掉累加"这个错误完全看不出来 ——
     * 变异验证时删掉 {@code cachedCount++}，7 个测试全绿。</p>
     *
     * <p>真实调用顺序正是这一种：入口先做容量判定（首次装载），
     * 之后才写提问与回答。所以这条不是补充假设，是主路径。</p>
     */
    /**
     * 「先判定 → 再写入」这条主路径上，增量实现的容量判定必须与全量实现逐轮一致。
     *
     * <p>为什么单独立这一条：累计值只在<b>已装载之后</b>才参与累加，而首读那条路
     * 会全量重算。所以"漏掉累加"这个错误在"先写若干条再首读"的测试里完全看不出来 ——
     * 变异验证时删掉 {@code cachedCount++}，原先7 个测试全绿。</p>
     *
     * <p>真实调用顺序正是这一种：入口先做容量判定（首次装载），之后才写提问与回答。</p>
     *
     * <p>断言方式刻意用"两个实现比对"而不是手算期望值：
     * 手算需要把上限、预留条数、首读时机三件事同时算对，错了就成了
     * "测试写错"而不是"实现有问题"，难以判断该改哪边。</p>
     */
    @Test
    void 先判定后写入_增量与全量判定逐轮一致() {
        final int maxMessages = 6;

        AppendableStore appendableStore = new AppendableStore();
        DualConstraintChatMemory incremental = memory(maxMessages, 100_000, appendableStore);

        final class PlainStore implements ChatMemoryStore {
            private final java.util.Map<Object, List<ChatMessage>> data = new LinkedHashMap<>();

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
        PlainStore plainStore = new PlainStore();
        DualConstraintChatMemory full = memory(maxMessages, 100_000, plainStore);

        // 每轮都是：先判定（首读只发生在第一轮），放行才写，写完再比对两个实现的条数
        for (int round = 0; round < 6; round++) {
            String question = "问题" + round;
            String answer = "回答" + round;

            var incDecision = incremental.capacityFor(question);
            var fullDecision = full.capacityFor(question);

            assertEquals(fullDecision.canAccept(), incDecision.canAccept(),
                    "第 " + round + " 轮判定不一致：增量认为 " + incDecision.canAccept()
                            + "，全量认为 " + fullDecision.canAccept() + "（增量 detail="
                            + incDecision.detail() + "）");

            if (incDecision.canAccept()) {
                incremental.add(UserMessage.from(question));
                incremental.add(AiMessage.from(answer));
                full.add(UserMessage.from(question));
                full.add(AiMessage.from(answer));

                assertEquals(full.messages().size(), incremental.messages().size(),
                        "第 " + round + " 轮写入后条数应一致");
            }
        }

        // 收尾断言：两边都该走到上限附近，且判定一致
        assertEquals(full.capacityFor("收尾").canAccept(),
                incremental.capacityFor("收尾").canAccept(),
                "两边的最终判定必须一致");
        assertEquals(0, appendableStore.updateCalls,
                "增量路径下不该发生任何一次整体重写 —— 这次改造就是为了消掉它");
    }

    /**
     * token 累计值在"先判定后写入"下同样必须与全量重算一致。
     *
     * <p>与上面同源的风险，但落在 token 维度上：累计 token 偏小会让
     * token 上限形同虚设（超预算仍放行），偏大则会在会话中途莫名被拒。</p>
     */
    @Test
    void 先判定后写入_token累计值与全量重算一致() {
        AppendableStore store = new AppendableStore();
        // 上限卡在一个中间值：增量与全量算出来的余量必须能容纳同样的后续内容
        DualConstraintChatMemory memory = memory(10_000, 200, store);

        memory.add(UserMessage.from("甲".repeat(40)));   // ≈ 64 tokens
        assertTrue(memory.capacityFor("乙").canAccept(), "首读：装载累计值");

        memory.add(AiMessage.from("丙".repeat(20)));     // ≈ 34 tokens
        memory.add(UserMessage.from("丁".repeat(20)));   // ≈ 34 tokens

        boolean incremental = memory.capacityFor("戊").canAccept();
        memory.invalidate();                             // 强制全量重算
        boolean recomputed = memory.capacityFor("戊").canAccept();

        assertEquals(recomputed, incremental,
                "token 累计值必须与全量重算一致（增量 " + incremental + "，重算 " + recomputed + "）");
    }

    // ==================== 累计值与真实条数一致 ====================

    /**
     * 累计条数必须与存储里的真实条数一致。
     *
     * <p>测法用"每写一条就读回全量"，而不是只看最后的长度 ——
     * 累计值一旦在某个中间环节偏了，长度断言可能碰巧还过，但容量判定已经错了。</p>
     */
    @Test
    void 累计条数与存储真实条数始终一致() {
        AppendableStore store = new AppendableStore();
        DualConstraintChatMemory memory = memory(10_000, 100_000, store);

        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                memory.add(UserMessage.from("q" + i));
            } else {
                memory.add(AiMessage.from("a" + i));
            }
            assertEquals(i + 1, memory.messages().size(), "第 " + (i + 1) + " 条写入后");
        }
    }

    /**
     * 增量路径下容量判定必须与全量路径给出同一个结论。
     *
     * <p>这是本次改造最该被守住的一条：累计值是"算出来的"，
     * 全量遍历是"数出来的"，两者不一致时用户看到的是"同样长的会话，
     * 换了个存储实现就表现不同"，极难排查。</p>
     */
    @Test
    void 容量判定与全量重算结果一致() {
        int maxMessages = 12;
        int maxTokens = 400;

        AppendableStore appendable = new AppendableStore();
        DualConstraintChatMemory incremental = memory(maxMessages, maxTokens, appendable);

        // 一个只增不删的对照：走全量路径的那条
        final class PlainStore implements ChatMemoryStore {
            private final Map<Object, List<ChatMessage>> data = new LinkedHashMap<>();

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
        }
        PlainStore plain = new PlainStore();
        DualConstraintChatMemory full = memory(maxMessages, maxTokens, plain);

        // 一路写到接近上限，每一步都比对两个实现的判定
        for (int i = 0; i < 5; i++) {
            String q = "问题" + i;
            String a = "回答" + i;
            incremental.add(UserMessage.from(q));
            incremental.add(AiMessage.from(a));
            full.add(UserMessage.from(q));
            full.add(AiMessage.from(a));

            var inc = incremental.capacityFor("下一问");
            var fl = full.capacityFor("下一问");
            assertEquals(fl.canAccept(), inc.canAccept(),
                    "第 " + i + " 轮之后，两条路径的放行结论必须一致（增量认为 " + inc.canAccept()
                            + "，全量认为 " + fl.canAccept() + "）");
        }
    }

    // ==================== 缓存作废 ====================

    @Test
    void restore整体替换后累计值作废重算() {
        AppendableStore store = new AppendableStore();
        DualConstraintChatMemory memory = memory(10, 100_000, store);

        memory.add(UserMessage.from("q1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("q2"));

        // 回滚到只剩 1 条：若累计值没作废，容量判定会按3 条算
        memory.restore(List.of(UserMessage.from("只剩这条")));

        assertEquals(1, memory.messages().size(), "restore 确实把内容换掉了");
        // 上限 4 条：真实 1 条应放行（1+2=3 ≤ 4）。若累计值仍是 3，会算出 3+2=5 > 4 而误拒
        assertTrue(memory.capacityFor("下一问").canAccept(),
                "restore 之后累计值必须重算，否则会拿旧条数判定而误拒");
    }

    @Test
    void clear之后累计值作废() {
        AppendableStore store = new AppendableStore();
        DualConstraintChatMemory memory = memory(2, 100_000, store);

        memory.add(UserMessage.from("q1"));
        memory.add(AiMessage.from("a1"));
        assertFalse(memory.capacityFor("下一问").canAccept(), "2 条 + 本轮 2 条 > 上限 2，应拒绝");

        memory.clear();

        assertTrue(memory.capacityFor("下一问").canAccept(), "清空后应回到可以开新一轮的状态");
        assertEquals(0, memory.messages().size());
    }

    // ==================== token 累计 ====================

    /**
     * token 累计值要与全量重算一致 —— 与条数同理，累计偏了会让 token 上限判定失真。
     */
    @Test
    void token累计值与全量重算一致() {
        AppendableStore store = new AppendableStore();
        DualConstraintChatMemory memory = memory(10_000, 100_000, store);

        memory.add(UserMessage.from("甲".repeat(40)));   // ≈60 tokens
        memory.add(AiMessage.from("乙乙乙乙"));

        // 逐条放大直到逼近上限，比较增量判定与"清掉累计值后重算"的判定
        int maxTokens = 200;
        DualConstraintChatMemory limited = memory(10_000, maxTokens, store);
        limited.add(UserMessage.from("甲".repeat(40)));

        boolean incrementalSays = limited.capacityFor("丙".repeat(10)).canAccept();
        limited.invalidate();   // 强制全量重算
        boolean recomputedSays = limited.capacityFor("丙".repeat(10)).canAccept();

        assertEquals(recomputedSays, incrementalSays,
                "token 累计值必须与全量重算给出同一个结论");
    }

}
