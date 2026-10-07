package com.zzkingcc.domainplane.example.chatwindow;

import com.zzkingcc.domainplane.api.agent.DomainPlaneAgent;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgentFactory;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 唯一的接口：收一句话，把这一轮的事件流原样推回去。
 *
 * 调用方只说一句话 —— 域与会话都由后端补齐：域在取门面时绑定（{@link #DOMAIN}），
 * 会话在进程内固定一个。两者都不经过 HTTP 边界，调用方也就没有传错的余地。
 *
 * 为什么流里的每一步都要打日志：一轮的状态散在「收到请求 → 建流 → 每个事件 →
 * 正常收尾 / 失败」四个点上，缺一个，"发了没反应"就查不下去 ——
 * 分不清是没收到、还是答完了、还是中途断了。
 *
 * @author zzkingcc
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    /** 请求体：只有一句话。域与会话在这里看不见，它们不是调用方的事。 */
    public record ChatRequest(String message) {
    }

    /** 域由后端定：要换域只改这一行。根域 {@code default} 即"挂在整棵域树上"。 */
    private static final String DOMAIN = Domains.DEFAULT;

    private final DomainPlaneAgentFactory agents;

    /** 会话也由后端定：同一进程里的每一句话都进同一个会话，模型才有上下文可依。 */
    private final String sessionId = UUID.randomUUID().toString();

    /**
     * 发一句话，把本轮事件流推回去。
     *
     * 返回的 {@code Flux} 由 WebFlux 直接写成 SSE —— 页面收的就是服务端自己的
     * {@link AgentEvent}，中间没有转换层。
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> chat(@RequestBody ChatRequest request) {
        String message = request == null ? null : request.message();

        // 只记长度：用户原话是用户数据，不进日志。排查"请求有没有带内容进来"看这个数就够。
        log.info("[示例] 收到消息：域[{}] 会话[{}] 长度={} 字",
                DOMAIN, sessionId, message == null ? 0 : message.length());

        DomainPlaneAgent agent = agents.forDomain(DOMAIN);
        log.info("[示例] 门面已就绪：agent={}", agent);

        AtomicInteger frames = new AtomicInteger();
        long startedAt = System.currentTimeMillis();

        /* 必须挪出 Netty 事件循环线程的是"建流"这一步：events() 里会同步调
           ClientCredential.get() 换凭证，而那一步是阻塞的（内部 block()）。WebFlux 的请求处理
           恰恰跑在事件循环线程上，在那里 block() 会直接抛
           "block() ... not supported in thread reactor-http-nio-N"。
           defer + subscribeOn(boundedElastic) 把"建流 + 订阅"整体搬到阻塞安全的线程池上，
           事件产出之后仍回到原线程，SSE 的推送不受影响。 */
        return Flux.defer(() -> agent.events(sessionId, message))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(event -> {
                    frames.incrementAndGet();
                    if (event.getType() == AgentEventType.ERROR) {
                        // ERROR 是事件而不是异常：SDK 不会替我们抛、也不会替我们记，
                        // 不在这里打一行，整轮失败就只存在于页面上了。
                        log.warn("[示例] 第 {} 帧 {}({}) {} code={}({}) traceId={}",
                                frames.get(), event.getType(), event.getType().getDescription(),
                                event.getContent(), event.getCode(), event.getCodeName(),
                                event.getTraceId());
                    } else {
                        log.info("[示例] 第 {} 帧 {}({}) content={} payload={}",
                                frames.get(), event.getType(), event.getType().getDescription(),
                                event.getContent(), event.getPayload());
                    }
                })
                .doOnComplete(() -> log.info("[示例] 本轮结束：会话[{}] 共 {} 帧，耗时 {}ms",
                        sessionId, frames.get(), System.currentTimeMillis() - startedAt))
                /* 失败也要以事件的形式交出去：连接被静默关掉时，页面只会停在"回答中"，
                   用户看不到任何原因 —— 这跟没有失败提示是一回事。 */
                .onErrorResume(e -> {
                    log.error("[示例] 本轮失败：会话[{}] 已收 {} 帧，耗时 {}ms，原因：{}",
                            sessionId, frames.get(), System.currentTimeMillis() - startedAt, e.toString());
                    return Flux.just(AgentEvent.error(sessionId, "调用失败：" + e.getMessage()));
                });
    }
}
