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
 * @author zzkingcc
 */
public class DualConstraintChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(DualConstraintChatMemory.class);

    private final Object id;
    private final int maxMessages;
    private final int maxTokens;
    private final ChatMemoryStore store;

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
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        messages.add(message);
        store.updateMessages(id, messages);
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
        List<ChatMessage> messages = store.getMessages(id);
        int currentTokens = estimateTokens(messages);
        int questionTokens = CjkWidth.estimateTokens(pendingQuestion) + PER_MESSAGE_OVERHEAD;
        int reserved = willWritePlaceholder
                ? MESSAGES_PER_ROUND + 1
                : MESSAGES_PER_ROUND;

        if (questionTokens > maxTokens) {
            return Capacity.full("单条提问已超过会话记忆上限（约 " + questionTokens + " tokens > 上限 "
                    + maxTokens + "），请缩短内容，或更换 sessionId 开启新会话");
        }
        if (messages.size() + reserved > maxMessages) {
            return Capacity.full("会话记忆已达上限（当前 " + messages.size() + " 条 + 本轮 " + reserved
                    + " 条 > 上限 " + maxMessages + " 条 ≈ " + (maxMessages / (MESSAGES_PER_ROUND + 1))
                    + " 轮问答），请更换 sessionId 开启新会话");
        }
        if (currentTokens + questionTokens > maxTokens) {
            return Capacity.full("会话记忆已达上限（当前约 " + currentTokens + " tokens + 本轮提问约 "
                    + questionTokens + " > 上限 " + maxTokens + " tokens），请更换 sessionId 开启新会话");
        }
        return Capacity.ok();
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
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    /**
     * 把记忆整体替换为给定内容(任务停止时回滚用)
     *
     * @param messages 目标内容;null 视为清空
     */
    public void restore(List<ChatMessage> messages) {
        List<ChatMessage> target = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        store.updateMessages(id, target);
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
