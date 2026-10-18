package com.zzkingcc.domainplane.server.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * MCP 装配：唯一职责是把"连接就绪回调"接上桥接盖章。
 *
 * {@link McpConnectionStore} / {@link McpConnectionRuntimeProvider} / {@link McpToolBridge}
 * / {@link McpCoordinator} 都是 {@code @Component}，由 Spring 自动构造装配；
 * 它们之间只差一个"provider 建连就绪后要通知 bridge 重新盖章"的回调，若在构造器里直接注入
 * bridge 会形成 provider ↔ bridge 的构造环（bridge 又依赖 provider），所以用本类在两者都
 * 构造完成后注入回调，绕开环。
 *
 * 即便回调设置得晚于某次异步建连，{@link McpCoordinator} 的 {@code @PostConstruct} 与
 * 周期 {@code reconcile} 也会对就绪连接补盖章，不会漏。
 *
 * @author zzkingcc
 */
@Component
public class McpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpConfiguration.class);

    private final McpConnectionRuntimeProvider runtimes;
    private final McpToolBridge bridge;

    public McpConfiguration(McpConnectionRuntimeProvider runtimes, McpToolBridge bridge) {
        this.runtimes = runtimes;
        this.bridge = bridge;
    }

    @PostConstruct
    public void wire() {
        runtimes.setAfterConnect(bridge::publish);
        log.info("[MCP 装配] 已接通 连接就绪 → 盖章 回调；MCP 接入启用");
    }
}
