package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.domain.rag.store.DocumentAgg;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原文档案的双向对账：孤儿回收 + 缺档补写（一审修订 A3 的落地）。
 *
 * <p>只回收孤儿不够。archive 的写入序是"ingest 成功 → 写 archive，失败只告警"，
 * 于是会出现"索引有文档、archive 无原文"的<b>缺档</b>——换模型重灌时这篇文档
 * 会被静默丢掉。对账必须双向：</p>
 * <ul>
 *   <li><b>孤儿</b>（archive 有、登记表无）→ 删。留着比缺档更危险：重灌会把
 *       已删除的文档原样复活，且没有任何报错；</li>
 *   <li><b>缺档</b>（登记表有、archive 无）→ 从 {@code store.scan()} 读回切片
 *       <b>补写</b>。{@code scan} 的存在理由正是这一条，而不是重灌主路径。</li>
 * </ul>
 *
 * <p>套路复用 {@code MediaBlobSweeper}：年龄护栏（防撞"刚写完还没登记"的窗口）、
 * 单个删不动下轮重试、列举失败整轮跳过。无人值守语义：一个坏对象不挡整轮。</p>
 *
 * <p>指标口径：{@code kbArchiveMissing} 持续上涨 = ingest 后的 archive 写入在
 * 失败（存储满 / 权限），这是换模型重灌会丢文档的直接前兆；{@code kbArchiveOrphans}
 * 偶发上涨正常（删文档/删域链路都会产生），持续上涨说明删除链路有漏。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ChunkArchiveReconciler {

    /** 登记表拉取上限（单 collection 的文档数；管控台 List 同量级） */
    private static final int DOC_LIMIT = 10_000;

    /** 连续失败多少轮后再次提醒（对账周期 5min × 6 ≈ 30 分钟一条） */
    private static final long FAILURE_REMIND_EVERY = 6;

    /** 列举 collection 的连续失败轮数（成功归零；只用于降噪，不影响对账语义） */
    private volatile long listFailures;

    /**
     * 一轮对账的报告。调度方丢弃返回值；测试与重灌前置对账用。
     *
     * @param scanned    本轮检查过的 archive 键数
     * @param orphans    回收的孤儿键数
     * @param missing    补写的缺档文档数
     * @param failed     删不动的键数（下轮重试）
     * @param skippedYoung 因年龄护栏跳过的键数
     */
    public record Report(int scanned, int orphans, int missing, int failed, int skippedYoung) {
    }

    private final ChunkArchive archive;
    /**
     * 检索计划：登记表经 {@link RetrievalPlan#keywordStore()} 取——以 keyword 后端为权威
     * （一审修订 B4——"有哪些文档"是登记视角，不是检索视角），
     * 与 {@link KnowledgeBaseService} 同一取值口径。
     */
    private final RetrievalPlan retrievalPlan;
    private final Duration minAge;

    public ChunkArchiveReconciler(ChunkArchive archive,
                                  RetrievalPlan retrievalPlan,
                                  @Value("${domainplane.knowledge.archive-reconcile-min-age-minutes:5}") long minAgeMinutes) {
        this.archive = archive;
        this.retrievalPlan = retrievalPlan;
        this.minAge = Duration.ofMinutes(Math.max(0, minAgeMinutes));
    }

    /** 登记表 store：keyword 后端（文档登记视角的权威，B4） */
    private KnowledgeStore store() {
        return retrievalPlan.keywordStore();
    }

    @Scheduled(fixedDelayString = "${domainplane.knowledge.archive-reconcile-ms:300000}")
    public Report reconcileAll() {
        List<String> collections;
        try {
            collections = store().listCollections();
        } catch (Exception e) {
            // 失败降噪：存储不可达时每轮 WARN 会变成固定噪音（对账周期 5min、
            // ES connectTimeout 5s——黑洞地址每轮白烧一次超时）。首轮 WARN 一条，
            // 之后降 debug，每连续 6 轮（约 30 分钟）提醒一次"仍在失败"。
            long streak = ++listFailures;
            boolean firstOrReminder = streak == 1 || streak % FAILURE_REMIND_EVERY == 0;
            if (firstOrReminder) {
                log.warn("[档案对账] 列举 collection 失败（连续 {} 轮，本轮跳过）：{}",
                        streak, e.getMessage());
            } else {
                log.debug("[档案对账] 列举 collection 失败（连续 {} 轮，本轮跳过）：{}",
                        streak, e.getMessage());
            }
            return new Report(0, 0, 0, 1, 0);
        }
        listFailures = 0;
        int scanned = 0;
        int orphans = 0;
        int missing = 0;
        int failed = 0;
        int skippedYoung = 0;
        for (String collection : collections) {
            Report r = reconcileCollection(collection);
            scanned += r.scanned();
            orphans += r.orphans();
            missing += r.missing();
            failed += r.failed();
            skippedYoung += r.skippedYoung();
        }
        // 档案里可能还有"collection 已不存在"的整域残留（purgeAll 前的旧数据 /
        // 删域时 archive 列举失败的漏网）：那些键的 collection 不在列表里，
        // 按孤儿处理——它们永远等不到登记表
        for (Map.Entry<String, Set<String>> e : archive.inventory().entrySet()) {
            if (collections.contains(e.getKey())) {
                continue;
            }
            for (String docId : e.getValue()) {
                String key = keyOf(e.getKey(), docId);
                if (archive.isYoung(key, minAge)) {
                    skippedYoung++;
                    continue;
                }
                try {
                    archive.delete(e.getKey(), docId);
                    orphans++;
                    MetricsRegistry.kbArchiveOrphan();
                } catch (Exception ex) {
                    failed++;
                    log.warn("[档案对账] 删除残留档案 {} 失败（下轮重试）: {}", key, ex.getMessage());
                }
            }
            scanned += e.getValue().size();
        }
        if (orphans > 0 || missing > 0 || failed > 0) {
            log.info("[档案对账] 本轮：检查 {} 键，回收孤儿 {}，补写缺档 {}，失败 {}，跳过在写 {}",
                    scanned, orphans, missing, failed, skippedYoung);
        } else {
            log.debug("[档案对账] 本轮：检查 {} 键，账实相符（跳过在写 {}）", scanned, skippedYoung);
        }
        return new Report(scanned, orphans, missing, failed, skippedYoung);
    }

    /**
     * 单个 collection 的双向对账（重灌前的清理也走它：孤儿不清掉，
     * 重灌会把已删文档原样复活）。
     *
     * @param minAge 年龄护栏；重灌等显式操作传 {@code Duration.ZERO} 关护栏
     *               （用户点了"重建"，不该被"文件太新"挡下）
     */
    public Report reconcileCollection(String collection, Duration minAge) {
        Set<String> registered;
        try {
            registered = new LinkedHashSet<>();
            for (DocumentAgg doc : store().documents(collection, DOC_LIMIT)) {
                if (doc.docId() != null) {
                    registered.add(doc.docId());
                }
            }
        } catch (Exception e) {
            // 登记表读不到就不猜：这一轮跳过整个 collection（下轮重试），
            // 而不是把全部档案当孤儿删掉——那是拿对账制造数据丢失
            log.warn("[档案对账] 读取 collection[{}]登记表失败（本轮跳过该 collection）：{}",
                    collection, e.getMessage());
            return new Report(0, 0, 0, 1, 0);
        }

        Set<String> archived;
        try {
            archived = new LinkedHashSet<>(archive.docIdsOf(collection));
        } catch (Exception e) {
            log.warn("[档案对账] 读取 collection[{}]档案清单失败（本轮跳过该 collection）：{}",
                    collection, e.getMessage());
            return new Report(0, 0, 0, 1, 0);
        }

        int scanned = archived.size();
        int orphans = 0;
        int missing = 0;
        int failed = 0;
        int skippedYoung = 0;

        // ① 孤儿：archive 有、登记表无 → 删
        for (String docId : archived) {
            if (registered.contains(docId)) {
                continue;
            }
            String key = keyOf(collection, docId);
            if (archive.isYoung(key, minAge)) {
                skippedYoung++;
                continue;
            }
            try {
                archive.delete(collection, docId);
                orphans++;
                MetricsRegistry.kbArchiveOrphan();
                log.info("[档案对账] 孤儿档案已回收（collection={}，docId={}）", collection, docId);
            } catch (Exception e) {
                failed++;
                log.warn("[档案对账] 删除孤儿档案失败（下轮重试）：{} - {}", key, e.getMessage());
            }
        }

        // ② 缺档：登记表有、archive 无 → scan 补写
        for (String docId : registered) {
            if (archived.contains(docId)) {
                continue;
            }
            try {
                int backfilled = backfill(collection, docId);
                missing++;
                MetricsRegistry.kbArchiveMissing();
                log.info("[档案对账] 缺档已补写（collection={}，docId={}，{} 条切片）",
                        collection, docId, backfilled);
            } catch (Exception e) {
                failed++;
                log.warn("[档案对账] 补写缺档失败（collection={}，docId={}，下轮重试）：{}",
                        collection, docId, e.getMessage());
            }
        }
        return new Report(scanned, orphans, missing, failed, skippedYoung);
    }

    /** 定时调度入口：带默认护栏 */
    public Report reconcileCollection(String collection) {
        return reconcileCollection(collection, minAge);
    }

    /**
     * 从索引读回一个文档的切片，补写进 archive。
     *
     * 走 {@code store.readByDocId}（SPI 的精确读）：早期的实现是全 collection
     * {@code scan} 后在内存里按 doc_id 过滤——{@code _doc} 序下同一文档的切片
     * 不相邻，必须扫到 collection 末尾，缺档一多就是全库反复扫描。精确读让
     * 补写从"扫全库"变成"一次查询"。
     *
     * @return 补写的切片条数；一条都没读到返回 0（登记表说有、索引实际没有：
     *   删除与刷新的竞态，补写空档案没有意义，让下轮再说）
     */
    private int backfill(String collection, String docId) {
        List<KbChunk> chunks = store().readByDocId(collection, docId);
        if (chunks.isEmpty()) {
            return 0;
        }
        archive.write(collection, docId, chunks);
        return chunks.size();
    }

    private static String keyOf(String collection, String docId) {
        return ChunkArchive.keyOf(collection, docId);
    }

    /** 按 collection 分组的档案清单（测试与排查用） */
    public Map<String, Set<String>> inventoryByCollection() {
        return new LinkedHashMap<>(archive.inventory());
    }
}
