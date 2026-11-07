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
 * <p><b>只量、只导、不删</b>：本类只提供盘点与副本，删除动作不在这里。
 * 存储无限增长仍是 accepted cost，运维侧需要的是<b>看得见</b>（哪个会话长到该注意了）
 * 和<b>搬得走</b>（要迁移、要留存、要给人看时拿得出一份完整副本）。</p>
 *
 * <p><b>为什么清理不放在这里</b>：单会话清空是会话维度、跨六路存储的级联动作，
 * 由 server 层的 {@code SessionPurgeService} 统一编排；本类在 infrastructure 层，
 * 看不到检查点、滚动摘要、倒排这些存储。口子仍然是收死的：允许人工指名删一个会话，
 * 不允许按体量排行、批量或定时清理 —— 后者与"原始记忆不主动淘汰"正面冲突。</p>
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

    /**
     * 一页轮记录（F04-d：按轮号区间分页，只读）。
     *
     * @param total            该会话的归档总轮数（LLEN，O(1)）
     * @param from             本页起始轮号，1 基；不给时落到最后一页
     * @param size             每页条数（已 clamp 到 1–50）
     * @param turns            本页轮记录，由旧到新；越界由 turnsBetween 收敛为空
     * @param assemblyReports  本页轮号命中的装配清单；账本只留最近 50 条且带 TTL，可能为空
     */
    public record Page(String sessionKey, long total, long from, int size,
                       List<TurnRecord> turns, List<ContextAssemblyReport> assemblyReports) {
    }

    /**
     * 会话清单（F04-e：按域筛选的表格）。
     *
     * @param domains  扫描到的<b>全部</b>域，不受筛选影响（下拉要能切回别的域）
     * @param sessions 筛选后按归档轮数降序的 top N 个会话
     */
    public record Listing(List<String> domains, List<Snapshot> sessions) {
    }

    /**
     * 按域列会话，同时给出有档案的域清单（F04-e 表格数据源）。
     *
     * <p>一次 SCAN 同时产出两样东西是刻意的：域清单必须来自<b>全部</b>归档键，
     * 不能只从筛选结果里取，否则选中某个域之后下拉里就只剩这一个域、切不回去了。
     * 过滤放在应用内而非拼进 SCAN 的 MATCH：域清单本来就需要遍历全量键，
     * 顺手比一下字符串比再拼一处 glob 更省事，也不用担心域路径里的特殊字符。</p>
     *
     * <p>筛选是<b>精确匹配</b>，不向下含子域：会话键里的域是开轮那一刻的装配域，
     * 而域链继承是能力绑定规则，两者不是一回事。</p>
     *
     * @param domain 域筛选；空 = 全部域
     * @param limit  返回条数（按归档轮数降序）
     */
    public Listing list(String domain, int limit) {
        int n = Math.max(1, limit);
        String filter = domain == null || domain.isBlank() ? null : domain.trim();
        List<Snapshot> out = new ArrayList<>();
        List<String> domains = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(ARCHIVE_PREFIX + "*").count(SCAN_BATCH).build())) {
            int scanned = 0;
            while (cursor.hasNext() && scanned < MAX_SCAN) {
                String key = cursor.next();
                scanned++;
                String sessionKey = key.substring(ARCHIVE_PREFIX.length());
                String d = SessionKeys.domainOf(sessionKey);
                if (d != null && !d.isBlank()) {
                    domains.add(d);
                }
                if (filter == null || filter.equals(d)) {
                    out.add(snapshotOf(sessionKey));
                }
            }
            if (scanned >= MAX_SCAN) {
                log.warn("[会话体量] 归档键超过 {} 个，本次只盘点了前 {} 个（提高 limit 无效，需按域分批查）",
                        MAX_SCAN, MAX_SCAN);
            }
        } catch (Exception e) {
            log.warn("[会话体量] 扫描归档键失败：{}", e.getMessage());
        }
        out.sort(Comparator.comparingLong(Snapshot::archiveTurns).reversed());
        List<Snapshot> page = out.size() <= n ? out : new ArrayList<>(out.subList(0, n));
        return new Listing(domains.stream().distinct().sorted().toList(), page);
    }

    /** 保留旧入口：体量最大的 {@code limit} 个会话（按归档轮数降序） */
    public List<Snapshot> topBySize(int limit) {
        return list(null, limit).sessions();
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

    /**
     * 按轮号区间取一页（F04-d：长会话不全量拉进内存）。
     *
     * <p>分页键是<b>轮号</b>而不是偏移量：存储下标恒为 {@code turnId - 1}，新轮只 RPUSH
     * 追加到尾部，已有页的轮号永不重排，翻页不会重复或漏轮，因此不需要游标、登记表或索引。</p>
     *
     * @param fromRaw 起始轮号；null / 非正数表示"最后一页"（查档默认从最新一轮看起）
     * @param sizeRaw 每页条数；null 取 20，clamp 到 1–50
     */
    public Page page(String sessionKey, Long fromRaw, Integer sizeRaw) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return new Page("", 0L, 1L, 20, List.of(), List.of());
        }
        int size = sizeRaw == null ? 20 : Math.max(1, Math.min(sizeRaw, 50));
        long total = lengthOf(ARCHIVE_PREFIX + sessionKey);
        long from = fromRaw == null || fromRaw <= 0
                ? Math.max(1L, total - size + 1) : fromRaw;
        /* 越过尾部直接回落到最新页，避免返回一个空页却把 from 留在界外 */
        if (total > 0 && from > total) {
            from = Math.max(1L, total - size + 1);
        }
        List<TurnRecord> turns = total <= 0 || archive == null
                ? List.of()
                : archive.turnsBetween(sessionKey, from, from + size - 1L);
        long lo = from, hi = from + size - 1L;
        List<ContextAssemblyReport> reports = assemblyReportStore == null
                ? List.of()
                : assemblyReportStore.recent(sessionKey, AssemblyReportStore.KEEP_PER_SESSION)
                .stream()
                /* turnId=0 是开轮失败落的账，不属于任何轮次 */
                .filter(r -> r != null && r.turnId() > 0 && r.turnId() >= lo && r.turnId() <= hi)
                .sorted(Comparator.comparingLong(ContextAssemblyReport::turnId))
                .toList();
        return new Page(sessionKey, total, from, size, turns, reports);
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
