package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.conversation.HistoryVectorIndex;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归档轮次向量索引的 Redis 实现（F04-b）。
 *
 * <p><b>为什么不用向量库</b>：这一路的数据量是"一个会话几百轮"，不是知识库那种跨全库的百万切片。
 * 一个 Hash 装下、检索时应用内算余弦就够了（500 轮 × 1024 维 ≈ 一次几毫秒的浮点循环）。
 * 为它引一套向量库，等于给"每会话几百条"的量级配上分布式检索的运维成本。
 * 真到了单会话几千轮还嫌慢，再换不迟——索引是派生数据，换实现就是重放一次归档。</p>
 *
 * <p><b>向量是长尾大值</b>：一轮 1024 维 float 约 4 KB base64，500 轮就是 2 MB 一个键。
 * 所以必须有 {@link #MAX_TURNS_PER_SESSION} 这个闸：老轮次的语义召回价值低
 * （它们在摘要里、在关键词索引里），却要占着最贵的存储。</p>
 *
 * @author zzkingcc
 */
public class RedisHistoryVectorIndex implements HistoryVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(RedisHistoryVectorIndex.class);

    /** 键前缀：{@code domainplane:conv:vec:{域}|{session}} */
    private static final String KEY_PREFIX = "domainplane:conv:vec:";

    private static final int SCAN_BATCH = 500;

    /** 与倒排索引同族口径：30 天无写入即回收 */
    private static final Duration TTL = Duration.ofDays(30);

    /** 裁剪的迟滞带：攒够这么多富余才动一次手，避免每轮都全量读 field 排序 */
    private static final int TRIM_SLACK = 20;

    private final StringRedisTemplate redisTemplate;

    public RedisHistoryVectorIndex(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void index(String sessionKey, long turnId, float[] vector) {
        if (sessionKey == null || sessionKey.isBlank() || vector == null || vector.length == 0) {
            return;
        }
        String key = KEY_PREFIX + sessionKey;
        try {
            redisTemplate.opsForHash().put(key, String.valueOf(turnId), encode(vector));
            redisTemplate.expire(key, TTL);
            trimIfNeeded(key);
        } catch (Exception e) {
            log.warn("[历史向量] 会话[{}] turnId={} 写向量索引失败，该轮靠关键词路兜底：{}",
                    sessionKey, turnId, e.getMessage());
        }
    }

    /**
     * 超出上限即裁最老的若干轮。
     *
     * <p>只在超过 {@code 上限 + 迟滞带} 时才动手：每次写都读全量 field 排序，
     * 等于给每轮闭轮加一次 O(轮数) 的操作，不值当。</p>
     */
    private void trimIfNeeded(String key) {
        try {
            Long size = redisTemplate.opsForHash().size(key);
            if (size == null || size <= MAX_TURNS_PER_SESSION + TRIM_SLACK) {
                return;
            }
            Set<Object> fields = redisTemplate.opsForHash().keys(key);
            if (fields == null || fields.size() <= MAX_TURNS_PER_SESSION) {
                return;
            }
            List<Long> ids = new ArrayList<>(fields.size());
            for (Object f : fields) {
                try {
                    ids.add(Long.parseLong(String.valueOf(f)));
                } catch (NumberFormatException ignored) {
                    // 非数字 field 不是本类写进去的，跳过即可（删它反而可能动到别人的数据）
                }
            }
            ids.sort(Comparator.naturalOrder());
            int drop = ids.size() - MAX_TURNS_PER_SESSION;
            Object[] victims = new Object[drop];
            for (int i = 0; i < drop; i++) {
                victims[i] = String.valueOf(ids.get(i));
            }
            redisTemplate.opsForHash().delete(key, victims);
        } catch (Exception e) {
            log.warn("[历史向量] 裁剪向量索引失败（不影响本轮对话）：{}", e.getMessage());
        }
    }

    @Override
    public List<Long> search(String sessionKey, float[] queryVector, int limit) {
        if (sessionKey == null || sessionKey.isBlank() || queryVector == null || queryVector.length == 0
                || limit <= 0) {
            return List.of();
        }
        String key = KEY_PREFIX + sessionKey;
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            List<long[]> scored = new ArrayList<>(entries.size());   // {turnId, score×1e6}
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                long turnId;
                try {
                    turnId = Long.parseLong(String.valueOf(e.getKey()));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                float[] v = decode(String.valueOf(e.getValue()));
                if (v == null || v.length != queryVector.length) {
                    // 维度对不上 = 换过向量模型，旧向量已无意义：跳过它，等它自然被裁掉
                    continue;
                }
                double similarity = cosine(queryVector, v);
                if (similarity < MIN_SIMILARITY) {
                    // 语义路宁可空手也不拿噪声充数：卡片段只有几张位子，塞进不相关的等于挤掉相关的
                    continue;
                }
                scored.add(new long[]{turnId, (long) (similarity * 1_000_000L)});
            }
            scored.sort((a, b) -> Long.compare(b[1], a[1]));
            List<Long> out = new ArrayList<>(Math.min(limit, scored.size()));
            for (int i = 0; i < limit && i < scored.size(); i++) {
                out.add(scored.get(i)[0]);
            }
            return out;
        } catch (Exception e) {
            log.warn("[历史向量] 会话[{}] 向量检索失败，本轮退化为纯关键词：{}", sessionKey, e.getMessage());
            return List.of();
        }
    }

    @Override
    public int size(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return 0;
        }
        try {
            Long size = redisTemplate.opsForHash().size(KEY_PREFIX + sessionKey);
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.warn("[历史向量] 会话[{}] 读向量条数失败：{}", sessionKey, e.getMessage());
            return 0;
        }
    }

    @Override
    public int deleteBySession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return 0;
        }
        try {
            Boolean removed = redisTemplate.delete(KEY_PREFIX + sessionKey);
            return Boolean.TRUE.equals(removed) ? 1 : 0;
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "删除会话历史向量索引失败（会话 " + sessionKey + "）：" + e.getMessage(), e);
        }
    }

    @Override
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        if (normalized.isEmpty()) {
            throw new ChatMemoryException(ErrorCode.INVALID_PARAMETER,
                    "deleteByDomain 收到空域，已拒绝执行（否则会清空全部域的历史向量索引）");
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
            log.info("[历史向量] 已按域清理 {} 个向量索引键（域 {}）", deleted, normalized);
            return deleted;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "按域清理历史向量索引失败（域 " + normalized + "）：" + e.getMessage()
                            + "。索引键可能残留 —— 需修复后重新执行删除", e);
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0.0 || nb <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String encode(float[] vector) {
        ByteBuffer buf = ByteBuffer.allocate(vector.length * Float.BYTES);
        buf.asFloatBuffer().put(vector);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static float[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length % Float.BYTES != 0) {
                return null;
            }
            FloatBuffer fb = ByteBuffer.wrap(bytes).asFloatBuffer();
            float[] out = new float[fb.remaining()];
            fb.get(out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
