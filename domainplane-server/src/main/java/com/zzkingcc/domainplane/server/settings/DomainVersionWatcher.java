package com.zzkingcc.domainplane.server.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 域配置版本落后巡检（F03 域存储增强方案 §4 的消费端雏形）。
 *
 * 定期比对本地权威版本与 Redis 登记的版本：本地落后说明别的写节点改过域配置。
 * 当前只告警并置位——自动拉取同步需要知道"写节点是谁"（实例登记表），
 * 属 F13 多实例范围；在此之前运维按日志提示从写节点拉取文件后重启。
 *
 * 为什么用定期比对而不是订阅频道：频道消费方的生命周期（重连、积压、启动时序）
 * 比一个 30s 的比对复杂得多，而域配置变更本就低频。键里存的就是权威版本，
 * 定期读它足够发现问题；频道先通车，低延迟消费方将来再上。
 *
 * @author zzkingcc
 */
@Component
public class DomainVersionWatcher {

    private static final Logger log = LoggerFactory.getLogger(DomainVersionWatcher.class);

    private final DomainStore domainStore;

    private final DomainVersionSignal signal;

    /** 是否正处于"本地落后"状态（供管控台展示；只在状态翻转时打日志，避免每轮刷屏） */
    private final AtomicBoolean behind = new AtomicBoolean(false);

    public DomainVersionWatcher(DomainStore domainStore, DomainVersionSignal signal) {
        this.domainStore = domainStore;
        this.signal = signal;
    }

    @Scheduled(fixedDelayString = "${domainplane.domain.version-check-ms:30000}")
    public void check() {
        long remote = signal.remoteVersion();
        if (remote < 0) {
            // Redis 未配置 / 未写过 / 读不到：无从判断，不置位
            return;
        }
        long local = domainStore.currentVersion();
        if (remote > local) {
            if (behind.compareAndSet(false, true)) {
                log.warn("[域版本] 本地配置版本落后（本地 v{}，权威 v{}）：其他节点已变更域配置。"
                                + "单机部署请确认没有第二个写节点；多实例部署的自动同步随 F13 落地，"
                                + "当前请从写节点拉取 config/domains.json（含 domains-history/）后重启",
                        local, remote);
            }
        } else if (behind.compareAndSet(true, false)) {
            log.info("[域版本] 本地配置版本已追上权威版本 v{}", local);
        }
    }

    /** 本地是否落后于权威版本（管控台版本信息展示） */
    public boolean isBehind() {
        return behind.get();
    }

    /** 本地权威版本号 */
    public long localVersion() {
        return domainStore.currentVersion();
    }
}
