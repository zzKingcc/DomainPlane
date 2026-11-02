package com.zzkingcc.domainplane.infrastructure.redis.conversation;

import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.HistoryVectorIndex;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 单会话体量盘点与导出副本（F04-c）。
 *
 * <p><b>只量、只导、不删</b>：这一块的存在前提就是"存储无限增长是 accepted cost，不是待办"
 * ——用户约束下记忆与归档只增不减，任何"清理/瘦身/LRU"需求都不得先于这条约束。
 * 于是运维侧真正缺的不是删除按钮，而是两件事：<b>看得见</b>（哪个会话长到该注意了）
 * 和<b>搬得走</b>（要迁移、要留存、要给人看时拿得出一份完整副本）。</p>
 *
 * <p><b>为什么不提供清理接口</b>：一旦有了"清理"这个动作，它就一定会长成"按体量自动清理"，
 * 而自动清理与"原始记忆不主动删除"是正面冲突的。这里把口子收死：接口只返回数字与副本。</p>
 *
 * <p>体量取自各键的 {@code LLEN} / {@code HLEN}，都是 O(1)，不做全量读取：
 * 盘点一个会话不该把它的全部内容拉进内存。</p>
 *
 * @author zzkingcc
 */
public class RedisSessionStorage {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStorage.class);

    private static final String MEMORY_PREFIX = "domainplane:chat:memory:";
    private static final String ARCHIVE_PREFIX = "domainplane:chat:archive:";
    private static final String ASSEMBLY_PREFIX = "domainplane:conv:asm:";

    /** 一次盘点最多扫多少个归档键（运维接口不能被一次 SCAN 拖死） */
    private static final int MAX_SCAN = 5_000;

    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redisTemplate;
    private final ConversationArchive archive;
    private final AssemblyReportStore assemblyReportStore;
    private final HistoryVectorIndex vectorIndex;

    public RedisSessionStorage(StringRedisTemplate redisTemplate, ConversationArchive archive,
                               AssemblyReportStore assemblyReportStore, HistoryVectorIndex vectorIndex) {
        this.redisTemplate = redisTemplate;
        this.archive = archive;
        this.assemblyReportStore = assemblyReportStore;
        this.vectorIndex = vectorIndex;
    }

    /**
     * 一个会话的体量快照。
     *
     * @param memoryMessages   记忆里的消息条数（近况窗口的原文来源）
     * @param archiveTurns     归档轮数（全流程档案，永不淘汰）
     * @param vectorTurns      已建向量的轮数（F04-b 语义路；0 = 未开启或未建）
     * @param assemblyReports  已留存的装配清单条数（F04-a；上限 {@link AssemblyReportStore#KEEP_PER_SESSION}）
     */
    public record Snapshot(String sessionKey, String domain, String sessionId,
                           long memoryMessages, long archiveTurns, int vectorTurns,
                           long assemblyReports) {
    }

    /** 导出副本：归档全轮 + 最近若干条装配清单 */
    public record Export(String sessionKey, Instant exportedAt, List<TurnRecord> turns,
                         List<ContextAssemblyReport> assemblyReports) {
    }

    /** 体量最大的 {@code limit} 个会话（按归档轮数降序） */
    public List<Snapshot> topBySize(int limit) {
        int n = Math.max(1, limit);
        List<Snapshot> out = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(ARCHIVE_PREFIX + "*").count(SCAN_BATCH).build())) {
            int scanned = 0;
            while (cursor.hasNext() && scanned < MAX_SCAN) {
                String key = cursor.next();
                scanned++;
                out.add(snapshotOf(key.substring(ARCHIVE_PREFIX.length())));
            }
            if (scanned >= MAX_SCAN) {
                log.warn("[会话体量] 归档键超过 {} 个，本次只盘点了前 {} 个（提高 limit 无效，需按域分批查）",
                        MAX_SCAN, MAX_SCAN);
            }
        } catch (Exception e) {
            log.warn("[会话体量] 扫描归档键失败：{}", e.getMessage());
        }
        out.sort(Comparator.comparingLong(Snapshot::archiveTurns).reversed());
        return out.size() <= n ? out : new ArrayList<>(out.subList(0, n));
    }

    /** 单个会话的体量快照（键不存在时各项为 0，不返回空——"这个会话没有档案"本身就是答案） */
    public Snapshot snapshotOf(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return new Snapshot("", "", "", 0L, 0L, 0, 0L);
        }
        return new Snapshot(sessionKey, SessionKeys.domainOf(sessionKey),
                SessionKeys.sessionIdOf(sessionKey),
                lengthOf(MEMORY_PREFIX + sessionKey), lengthOf(ARCHIVE_PREFIX + sessionKey),
                vectorIndex == null ? 0 : vectorIndex.size(sessionKey),
                lengthOf(ASSEMBLY_PREFIX + sessionKey));
    }

    /**
     * 只知道 sessionId 时反查会话键（管控台常常只有 sessionId，域要现查）。
     *
     * <p>扫描而不是建一张"sessionId → sessionKey"的登记表：登记表是又一处要跟着删域级联扩的键空间，
     * 而反查只在运维接口上低频发生，慢一点可以接受。</p>
     */
    public Optional<String> findSessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String suffix = SessionKeys.SEPARATOR + sessionId;
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(ARCHIVE_PREFIX + "*").count(SCAN_BATCH).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String sessionKey = key.substring(ARCHIVE_PREFIX.length());
                if (sessionKey.endsWith(suffix)) {
                    return Optional.of(sessionKey);
                }
            }
        } catch (Exception e) {
            log.warn("[会话体量] 反查会话键失败（sessionId={}）：{}", sessionId, e.getMessage());
        }
        return Optional.empty();
    }

    /** 导出一个会话的副本；会话没有归档时返回空 */
    public Optional<Export> export(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank() || archive == null) {
            return Optional.empty();
        }
        long turns = lengthOf(ARCHIVE_PREFIX + sessionKey);
        if (turns <= 0) {
            return Optional.empty();
        }
        List<TurnRecord> records = archive.turnsBetween(sessionKey, 1, turns);
        List<ContextAssemblyReport> reports = assemblyReportStore == null
                ? List.of() : assemblyReportStore.recent(sessionKey, AssemblyReportStore.KEEP_PER_SESSION);
        return Optional.of(new Export(sessionKey, Instant.now(), records, reports));
    }

    private long lengthOf(String key) {
        try {
            Long len = redisTemplate.opsForList().size(key);
            return len == null ? 0L : len;
        } catch (Exception e) {
            log.warn("[会话体量] 读键[{}]长度失败，按 0 计：{}", key, e.getMessage());
            return 0L;
        }
    }
}
