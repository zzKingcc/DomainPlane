package com.zzkingcc.domainplane.clientcore.http;

import com.zzkingcc.domainplane.clientcore.exception.ClientErrorCode;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneStartupException;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证换取与缓存：对话 SDK 与知识库 SDK 共用的这一段，行为必须与引了哪个 SDK 无关。
 */
class ClientCredentialTest {

    private final List<ClientRequest> requests = new ArrayList<>();

    private ClientCredential credential;

    /** 用 {@code exchangeFunction} 挡掉真实网络，只验证"发不发请求、发到哪、怎么解读响应" */
    private ClientCredential credentialReturning(ClientResponse response, DomainPlaneProperties server) {
        WebClient webClient = WebClient.builder()
                .baseUrl(server.getServerUrl())
                .exchangeFunction(request -> {
                    requests.add(request);
                    return Mono.just(response);
                })
                .build();
        credential = new ClientCredential(webClient, server, new ClientProperties());
        return credential;
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static DomainPlaneProperties server(String username, String password) {
        DomainPlaneProperties properties = new DomainPlaneProperties();
        properties.setServer("http://localhost:9527");
        properties.setUsername(username);
        properties.setPassword(password);
        return properties;
    }

    @Test
    void 登录成功后缓存凭证且只登录一次() {
        ClientCredential credential = credentialReturning(
                json(HttpStatus.OK, "{\"credential\":\"cred-1\"}"), server("domainplane", "domainplane"));

        assertEquals("cred-1", credential.get());
        assertEquals("cred-1", credential.get(), "第二次取应命中缓存");

        assertEquals(1, requests.size(), "缓存命中就不该再登录");
        assertEquals("/api/agent/login", requests.get(0).url().getPath());
    }

    @Test
    void 凭证失效后重新登录换新凭证() {
        ClientCredential credential = credentialReturning(
                json(HttpStatus.OK, "{\"credential\":\"cred-1\"}"), server("domainplane", "domainplane"));
        assertEquals("cred-1", credential.get());

        credential.invalidate();

        assertEquals("cred-1", credential.get());
        assertEquals(2, requests.size(), "invalidate 之后必须重新登录，不能继续用旧凭证");
    }

    @Test
    void 账号密码为空时直接失败不请求服务端() {
        ClientCredential credential = credentialReturning(
                json(HttpStatus.OK, "{\"credential\":\"cred-1\"}"), server(" ", ""));

        DomainPlaneStartupException ex = assertThrows(DomainPlaneStartupException.class, credential::get);
        assertEquals(ClientErrorCode.INVALID_ARGUMENT, ex.getClientCode());
        assertTrue(ex.getMessage().contains("domainplane.username"), "提示要给出该改哪个配置键");
        assertTrue(requests.isEmpty(), "账号密码缺失属本地配置问题，不该发请求");
    }

    @Test
    void 服务端401翻译成账号或密码错误() {
        ClientCredential credential = credentialReturning(
                json(HttpStatus.UNAUTHORIZED, "{\"code\":10003}"), server("domainplane", "wrong"));

        DomainPlaneStartupException ex = assertThrows(DomainPlaneStartupException.class, credential::get);
        assertEquals(ClientErrorCode.CREDENTIAL_REJECTED, ex.getClientCode());
        assertTrue(ex.getMessage().contains("domainplane.password"));
    }

    @Test
    void 服务端409提示先完成初始化() {
        ClientCredential credential = credentialReturning(
                json(HttpStatus.CONFLICT, "{\"code\":10005}"), server("domainplane", "domainplane"));

        DomainPlaneStartupException ex = assertThrows(DomainPlaneStartupException.class, credential::get);
        assertEquals(ClientErrorCode.SERVER_NOT_INITIALIZED, ex.getClientCode());
        assertTrue(ex.getMessage().contains("/console/login.html"), "要指到初始化入口而不是只说失败");
    }

    @Test
    void 响应里没有凭证说明地址指错了() {
        ClientCredential credential = credentialReturning(
                json(HttpStatus.OK, "{\"token\":\"something-else\"}"), server("domainplane", "domainplane"));

        DomainPlaneStartupException ex = assertThrows(DomainPlaneStartupException.class, credential::get);
        assertEquals(ClientErrorCode.RESPONSE_UNREADABLE, ex.getClientCode());
        assertTrue(ex.getMessage().contains("http://localhost:9527"));
    }

    @Test
    void 从异常链里提取状态码并判定凭证失效() {
        assertNull(ClientCredential.extractHttpStatus(new IllegalStateException("裸异常")));

        Throwable wrapped = new RuntimeException("外层",
                new IllegalStateException("中层",
                        WebClientResponseException.create(
                                401, "Unauthorized", HttpHeaders.EMPTY, null, null)));

        assertEquals("401", ClientCredential.extractHttpStatus(wrapped));
        assertTrue(ClientCredential.isUnauthorized(wrapped));
        assertFalse(ClientCredential.isUnauthorized(new IllegalStateException("不是凭证问题")));
    }

    @Test
    void 凭证请求头与服务端约定一致() {
        assertEquals("X-DomainPlane-Credential", ClientCredential.CREDENTIAL_HEADER);
        assertSame(ClientErrorCode.CREDENTIAL_REJECTED, ClientErrorCode.of(95003),
                "客户端码表要能按数字码反查，接入方硬编码数字时才不会落空");
    }
}
