package com.zzkingcc.domainplane.toolprovider.spring;

import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import com.zzkingcc.domainplane.toolprovider.ToolInstanceConfig;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具实例配置（{@code domainplane.tool-instance.*}）
 * 默认不启用：它会在客户应用里起一个心跳线程并暴露一个 HTTP 端点，
 * 属于"要不要当工具提供方"的显式选择，不该因为引了 jar 就默认发生。
 * @author zzkingcc
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "domainplane.tool-instance")
public class ToolInstanceProperties {

    /**
     * 是否扫描 {@code @Tool} 注解方法并自动注册（默认 true）。
     * 关掉它就只能用 {@code ToolInstanceContributor} 编程式注册——
     * 适合"工具清单要在启动时动态拼装"或"容器里带同名方法不想被扫到"的场景。
     */
    private boolean scanAnnotated = true;

    /** 实例标识；重连必须沿用同一个，否则服务端会留下一个摘不掉的旧副本 */
    private String instanceId;

    /**
     * 本实例的工具调用回流地址：服务端 POST 到它执行工具，必须服务端可达。
     * 留空即按 {@code http://localhost:本进程端口/domainplane/invoke} 自动推导——
     * 只对"服务端与工具实例同机"成立，跨机或前面有网关时必须显式填写。
     * 推导不出来时视为空（不接反向调用），不影响工具注册与心跳，也不阻断启动。
     */
    private String endpoint;

    /** 心跳周期（秒）。服务端判死窗默认 35s = 它的 7 倍 */
    private int heartbeatIntervalSeconds = ToolInstanceConfig.DEFAULT_HEARTBEAT_SECONDS;

    /** 心跳连续失败时的退避上限（秒） */
    private int maxBackoffSeconds = ToolInstanceConfig.DEFAULT_MAX_BACKOFF_SECONDS;

    /** 单次 HTTP 超时（毫秒） */
    private int requestTimeoutMillis = ToolInstanceConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS;

    /** 转成内核用的不可变配置（顺带做必填校验，缺失即启动失败） */
    public ToolInstanceConfig toConfig(DomainPlaneProperties server, String endpoint) {
        return new ToolInstanceConfig(server.getHost(), server.getPort(), server.getUsername(), server.getPassword(),
                instanceId, endpoint, heartbeatIntervalSeconds, maxBackoffSeconds, requestTimeoutMillis);
    }
}
