package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.conversation.ConversationSummaryStore;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 滚动摘要的 Redis 实现：一个会话一个 Hash，{@code text} + {@code upTo} 两个字段。
 *
 * <p>键形态 {@code domainplane:conv:sum:{域}|{sessionId}}，与归档 / 索引同族的
 * {@code domainplane:conv:*} 前缀，便于按域级联清理与排查。</p>
 *
 * <p>用 Hash 而不是一根字符串：{@code text} 与 {@code upTo} 必须一起更新，
 * 但读的时候常常只需要其中一个（装配只要 text，调度层只要 upTo）。
 * 两个字段一次 {@code HMGET} 取回，写一次 {@code HSET} 落两个字段，
 * 不需要"读全量 → 改 → 写全量"。</p>
 *
 * <p><b>不设 TTL</b>：摘要是有损压缩的产物，过期之后重放归档也复原不出同一份措辞，
 * 所以按档案级对待。生命周期与归档一致，删域即清（{@link #deleteByDomain}），
 * 那是 fail-closed 的，漏删等于旧会话的摘要在域重建后复活。</p>
 *
 * @author zzkingcc
 */
public class RedisConversationSummary implements ConversationSummaryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationSummary.class);

    /** 摘要键前缀：{@code domainplane:conv:sum:{域}|{session}} */
    private static final String KEY_PREFIX = "domainplane:conv:sum:";

    /** 摘要正文字段 */
    private static final String FIELD_TEXT = "text";

    /** 已摘要到的轮号字段 */
    private static final String FIELD_UP_TO = "upTo";

    /** SCAN 每批拉取的键数 */
    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redisTemplate;

    public RedisConversationSummary(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<Stored> load(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        try {
            var entries = redisTemplate.opsForHash().entries(KEY_PREFIX + sessionKey);
            if (entries == null || entries.isEmpty()) {
                return Optional.empty();
            }
            Object rawText = entries.get(FIELD_TEXT);
            Object rawUpTo = entries.get(FIELD_UP_TO);
            if (rawText == null && rawUpTo == null) {
                return Optional.empty();
            }
            String text = rawText == null ? "" : String.valueOf(rawText);
            return Optional.of(new Stored(text, parseLong(rawUpTo)));
        } catch (Exception e) {
            log.warn("[会话摘要] 读取会话[{}]摘要失败：{}", sessionKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String sessionKey, Stored summary) {
        if (sessionKey == null || sessionKey.isBlank() || summary == null) {
            return;
        }
        String text = summary.text() == null ? "" : summary.text();
        try {
            redisTemplate.opsForHash().putAll(KEY_PREFIX + sessionKey, Map.of(
                    FIELD_TEXT, text,
                    FIELD_UP_TO, String.valueOf(summary.upToTurnId())));
            log.debug("[会话摘要] 会话[{}] 摘要已更新：{} 字符，已摘要到第 {} 轮",
                    sessionKey, text.length(), summary.upToTurnId());
        } catch (Exception e) {
            // 摘要写失败只告警：下一次闭轮会再试；装配侧读不到就退化为 digest 拼接
            log.warn("[会话摘要] 写入会话[{}]摘要失败：{}", sessionKey, e.getMessage());
        }
    }

    @Override
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        // 空域守卫：pattern 会退化成匹配全部域，而摘要清掉不可恢复
        if (normalized.isEmpty()) {
            throw new ChatMemoryException(ErrorCode.INVALID_PARAMETER,
                    "deleteByDomain 收到空域，已拒绝执行（否则会清空全部域的会话摘要）");
        }
        String pattern = KEY_PREFIX + normalized + SessionKeys.SEPARATOR + "*";
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
            log.info("[会话摘要] 已按域清理 {} 个摘要键（域 {}）", deleted, normalized);
            return deleted == null ? 0 : deleted.intValue();
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "按域清理会话摘要失败（域 " + normalized + "）：" + e.getMessage()
                            + "。摘要键可能残留 —— 需修复后重新执行删除", e);
        }
    }

    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            // 字段被外部写坏：按"还没摘要过"处理，装配侧会按 digest 补齐，不会丢内容
            return 0L;
        }
    }
}
