package com.zzkingcc.domainplane.server.settings;

import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.audit.AuditLog;
import com.zzkingcc.domainplane.server.config.DomainProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 宽限期到点的定时硬删（F03 软删三阶段·阶段三的执行端）。
 *
 * 软删只是打标记（阶段一），物理删除由本调度器在宽限期到头时执行：
 * 到点即跑既有级联清理，强杀语义（不等在飞会话停止）——宽限期已给足，
 * 死守"停不掉就不删"会把删除变成永远完不成的运维动作。
 *
 * 与管控台立即删的分工：那条路径（force / 宽限期置 0）同步执行并返回结果；
 * 本调度器无人值守，单个域失败只记录并继续下一个，绝不让一个坏域
 * 挡住后面所有到期的域（标记还在，下轮扫描自然重试）。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class DomainPurgeScheduler {

    private final DomainStore domainStore;

    private final DomainRegistry domainRegistry;

    private final DomainProperties domainProperties;

    private final DomainPurgeService purgeService;

    public DomainPurgeScheduler(DomainStore domainStore,
                                DomainRegistry domainRegistry,
                                DomainProperties domainProperties,
                                DomainPurgeService purgeService) {
        this.domainStore = domainStore;
        this.domainRegistry = domainRegistry;
        this.domainProperties = domainProperties;
        this.purgeService = purgeService;
    }

    @Scheduled(fixedDelayString = "${domainplane.domain.purge-scan-ms:60000}")
    public void purgeExpired() {
        Duration grace = domainProperties.getDeleteGrace();
        if (grace.isZero() || grace.isNegative()) {
            // 宽限期置 0 = 旧语义：删除即硬删，没有软删标记可扫
            return;
        }
        long now = System.currentTimeMillis();
        long graceMs = grace.toMillis();
        // pendingDeletes() 是快照：遍历期间新的软删/撤销不影响本轮
        for (Map.Entry<String, Long> marker : domainStore.pendingDeletes().entrySet()) {
            if (now - marker.getValue() < graceMs) {
                continue;
            }
            hardDelete(marker.getKey());
        }
    }

    private void hardDelete(String domainId) {
        if (purgeService.isPurging(domainId)) {
            // 并发路径（管控台 force）正在删同一个域：不双跑
            return;
        }
        if (!domainRegistry.contains(domainId)) {
            // 域已不在注册表（派生域的工具声明消失等并发后果）：只清标记，不跑级联。
            // purge 对"域不存在"会抛错，无人值守路径接住它就只是噪声
            domainStore.clearDeleted(domainId);
            domainStore.saveDeclarations(domainRegistry.manualConfig());
            log.info("[域调度] 软删标记 {} 的域已不在注册表，仅清除标记", domainId);
            return;
        }
        try {
            log.info("[域调度] 域 {} 宽限期已到，执行硬删", domainId);
            purgeService.purge(domainId, false);
            AuditLog.record("scheduler hard-delete domain " + domainId, "scheduler", "200");
        } catch (Exception e) {
            // 无人值守：单域失败不扩散，标记还在，下轮扫描自然重试
            log.error("[域调度] 硬删域 {} 失败（下轮重试）：{}", domainId, e.getMessage(), e);
            AuditLog.record("scheduler hard-delete domain " + domainId, "scheduler",
                    "500: " + e.getMessage());
        }
    }
}
