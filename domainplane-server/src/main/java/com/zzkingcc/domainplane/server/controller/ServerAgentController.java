package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.CallerContext;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.common.util.InputSanitizer;
import com.zzkingcc.domainplane.server.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * DomainPlane 服务端 Agent HTTP 入口
 * @author zzkingcc
 */
@RestController
@RequestMapping("/api/agent")
public class ServerAgentController {

    private static final Logger log = LoggerFactory.getLogger(ServerAgentController.class);

    private final AgentService agentService;
    private final ServerInfo serverInfo;

    public ServerAgentController(AgentService agentService, ServerInfo serverInfo) {
        this.agentService = agentService;
        this.serverInfo = serverInfo;
    }

    /**
     * 健康探测（供客户端启动期连通性校验）
     *
     * @return 服务名、版本与状态
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "code", 0,
                "status", "UP",
                "service", "domainplane-server",
                "version", serverInfo.version());
    }

    /**
     * 发起一轮对话（事件流）
     *
     * @param request 请求体，sessionId 与 message 必填
     * @return 事件流（SSE），订阅后开始执行
     */
    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<AgentEvent> chat(@RequestBody AgentRequest request) {
        AgentRequest safeRequest = AgentRequest.builder()
                .sessionId(request.getSessionId())
                .message(InputSanitizer.validate(request.getMessage()))
                // 域必须原样透传：Builder.build() 对空 profile 直接抛异常（→ 400），
                // 不会把它归一化成根域。这与"漏传 permissions 静默降级 public"是同一类错误形态，
                // 且后果更直接。注意"空域归一化为根域"只发生在编排层收到非空 CallerContext 之后，
                // 走 HTTP 时根本到不了那一步。
                .profile(request.getProfile())
                .tenantId(request.getTenantId())
                .userId(request.getUserId())
                .attributes(request.getAttributes())
                .build();
        // 记消息长度而不是全文：用户内容属隐私，且全文会显著膨胀日志体积；
        // 确需排查时用 DEBUG 级别单独输出（此处不输出，避免敏感内容落盘）
        log.info("[Agent入口] 会话[{}] chat 请求, profile={}, tenantId={}, userId={}, 消息长度={}",
                safeRequest.getSessionId(), safeRequest.getProfile(),
                safeRequest.getTenantId(), safeRequest.getUserId(),
                safeRequest.getMessage().length());
        return agentService.chat(safeRequest);
    }

    /**
     * 恢复被 INTERRUPT 事件挂起的会话
     *
     * @param sessionId 会话 ID，需与 chat 时一致
     * @param approved  true=批准执行工具；false=拒绝
     * @param caller    调用方身份（必填），需携带 profile
     * @return 事件流（SSE）
     */
    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<AgentEvent> resume(@RequestParam String sessionId,
                                   @RequestParam boolean approved,
                                   @RequestBody CallerContext caller) {
        log.info("[Agent入口] 会话[{}] resume 请求, approved={}, profile={}",
                sessionId, approved, caller == null ? null : caller.profile());
        return agentService.resume(sessionId, approved, caller);
    }

    /**
     * 停止正在执行的任务（不可恢复）
     *
     * <p>会话状态按 (域, sessionId) 隔离：停止必须带上与 chat 时相同的域。
     * body 省略时按根域算（只能停根域上的会话）。</p>
     *
     * @param sessionId 会话 ID
     * @param caller    调用方身份（可选；建议与 chat 一致）
     * @return {"code":0,"sessionId":"...","stopRequested":true|false}
     */
    @PostMapping("/stop/{sessionId}")
    public Map<String, Object> stop(@PathVariable String sessionId,
                                    @RequestBody(required = false) CallerContext caller) {
        boolean triggered = agentService.stop(sessionId, caller);
        log.info("[Agent入口] 会话[{}] stop 请求, stopRequested={}, profile={}",
                sessionId, triggered, caller == null ? null : caller.profile());
        return Map.of("code", 0, "sessionId", sessionId, "stopRequested", triggered);
    }
}
