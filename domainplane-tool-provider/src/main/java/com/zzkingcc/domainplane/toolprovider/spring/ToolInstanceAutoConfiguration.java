package com.zzkingcc.domainplane.toolprovider.spring;

import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import com.zzkingcc.domainplane.toolprovider.ToolInstanceClient;
import com.zzkingcc.domainplane.toolprovider.ToolInstanceConfig;
import com.zzkingcc.domainplane.toolprovider.ToolInstanceContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.URI;

/**
 * 工具实例 SDK 自动装配
 *
 * @author zzkingcc
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "domainplane", name = "tools", havingValue = "true")
@EnableConfigurationProperties({DomainPlaneProperties.class, ToolInstanceProperties.class})
@Import(ToolInstanceInvokeController.class)
public class ToolInstanceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolInstanceAutoConfiguration.class);

    /** 未显式配置回流地址时的推导主机：只对"服务端与工具实例同机"成立 */
    private static final String DERIVED_HOST = "localhost";

    /**
     * 工具实例客户端：收集工具声明 + 建好心跳器（启动在 {@link ToolInstanceBootstrap}）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolInstanceClient toolInstanceClient(DomainPlaneProperties server,
                                                ToolInstanceProperties properties,
                                                Environment environment,
                                                ListableBeanFactory beanFactory,
                                                ObjectProvider<ToolInstanceContributor> contributors) {
        ToolInstanceConfig config = properties.toConfig(server, resolveEndpoint(properties, environment));
        ToolInstanceClient client = new ToolInstanceClient(config);

        // ① 注解式：方法上的 @Tool 直接成为工具
        int annotated = 0;
        if (properties.isScanAnnotated()) {
            annotated = new ToolProviderScanner(beanFactory).registerTo(client);
        }

        // ② 编程式：ToolInstanceContributor 后注册，重名时覆盖注解声明（显式优先于扫描）
        int count = 0;
        for (ToolInstanceContributor contributor : contributors) {
            contributor.contribute(client);
            count++;
        }

        log.info("[工具实例] {} 已装配：{} 个工具（注解扫描 {} 个、贡献者 {} 个），服务端 {}，回流地址 {}",
                config.instanceId(), client.toolNames().size(), annotated, count,
                config.serverUrl(), config.endpoint());
        warnIfEndpointPathMismatch(config);
        warnAboutUnauthenticatedInvokeEndpoint(config);
        return client;
    }

    /**
     * 提示端点的暴露面 —— 刻意提醒而非拦截。
     *
     * <p>{@code /domainplane/invoke} 不自带鉴权是产品契约（见 docs/INSTANCE.md §4.7）：
     * 实例端口的暴露面由接入方负责收敛。因此这里只把这条事实讲清楚，
     * 让接入方在日志里就能看到，不做任何行为变更。</p>
     */
    private void warnAboutUnauthenticatedInvokeEndpoint(ToolInstanceConfig config) {
        log.info("[工具实例] {} 暴露的 {} 不自带鉴权（SDK 刻意不内置）："
                        + "能连到本端口的一方可以直接执行本实例的工具，且不经过服务端的审批闸门与域过滤。"
                        + "请确保该端口只在可信网络内可达；需要鉴权时在自己的应用里加拦截器即可。",
                config.instanceId(), ToolInstanceInvokeController.INVOKE_PATH);
    }

    /** 心跳开关（装配完成后启动，关闭时停止） */
    @Bean
    @ConditionalOnMissingBean
    public ToolInstanceBootstrap toolInstanceBootstrap(ToolInstanceClient client) {
        return new ToolInstanceBootstrap(client);
    }

    /**
     * 解析工具调用回流地址：显式配置优先，留空则按本进程端口推导。
     *
     * <p>端口依次取 {@code local.server.port}（容器实际端口，随机端口时才有）与
     * {@code server.port}；两者都拿不到说明本进程没有可用的 Web 端口，此时推导不出地址，
     * 直接失败比注册一个服务端永远回调不到的地址更可控。</p>
     */
    private String resolveEndpoint(ToolInstanceProperties properties, Environment environment) {
        String explicit = properties.getEndpoint();
        if (StringUtils.hasText(explicit)) {
            return explicit.trim();
        }
        String port = environment.getProperty("local.server.port");
        if (!StringUtils.hasText(port)) {
            port = environment.getProperty("server.port", "8080");
        }
        if (!StringUtils.hasText(port) || "0".equals(port.trim())) {
            throw new IllegalStateException("无法推导工具调用回流地址：本进程没有可用的 Web 端口，"
                    + "请显式配置 domainplane.tool-instance.endpoint 为服务端可达的完整地址"
                    + "（形如 http://10.0.0.5:8081" + ToolInstanceInvokeController.INVOKE_PATH + "）");
        }
        String derived = "http://" + DERIVED_HOST + ":" + port.trim() + ToolInstanceInvokeController.INVOKE_PATH;
        log.info("[工具实例] 未配置 domainplane.tool-instance.endpoint，按本进程端口推导为 {}；"
                + "服务端与工具实例不同机（或前面有网关 / 容器映射）时必须显式配置，否则服务端回调不到本实例", derived);
        return derived;
    }

    /**
     * 提示 endpoint 的路径与本 SDK 实际监听的路径不一致。
     */
    private void warnIfEndpointPathMismatch(ToolInstanceConfig config) {
        String path;
        try {
            path = URI.create(config.endpoint()).getPath();
        } catch (Exception e) {
            log.warn("[工具实例] endpoint 不是合法 URL（{}），服务端将无法回调本实例", config.endpoint());
            return;
        }
        if (path == null || !path.endsWith(ToolInstanceInvokeController.INVOKE_PATH)) {
            log.warn("[工具实例] endpoint 的路径是 {}，而本 SDK 只在 {} 上接收工具调用。"
                            + "若前方没有把请求转发到该路径的网关，调用会全部 404",
                    path, ToolInstanceInvokeController.INVOKE_PATH);
        }
    }
}
