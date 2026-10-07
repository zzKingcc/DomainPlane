package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 会话记忆配置
 *
 * 前缀 {@code domainplane.memory}。阈值统一外置在 yaml，便于调整窗口而无需重新编译。
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.memory")
public class MemoryProperties {

    /** 单会话保留的消息条数上限(约等于 maxMessages/2 轮问答) */
    private int maxMessages = 100;

    /** 单会话消息累计 Token 估算上限 */
    private int maxTokens = 30000;

    /** 会话记忆保留期；{@code null} = 永久不过期（一期默认）。
     *  记忆是长期存储（靠 Redis RDB+AOF 保住），按缓存对待并不合适 —— 到上限后由入口拒绝新一轮，
     *  而不是丢弃最旧的消息（静默丢历史会让用户"以为还记得"）。 */
    private Duration ttl = null;

    /** 图检查点保留时长;中断后未 resume 的会话靠此兜底清理 */
    private Duration checkpointTtl = Duration.ofHours(24);
}
