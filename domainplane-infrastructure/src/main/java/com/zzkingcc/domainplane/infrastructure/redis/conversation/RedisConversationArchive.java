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
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.FlowEvent;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.common.util.TextClip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Redis 的会话归档（方案 §4.2）：一条 List 存全部轮记录，无封顶无淘汰。
 *
 * <p>键形如 {@code domainplane:chat:archive:{域}|{sessionId}}，一个元素 = 一轮的 JSON。
 * 与 {@code RedisChatMemoryStore} 同形态同语义（有序、原子追加、域级 SCAN 清理），
 * 差别只在元素粒度：那边一条消息，这边一整轮（含全流程）。</p>
 *
 * <p><b>为什么 List 而不是 Hash</b>：追加 = 一次 {@code RPUSH}，尾部读 = {@code LRANGE -N -1}，
 * 随机读 = {@code LINDEX}（O(index)，万轮级亚毫秒可接受）。Hash 存整轮还得自己编序号、
 * 顺序另想办法，没有好处。</p>
 *
 * <p><b>turnId 与下标</b>：{@code turnId} = 开轮时的 List 长度 + 1，下标恒为 {@code turnId - 1}。
 * 这条不变式的前提是"同一会话开轮串行"（编排层的同会话并发控制保证）；
 * {@code deleteTurn} 只删尾部开放轮，删完长度减一，下一个 turnId 自然复用，不变式不破。</p>
 *
 * <p><b>无 TTL</b>：归档是长期档案不是缓存，不过期。若要保留期，应该像
 * {@code MemoryProperties.ttl} 一样单独立项，而不是搭记忆的车：
 * 两者生命周期语义不同（检查点 24h、记忆默认永久、归档永久）。</p>
 *
 * @author zzkingcc
 */
public class RedisConversationArchive implements ConversationArchive {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationArchive.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "domainplane:chat:archive:";

    /** SCAN 每批拉取的键数（与记忆存储同口径） */
    private static final int SCAN_BATCH = 500;

    /** 审批被拒的固定标记（与编排层补进记忆的占位回答同一语义） */
    private static final String APPROVAL_REJECTED = "REJECTED";

    /**
     * 轮记录的 JSON 映射器。
     *
     * <p>{@code Instant} 走自定义的毫秒数值序列化，而不是引 {@code jackson-datatype-jsr310}：
     * 基础设施层只有 {@code jackson-databind}（Redis / ES 引入的），加一个 jsr310 依赖
     * 只为两个时间字段不划算；毫秒数值比 ISO 串更短，归档是长列表，省一点是一点。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .registerModule(new SimpleModule("domainplane-archive")
                    .addSerializer(Instant.class, new InstantSerializer())
                    .addDeserializer(Instant.class, new InstantDeserializer()));

    /** {@code Instant} → 毫秒数值 */
    private static final class InstantSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws java.io.IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeNumber(value.toEpochMilli());
        }
    }

    /** 毫秒数值 → {@code Instant}；历史数据若存的是 ISO 串也一并认 */
    private static final class InstantDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctx) throws java.io.IOException {
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

    private final StringRedisTemplate redisTemplate;

    public RedisConversationArchive(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== 开轮 / 闭轮 ====================

    @Override
    public long openTurn(String sessionKey, UserInput user) {
        String key = buildKey(sessionKey);
        try {
            Long len = redisTemplate.opsForList().size(key);
            long turnId = (len == null ? 0L : len) + 1;
            TurnRecord open = new TurnRecord(
                    turnId, sessionKey, Instant.now(), null,
                    user == null ? UserInput.of(null, List.of()) : user,
                    List.of(), null, null, TurnFlags.open(), null);
            redisTemplate.opsForList().rightPush(key, toJson(open));
            log.debug("[会话归档] 会话[{}] 开轮 turnId={}", sessionKey, turnId);
            return turnId;
        } catch (Exception e) {
            throw wrap("归档开轮失败（会话 " + sessionKey + "）", e);
        }
    }

    @Override
    public void closeTurn(String sessionKey, long turnId, TurnRecord record) {
        String key = buildKey(sessionKey);
        // LSET 的下标是 turnId - 1：turnId=0 会变成 -1，而 Redis 把 -1 解释成"最后一个元素"，
        // 于是不报错、不越界，静默改写最近一轮的记录。readAt 有同样的守卫，这里漏了。
        if (turnId < 1) {
            throw wrap("归档闭轮失败（会话 " + sessionKey + " turnId=" + turnId + "）",
                    new IllegalArgumentException("turnId 必须 >= 1"));
        }
        try {
            // 调用方已用 turnAt 校验过存在性与 turnId 一致性；这里只做同位写入。
            // LSET 下标越界会抛（Redis 服务端错误），那意味着不变式被外部破坏，交上层告警。
            redisTemplate.opsForList().set(key, turnId - 1, toJson(record));
            log.debug("[会话归档] 会话[{}] 闭轮 turnId={}，流程事件 {} 个", sessionKey, turnId,
                    record.flow() == null ? 0 : record.flow().size());
        } catch (Exception e) {
            throw wrap("归档闭轮失败（会话 " + sessionKey + " turnId=" + turnId + "）", e);
        }
    }

    @Override
    public void abortTurn(String sessionKey, long turnId, String reason, String partialAnswer) {
        closeAsAborted(sessionKey, turnId, reason, partialAnswer, null);
    }

    @Override
    public void rejectTurn(String sessionKey, long turnId) {
        closeAsAborted(sessionKey, turnId,
                "（用户未确认审批并开启了新对话，该次操作已取消）", null, APPROVAL_REJECTED);
    }

    /**
     * 中止 / 审批被拒的公共收尾：读原元素 → 补标记与现场 → 原位写回。
     *
     * <p>读不到就不补（幂等）：重复 abort 同一轮（停止后又踩到异常路径）不能报错，
     * 也不能把一条已经闭好的轮重新打开。</p>
     */
    private void closeAsAborted(String sessionKey, long turnId, String reason,
                                String partialAnswer, String approvalOutcome) {
        String key = buildKey(sessionKey);
        try {
            TurnRecord existing = readAt(key, turnId).orElse(null);
            if (existing == null || !existing.isOpen()) {
                // 已闭合的轮不再补标记：停止后又踩到异常路径时，第二次 abort 必须无痕，
                // 否则会叠加一条重复的 ERROR 事件、并覆盖掉真正的闭合时间
                log.warn("[会话归档] 会话[{}] turnId={} 不存在或已闭合，跳过中止闭合", sessionKey, turnId);
                return;
            }
            List<FlowEvent> flow = new ArrayList<>(existing.flow());
            if (reason != null && !reason.isBlank()) {
                flow.add(FlowEvent.of(FlowEventType.ERROR, reason));
            }
            if (partialAnswer != null && !partialAnswer.isBlank()) {
                // 半截回答也是现场：用户停止了，但他问过什么、模型答到哪儿，都留在档里
                flow.add(FlowEvent.of(FlowEventType.ASSISTANT_DELTA,
                        TextClip.clip(partialAnswer, MAX_ELEMENT_CHARS)));
            }
            TurnRecord closed = new TurnRecord(
                    turnId, sessionKey, existing.startedAt(), Instant.now(),
                    existing.user(), flow, null, existing.usage(),
                    new TurnFlags(existing.flags() == null || existing.flags().truncated(),
                            true,
                            existing.flags() != null && existing.flags().indexed(),
                            approvalOutcome != null ? approvalOutcome
                                    : (existing.flags() == null ? null : existing.flags().approvalOutcome())),
                    existing.digest());
            redisTemplate.opsForList().set(key, turnId - 1, toJson(closed));
            log.info("[会话归档] 会话[{}] turnId={} 已标记中止闭合（{}）", sessionKey, turnId, reason);
        } catch (Exception e) {
            throw wrap("归档中止闭合失败（会话 " + sessionKey + " turnId=" + turnId + "）", e);
        }
    }

    @Override
    public void deleteTurn(String sessionKey, long turnId) {
        String key = buildKey(sessionKey);
        try {
            Long size = redisTemplate.opsForList().size(key);
            if (size == null || size == 0) {
                log.warn("[会话归档] 会话[{}] 归档为空，删除开放轮 {} 无从执行", sessionKey, turnId);
                return;
            }
            TurnRecord tail = readAt(key, size).orElse(null);
            if (tail == null || tail.turnId() != turnId || !tail.isOpen()) {
                // 不删不该删的元素：宁可留下一条 aborted，也不能把别的轮pop掉
                log.warn("[会话归档] 会话[{}] 删除开放轮 {} 被拒：尾部元素不是这条未闭合轮（{}）",
                        sessionKey, turnId, tail == null ? "空" : "turnId=" + tail.turnId());
                return;
            }
            redisTemplate.opsForList().rightPop(key);
            log.info("[会话归档] 会话[{}] 开放轮 {} 已删除（图编排未启动，视为未发生）", sessionKey, turnId);
        } catch (Exception e) {
            throw wrap("归档删除开放轮失败（会话 " + sessionKey + " turnId=" + turnId + "）", e);
        }
    }

    // ==================== 读 ====================

    @Override
    public Optional<TurnRecord> findOpenTurn(String sessionKey) {
        String key = buildKey(sessionKey);
        try {
            List<String> tail = redisTemplate.opsForList().range(key, -1, -1);
            if (tail == null || tail.isEmpty()) {
                return Optional.empty();
            }
            TurnRecord record = parse(tail.get(0));
            // 不变式：至多一条开放轮且必是尾部。尾部不开放 = 没有开放轮
            return record != null && record.isOpen() ? Optional.of(record) : Optional.empty();
        } catch (Exception e) {
            throw wrap("归档读取开放轮失败（会话 " + sessionKey + "）", e);
        }
    }

    @Override
    public Optional<TurnRecord> turnAt(String sessionKey, long turnId) {
        String key = buildKey(sessionKey);
        try {
            return readAt(key, turnId);
        } catch (Exception e) {
            throw wrap("归档读取轮记录失败（会话 " + sessionKey + " turnId=" + turnId + "）", e);
        }
    }

    @Override
    public List<TurnRecord> recentTurns(String sessionKey, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String key = buildKey(sessionKey);
        try {
            List<String> raw = redisTemplate.opsForList().range(key, -limit, -1);
            return parseAll(raw, sessionKey);
        } catch (Exception e) {
            throw wrap("归档读取近况轮失败（会话 " + sessionKey + "）", e);
        }
    }

    @Override
    public List<TurnRecord> turnsBetween(String sessionKey, long fromInclusive, long toInclusive) {
        if (fromInclusive < 1 || toInclusive < fromInclusive) {
            return List.of();
        }
        String key = buildKey(sessionKey);
        try {
            Long len = redisTemplate.opsForList().size(key);
            if (len == null || len == 0) {
                return List.of();
            }
            long last = Math.min(toInclusive, len);
            if (fromInclusive > last) {
                return List.of();
            }
            List<String> raw = redisTemplate.opsForList().range(key, fromInclusive - 1, last - 1);
            return parseAll(raw, sessionKey);
        } catch (Exception e) {
            throw wrap("归档读取轮区间失败（会话 " + sessionKey + " [" + fromInclusive + ","
                    + toInclusive + "]）", e);
        }
    }

    @Override
    public int recoverStaleOpenTurn(String sessionKey) {
        Optional<TurnRecord> open = findOpenTurn(sessionKey);
        if (open.isEmpty()) {
            return 0;
        }
        TurnRecord stale = open.get();
        abortTurn(sessionKey, stale.turnId(),
                "上一轮未闭合（进程中断或断点已过期），恢复时标记中止", null);
        return 1;
    }

    // ==================== 清理 ====================

    @Override
    public void clearSession(String sessionKey) {
        String key = buildKey(sessionKey);
        try {
            redisTemplate.delete(key);
            log.debug("[会话归档] 会话[{}] 归档已清除", sessionKey);
        } catch (Exception e) {
            throw wrap("归档清除失败（会话 " + sessionKey + "）", e);
        }
    }

    @Override
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        // 同记忆存储的空域守卫：空域会让 pattern 退化成「前缀|*」匹配全部域，
        // 一次误传就是全量清空，而归档含全量对话、不可恢复
        if (normalized.isEmpty()) {
            throw new ChatMemoryException(ErrorCode.INVALID_PARAMETER,
                    "deleteByDomain 收到空域，已拒绝执行（否则会清空全部域的会话归档）");
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
            int count = deleted == null ? 0 : deleted.intValue();
            log.info("[会话归档] 已按域清理 {} 个键（模式 {}）", count, pattern);
            return count;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            // 失败必须抛：删域流程靠它中止（清理在删域之前）。只 warn 等于把
            // "归档含全量对话"这条隐私保证静默降级，而用户看到的是"删除成功"
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "按域清理会话归档失败（模式 " + pattern + "）：" + e.getMessage()
                            + "。归档键可能残留 —— 需修复后重新执行删除", e);
        }
    }

    // ==================== 内部 ====================

    /**
     * 按 turnId 读一条，并校验元素自带的 turnId 与入参一致。
     *
     * <p>下标与 turnId 的双重校验防的是"外部改动过 List"（手工删过中间元素）：
     * 那种情况下盲目按下标写会覆盖别人的轮记录。</p>
     */
    private Optional<TurnRecord> readAt(String key, long turnId) {
        if (turnId < 1) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForList().index(key, turnId - 1);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        TurnRecord record = parse(raw);
        if (record == null) {
            return Optional.empty();
        }
        if (record.turnId() != turnId) {
            log.warn("[会话归档] 键 {} 下标 {} 处的轮记录 turnId={} 与期望 {} 不一致，按不存在处理",
                    key, turnId - 1, record.turnId(), turnId);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /**
     * 批量解析：单条坏元素只跳过并告警，不让整段历史读不出来。
     *
     * <p>归档是长期存储，里面可能混着改版前写入的老格式数据：
     * 为一条坏数据丢掉整段对话不划算。与记忆存储同一口径。</p>
     */
    private List<TurnRecord> parseAll(List<String> raw, String sessionKey) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<TurnRecord> out = new ArrayList<>(raw.size());
        for (String json : raw) {
            if (json == null || json.isBlank()) {
                continue;
            }
            TurnRecord record = parse(json);
            if (record == null) {
                log.warn("[会话归档] 会话[{}] 有 1 条轮记录无法解析，已跳过", sessionKey);
                continue;
            }
            out.add(record);
        }
        return List.copyOf(out);
    }

    private static TurnRecord parse(String json) {
        try {
            return MAPPER.readValue(json, TurnRecord.class);
        } catch (Exception e) {
            log.warn("[会话归档] 轮记录 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static String toJson(TurnRecord record) {
        try {
            return MAPPER.writeValueAsString(record);
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_WRITE_ERROR,
                    "轮记录序列化失败: " + e.getMessage(), e);
        }
    }

    private static ChatMemoryException wrap(String what, Exception e) {
        log.error("[会话归档] {}", what, e);
        return new ChatMemoryException(ErrorCode.CHAT_MEMORY_WRITE_ERROR, what + ": " + e.getMessage(), e);
    }

    private static String buildKey(String sessionKey) {
        return KEY_PREFIX + sessionKey;
    }
}
