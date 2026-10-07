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
 * 基于 Redis 的会话记忆持久化存储
 * @author zzkingcc
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "domainplane:chat:memory:";

    /** SCAN 每批拉取的键数（大批量扫描时的单次往返规模） */
    private static final int SCAN_BATCH = 500;

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

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            String json = redisTemplate.opsForValue().get(key);

            if (json == null || json.isBlank()) {
                log.debug("[会话记忆] 读取会话[{}]：无历史消息", memoryId);
                return new ArrayList<>();
            }

            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            log.debug("[会话记忆] 读取会话[{}]：{} 条历史消息", memoryId, messages.size());
            return messages;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 读取会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_READ_ERROR, "读取会话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            if (messages == null || messages.isEmpty()) {
                redisTemplate.delete(key);
                log.debug("[会话记忆] 会话[{}]消息列表为空，已清除旧记录", memoryId);
                return;
            }

            String json = ChatMessageSerializer.messagesToJson(messages);
            redisTemplate.opsForValue().set(key, json);

            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.expire(key, ttl);
                log.debug("[会话记忆] 更新会话[{}]：写入 {} 条消息，TTL={} 分钟",
                        memoryId, messages.size(), ttl.toMinutes());
            } else {
                // 负数 TTL 落进 Redis 的 EXPIRE 会被服务端当作「立即删除」——
                // 记忆会刚写进去就没了，Agent 表现为"永远失忆"且没有任何报错。
                // 所以负数一律按"永久"处理并告警，让配置错误浮出来。
                if (ttl != null && ttl.isNegative()) {
                    log.warn("[会话记忆] 会话[{}] 的 TTL 配置为负数（{}），已按永久处理。"
                                    + "负数 TTL 会让 Redis 立即删除该键（表现为记忆刚写即丢）。请改为正数或留空",
                            memoryId, ttl);
                }
                log.debug("[会话记忆] 更新会话[{}]：写入 {} 条消息，TTL=永久",
                        memoryId, messages.size());
            }
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 更新会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_WRITE_ERROR, "更新会话记忆失败: " + e.getMessage(), e);
        }
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
     * 记忆保留期默认是永久，不清掉的话：同路径域将来重建，这段对话历史会原样复活 ——
     * 用户以为域已删干净，实际上一句旧话就把它勾回来了。
     *
     * 用 SCAN 而非 KEYS：KEYS 会阻塞整个 Redis 实例。
     *
     * @return 删除的键数
     */
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        // 守卫必须在拼 pattern 之前：domain 为空时 pattern 会退化成
        // "domainplane:chat:memory:|*"，匹配全部域的记忆 —— 一次误传就是全量清空，
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
     *   • 记忆保留期默认永久 —— 不清的话同路径域重建，这段对话历史原样复活；
     *   • 断点里存着"即将执行、尚未执行"的工具调用 —— 不清的话，24 小时内重建域
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
