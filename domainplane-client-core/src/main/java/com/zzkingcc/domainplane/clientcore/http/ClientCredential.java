package com.zzkingcc.domainplane.clientcore.http;

import com.zzkingcc.domainplane.clientcore.exception.ClientErrorCode;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneErrors;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneStartupException;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端凭证持有者：账号密码 → 签名凭证，进程内缓存一份。
 *
 * 对话 SDK 与知识库 SDK 共用同一个实例（同一份配置只换一次凭证），
 * 因此它是 Bean 而不是各 SDK 内部 new 出来的字段。
 *
 * @author zzkingcc
 */
public class ClientCredential {

    private static final Logger log = LoggerFactory.getLogger(ClientCredential.class);

    /** 与服务端约定的凭证请求头（{@code CredentialService.CREDENTIAL_HEADER}） */
    public static final String CREDENTIAL_HEADER = "X-DomainPlane-Credential";

    /** 服务端登录端点 */
    static final String LOGIN_PATH = "/api/agent/login";

    private final WebClient webClient;
    private final DomainPlaneProperties server;
    private final ClientProperties properties;

    private final AtomicReference<String> credential = new AtomicReference<>();

    public ClientCredential(WebClient webClient, DomainPlaneProperties server, ClientProperties properties) {
        this.webClient = webClient;
        this.server = server;
        this.properties = properties;
    }

    /**
     * 取凭证；没有就登录一次（并发下只登一次）。
     *
     * 不在启动期调用：凭证在首次业务调用时才换取，服务端未启动不阻断接入方启动。
     * 登录失败抛 {@link DomainPlaneStartupException}，由调用方经
     * {@link DomainPlaneErrors#fromTransport} 翻成统一的带码业务异常。
     */
    public String get() {
        String cached = credential.get();
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = credential.get();
            if (cached == null) {
                cached = login();
                credential.set(cached);
            }
            return cached;
        }
    }

    /** 丢弃缓存的凭证（收到 401 时调用，下次请求会自动重新登录） */
    public void invalidate() {
        credential.set(null);
    }

    /**
     * 用配置的账号密码换凭证。
     *
     * @throws DomainPlaneStartupException 账号密码为空、服务端未初始化、账号或密码被拒
     */
    @SuppressWarnings("unchecked")
    public String login() {
        String username = server.getUsername();
        String password = server.getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new DomainPlaneStartupException(ClientErrorCode.INVALID_ARGUMENT,
                    "未配置接入账号：请在 domainplane.username / domainplane.password 中填写服务端账号"
                            + "（服务端默认由种子初始化为 domainplane / domainplane）");
        }

        try {
            Map<String, Object> body = webClient.post()
                    .uri(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("username", username, "password", password))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(properties.getHealthCheckTimeout());

            Object value = body == null ? null : body.get("credential");
            if (value == null || value.toString().isBlank()) {
                throw new DomainPlaneStartupException(ClientErrorCode.RESPONSE_UNREADABLE,
                        "服务端未返回凭证，请确认 " + server.getServerUrl() + " 是 DomainPlane 服务端");
            }
            // 只记账号，凭证是凭据，明文不进日志
            log.info("[DomainPlane客户端] 已用账号 [{}] 换取访问凭证（有效期由服务端密码决定，不设过期时间）", username);
            return value.toString();
        } catch (DomainPlaneStartupException e) {
            throw e;
        } catch (Exception e) {
            // 码按 HTTP 状态码细分（401 密码错 / 409 未初始化 / 403 被拒 / 超时 / 连不上），
            // 与启动探测 health 阶段的三码分支粒度对齐；文案仍由 describeLoginFailure 给出可操作提示。
            throw new DomainPlaneStartupException(DomainPlaneErrors.forLoginFailure(e),
                    describeLoginFailure(e), e);
        }
    }

    /**
     * 把登录失败翻译成可操作的提示。
     */
    private String describeLoginFailure(Exception e) {
        String status = extractHttpStatus(e);
        if ("401".equals(status)) {
            return "账号或密码被服务端拒绝。若最近在管控台改过密码，请同步更新 domainplane.password";
        }
        if ("409".equals(status)) {
            return "服务端账号尚未初始化：请先打开 " + server.getServerUrl()
                    + "/console/login.html 完成首次初始化";
        }
        if ("403".equals(status)) {
            return "服务端拒绝了本次登录（403），请检查网络侧访问控制";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 从 WebClient 异常里提取 HTTP 状态码（{@code WebClientResponseException} 才有） */
    public static String extractHttpStatus(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof org.springframework.web.reactive.function.client.WebClientResponseException w) {
                return String.valueOf(w.getStatusCode().value());
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** 是否为 401 / 403（凭证失效类，触发重新登录） */
    public static boolean isUnauthorized(Throwable e) {
        String status = extractHttpStatus(e);
        return "401".equals(status) || "403".equals(status);
    }
}
