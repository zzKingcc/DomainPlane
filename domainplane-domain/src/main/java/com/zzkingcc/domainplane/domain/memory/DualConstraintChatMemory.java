package com.zzkingcc.domainplane.domain.memory;

import com.zzkingcc.domainplane.common.util.CjkWidth;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 双约束会话记忆（消息条数 + Token 估算）
 *
 * 一期语义：只增不淘汰。记忆是长期存储（Redis + RDB/AOF），不会为了塞下新消息而丢弃最旧的
 * —— 静默丢历史会让用户"以为还记得"。到上限后改由入口拒绝新一轮：该会话作废，调用方必须换
 * {@code sessionId} 重新开始（判定见 {@link #capacityFor(String)}）。
 *
 * 上限只约束"能不能开新一轮"，不约束"能不能收尾"：最终回答永远允许写入，否则会留下有问无答的
 * 孤立提问。因此一轮结束后总量可能略微超过上限（回答长度不可预估）。
 *
 * 性能：写入与容量判定都做成增量，不在每条消息上遍历全量。
 *
 *   • 写入：存储若支持单条追加（{@link #appendStore} 探测得到），一次 {@code RPUSH} 完成；
 *     否则退回"读全量 → 加一条 → 写全量"，行为不变、只是慢一些。
 *   • 容量：条数与 token 量都靠累计值给出准数（记忆只增不减，累加即可），
 *     只有本次会话的首读需要全量算一遍，之后每次 {@code add} 都同步累加。
 *
 * 改这一层是因为原先每写一条消息都要全量读写一次，而一次对话要走六遍这条路
 * （容量判定、拍快照、写提问、读历史、写回答），复杂度 O(全量) —— 会话越长越慢。
 * 这个开销与 langchain4j 无关，出在"把整段历史反复读出再写回"这个做法上。
 *
 * @author zzkingcc
 */
public class DualConstraintChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(DualConstraintChatMemory.class);

    private final Object id;
    private final int maxMessages;
    private final int maxTokens;
    private final ChatMemoryStore store;

    /**
     * 存储是否支持单条追加。
     *
     * 用方法名探测而不是 instanceof：可追加的实现落在基础设施层
     * （{@code RedisChatMemoryStore}），而领域层不能反向依赖它 ——
     * 声明一个共享接口也一样会把基础设施拖进领域层的依赖里。
     * 探测只在本对象构造时做一次，运行期零开销；探测不到就退回全量写路径，
     * 单测里的 Map 实现走的正是那条，行为完全不变。
     */
    private final boolean appendable;

    /** 是否已从存储装载过一次（装载后才能给出累计值的准数） */
    private boolean loaded;

    /** 累计条数（loaded 为 true 时有效） */
    private int cachedCount;

    /** 累计 token 量，含每条消息的结构开销（loaded 为 true 时有效） */
    private int cachedTokens;

    /**
     * @param id          会话 ID
     * @param maxMessages 消息条数上限
     * @param maxTokens   Token 估算上限
     * @param store       持久化存储（如 RedisChatMemoryStore）
     */
    public DualConstraintChatMemory(Object id, int maxMessages, int maxTokens, ChatMemoryStore store) {
        this.id = id;
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.store = store;
        this.appendable = detectAppendable(store);
    }

    /**
     * 探测存储是否提供"追加一条"的能力。
     *
     * 只认签名完全一致的 {@code appendMessage(Object, ChatMessage)}：
     * 名字相近但参数不同的方法被调用会在运行期抛 {@code IllegalArgumentException}，
     * 那比退回全量写路径糟得多。
     */
    private static boolean detectAppendable(ChatMemoryStore store) {
        try {
            store.getClass().getMethod("appendMessage", Object.class, ChatMessage.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (RuntimeException e) {
            // 反射在特定类加载环境下可能被拒绝：探测失败按"不支持"处理，退回全量写路径
            return false;
        }
    }

    @Override
    public Object id() {
        return id;
    }

    /** 每条消息的结构开销 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /**
     * 一条正常轮次写入记忆的消息条数：提问 + 最终回答。
     *
     * 存在待审批断点时还要多补一条占位回答，判定入口会按 3 条预留
     * （见 {@link #capacityFor(String, boolean)}）。
     */
    private static final int MESSAGES_PER_ROUND = 2;

    @Override
    public void add(ChatMessage message) {
        // 纯追加：不淘汰、不判上限。上限由入口的 capacityFor(...) 把关，
        // 出口（最终回答）永远允许写入。
        if (appendable) {
            // 增量写：一次 RPUSH 落盘，不再"读全量 → 写全量"
            invokeAppend(message);
            // 累计值同步累加：记忆只增不减，累加即可给出准数，
            // 免得每次容量判定都回存储重算一遍。
            if (loaded) {
                cachedCount++;
                cachedTokens += CjkWidth.estimateTokens(extractText(message)) + PER_MESSAGE_OVERHEAD;
            }
            return;
        }

        // 存储不支持追加（单测的 Map 实现等）：退回全量写，行为与改造前一致
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        messages.add(message);
        store.updateMessages(id, messages);
        if (loaded) {
            cachedCount = messages.size();
            cachedTokens = estimateTokens(messages);
        }
    }

    /**
     * 反射调用存储的 {@code appendMessage}。
     *
     * 每写一条都 {@code getMethod} 一次确实有开销，但相比一次 Redis 往返与整段
     * 历史的重写，这个量级可以忽略；把它缓存成 {@link java.lang.reflect.Method}
     * 又会引入字段与构造顺序上的麻烦，不值当。
     */
    private void invokeAppend(ChatMessage message) {
        try {
            store.getClass()
                    .getMethod("appendMessage", Object.class, ChatMessage.class)
                    .invoke(store, id, message);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("追加会话记忆失败", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("追加会话记忆失败", e);
        }
    }

    /**
     * 入口容量判定：把"本轮提问"算进去，判断这个会话还能不能再开一轮。
     *
     * 判定是粘性的：记忆只增不减，所以一旦满了，之后每次判定都会失败 ——
     * 不需要额外记"已封顶"标记位。（唯一能解除的是 Redis 数据丢失，所以 RDB+AOF 是这套语义的前提。）
     *
     * 条数按整轮预留判定，让"100 条"成为真正的硬上限；Token 只按提问判定
     * （回答长度不可预估），因此允许一轮结束后轻度超出。
     */
    public Capacity capacityFor(String pendingQuestion) {
        return capacityFor(pendingQuestion, false);
    }

    /**
     * 入口容量判定，显式告知本轮是否还要补一条占位回答。
     *
     * 正常轮次写 2 条（提问 + 最终回答）；待审批断点路径会先补一条占位回答再走完本轮，实际写 3 条。
     * 一律按 2 预留会在上限临界处误放行（如 98 + 2 > 100 为假 → 写完 101 条），
     * 因只增不淘汰而永久超上限、会话作废。占位回答是把上一轮补成完整轮次的必需步骤，
     * 其存在必须由调用方告知，不能在此假定。
     *
     * @param pendingQuestion      本轮提问
     * @param willWritePlaceholder 本轮是否还会额外补一条占位回答（存在待审批断点时为 {@code true}）
     */
    public Capacity capacityFor(String pendingQuestion, boolean willWritePlaceholder) {
        Counts counts = counts();
        int currentTokens = counts.tokens();
        int questionTokens = CjkWidth.estimateTokens(pendingQuestion) + PER_MESSAGE_OVERHEAD;
        int reserved = willWritePlaceholder
                ? MESSAGES_PER_ROUND + 1
                : MESSAGES_PER_ROUND;

        if (questionTokens > maxTokens) {
            return Capacity.full("单条提问已超过会话记忆上限（约 " + questionTokens + " tokens > 上限 "
                    + maxTokens + "），请缩短内容，或更换 sessionId 开启新会话");
        }
        if (counts.messages() + reserved > maxMessages) {
            return Capacity.full("会话记忆已达上限（当前 " + counts.messages() + " 条 + 本轮 " + reserved
                    + " 条 > 上限 " + maxMessages + " 条 ≈ " + (maxMessages / (MESSAGES_PER_ROUND + 1))
                    + " 轮问答），请更换 sessionId 开启新会话");
        }
        if (currentTokens + questionTokens > maxTokens) {
            return Capacity.full("会话记忆已达上限（当前约 " + currentTokens + " tokens + 本轮提问约 "
                    + questionTokens + " > 上限 " + maxTokens + " tokens），请更换 sessionId 开启新会话");
        }
        return Capacity.ok();
    }

    /**
     * 当前条数与 token 累计值。
     *
     * 首次读取时全量算一遍并记住，之后由 {@link #add(ChatMessage)} 同步累加。
     * 记忆只增不减、且本类的写入全部经过 {@code add}，所以累计值始终是准数 ——
     * 唯一的例外是别处直接改了存储（当前没有这种调用方），那种情况下显式调
     * {@link #invalidate()} 作废缓存。
     */
    private Counts counts() {
        if (!loaded) {
            List<ChatMessage> messages = store.getMessages(id);
            this.cachedCount = messages.size();
            this.cachedTokens = estimateTokens(messages);
            this.loaded = true;
        }
        return new Counts(cachedCount, cachedTokens);
    }

    /**
     * 作废累计值缓存 —— 下次读取时重新全量装载。
     *
     * {@link #restore(List)} 整体替换记忆后必须调它：那份内容不是逐条累加来的。
     */
    public void invalidate() {
        this.loaded = false;
        this.cachedCount = 0;
        this.cachedTokens = 0;
    }

    /** 条数与 token 量的快照 */
    private record Counts(int messages, int tokens) {
    }

    /** 入口容量判定结果；{@code canAccept=false} 时 {@code detail} 是可直接回给调用方的拒绝原因 */
    public record Capacity(boolean canAccept, String detail) {

        static Capacity ok() {
            return new Capacity(true, null);
        }

        static Capacity full(String detail) {
            return new Capacity(false, detail);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        /* 必须回存储拿真值而不是返回缓存：调用方会遍历、拷贝（{@code List.copyOf}）、
           交给图编排组装请求体，给一份共享的可变列表会被调用方改坏，
           也会让"调用方改了自己的副本"变成"污染了记忆"。
           读是一次 LRANGE，代价是一次往返，与是否缓存无关。 */
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
        invalidate();
    }

    /**
     * 把记忆整体替换为给定内容(任务停止时回滚用)
     *
     * @param messages 目标内容;null 视为清空
     */
    public void restore(List<ChatMessage> messages) {
        List<ChatMessage> target = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        store.updateMessages(id, target);
        /* 恢复是整体替换，不走 add，所以累计值不再准：直接重算而不是继续累加。 */
        invalidate();
        log.info("[会话记忆] 会话[{}] 记忆已恢复为 {} 条", id, target.size());
    }

    /**
     * 估算消息列表的总 Token 数
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += CjkWidth.estimateTokens(extractText(msg));
        }
        // 每条消息额外计入 PER_MESSAGE_OVERHEAD token 的结构开销（role 标记等）
        total += messages.size() * PER_MESSAGE_OVERHEAD;
        return total;
    }

    /**
     * 从消息中提取纯文本内容
     */
    private String extractText(ChatMessage message) {
        if (message instanceof SystemMessage sm) {
            return sm.text();
        } else if (message instanceof AiMessage am) {
            return am.text() != null ? am.text() : "";
        } else if (message instanceof UserMessage um) {
            try {
                return um.singleText();
            } catch (Exception e) {
                return um.toString();
            }
        } else if (message instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return "";
    }
}
