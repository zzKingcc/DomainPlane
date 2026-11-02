package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.HistoryVectorIndex;
import com.zzkingcc.domainplane.api.conversation.TextEmbedder;
import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import com.zzkingcc.domainplane.common.util.TokenizerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F04-b 混合检索的两条降级契约。
 *
 * <p>关键词路认词形、向量路认语义：<b>纯标点查询切不出任何词根</b>，关键词路必然空手，
 * 这时语义路是唯一的召回来源——这个用例正好把"两路互补"钉死在最简单的断面上。
 * 反过来，语义路的任何一环不达（没开、嵌入失败、索引里是孤儿轮号）
 * 都只能让结果变回关键词路的样子，不能抛、不能返回半张卡片。</p>
 *
 * @author zzkingcc
 */
@DisplayName("F04-b 混合检索：语义路补召回 / 任一环不达即退化")
class RedisHistorySearchSemanticTest {

    private static final String SESSION = "default.sales|s-1";

    /** 只桩 Hash（向量索引要用），其余方法未用到 —— 碰到就炸，正好暴露测试走偏 */
    private static final class StubTemplate extends StringRedisTemplate {
        final Map<String, String> hash = new LinkedHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public HashOperations<String, Object, Object> opsForHash() {
            return (HashOperations<String, Object, Object>) Proxy.newProxyInstance(
                    StubTemplate.class.getClassLoader(),
                    new Class<?>[]{HashOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "put" -> {
                            hash.put(String.valueOf(args[1]), String.valueOf(args[2]));
                            yield null;
                        }
                        case "entries" -> new HashMap<>(hash);
                        case "size" -> (long) hash.size();
                        case "keys" -> Set.copyOf(hash.keySet());
                        case "toString" -> "stub-hash-ops";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "未桩的 HashOperations 方法：" + method.getName());
                    });
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            return true;
        }
    }

    private static TurnRecord turn(long turnId, String digest) {
        return new TurnRecord(turnId, SESSION,
                Instant.parse("2026-10-14T10:00:00Z").plusSeconds(turnId),
                Instant.parse("2026-10-14T10:00:08Z").plusSeconds(turnId),
                UserInput.of("问 " + turnId, List.of()), List.of(), "答 " + turnId,
                new TokenUsage(1, 2, 3), new TurnFlags(false, false, false, null), digest);
    }

    @SuppressWarnings("unchecked")
    private static ConversationArchive archiveWith(TurnRecord... records) {
        Map<Long, TurnRecord> byId = new HashMap<>();
        for (TurnRecord r : records) {
            byId.put(r.turnId(), r);
        }
        return (ConversationArchive) Proxy.newProxyInstance(
                RedisHistorySearchSemanticTest.class.getClassLoader(),
                new Class<?>[]{ConversationArchive.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "turnAt" -> Optional.ofNullable(byId.get((Long) args[1]));
                    case "recentTurns" -> new ArrayList<>(byId.values());
                    case "turnsBetween" -> new ArrayList<>(byId.values());
                    case "toString" -> "stub-archive";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "未桩的归档方法：" + method.getName());
                });
    }

    private static RedisHistorySearch search(StubTemplate template, ConversationArchive archive,
                                             TextEmbedder embedder, boolean enabled) {
        HistoryVectorIndex vectorIndex = new RedisHistoryVectorIndex(template);
        return new RedisHistorySearch(template, archive, TokenizerProfile.DEFAULT,
                vectorIndex, embedder, enabled);
    }

    @Test
    @DisplayName("关键词路空手时，语义路仍能把相关的老轮次勾回来")
    void semanticPathRecallsWhenKeywordMisses() {
        StubTemplate template = new StubTemplate();
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);
        index.index(SESSION, 2L, new float[]{1f, 0f, 0f});
        index.index(SESSION, 3L, new float[]{0f, 1f, 0f});

        TextEmbedder embedder = text -> new float[]{1f, 0f, 0f};
        RedisHistorySearch search = search(template, archiveWith(turn(2L, "退款时效"), turn(3L, "发票")),
                embedder, true);

        // 纯标点：切不出任何词根，关键词路必然空手
        List<HistoryCard> cards = search.search(SESSION, "！！！", 5);

        assertEquals(List.of(2L), cards.stream().map(HistoryCard::turnId).toList(),
                "语义路命中第 2 轮：字面无关但语义相同，正是加这一路的理由");
    }

    @Test
    @DisplayName("嵌入失败退化成关键词路的结果：不抛、不返回半截数据")
    void embedderFailureDegradesToKeyword() {
        StubTemplate template = new StubTemplate();
        TextEmbedder broken = text -> {
            throw new IllegalStateException("嵌入服务不可用");
        };
        RedisHistorySearch search = search(template, archiveWith(turn(2L, "退款")), broken, true);

        List<HistoryCard> cards = search.search(SESSION, "！！！", 5);
        assertTrue(cards.isEmpty(), "语义路挂了就只剩关键词路（这里关键词路也是空的）");
    }

    @Test
    @DisplayName("开关关闭 = 纯关键词：语义索引里有数据也不查")
    void disabledKeepsPureKeywordBehaviour() {
        StubTemplate template = new StubTemplate();
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);
        index.index(SESSION, 2L, new float[]{1f, 0f, 0f});

        RedisHistorySearch search = search(template, archiveWith(turn(2L, "退款")),
                text -> new float[]{1f, 0f, 0f}, false);

        assertTrue(search.search(SESSION, "！！！", 5).isEmpty(),
                "关掉语义路后行为必须与加这一路之前一字不变");
    }

    @Test
    @DisplayName("索引里的孤儿轮号（归档读不到）直接跳过，不让整次检索失败")
    void orphanVectorHitIsSkipped() {
        StubTemplate template = new StubTemplate();
        RedisHistoryVectorIndex index = new RedisHistoryVectorIndex(template);
        index.index(SESSION, 99L, new float[]{1f, 0f, 0f});   // 归档里没有第 99 轮

        RedisHistorySearch search = search(template, archiveWith(turn(2L, "退款")),
                text -> new float[]{1f, 0f, 0f}, true);

        assertTrue(search.search(SESSION, "！！！", 5).isEmpty(),
                "索引是派生数据：真相里没有的轮次，不该凭索引编一张卡片出来");
    }

    @Test
    @DisplayName("嵌入器返回空（未配向量模型）时静默走关键词路")
    void nullEmbeddingMeansKeywordOnly() {
        StubTemplate template = new StubTemplate();
        RedisHistorySearch search = search(template, archiveWith(turn(2L, "退款")),
                text -> null, true);

        assertTrue(search.search(SESSION, "！！！", 5).isEmpty());
        assertEquals(0, new RedisHistoryVectorIndex(template).size(SESSION),
                "返回空向量不应写进索引");
    }
}
