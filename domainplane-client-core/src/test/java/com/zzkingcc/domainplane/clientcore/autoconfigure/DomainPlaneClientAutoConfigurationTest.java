package com.zzkingcc.domainplane.clientcore.autoconfigure;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneErrors;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneStartupException;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端底座：WebClient 与凭证持有者。
 *
 * <p>这两样与"引了哪个 SDK"无关 —— 对话 SDK 与知识库 SDK 拿到的是同一份，
 * 因此它单独成模块、单独验证：少了它两个 SDK 都起不来，多了它也不该替谁做业务决定。</p>
 *
 * <p>底座刻意不做启动期连通性校验：服务端未启动不阻断接入方启动，
 * 远端不可达由首次业务调用暴露成带码异常，由下面两个用例锁定。</p>
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
    void 装配本身不发起远程调用() {
        // 服务端地址指向必然连不通的端口：装配能返回，就证明没有启动期探测。
        // 这是"接入方程序不被服务端绑死"的底线 —— 曾经这里会抛异常并中断启动。
        DomainPlaneProperties server = server("http://127.0.0.1:1", "domainplane", "domainplane");

        WebClient webClient = configuration.domainplaneWebClient(server, properties);

        assertNotNull(webClient);
        assertNotNull(configuration.domainplaneClientCredential(webClient, server, properties));
    }

    @Test
    void 服务端不可达时由首次调用抛带码异常而非启动期异常() {
        DomainPlaneProperties server = server("http://127.0.0.1:1", "domainplane", "domainplane");
        WebClient webClient = configuration.domainplaneWebClient(server, properties);
        ClientCredential credential = configuration.domainplaneClientCredential(webClient, server, properties);

        DomainPlaneStartupException startup = assertThrows(DomainPlaneStartupException.class, credential::get);

        // 经错误翻译后：码保留，但类型归一 —— 业务调用方不需要知道"启动期"这件事
        DomainPlaneException mapped = DomainPlaneErrors.fromTransport(startup);
        assertEquals(ErrorCode.SERVER_UNREACHABLE, mapped.getErrorCode());
        assertEquals(90002, mapped.getCode());
        assertEquals(DomainPlaneException.class, mapped.getClass(),
                "对业务调用方必须是普通业务异常，而不是启动期异常的子类");
    }

    @Test
    void 未配置账号时同样归一成带码业务异常() {
        DomainPlaneProperties server = server("http://localhost:9527", "", "");
        WebClient webClient = configuration.domainplaneWebClient(server, properties);
        ClientCredential credential = configuration.domainplaneClientCredential(webClient, server, properties);

        DomainPlaneStartupException startup = assertThrows(DomainPlaneStartupException.class, credential::get);

        DomainPlaneException mapped = DomainPlaneErrors.fromTransport(startup);
        assertEquals(ErrorCode.AUTH_REQUIRED, mapped.getErrorCode());
        assertEquals(10002, mapped.getCode(), "失败也要带码，接入方才能按码分支");
        assertTrue(mapped.getMessage().contains("domainplane.username"),
                "没配账号时要直指配置项：" + mapped.getMessage());
    }

    @Test
    void 正常业务异常原样透传不被二次包装() {
        DomainPlaneException original =
                new DomainPlaneException(ErrorCode.DOMAIN_NOT_FOUND, "域不存在");
        assertInstanceOf(DomainPlaneException.class, DomainPlaneErrors.fromTransport(original));
        assertEquals(ErrorCode.DOMAIN_NOT_FOUND, DomainPlaneErrors.fromTransport(original).getErrorCode());
    }
}
