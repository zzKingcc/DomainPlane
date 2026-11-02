package com.zzkingcc.domainplane.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 域树运行时配置（{@code domainplane.domain.*}）。
 *
 * @author zzkingcc
 */
@ConfigurationProperties(prefix = "domainplane.domain")
public class DomainProperties {

    /**
     * 软删宽限期：删除请求先打标记，宽限期到才硬删。
     *
     * 默认 24h，与检查点 TTL（{@code domainplane.memory.checkpoint-ttl}）对齐：
     * 宽限期短于检查点 TTL 时，硬删清掉的检查点本可以被 resume 用上，白等；
     * 长于 TTL 时挂起的会话会先因断点过期而无法收尾，宽限期白给。
     * 置 0 = 维持"立即硬删"的旧语义（停不掉在飞会话即拒删）。
     */
    private Duration deleteGrace = Duration.ofHours(24);

    /**
     * 多实例版本落后巡检间隔：定期比对本地版本与 Redis 登记的权威版本。
     * 检测到落后只告警（自动同步随 F13 落地），不阻断本地服务。
     */
    private Duration versionCheckInterval = Duration.ofSeconds(30);

    public Duration getDeleteGrace() {
        return deleteGrace;
    }

    public void setDeleteGrace(Duration deleteGrace) {
        this.deleteGrace = deleteGrace == null ? Duration.ofHours(24) : deleteGrace;
    }

    public Duration getVersionCheckInterval() {
        return versionCheckInterval;
    }

    public void setVersionCheckInterval(Duration versionCheckInterval) {
        this.versionCheckInterval = versionCheckInterval == null ? Duration.ofSeconds(30) : versionCheckInterval;
    }
}
