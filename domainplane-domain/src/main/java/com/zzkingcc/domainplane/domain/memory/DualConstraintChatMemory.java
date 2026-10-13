package com.zzkingcc.domainplane.domain.memory;

import com.zzkingcc.domainplane.common.util.CjkWidth;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆（只追加，不淘汰）
 *
 * 记忆是长期存储（Redis + RDB/AOF），不会为了塞下新消息而丢弃最旧的，静默丢历史会让用户
 * "以为还记得"。M2 起封顶语义已移除：到上限不再拒绝新一轮，会话无限增长；
 * "这轮给模型看多少"由编排层的 {@code ContextAssembler} 预算与降级链决定。
 *
 * {@link #capacityFor(String)} 保留下来只作内部自检与回归测试口径，主线不再调用：上限只
 * 约束"存了多少"，约束不了"这轮给模型看多少"。最终回答永远允许写入，否则会留下有问无答的
 * 孤立提问，所以一轮结束后总量可能略微超出。
 *
 * 性能：写入与容量判定都做成增量，不在每条消息上遍历全量。
 *
 *   • 写入：存储若支持单条追加（{@link #appendStore} 探测得到），一次 {@code RPUSH} 完成；
 *     否则退回"读全量 → 加一条 → 写全量"，行为不变、只是慢一些。
 *   • 容量：条数与 token 量都靠累计值给出准数（记忆只增不减，累加即可），
 *     只有本次会话的首读需要全量算一遍，之后每次 {@code add} 都同步累加。
 *   • 读窗口：近况窗口走 {@link #tail(int)}（尾部 LRANGE），不再全量读后再取尾。
 *   • 回滚：{@link #truncateTo(int)} 只剪尾部（一次 LTRIM），配 {@link #size()}
 *     记下的长度即可：原先是"读全量快照 → 整体回写"，读写都是 O(全量)。
 *
 * 改这一层是因为原先每写一条消息都要全量读写一次，而一次对话要走六遍这条路
 * （容量判定、拍快照、写提问、读历史、写回答），复杂度 O(全量)：会话越长越慢。
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
     * （{@code RedisChatMemoryStore}），而领域层不能反向依赖它：
     * 声明一个共享接口也一样会把基础设施拖进领域层的依赖里。
     * 探测只在本对象构造时做一次，运行期零开销；探测不到就退回全量写路径，
     * 单测里的 Map 实现走的正是那条，行为完全不变。
     */
    private final boolean appendable;

    /**
     * 存储是否支持"只取尾部 N 条"（{@code tailMessages(Object,int)}）。
     *
     * <p>近况窗口只用到尾部，全量读是白跑一趟（M4-R5）。探测不到就退回
     * {@link #messages()} 再取尾：行为不变，只是慢。
     */
    private final boolean tailable;

    /** 存储是否支持"取条数"（{@code sizeOf(Object)}，Redis 侧是 O(1) 的 LLEN） */
    private final boolean sizable;

    /** 存储是否支持"截断到前 N 条"（{@code truncate(Object,int)}） */
    private final boolean truncatable;

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
        this.tailable = hasMethod(store, "tailMessages", Object.class, int.class);
        this.sizable = hasMethod(store, "sizeOf", Object.class);
        this.truncatable = hasMethod(store, "truncate", Object.class, int.class);
    }

    /**
     * 探测存储是否提供"追加一条"的能力。
     *
     * 只认签名完全一致的 {@code appendMessage(Object, ChatMessage)}：
     * 名字相近但参数不同的方法被调用会在运行期抛 {@code IllegalArgumentException}，
     * 那比退回全量写路径糟得多。
     */
    private static boolean detectAppendable(ChatMemoryStore store) {
        return hasMethod(store, "appendMessage", Object.class, ChatMessage.class);
    }

    /**
     * 按精确签名探测存储的可选能力。
     *
     * 用方法名探测而不是 instanceof：可选的实现落在基础设施层
     * （{@code RedisChatMemoryStore}），而领域层不能反向依赖它：
     * 声明一个共享接口也一样会把基础设施拖进领域层的依赖里。
     * 探测只在本对象构造时做一次，运行期零开销；探测不到就退回全量路径，
     * 单测里的 Map 实现走的正是那条，行为完全不变。
     */
    private static boolean hasMethod(ChatMemoryStore store, String name, Class<?>... params) {
        try {
            store.getClass().getMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (RuntimeException e) {
            // 反射在特定类加载环境下可能被拒绝：探测失败按"不支持"处理，退回全量路径
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
     * 单张图片折算的 token 数（粗估，只用于容量判定）。
     *
     * <p>真实开销由厂商定义：OpenAI 系约 {@code 85 + 170×⌈宽/512⌉×⌈高/512⌉}（1024² 约 765），
     * Anthropic 约 {@code 宽×高/750}。这里不去解析图片尺寸：base64 图要拿到宽高得先解码，
     * URL 图则根本拿不到，为一个估算值引一条解码路径不划算。</p>
     *
     * <p>取 800（≈ 1 兆像素）并刻意偏高：容量判定宁可早一轮拒绝，也不要把上限写爆，
     * 记忆只增不淘汰，一旦破限该会话永久作废。</p>
     */
    public static final int IMAGE_TOKEN_ESTIMATE = 800;

    /**
     * 单条音频折算的 token 数（粗估，只用于容量判定）。
     *
     * <p>口径：OpenAI 系音频输入约 100 tokens/秒，取 6000 ≈ 1 分钟。
     * 与图片同理不解析真实时长（base64 音频拿时长要解码容器头），
     * 取整分钟量级并偏高：容量判定宁可早一轮拒绝，不把上限写爆。</p>
     */
    public static final int AUDIO_TOKEN_ESTIMATE = 6000;

    /**
     * 单条视频折算的 token 数（粗估，只用于容量判定）。
     *
     * <p>口径：Gemini 系视频约 258 tokens/秒，取 12000 ≈ 45 秒。
     * 视频是所有媒体里最贵的（一条 30 秒片段就能顶掉半条会话预算），
     * 估得保守才能让"发三条长视频"这种输入在入口就被挡住，
     * 而不是让会话带着必然超限的上下文开跑。</p>
     */
    public static final int VIDEO_TOKEN_ESTIMATE = 12000;

    /**
     * 一条正常轮次写入记忆的消息条数：提问 + 最终回答。
     *
     * 存在待审批断点时还要多补一条占位回答，判定入口会按 3 条预留
     * （见 {@link #capacityFor(String, boolean)}）。
     */
    private static final int MESSAGES_PER_ROUND = 2;

    @Override
    public void add(ChatMessage message) {
        // 纯追加：不淘汰、不判上限。上限判定在 capacityFor(...)，只作自检、不拦开轮，
        // 出口（最终回答）永远允许写入。
        if (appendable) {
            // 增量写：一次 RPUSH 落盘，不再"读全量 → 写全量"
            invokeAppend(message);
            // 累计值同步累加：记忆只增不减，累加即可给出准数，
            // 免得每次容量判定都回存储重算一遍。
            // 图片开销必须一起累加（mediaTokensOf）：否则增量值与全量重算值分叉，
            // 而"增量 == 重算"正是这套缓存能被信任的前提。
            if (loaded) {
                cachedCount++;
                cachedTokens += CjkWidth.estimateTokens(extractText(message))
                        + mediaTokensOf(message) + PER_MESSAGE_OVERHEAD;
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
        invoke("appendMessage", new Class<?>[]{Object.class, ChatMessage.class}, new Object[]{id, message});
    }

    /**
     * 反射调用存储的可选方法，把目标抛出的运行期异常原样翻出来。
     *
     * {@code InvocationTargetException} 会把真实异常包一层，不拆开的话上层
     * （如编排层的"未配置/中断"判定）就认不出它们，只能看到一句笼统的失败。
     */
    private Object invoke(String name, Class<?>[] paramTypes, Object[] args) {
        try {
            return store.getClass().getMethod(name, paramTypes).invoke(store, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("调用会话记忆存储失败：" + name, cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用会话记忆存储失败：" + name, e);
        }
    }

    /**
     * 容量自检：把"本轮提问"算进去，给出这个会话按上限算是满还是没满。
     *
     * <p>M2 起主线不再用它拦开轮，只留给自检与回归测试；真正决定"给模型看多少"的是
     * {@code ContextAssembler}。</p>
     *
     * 判定是粘性的：记忆只增不减，所以一旦满了，之后每次判定都会失败，
     * 不需要额外记"已封顶"标记位。（唯一能解除的是 Redis 数据丢失，所以 RDB+AOF 是这套语义的前提。）
     *
     * 条数按整轮预留判定；Token 只按提问判定（回答长度不可预估），因此允许一轮结束后轻度超出。
     */
    public Capacity capacityFor(String pendingQuestion) {
        return capacityFor(pendingQuestion, 0, false);
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
        return capacityFor(pendingQuestion, 0, willWritePlaceholder);
    }

    /**
     * 入口容量判定，并把本轮随提问一起上行的图片计入。
     *
     * <p>图片开销必须显式传进来：本层看不到内容块（{@code domain} 模块不依赖 {@code api}），
     * 而调用方（编排层）手里就有内容块，数一下图有几张是最便宜的传法。
     * 不传的话，一条"图 + 三个字"的提问会被算成个位数 token：记忆照放行，
     * 真实开销却已经把上下文顶到上限。</p>
     *
     * @param pendingQuestion      本轮提问的纯文本
     * @param imageCount           本轮随提问上行的图片张数（无图传 0）
     * @param willWritePlaceholder 本轮是否还会额外补一条占位回答
     */
    public Capacity capacityFor(String pendingQuestion, int imageCount, boolean willWritePlaceholder) {
        return capacityFor(pendingQuestion, imageCount, 0, 0, willWritePlaceholder);
    }

    /**
     * 入口容量判定，图片 / 音频 / 视频分别计数计入。
     *
     * <p>三种媒体分开传而不是合成一个"媒体数"：单价差一个数量级
     * （图 800 / 音 6000 / 视 12000），合成一个数就得让调用方自己知道该乘哪一档，
     * 迟早出现"按图价给视频计容"这种错配。旧的三参重载保留（音频 / 视频传 0），
     * 既有调用方与测试零改动。</p>
     *
     * @param pendingQuestion      本轮提问的纯文本
     * @param imageCount           本轮随提问上行的图片张数（无图传 0）
     * @param audioCount           本轮随提问上行的音频条数（无音频传 0）
     * @param videoCount           本轮随提问上行的视频条数（无视频传 0）
     * @param willWritePlaceholder 本轮是否还会额外补一条占位回答
     */
    public Capacity capacityFor(String pendingQuestion, int imageCount, int audioCount, int videoCount,
                                boolean willWritePlaceholder) {
        Counts counts = counts();
        int currentTokens = counts.tokens();
        int mediaTokens = Math.max(0, imageCount) * IMAGE_TOKEN_ESTIMATE
                + Math.max(0, audioCount) * AUDIO_TOKEN_ESTIMATE
                + Math.max(0, videoCount) * VIDEO_TOKEN_ESTIMATE;
        int questionTokens = CjkWidth.estimateTokens(pendingQuestion) + PER_MESSAGE_OVERHEAD + mediaTokens;
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
     * 记忆只增不减、且本类的写入全部经过 {@code add}，所以累计值始终是准数：
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
     * 作废累计值缓存：下次读取时重新全量装载。
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
     * 只取最近 {@code limit} 条（近况窗口用）。
     *
     * <p>存储支持尾部读时走一次尾部 {@code LRANGE}（M4-R5）；否则退回全量读再取尾，
     * 后者只为在 Map 实现（单测）上保持行为一致，生产路径不会走到。
     * 返回的是新列表，调用方可以随意遍历/拷贝。</p>
     */
    public List<ChatMessage> tail(int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        if (tailable) {
            Object result = invoke("tailMessages",
                    new Class<?>[]{Object.class, int.class}, new Object[]{id, limit});
            if (result instanceof List<?> list) {
                List<ChatMessage> out = new ArrayList<>(list.size());
                for (Object element : list) {
                    if (element instanceof ChatMessage message) {
                        out.add(message);
                    }
                }
                return out;
            }
            return new ArrayList<>();
        }
        List<ChatMessage> all = store.getMessages(id);
        int from = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    /**
     * 当前记忆条数。
     *
     * <p>回滚只需要一个长度（"本轮写入前有多长"），不需要内容：存储支持时
     * 是 O(1) 的 {@code LLEN}（M4-R5）。</p>
     */
    public int size() {
        if (sizable) {
            Object count = invoke("sizeOf", new Class<?>[]{Object.class}, new Object[]{id});
            if (count instanceof Number number) {
                return number.intValue();
            }
            return 0;
        }
        return store.getMessages(id).size();
    }

    /**
     * 回滚到"只保留前 {@code keep} 条"：即删掉本轮往尾部追加的那几条。
     *
     * <p>取代原先的 {@code restore(快照)}：快照要先把整段历史读出来存住，
     * 回滚时再整体回写一遍，读写都是 O(全量)；而"本轮追加了什么，回滚就从尾部剪掉什么"
     * 只需要一次 {@code LTRIM}，代价与保留量无关（M4-R5）。</p>
     *
     * <p><b>语义比 {@link #restore(List)} 窄</b>：它只剪尾部，绝不会把已存在的内容
     * 改写成另一份。唯一的调用场景是"本轮以失败/停止收尾，撤掉本轮写的提问"，
     * 正好只需要剪尾部。反过来说，它只对本对象自己的追加负责：
     * 若别处绕开本类直接改了存储，剪尾部是修不回来的（那种情况下用 {@code restore}）。</p>
     *
     * @param keep 保留前多少条；{@code <= 0} 表示清空
     */
    public void truncateTo(int keep) {
        int target = Math.max(0, keep);
        if (truncatable) {
            invoke("truncate", new Class<?>[]{Object.class, int.class}, new Object[]{id, target});
        } else {
            List<ChatMessage> all = store.getMessages(id);
            List<ChatMessage> kept = target >= all.size()
                    ? new ArrayList<>(all)
                    : new ArrayList<>(all.subList(0, target));
            store.updateMessages(id, kept);
        }
        /* 被剪掉那几条的 token 量无从扣减，只能下次重算：直接作废累计值比"猜一个"可靠。 */
        invalidate();
        log.info("[会话记忆] 会话[{}] 已回滚为前 {} 条", id, target);
    }

    /**
     * 估算消息列表的总 Token 数
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += CjkWidth.estimateTokens(extractText(msg)) + mediaTokensOf(msg);
        }
        // 每条消息额外计入 PER_MESSAGE_OVERHEAD token 的结构开销（role 标记等）
        total += messages.size() * PER_MESSAGE_OVERHEAD;
        return total;
    }

    /**
     * 一条消息里媒体块折算的 token 数（图 / 音 / 视分档计）。
     *
     * 与 {@link #extractText} 分工明确：那边只管文本，媒体一律在这里计，
     * 混在一起写就会有人只改一处，增量值与全量重算值从此分叉。
     * 写记忆的消息就是 {@code buildUserMessage} 的产物，媒体块形态与之对齐。
     */
    private int mediaTokensOf(ChatMessage message) {
        if (!(message instanceof UserMessage user)) {
            return 0;
        }
        int tokens = 0;
        for (Content content : user.contents()) {
            if (content instanceof ImageContent) {
                tokens += IMAGE_TOKEN_ESTIMATE;
            } else if (content instanceof AudioContent) {
                tokens += AUDIO_TOKEN_ESTIMATE;
            } else if (content instanceof VideoContent) {
                tokens += VIDEO_TOKEN_ESTIMATE;
            }
        }
        return tokens;
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
            if (um.hasSingleText()) {
                return um.singleText();
            }
            /* 多模态消息（文本 + 图）只能逐块收文本，绝不能回落到 toString()：
               toString() 会把整段 base64 原样拼进去，一张 1MB 的照片在容量判定里
               会被算成上百万 token：该会话此后每一轮都被判"已满"，且清不掉
               （记忆只增不淘汰）。图片开销由 mediaTokensOf 单独计。 */
            StringBuilder text = new StringBuilder();
            for (Content content : um.contents()) {
                if (content instanceof TextContent tc && tc.text() != null) {
                    text.append(tc.text());
                }
            }
            return text.toString();
        } else if (message instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return "";
    }
}
