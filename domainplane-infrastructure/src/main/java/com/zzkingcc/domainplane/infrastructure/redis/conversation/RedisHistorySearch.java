package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.common.util.CjkTokenizer;
import com.zzkingcc.domainplane.common.util.TokenizerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Redis 的会话历史倒排检索（方案 §6.2）：一词根一 ZSET + 一个词根登记 Set。
 *
 * <p><b>为什么一词根一键</b>：Redis 没有"按集合成员反查"的原生结构，一词根一 ZSET
 * 让"这个词命中了哪些轮"就是一次 {@code ZREVRANGE}，member/score 都用 turnId
 * （score 即轮号，天然支持"取最新的 N 轮"）。</p>
 *
 * <p><b>为什么还要一个 terms Set</b>：删域时要精确删掉本会话的索引键。
 * 按 {@code domainplane:conv:idx:{域}|*} 通配删会漏（sessionId 含 glob 字符时
 * 通配语义还会扩大删除面），登记表让删除是精确集合运算。</p>
 *
 * <p><b>派生数据</b>：写失败只告警（归档已经落盘，重放归档即可重建索引），
 * 查不到走 {@link HistorySearch#FALLBACK_TAIL} 子串扫描。唯一必须抛的是
 * {@link #deleteByDomain} —— 那是删域级联，漏删等于历史还能被勾回来。</p>
 *
 * @author zzkingcc
 */
public class RedisHistorySearch implements HistorySearch {

    private static final Logger log = LoggerFactory.getLogger(RedisHistorySearch.class);

    /** 索引键前缀：{@code domainplane:conv:idx:{域}|{session}:{词根}} */
    private static final String IDX_PREFIX = "domainplane:conv:idx:";

    /** 词根登记键前缀：{@code domainplane:conv:terms:{域}|{session}} */
    private static final String TERMS_PREFIX = "domainplane:conv:terms:";

    /** SCAN 每批拉取的键数 */
    private static final int SCAN_BATCH = 500;

    /** 新近衰减权重（方案 §6.2）：{@code RECENCY_WEIGHT × 0.5^(轮龄/50)} */
    private static final double RECENCY_WEIGHT = 2.0;

    /** 轮龄衰减半衰期（轮） */
    private static final double RECENCY_HALF_LIFE = 50.0;

    private final StringRedisTemplate redisTemplate;
    private final ConversationArchive archive;
    private final TokenizerProfile profile;

    /** 按默认分词口径构造（权重 3/2/1，仅内置停用词表） */
    public RedisHistorySearch(StringRedisTemplate redisTemplate, ConversationArchive archive) {
        this(redisTemplate, archive, TokenizerProfile.DEFAULT);
    }

    /**
     * @param profile 分词调优参数（M4 治理）：权重负责排序、追加停用词负责滤噪。
     *                <b>它同时决定写索引与查询两侧的口径</b>，改动后已建索引需重放归档重建。
     */
    public RedisHistorySearch(StringRedisTemplate redisTemplate, ConversationArchive archive,
                              TokenizerProfile profile) {
        this.redisTemplate = redisTemplate;
        this.archive = archive;
        this.profile = profile == null ? TokenizerProfile.DEFAULT : profile;
    }

    /**
     * 索引键的存活期。
     *
     * 索引是派生数据：归档才是事实来源。会话归档被清理之后，{@code idx:*} / {@code terms:*}
     * 没有任何按会话清理的入口，不设 TTL 就永久残留。30 天无写入即自然回收。
     */
    private static final java.time.Duration INDEX_TTL = java.time.Duration.ofDays(30);

    // ==================== 写 ====================

    @Override
    public void indexTurn(String sessionKey, TurnRecord record) {
        if (record == null || record.isOpen() || sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        List<CjkTokenizer.Term> terms = CjkTokenizer.tokenize(indexTextOf(record), profile);
        if (terms.isEmpty()) {
            return;
        }
        String member = String.valueOf(record.turnId());
        try {
            // SessionCallback.execute 是泛型方法（<K,V>），lambda 描述不了它，只能用匿名类。
            // 用 SessionCallback 而不是裸 RedisCallback：opsForZSet/opsForSet 会自己处理
            // 键值序列化，裸连接那套要手写 UTF-8 编解码，多一处能错的地方。
            List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    /* execute 的 <K,V> 是方法级类型变量，在方法体内推不出具体类型，
                       必须显式收窄成 String。StringRedisTemplate 的键值序列化器
                       恒为 StringRedisSerializer，这个强转是安全的。 */
                    RedisOperations<String, String> session =
                            (RedisOperations<String, String>) operations;
                    for (CjkTokenizer.Term term : terms) {
                        String key = idxKey(sessionKey, term.text());
                        session.opsForZSet().add(key, member, (double) record.turnId());
                        // 派生索引必须自带存活期：会话归档被删之后这些键没有别的清理入口，
                        // 不设 TTL 就永久残留，而且每轮都是几十个键起。
                        session.expire(key, INDEX_TTL);
                    }
                    session.opsForSet().add(termsKey(sessionKey),
                            terms.stream().map(CjkTokenizer.Term::text).toArray(String[]::new));
                    session.expire(termsKey(sessionKey), INDEX_TTL);
                    return null;
                }
            });
            raiseIfPipelineFailed(results, "写入索引");
            MetricsRegistry.indexed(terms.size());
            log.debug("[会话检索] 会话[{}] turnId={} 已入索引，词根 {} 个", sessionKey, record.turnId(),
                    terms.size());
        } catch (Exception e) {
            // 索引是派生数据：归档已经落盘，这一轮只是不能被倒排列中，
            // 靠 FALLBACK_TAIL 子串扫描兜底。抛出去会让一次正常对话失败，不值当。
            MetricsRegistry.indexWriteFailure();
            log.warn("[会话检索] 会话[{}] turnId={} 写索引失败，该轮靠子串扫描兜底：{}",
                    sessionKey, record.turnId(), e.getMessage());
        }
    }

    /**
     * 索引文本 = digest + 用户输入 + 最终回答 + 工具名。<b>思考不进索引</b>。
     */
    private static String indexTextOf(TurnRecord record) {
        StringBuilder sb = new StringBuilder();
        if (record.digest() != null) {
            sb.append(record.digest()).append('\n');
        }
        if (record.user() != null && record.user().text() != null) {
            sb.append(record.user().text()).append('\n');
        }
        if (record.answer() != null) {
            sb.append(record.answer()).append('\n');
        }
        if (record.flow() != null) {
            for (var e : record.flow()) {
                if (e.type() == FlowEventType.TOOL_CALL && e.toolName() != null) {
                    sb.append(e.toolName()).append(' ');
                }
            }
        }
        return sb.toString();
    }

    // ==================== 查 ====================

    @Override
    public List<HistoryCard> search(String sessionKey, String query, int limit) {
        if (sessionKey == null || sessionKey.isBlank() || query == null || query.isBlank()
                || limit <= 0) {
            return List.of();
        }
        List<CjkTokenizer.Term> terms = CjkTokenizer.tokenize(query, profile);
        if (terms.isEmpty()) {
            // 查询切不出任何词根（纯标点）：算一次空手，但没走兜底
            MetricsRegistry.recallSearch(false, false);
            return List.of();
        }

        Map<Long, Integer> hits = new HashMap<>();
        try {
            hits = candidatesFromIndex(sessionKey, terms);
        } catch (Exception e) {
            log.warn("[会话检索] 会话[{}] 倒排查询失败，降级到子串扫描：{}", sessionKey, e.getMessage());
        }
        if (hits.isEmpty()) {
            List<HistoryCard> fallback = fallbackSubstringScan(sessionKey, query, limit);
            // 兜底有结果也算命中，但 fallback 标记要留，与命中率一起看才说明"索引是否真的在用"
            MetricsRegistry.recallSearch(!fallback.isEmpty(), true);
            return fallback;
        }

        List<TurnRecord> records = readTurns(sessionKey, hits.keySet());
        List<Long> ranked = rank(records, hits, limit);
        List<HistoryCard> cards = cardsOf(readTurnsInOrder(sessionKey, ranked));
        // 索引给了候选、但卡片全被过滤掉（开放轮 / 读档失败）同样是空手
        MetricsRegistry.recallSearch(!cards.isEmpty(), false);
        return cards;
    }

    /** 每词根取最新 {@link HistorySearch#CANDIDATES_PER_TERM} 轮，合并成"轮号 → 命中词根权重和" */
    private Map<Long, Integer> candidatesFromIndex(String sessionKey, List<CjkTokenizer.Term> terms) {
        List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> session =
                        (RedisOperations<String, String>) operations;
                for (CjkTokenizer.Term term : terms) {
                    session.opsForZSet()
                            .reverseRange(idxKey(sessionKey, term.text()), 0, CANDIDATES_PER_TERM - 1);
                }
                return null;
            }
        });
        raiseIfPipelineFailed(results, "倒排查询");

        Map<Long, Integer> hits = new HashMap<>();
        int termIndex = 0;
        for (CjkTokenizer.Term term : terms) {
            Object raw = results.size() > termIndex ? results.get(termIndex) : null;
            termIndex++;
            for (String member : membersOf(raw)) {
                try {
                    hits.merge(Long.parseLong(member), profile.weightOf(term.kind()), Integer::sum);
                } catch (NumberFormatException ignore) {
                    // 成员不是 turnId：索引被外部改过，跳过这一个
                }
                if (hits.size() >= MAX_CANDIDATES) {
                    return hits;
                }
            }
        }
        return hits;
    }

    /**
     * pipeline 单条命令的返回值 → 成员列表。
     *
     * <p>{@code reverseRange} 回来的是 {@link Set}（Lettuce 侧是 LinkedHashSet），
     * 不是 List，早年这里写成 {@code (List<String>) (List<?>) set}，看着像"泛型强转"，
     * 实际 checkcast 落的是 {@code java.util.List}，一跑就 CCE。按集合迭代，
     * 非 String 成员（索引被外部写坏）跳过，不让一条脏数据炸掉整次检索。</p>
     */
    private static List<String> membersOf(Object raw) {
        if (raw instanceof Collection<?> c) {
            List<String> out = new ArrayList<>(c.size());
            for (Object o : c) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    /**
     * 打分排序：命中词根权重和 + 新近衰减，返回排好序的 turnId。
     *
     * <p>轮龄的"当前轮"取候选集合里最大的 turnId，而不是去读归档的最新轮号：
     * 多一次 Redis 往返只为拿一个参照点不划算，而按候选内最大值算时，
     * 候选里最新的那一轮照样拿到最高新近分，排序结果不受影响。</p>
     */
    private static List<Long> rank(List<TurnRecord> records, Map<Long, Integer> hits, int limit) {
        long newest = records.stream().mapToLong(TurnRecord::turnId).max().orElse(0L);
        List<Map.Entry<Long, Double>> scored = new ArrayList<>();
        for (TurnRecord r : records) {
            Integer weight = hits.get(r.turnId());
            if (weight == null || r.isOpen()) {
                continue;
            }
            double age = Math.max(0, newest - r.turnId());
            scored.add(Map.entry(r.turnId(),
                    weight + RECENCY_WEIGHT * Math.pow(0.5, age / RECENCY_HALF_LIFE)));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Long> out = new ArrayList<>(scored.size());
        for (Map.Entry<Long, Double> e : scored) {
            out.add(e.getKey());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** 按轮号读轮记录（逐个 LINDEX；候选数 ≤ limit，通常是个位数） */
    private List<TurnRecord> readTurns(String sessionKey, Set<Long> turnIds) {
        if (turnIds.isEmpty()) {
            return List.of();
        }
        List<TurnRecord> out = new ArrayList<>(turnIds.size());
        for (Long id : turnIds) {
            try {
                archive.turnAt(sessionKey, id).ifPresent(out::add);
            } catch (Exception e) {
                log.warn("[会话检索] 会话[{}] 读 turnId={} 失败，跳过该轮", sessionKey, id);
            }
        }
        return out;
    }

    /** 按给定顺序读轮记录，保持顺序（卡片要按相关度从高到低给出） */
    private List<TurnRecord> readTurnsInOrder(String sessionKey, List<Long> turnIds) {
        List<TurnRecord> out = new ArrayList<>(turnIds.size());
        for (Long id : turnIds) {
            try {
                archive.turnAt(sessionKey, id).ifPresent(out::add);
            } catch (Exception e) {
                log.warn("[会话检索] 会话[{}] 读 turnId={} 失败，跳过该轮", sessionKey, id);
            }
        }
        return out;
    }

    /**
     * 降级：对最近 {@link HistorySearch#FALLBACK_TAIL} 轮做应用内子串匹配。
     *
     * <p>覆盖两种"索引不达"：本会话从没写过索引（老数据），以及刚写索引失败。
     * 子串匹配比倒排粗（无分词、无权重），但它是<b>兜底</b>不是主路径：
     * 有结果总比没有好，而"没有"会让卡片段整段消失。</p>
     */
    private List<HistoryCard> fallbackSubstringScan(String sessionKey, String query, int limit) {
        List<TurnRecord> tail;
        try {
            tail = archive.recentTurns(sessionKey, FALLBACK_TAIL);
        } catch (Exception e) {
            log.warn("[会话检索] 会话[{}] 子串扫描兜底也失败：{}", sessionKey, e.getMessage());
            return List.of();
        }
        String needle = query.trim();
        List<TurnRecord> matched = new ArrayList<>();
        for (int i = tail.size() - 1; i >= 0 && matched.size() < limit; i--) {
            TurnRecord t = tail.get(i);
            if (t.isOpen()) {
                continue;
            }
            if (indexTextOf(t).contains(needle)) {
                matched.add(t);
            }
        }
        return cardsOf(matched);
    }

    // ==================== 清理 ====================

    @Override
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        // 同归档存储的空域守卫：空域会让 pattern 退化成匹配全部域
        if (normalized.isEmpty()) {
            throw new ChatMemoryException(ErrorCode.INVALID_PARAMETER,
                    "deleteByDomain 收到空域，已拒绝执行（否则会清空全部域的会话检索索引）");
        }
        String termsPattern = TERMS_PREFIX + normalized + SessionKeys.SEPARATOR + "*";
        String idxPattern = IDX_PREFIX + normalized + SessionKeys.SEPARATOR + "*";
        try {
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(
                    ScanOptions.scanOptions().match(termsPattern).count(SCAN_BATCH).build())) {
                cursor.forEachRemaining(keys::add);
            }
            // 先按登记表精确删索引键，再删登记表自身；最后按前缀兜一次
            // （登记表可能因写索引时崩溃而缺条目，前缀扫描负责捡漏）
            int deleted = 0;
            for (String termsKey : keys) {
                Set<String> terms = redisTemplate.opsForSet().members(termsKey);
                if (terms != null && !terms.isEmpty()) {
                    List<String> idxKeys = new ArrayList<>(terms.size());
                    for (String term : terms) {
                        idxKeys.add(idxKeyOfTermsKey(termsKey, term));
                    }
                    Long removed = redisTemplate.delete(idxKeys);
                    deleted += removed == null ? 0 : removed.intValue();
                }
            }
            if (!keys.isEmpty()) {
                Long removedTerms = redisTemplate.delete(keys);
                deleted += removedTerms == null ? 0 : removedTerms.intValue();
            }

            List<String> leftovers = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(
                    ScanOptions.scanOptions().match(idxPattern).count(SCAN_BATCH).build())) {
                cursor.forEachRemaining(leftovers::add);
            }
            if (!leftovers.isEmpty()) {
                Long removed = redisTemplate.delete(leftovers);
                deleted += removed == null ? 0 : removed.intValue();
            }
            log.info("[会话检索] 已按域清理 {} 个索引键（域 {}）", deleted, normalized);
            return deleted;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "按域清理会话检索索引失败（域 " + normalized + "）：" + e.getMessage()
                            + "。索引键可能残留 —— 需修复后重新执行删除", e);
        }
    }

    // ==================== 内部 ====================

    private static String idxKey(String sessionKey, String term) {
        return IDX_PREFIX + sessionKey + ':' + term;
    }

    private static String termsKey(String sessionKey) {
        return TERMS_PREFIX + sessionKey;
    }

    /** 由登记表键反推索引键：{@code terms:{域}|{session}} → {@code idx:{域}|{session}:{词根}} */
    private static String idxKeyOfTermsKey(String termsKey, String term) {
        return IDX_PREFIX + termsKey.substring(TERMS_PREFIX.length()) + ':' + term;
    }

    /**
     * pipeline 的结果里出现异常必须翻出来。
     *
     * <p>Lettuce 的 pipeline 是惰性的：命令的异常不会在发送时抛出，
     * 而是作为结果列表里的一个元素返回。不检查就等于把所有写失败都当成成功：
     * 对派生数据是"索引静默缺失"，对 {@code deleteByDomain} 是"删域漏删"。</p>
     */
    private static void raiseIfPipelineFailed(List<Object> results, String what) {
        if (results == null) {
            return;
        }
        for (Object r : results) {
            if (r instanceof Exception e) {
                throw new IllegalStateException(what + " 的 pipeline 命令失败：" + e.getMessage(), e);
            }
        }
    }

    /**
     * 把轮记录组装成卡片。
     *
     * <p>与运行层 {@code ContextAssembler.cardsOf} 同口径，但基础设施层看不到运行层，
     * 只能各写一份，两条规则必须同时改：排除开放轮与中止轮、工具名去重保序。</p>
     */
    private static List<HistoryCard> cardsOf(List<TurnRecord> records) {
        List<HistoryCard> out = new ArrayList<>(records.size());
        for (TurnRecord t : records) {
            if (t == null || t.isOpen()) {
                continue;
            }
            if (t.flags() != null && t.flags().aborted()) {
                continue;
            }
            Set<String> tools = new LinkedHashSet<>();
            for (var e : t.flow()) {
                if (e.type() == FlowEventType.TOOL_CALL && e.toolName() != null) {
                    tools.add(e.toolName());
                }
            }
            out.add(new HistoryCard(t.turnId(), t.startedAt(), t.digest(), List.copyOf(tools)));
        }
        return out;
    }
}
