package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 孤儿 blob 回收 —— 登记表过期之后，字节就没有主了。
 *
 * <p>字节侧（本地磁盘 / 远程 bucket）没有原生过期：Redis 管好登记表的 TTL，
 * 但"登记表没了，字节该怎么办"这件事没有人回答，孤儿就会一直烧磁盘/费用，
 * 而且是静默地烧。本调度器就是回答这个问题的：拿 blob 存储里实际存在的键
 * 与登记表里活着的引用名对账，无主的回收掉。</p>
 *
 * <p><b>竞态护栏</b>：put 的顺序是"先落字节、后写登记表"，扫描撞进这个窗口会把
 * 还没登记的字节当孤儿删掉——那是一个"能引用、取不回"的坏附件。所以年龄小于
 * 护栏线的对象本轮跳过。注意这不延迟正常回收：正常孤儿诞生时（登记表过期），
 * 字节的年龄早就超过了 TTL（默认 30 分钟），远大于护栏线。</p>
 *
 * <p><b>无人值守语义</b>：单个对象删不掉只计数、下轮重试，绝不让一个坏对象
 * 挡住后面所有孤儿（与 {@code DomainPurgeScheduler} 同一条纪律）。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class MediaBlobSweeper {

    /**
     * 一轮扫描的报告。调度方丢弃返回值；测试断言用。
     *
     * @param scanned      本轮检查过的 blob 数
     * @param reclaimed    回收掉的无主 blob 数
     * @param failed       删除失败数（下轮重试）
     * @param skippedYoung 因竞态护栏跳过的在写对象数
     */
    record Report(int scanned, int reclaimed, int failed, int skippedYoung) {
    }

    private final BlobMediaAttachmentStore store;
    private final BlobStore blobStore;
    private final Duration minAge;

    public MediaBlobSweeper(BlobMediaAttachmentStore store,
                            BlobStore blobStore,
                            @Value("${domainplane.vision.blob-sweep-min-age-minutes:5}") long minAgeMinutes) {
        this.store = store;
        this.blobStore = blobStore;
        this.minAge = Duration.ofMinutes(Math.max(0, minAgeMinutes));
    }

    @Scheduled(fixedDelayString = "${domainplane.vision.blob-sweep-ms:300000}")
    public Report sweepOrphanBlobs() {
        List<String> blobKeys;
        try {
            blobKeys = blobStore.listKeys(MediaIds.BLOB_PREFIX);
        } catch (Exception e) {
            // 列举失败（存储故障）：本轮整体跳过。计数进 failures，下轮自然重试
            log.warn("[附件回收] 列举 blob 失败（本轮跳过，下轮重试）：{}", e.getMessage());
            MetricsRegistry.blobSweep(0, 0, 1);
            return new Report(0, 0, 1, 0);
        }
        int scanned = 0;
        int reclaimed = 0;
        int failed = 0;
        int skippedYoung = 0;
        for (String blobKey : blobKeys) {
            if (!blobKey.startsWith(MediaIds.BLOB_PREFIX)) {
                // 理论上 listKeys 已按前缀过滤；真出现了说明实现与约定分叉，跳过不猜
                continue;
            }
            scanned++;
            String attachmentId = MediaIds.attachmentIdOf(blobKey);
            if (store.registered(attachmentId)) {
                continue;
            }
            if (isYoung(blobKey)) {
                skippedYoung++;
                continue;
            }
            try {
                blobStore.delete(blobKey);
                reclaimed++;
                log.debug("[附件回收] 无主 blob 已回收：{}", blobKey);
            } catch (Exception e) {
                failed++;
                log.warn("[附件回收] 删除无主 blob 失败（下轮重试）：{} - {}", blobKey, e.getMessage());
            }
        }
        MetricsRegistry.blobSweep(scanned, reclaimed, failed);
        if (reclaimed > 0 || failed > 0) {
            log.info("[附件回收] 本轮检查 {} 个 blob：回收 {}，失败 {}，跳过在写 {}",
                    scanned, reclaimed, failed, skippedYoung);
        } else {
            log.debug("[附件回收] 本轮检查 {} 个 blob：无无主对象（跳过在写 {}）", scanned, skippedYoung);
        }
        return new Report(scanned, reclaimed, failed, skippedYoung);
    }

    /**
     * 竞态护栏：年龄小于护栏线的跳过。
     *
     * <p>取不到修改时间时不跳过——登记表说它无主，年龄只是护栏不是判据；
     * 为一条读不到的时间把无主对象永久留下，是把纠错逻辑建立在另一个故障上。</p>
     */
    private boolean isYoung(String blobKey) {
        if (minAge.isZero()) {
            return false;
        }
        return blobStore.lastModified(blobKey)
                .map(time -> Duration.between(time, Instant.now()).compareTo(minAge) < 0)
                .orElse(false);
    }
}
