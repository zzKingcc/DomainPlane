package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
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
     * 注入命中时返回给调用方的固定话术。
     *
     * 三条约束：不确认身份（对"你是什么模型"不回答本身就是原则）、
     * 不解释拦截原因（不告诉输入方哪条规则命中，避免被用来绕）、
     * 引导继续对话。对攻击者与对普通用户是同一副面孔 —— 调用方无感知差异。
     */
    private static final String REJECTED_REPLY =
            "抱歉，这个问题我无法回答。你可以换个说法，或继续问我其他问题。";

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
        // 先过参数校验（sessionId / message / domain 必填），与改造前一致：
        // 缺参仍是 400 JSON，不会被后面的拦截分支掩盖
        AgentRequest safeRequest = AgentRequest.builder()
                .sessionId(request.getSessionId())
                .message(request.getMessage())
                .domain(request.getDomain())
                .build();

        // 注入命中：不进编排、不写记忆、不调模型、不建断点，以固定话术走完一轮
        // 正常事件序列（TOKEN + DONE）。对调用方与普通回答完全同构（HTTP 200 + SSE），
        // 前端按增量文本渲染，SDK 累积成 answer 后照常收到 DONE。
        // 判定细节（命中哪条规则）只进日志，不下发给调用方。
        if (InputSanitizer.isMalicious(request.getMessage())) {
            log.info("[输入拦截] 已返回固定话术 session={}, domain={}, 命中={}",
                    safeRequest.getSessionId(), safeRequest.getDomain(),
                    InputSanitizer.detectDetails(request.getMessage()));
            return Flux.just(
                    AgentEvent.token(safeRequest.getSessionId(), REJECTED_REPLY),
                    AgentEvent.done(safeRequest.getSessionId()));
        }

        AgentRequest validated = AgentRequest.builder()
                .sessionId(safeRequest.getSessionId())
                .message(InputSanitizer.validate(request.getMessage()))
                // 域原样透传（空域在前面那次 build 就已 400）："空域归一化为根域"
                // 只发生在编排层拿到域之后，走 HTTP 根本到不了那一步
                .domain(safeRequest.getDomain())
                .build();
        // 记消息长度而不是全文：用户内容属隐私，且全文会显著膨胀日志体积；
        // 确需排查时用 DEBUG 级别单独输出（此处不输出，避免敏感内容落盘）
        log.info("[Agent入口] 会话[{}] chat 请求, domain={}, 消息长度={}",
                validated.getSessionId(), validated.getDomain(),
                validated.getMessage().length());
        return agentService.chat(validated);
    }

    /**
     * 恢复被 INTERRUPT 事件挂起的会话
     *
     * @param sessionId 会话 ID，需与 chat 时一致
     * @param approved  true=批准执行工具；false=拒绝
     * @param domain    会话所属的域（必填），需与 chat 时一致
     * @return 事件流（SSE）
     */
    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<AgentEvent> resume(@RequestParam String sessionId,
                                   @RequestParam boolean approved,
                                   @RequestParam String domain) {
        log.info("[Agent入口] 会话[{}] resume 请求, approved={}, domain={}",
                sessionId, approved, domain);
        return agentService.resume(sessionId, approved, domain);
    }

    /**
     * 停止正在执行的任务（不可恢复）
     *
     * 会话状态按 (域, sessionId) 隔离：停止必须带上与 chat 时相同的域。
     * domain 省略时按根域算（只能停根域上的会话）。
     *
     * @param sessionId 会话 ID
     * @param domain    会话所属的域（可选；建议与 chat 一致）
     * @return {"code":0,"sessionId":"...","stopRequested":true|false}
     */
    @PostMapping("/stop/{sessionId}")
    public Map<String, Object> stop(@PathVariable String sessionId,
                                    @RequestParam(required = false) String domain) {
        boolean triggered = agentService.stop(sessionId, domain);
        log.info("[Agent入口] 会话[{}] stop 请求, stopRequested={}, domain={}",
                sessionId, triggered, domain);
        return Map.of("code", 0, "sessionId", sessionId, "stopRequested", triggered);
    }
}
