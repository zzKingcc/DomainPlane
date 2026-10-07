package com.zzkingcc.domainplane.clientcore.autoconfigure;

import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * DomainPlane SDK 客户端基础设施自动配置。
 *
 * 只装两样东西：WebClient 与凭证持有者，与"引了哪个 SDK"无关。
 * 对话 SDK 与知识库 SDK 都通过 {@link ClientCredential} 换同一份凭证、走同一个 {@link WebClient}，
 * 因此同时引入两个 SDK 也只会装配一份（{@code @ConditionalOnMissingBean}）。
 *
 * 刻意不在这里做启动期连通性校验：服务端未启动不该让接入方的程序停机。
 * 远端不可达由首次业务调用暴露，经错误翻译变成带码的 {@code DomainPlaneException}
 * （90002 服务端不可达 / 90001 超时 / 10002 凭证失效），
 * 与服务端处理 WebFlux / ES / Redis 失败时"照常启动、把原因写在日志与状态里"的做法一致。
 *
 * @author zzkingcc
 */
@AutoConfiguration
@EnableConfigurationProperties({DomainPlaneProperties.class, ClientProperties.class})
public class DomainPlaneClientAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DomainPlaneClientAutoConfiguration.class);

    /**
     * 客户端专用 WebClient：baseUrl 指向服务端，SSE 长连接与 multipart 上传共用。
     */
    @Bean
    @ConditionalOnMissingBean(name = "domainplaneWebClient")
    public WebClient domainplaneWebClient(DomainPlaneProperties server, ClientProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getReadTimeout())
                .doOnConnected(conn -> {
                    long readMillis = properties.getReadTimeout().toMillis();
                    if (readMillis > 0) {
                        conn.addHandlerLast(new ReadTimeoutHandler(readMillis, TimeUnit.MILLISECONDS));
                    }
                });

        log.info("[DomainPlane客户端] 已装配远程通道，服务端地址={}，接入账号={}；"
                        + "连通性将在首次调用时校验",
                server.getServerUrl(), server.getUsername());

        return WebClient.builder()
                .baseUrl(server.getServerUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 访问凭证持有者：账号密码换签名凭证并缓存（进程内单例，业务调用共用）。
     *
     * 凭证在首次业务调用时才换取，不在启动期换取 —— 见本类的 {@code domainplaneClientAutoConfiguration} 注释。
     */
    @Bean
    @ConditionalOnMissingBean
    public ClientCredential domainplaneClientCredential(WebClient domainplaneWebClient,
                                                     DomainPlaneProperties server,
                                                     ClientProperties properties) {
        return new ClientCredential(domainplaneWebClient, server, properties);
    }
}
