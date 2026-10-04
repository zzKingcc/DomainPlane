package com.zzkingcc.domainplane.clientcore.autoconfigure;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneStartupException;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 客户端底座：WebClient / 凭证 / 启动期探测。
 *
 * <p>这三样与"引了哪个 SDK"无关 —— 对话 SDK 与知识库 SDK 拿到的是同一份，
 * 因此它单独成模块、单独验证：少了它两个 SDK 都起不来，多了它也不该替谁做业务决定。</p>
 */
class DomainPlaneClientAutoConfigurationTest {

    private final DomainPlaneClientAutoConfiguration configuration = new DomainPlaneClientAutoConfiguration();
    private final ClientProperties properties = new ClientProperties();

    private static DomainPlaneProperties server(String url, String username, String password) {
        DomainPlaneProperties server = new DomainPlaneProperties();
        server.setServer(url);
        server.setUsername(username);
        server.setPassword(password);
        return server;
    }

    @Test
    void 底座按同一份配置装配出WebClient与凭证持有者() {
        DomainPlaneProperties server = server("http://localhost:9527", "domainplane", "domainplane");

        WebClient webClient = configuration.domainplaneWebClient(server, properties);
        ClientCredential credential = configuration.domainplaneClientCredential(webClient, server, properties);

        assertNotNull(webClient);
        assertNotNull(credential);
        assertEquals(Duration.ofSeconds(5), properties.getConnectTimeout());
        assertEquals(Duration.ofMinutes(10), properties.getReadTimeout(), "SSE 是长连接，读超时必须放宽");
    }

    @Test
    void 未配置账号时启动探测中断启动并给出码() {
        DomainPlaneProperties server = server("http://localhost:9527", "", "");
        WebClient webClient = configuration.domainplaneWebClient(server, properties);
        ClientCredential credential = configuration.domainplaneClientCredential(webClient, server, properties);
        SmartInitializingSingleton probe =
                configuration.domainplaneConnectivityCheck(webClient, server, properties, credential);

        DomainPlaneStartupException ex = assertThrows(DomainPlaneStartupException.class, probe::afterSingletonsInstantiated);

        assertEquals(ErrorCode.AUTH_REQUIRED, ex.getErrorCode());
        assertEquals(10002, ex.getCode(), "启动期失败也要带码，接入方才能按码分支");
    }

    @Test
    void 服务端不可达时启动探测中断启动() {
        DomainPlaneProperties server = server("http://127.0.0.1:1", "domainplane", "domainplane");
        WebClient webClient = configuration.domainplaneWebClient(server, properties);
        ClientCredential credential = configuration.domainplaneClientCredential(webClient, server, properties);
        SmartInitializingSingleton probe =
                configuration.domainplaneConnectivityCheck(webClient, server, properties, credential);

        DomainPlaneStartupException ex = assertThrows(DomainPlaneStartupException.class, probe::afterSingletonsInstantiated);

        assertEquals(ErrorCode.SERVER_UNREACHABLE, ex.getErrorCode());
    }
}
