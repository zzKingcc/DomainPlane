package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 装配清单的 Redis 存放（F04-a）。
 *
 * <p>一个会话一个 List 键，LPUSH 新条目 + LTRIM 到 {@link #KEEP_PER_SESSION} 条：
 * 账本只回答"最近几轮为什么这么装"，三个月前那轮的装配细节没有排障价值，
 * 留着只是让键无限增长。<b>这里删得理直气壮，因为它是派生数据</b>——
 * 与记忆/归档那条"只增不减"的硬约束不冲突（那条管的是有真相价值的数据）。</p>
 *
 * <p>写失败只告警：清单不影响对话，装配完照样能跑。
 * 唯一必须抛的是 {@link #deleteByDomain}：删域级联漏删会留下孤儿键。</p>
 *
 * @author zzkingcc
 */
public class RedisAssemblyReportStore implements AssemblyReportStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAssemblyReportStore.class);

    /** 键前缀：{@code domainplane:conv:asm:{域}|{session}} */
    private static final String KEY_PREFIX = "domainplane:conv:asm:";

    /** SCAN 每批拉取的键数（与倒排索引同口径） */
    private static final int SCAN_BATCH = 500;

    /** 账本存活期：与倒排索引同取 30 天（同一类派生数据，同一套回收口径） */
    private static final Duration TTL = Duration.ofDays(30);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .registerModule(new SimpleModule("domainplane-assembly-report")
                    .addSerializer(Instant.class, new InstantSerializer())
                    .addDeserializer(Instant.class, new InstantDeserializer()));

    private final StringRedisTemplate redisTemplate;

    public RedisAssemblyReportStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(String sessionKey, ContextAssemblyReport report) {
        if (sessionKey == null || sessionKey.isBlank() || report == null) {
            return;
        }
        String key = KEY_PREFIX + sessionKey;
        try {
            String json = MAPPER.writeValueAsString(report);
            redisTemplate.opsForList().leftPush(key, json);
            redisTemplate.opsForList().trim(key, 0, KEEP_PER_SESSION - 1L);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[装配清单] 会话[{}] turnId={} 落账失败（不影响本轮对话）：{}",
                    sessionKey, report.turnId(), e.getMessage());
        }
    }

    @Override
    public List<ContextAssemblyReport> recent(String sessionKey, int limit) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return List.of();
        }
        int n = Math.min(Math.max(1, limit), KEEP_PER_SESSION);
        String key = KEY_PREFIX + sessionKey;
        try {
            List<String> raw = redisTemplate.opsForList().range(key, 0, n - 1L);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<ContextAssemblyReport> out = new ArrayList<>(raw.size());
            for (String s : raw) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readValue(s, ContextAssemblyReport.class));
                } catch (Exception e) {
                    // 一条坏数据不该让"看账本"整个失败：跳过它，其余照常返回
                    log.warn("[装配清单] 会话[{}] 有一条清单解析失败已跳过：{}", sessionKey, e.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[装配清单] 会话[{}] 读清单失败：{}", sessionKey, e.getMessage());
            return List.of();
        }
    }

    @Override
    public int deleteBySession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return 0;
        }
        try {
            // 单键 delete 返回 Boolean（键不存在即 false），不是 Long：批量那个重载才返回计数
            Boolean removed = redisTemplate.delete(KEY_PREFIX + sessionKey);
            return Boolean.TRUE.equals(removed) ? 1 : 0;
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "删除会话装配清单失败（会话 " + sessionKey + "）：" + e.getMessage(), e);
        }
    }

    @Override
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        if (normalized.isEmpty()) {
            throw new ChatMemoryException(ErrorCode.INVALID_PARAMETER,
                    "deleteByDomain 收到空域，已拒绝执行（否则会清空全部域的装配清单）");
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
            Long removed = redisTemplate.delete(keys);
            int deleted = removed == null ? 0 : removed.intValue();
            log.info("[装配清单] 已按域清理 {} 个清单键（域 {}）", deleted, normalized);
            return deleted;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "按域清理装配清单失败（域 " + normalized + "）：" + e.getMessage()
                            + "。清单键可能残留 —— 需修复后重新执行删除", e);
        }
    }

    private static final class InstantSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeNumber(value.toEpochMilli());
        }
    }

    private static final class InstantDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            String raw = p.getValueAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            if (raw.chars().allMatch(Character::isDigit)) {
                return Instant.ofEpochMilli(Long.parseLong(raw));
            }
            return Instant.parse(raw);
        }
    }
}
