package com.zzkingcc.domainplane.chatclient.client;

import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneErrors;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * DomainPlane 客户端
 * @author zzkingcc
 */
public class AgentServiceClient implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceClient.class);

    private final WebClient webClient;
    private final ClientCredential credential;

    public AgentServiceClient(WebClient webClient, DomainPlaneProperties server, ClientCredential credential) {
        this.webClient = webClient;
        this.credential = credential;
        log.info("[DomainPlane客户端] 初始化完成，服务端地址={}，接入账号={}",
                server.getServerUrl(), server.getUsername());
    }

    @Override
    public Flux<AgentEvent> chat(AgentRequest request) {
        log.info("[DomainPlane客户端] chat 会话[{}] domain={}",
                request.getSessionId(), request.getDomain());
        // defer 是必需的：凭证在首次调用时才换取，若在链外同步 get()，换凭证失败会直接抛在
        // chat() 调用处，onErrorMap 根本拦不到，接入方就会拿到"启动期异常"而不是带码的业务异常。
        // 包进 defer 后，凭证失败与网络失败走同一条错误翻译，出口统一。
        return Flux.defer(() -> webClient.post()
                .uri("/api/agent/chat")
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(AgentEvent.class))
                .doOnError(this::invalidateIfUnauthorized)
                // 传输层失败不会变成"流里的一项"，而是 Flux.onError 里的裸异常（Netty / Reactor 包了好几层）。
                // 统一翻译成带码的 DomainPlaneException，接入方 catch 到的就是 90001/90002/10002 这类可分支的码，
                // 不必自己去 WebClientResponseException 链里刨 HTTP 状态码。
                .onErrorMap(DomainPlaneErrors::fromTransport);
    }

    /**
     * 恢复被挂起的会话。
     *
     * 域走查询参数：会话状态按 (域, sessionId) 隔离，服务端要拿它定位到那个域里的会话。
     */
    @Override
    public Flux<AgentEvent> resume(String sessionId, boolean approved, String domain) {
        log.info("[DomainPlane客户端] resume 会话[{}] approved={} domain={}",
                sessionId, approved, domain);
        return Flux.defer(() -> webClient.post()
                .uri(uriBuilder -> uriBuilder.path("/api/agent/resume")
                        .queryParam("sessionId", sessionId)
                        .queryParam("approved", approved)
                        .queryParam("domain", domain)
                        .build())
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(AgentEvent.class))
                .doOnError(this::invalidateIfUnauthorized)
                .onErrorMap(DomainPlaneErrors::fromTransport);
    }

    /**
     * 停止任务。
     *
     * 会话状态在服务端按 (域, sessionId) 隔离，因此 stop 必须携带与 chat 相同的域：
     * 门面已绑定域，这里由调用方传入的 {@code domain} 决定。
     */
    @Override
    public boolean stop(String sessionId, String domain) {
        log.info("[DomainPlane客户端] stop 会话[{}] domain={}", sessionId, domain);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/api/agent/stop/{sessionId}")
                            .queryParam("domain", domain)
                            .build(sessionId))
                    .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            DomainPlaneException businessError = DomainPlaneErrors.fromResponseBody(resp);
            if (businessError != null) {
                log.warn("[DomainPlane客户端] stop 会话[{}] 被服务端拒绝: {}",
                        sessionId, businessError.describe());
                return false;
            }
            return resp != null && Boolean.TRUE.equals(resp.get("stopRequested"));
        } catch (Exception e) {
            invalidateIfUnauthorized(e);
            DomainPlaneException mapped = DomainPlaneErrors.fromTransport(e);
            log.error("[DomainPlane客户端] stop 会话[{}] 失败: {}",
                    sessionId, mapped.describe(), e);
            return false;
        }
    }

    /**
     * 凭证失效则丢弃缓存。
     */
    private void invalidateIfUnauthorized(Throwable e) {
        if (ClientCredential.isUnauthorized(e)) {
            credential.invalidate();
            log.warn("[DomainPlane客户端] 服务端拒绝了凭证（401/403），已清除本地缓存；"
                    + "下次调用将自动重新登录。若持续失败，通常是服务端改过密码，请同步 domainplane.password");
        }
    }
}
