package com.zzkingcc.domainplane.infrastructure.redis.memory;

import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的会话记忆持久化存储 —— 每条消息一个 List 元素，增量追加。
 *
 * 存储形态：{@code RPUSH domainplane:chat:memory:{域}|{sessionId}}，
 * 一个元素 = 一条消息的 JSON。
 *
 * 为什么改用 List：原先把整个会话序列化成一根字符串值，每写一条消息都要
 * "读全量 → 反序列化 → 加一条 → 序列化 → 写全量"五步全量开销，
 * 而一次对话要走六遍这条路（容量判定、拍快照、写提问、再读历史、写回答…），
 * 复杂度是 O(全量)。改 List 之后写入退化���一次 {@code RPUSH}，
 * 读取一次 {@code LRANGE} 取回，复杂度降到 O(增量)。
 *
 * 选 List 而不是 Hash：List 天然有序且支持尾部增量追加；
 * Hash 的字段名还得自己编序号，顺序还得另想办法保证。
 *
 * 附带好处：{@code RPUSH} 是单条原子命令，并发写不会互相覆盖，
 * 原先"读全量改全量写"存在丢消息的窗口。
 *
 * 只增不减的语义仍由上层保证，本类不做淘汰。
 *
 * <p>M4-R5 起另有两件"不必读全量"的事也下沉到这里：近况窗口取尾部
 * （{@link #tailMessages}）、回滚前拿长度并截尾（{@link #sizeOf} / {@link #truncate}）。
 * 编排层原先每轮都要 "读全量快照 → 失败时整体回写"，在长会话上是 O(n) 的读与写；
 * 换成"读尾部 + 记长度 + 截尾回滚"后与历史长度无关。</p>
 *
 * @author zzkingcc
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "domainplane:chat:memory:";

    /** SCAN 每批拉取的键数（大批量扫描时的单次往返规模） */
    private static final int SCAN_BATCH = 500;

    /**
     * 单次 {@code LRANGE} 一次取回的最大条数。
     *
     * 只防"一次请求拉回几十万条把内存打爆"这类异常；正常会话远达不到。
     * 这里截断是有意的上限，与静默截断历史的区别是：超限时会被调用方看到日志。
     */
    private static final int MAX_READ = 10_000;

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    /**
     * @param redisTemplate Redis 模板
     * @param ttl           记忆保留期；{@code null} = 永久不过期（一期默认：记忆是长期存储，不是缓存）
     */
    public RedisChatMemoryStore(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    /**
     * 一次 {@code LRANGE} 取回整个 List，逐条反序列化。
     *
     * 单条坏元素只跳过并告警、不让整段历史读不出来：记忆是长期存储，
     * 里面可能混着改版前写入的老格式数据，为一条坏数据丢掉整段对话不划算。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            List<String> raw = redisTemplate.opsForList().range(key, 0, MAX_READ);
            if (raw == null || raw.isEmpty()) {
                log.debug("[会话记忆] 读取会话[{}]：无历史消息", memoryId);
                return new ArrayList<>();
            }
            // LRANGE 的结束下标是闭区间，这里多读一条专门用来判"有没有被截断"。
            // 不能静默截断：调用方多是"读全量 → 追加 → 整段写回"，
            // 被截掉的尾部会在那一次回写里永久消失，而且全程没有任何信号。
            if (raw.size() > MAX_READ) {
                log.error("[会话记忆] 会话[{}] 的历史超过单次读取上限 {} 条，本次只取前 {} 条。"
                                + "若有调用方整段回写，尾部历史会丢失 —— 请缩短会话或调大上限",
                        memoryId, MAX_READ, MAX_READ);
                raw = new ArrayList<>(raw.subList(0, MAX_READ));
            }
            List<ChatMessage> messages = deserialize(memoryId, raw);
            log.debug("[会话记忆] 读取会话[{}]：{} 条历史消息", memoryId, messages.size());
            return messages;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 读取会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_READ_ERROR, "读取会话记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 只取<b>最近</b> {@code limit} 条（一次 {@code LRANGE key -limit -1}）。
     *
     * <p>近况窗口要的就是尾部若干轮的原文，而 {@link #getMessages} 是全量读：
     * 在万轮会话上，为了一个 16 条的窗口把整段历史拉回来再丢掉，是纯粹的白跑一趟
     * （M4-R5）。{@code LRANGE} 的负下标天然从尾部数，且 Redis List 是双端结构，
     * 从尾部取 N 条的代价与总量无关。</p>
     *
     * @param limit 最多取多少条；{@code <= 0} 返回空
     */
    public List<ChatMessage> tailMessages(Object memoryId, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        String key = buildKey(memoryId);
        try {
            List<String> raw = redisTemplate.opsForList().range(key, -((long) limit), -1L);
            List<ChatMessage> messages = deserialize(memoryId, raw);
            log.debug("[会话记忆] 读取会话[{}]尾部：{} 条（上限 {}）", memoryId, messages.size(), limit);
            return messages;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 读取会话[{}]尾部失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_READ_ERROR,
                    "读取会话记忆尾部失败: " + e.getMessage(), e);
        }
    }

    /**
     * 当前条数（一次 {@code LLEN}，O(1)）。
     *
     * <p>回滚只需要"本轮写入之前有多长"，拿一个长度比把整段历史读回来便宜得多
     * （M4-R5）。</p>
     */
    public long sizeOf(Object memoryId) {
        try {
            Long n = redisTemplate.opsForList().size(buildKey(memoryId));
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.error("[会话记忆] 读取会话[{}]条数失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_READ_ERROR,
                    "读取会话记忆条数失败: " + e.getMessage(), e);
        }
    }

    /**
     * 截断为只保留前 {@code keep} 条（一次 {@code LTRIM}）。
     *
     * <p>这是"回滚本轮"的实现手段：本轮往记忆里追加了几条，回滚就是把它们从尾部剪掉，
     * 而 {@code LTRIM} 从尾部剪的代价与保留量无关。{@code keep <= 0} 退化为整键删除。</p>
     *
     * <p>比"读快照 → 整体替换回写"少一次全量读与一次全量写，而且语义更窄：
     * 它只能剪尾部，绝不会把历史改写成另一份内容。</p>
     */
    public void truncate(Object memoryId, int keep) {
        String key = buildKey(memoryId);
        try {
            if (keep <= 0) {
                redisTemplate.delete(key);
                log.debug("[会话记忆] 会话[{}] 截断到 0 条，已删除该键", memoryId);
                return;
            }
            redisTemplate.opsForList().trim(key, 0L, keep - 1L);
            log.debug("[会话记忆] 会话[{}] 已截断为前 {} 条", memoryId, keep);
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 截断会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_WRITE_ERROR, "截断会话记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 原始元素 → 消息列表。单条坏元素只跳过并告警、不让整段历史读不出来：记忆是长期存储，
     * 里面可能混着改版前写入的老格式数据，为一条坏数据丢掉整段对话不划算。
     */
    private List<ChatMessage> deserialize(Object memoryId, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> messages = new ArrayList<>(raw.size());
        for (String json : raw) {
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                messages.addAll(ChatMessageDeserializer.messagesFromJson(json));
            } catch (Exception e) {
                log.warn("[会话记忆] 会话[{}] 中有 1 条消息无法解析，已跳过：{}", memoryId, e.getMessage());
            }
        }
        return messages;
    }

    /**
     * 用给定内容**整体替换**该会话的记忆（语义由上层决定：回滚、断点清理都要改成另一份）。
     *
     * 写入用先删后写的两条命令，而不是 Pipeline：{@code DEL} 与 {@code RPUSH} 之间
     * 若有并发写入，合并成一条 pipeline 仍可能让后到的 RPUSH 被前面的 DEL 清掉。
     * 分开两条时最坏结果是"这次写入没生效"，不会出现"记忆被清空"。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            if (messages == null || messages.isEmpty()) {
                redisTemplate.delete(key);
                log.debug("[会话记忆] 会话[{}]消息列表为空，已清除旧记录", memoryId);
                return;
            }

            /* 逐条序列化（每条一个元素），不用批量接口再切分：
               批量接口只能拿到一整根 JSON，切不出单条边界，而"逐条各序列化一次"
               的内存占用与历史长度无关。 */
            List<String> elements = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                elements.add(ChatMessageSerializer.messagesToJson(List.of(message)));
            }

            redisTemplate.delete(key);
            redisTemplate.opsForList().rightPushAll(key, elements);
            applyTtl(key, memoryId, messages.size());
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 更新会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_WRITE_ERROR, "更新会话记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 追加一条消息 —— 本类存在的意义：把写入从 O(全量) 降到 O(1)。
     *
     * {@code ChatMemory.add} 每写一条都会调 {@link #updateMessages}，那条路径必然
     * 带着整段历史一起重写。langchain4j 的 {@code ChatMemoryStore} 接口没有单独的
     * append 语义，所以本类额外暴露这个方法，由 {@code DualConstraintChatMemory} 直接调用。
     *
     * 并发安全：{@code RPUSH} 是单条原子命令，两个并发写不会互相覆盖，
     * 这正是选 List 的附带好处（原先"读全量改全量写"有丢消息的窗口）。
     */
    public void appendMessage(Object memoryId, ChatMessage message) {
        String key = buildKey(memoryId);
        try {
            String element = ChatMessageSerializer.messagesToJson(List.of(message));
            redisTemplate.opsForList().rightPush(key, element);
            applyTtl(key, memoryId, -1);
        } catch (Exception e) {
            log.error("[会话记忆] 追加消息到会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_WRITE_ERROR, "追加会话记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写完刷新过期时间。
     *
     * @param count 实际写入条数；负数表示是单条追加（不必打印条数）
     */
    private void applyTtl(String key, Object memoryId, int count) {
        String what = count >= 0 ? "写入 " + count + " 条消息" : "追加消息";
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            redisTemplate.expire(key, ttl);
            log.debug("[会话记忆] 会话[{}]{}：TTL={} 分钟", memoryId, what, ttl.toMinutes());
            return;
        }
        if (ttl != null && ttl.isNegative()) {
            // 负数 TTL 落进 Redis 的 EXPIRE 会被服务端当作「立即删除」：
            // 记忆会刚写进去就没了，Agent 表现为"永远失忆"且没有任何报错。
            // 所以负数一律按"永久"处理并告警，让配置错误浮出来。
            log.warn("[会话记忆] 会话[{}] 的 TTL 配置为负数（{}），已按永久处理。"
                            + "负数 TTL 会让 Redis 立即删除该键（表现为记忆刚写即丢）。请改为正数或留空",
                    memoryId, ttl);
        }
        log.debug("[会话记忆] 会话[{}]{}：TTL=永久", memoryId, what);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            redisTemplate.delete(key);
            log.debug("[会话记忆] 删除会话[{}]记忆", memoryId);
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 删除会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "删除会话记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除某个域下的全部会话记忆（删域级联清理用）。
     *
     * 键形如 {@code domainplane:chat:memory:{域}|{sessionId}}，因此按 {@code 域|} 前缀扫描即可。
     * 记忆保留期默认是永久，不清掉的话：同路径域将来重建，这段对话历史会原样复活，
     * 用户以为域已删干净，实际上一句旧话就把它勾回来了。
     *
     * 用 SCAN 而非 KEYS：KEYS 会阻塞整个 Redis 实例。
     *
     * @return 删除的键数
     */
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        // 守卫必须在拼 pattern 之前：domain 为空时 pattern 会退化成
        // "domainplane:chat:memory:|*"，匹配全部域的记忆，一次误传就是全量清空，
        // 而记忆保留期默认永久、清掉不可恢复。RedisCheckpointSaver 里有同款守卫。
        if (normalized.isEmpty()) {
            throw new ChatMemoryException(ErrorCode.INVALID_PARAMETER,
                    "deleteByDomain 收到空域，已拒绝执行（否则会清空全部域的记忆）");
        }
        return deleteByPattern(KEY_PREFIX + normalized + SessionKeys.SEPARATOR + "*", "会话记忆");
    }

    /**
     * 按模式删除本存储负责的键。失败直接抛，不再"只告警"。
     *
     * 删域流程里这一步在 {@code domainRegistry.delete} 之前，抛异常正好中止删域，域仍保留。
     * 这两处 Redis 清理各自承担一条安全保证：
     *
     *   • 记忆保留期默认永久，不清的话同路径域重建，这段对话历史原样复活；
     *   • 断点里存着"即将执行、尚未执行"的工具调用，不清的话，24 小时内重建域
     *       就能 {@code resume(approved=true)} 把当初被拦下的破坏性动作补执行掉。
     *
     * 失败只 warn 等于把两条保证静默降级为"尽力而为"，而用户看到的是"删除成功"。
     */
    private int deleteByPattern(String pattern, String what) {
        if (pattern == null || pattern.isBlank()) {
            return 0;
        }
        try {
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(
                    ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (keys.isEmpty()) {
                return 0;
            }
            Long deleted = redisTemplate.delete(keys);
            log.info("[{}] 已按域清理 {} 个键（模式 {}）", what, deleted, pattern);
            return deleted == null ? 0 : deleted.intValue();
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "按模式清理" + what + "失败（模式 " + pattern + "）：" + e.getMessage()
                            + "。相关键可能残留 —— 需修复后重新执行删除", e);
        }
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }

}
