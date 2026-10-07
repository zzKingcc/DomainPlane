package com.zzkingcc.domainplane.chatclient.client;

import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException;
import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.ApprovalRequiredException;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgent;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgentFactory;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一入口 {@code DomainPlaneAgent} 的三种消费方式：ask / stream / events。
 */
class DefaultDomainPlaneAgentTest {

    private static final String SESSION = "s1";

    /** 中断 payload 的形态与服务端 {@code ToolCallPayload} 一致（arguments 是内联 JSON 对象） */
    private static final String INTERRUPT_PAYLOAD =
            "{\"tools\":[{\"name\":\"closeOrder\",\"arguments\":{\"orderNo\":\"FR2024001\"},"
                    + "\"requireApproval\":true}]}";

    private final List<AgentRequest> chats = new ArrayList<>();
    private final List<String> resumes = new ArrayList<>();
    private final List<String> stopDomains = new ArrayList<>();
    private final List<String> stops = new ArrayList<>();

    private Flux<AgentEvent> scripted = Flux.empty();

    private final AgentService fakeTransport = new AgentService() {
        @Override
        public Flux<AgentEvent> chat(AgentRequest request) {
            chats.add(request);
            return scripted;
        }

        @Override
        public Flux<AgentEvent> resume(String sessionId, boolean approved, String domain) {
            resumes.add(domain);
            return Flux.just(AgentEvent.done(sessionId));
        }

        @Override
        public boolean stop(String sessionId, String domain) {
            stops.add(sessionId);
            stopDomains.add(domain);
            return true;
        }
    };

    private DomainPlaneAgent agentOf(String domain) {
        return new DefaultDomainPlaneAgentFactory(fakeTransport).forDomain(domain);
    }

    @Test
    void ask拼接增量输出并把域带进请求() {
        scripted = Flux.just(
                AgentEvent.token(SESSION, "订单 "),
                AgentEvent.toolCall(SESSION, "queryOrder", "{}"),
                AgentEvent.toolResult(SESSION, "queryOrder", "已发货"),
                AgentEvent.token(SESSION, "已发货"),
                AgentEvent.done(SESSION));

        String answer = agentOf("customer").ask(SESSION, "我的订单到哪了");

        assertEquals("订单 已发货", answer);
        assertEquals("customer", chats.get(0).getDomain());
        assertEquals(SESSION, chats.get(0).getSessionId());
        assertEquals("我的订单到哪了", chats.get(0).getMessage());
    }

    @Test
    void ask遇审批中断抛ApprovalRequiredException并带出待审批清单() {
        scripted = Flux.just(
                AgentEvent.token(SESSION, "准备关单"),
                AgentEvent.interrupt(SESSION, INTERRUPT_PAYLOAD));

        ApprovalRequiredException ex = assertThrows(ApprovalRequiredException.class,
                () -> agentOf("admin").ask(SESSION, "关掉 FR2024001"));

        assertEquals(SESSION, ex.getSessionId());
        assertEquals("admin", ex.getDomainId());
        assertEquals(1, ex.getTools().size());
        assertEquals("closeOrder", ex.getTools().get(0).getName());
        assertTrue(ex.getTools().get(0).getArguments().contains("FR2024001"),
                "arguments 要能从内联 JSON 对象还原成文本");
        assertTrue(ex.getTools().get(0).isRequireApproval());
    }

    @Test
    void ask遇ERROR事件还原成带码异常() {
        scripted = Flux.just(AgentEvent.error(SESSION, "指定的域不存在",
                ErrorCode.DOMAIN_NOT_FOUND, "trace-9"));

        DomainPlaneException ex = assertThrows(DomainPlaneException.class,
                () -> agentOf("ghost").ask(SESSION, "在吗"));

        assertEquals(ErrorCode.DOMAIN_NOT_FOUND, ex.getErrorCode());
        assertEquals(10004, ex.getCode());
        assertTrue(ex.getMessage().contains("trace-9"), "traceId 要并进文案便于排障");
        assertFalse(ex.isRetryable());
    }

    @Test
    void ask被停止时返回已产出的部分() {
        scripted = Flux.just(
                AgentEvent.token(SESSION, "说到一半"),
                AgentEvent.stopped(SESSION));

        assertEquals("说到一半", agentOf("customer").ask(SESSION, "讲个长故事"));
    }

    @Test
    void stream只出文本events给完整链路() {
        scripted = Flux.just(
                AgentEvent.token(SESSION, "你好"),
                AgentEvent.toolCall(SESSION, "ping", "{}"),
                AgentEvent.token(SESSION, "，在的"),
                AgentEvent.done(SESSION));

        List<String> chunks = agentOf("customer").stream(SESSION, "在吗").collectList().block();
        assertEquals(List.of("你好", "，在的"), chunks);

        List<AgentEvent> events = agentOf("customer").events(SESSION, "在吗").collectList().block();
        assertEquals(4, events.size(), "events 原样透传，不做筛选");
    }

    /**
     * 流被容器/网关中途掐断时，只有半截内容、没有终止事件。
     *
     * <p>这曾被当成"流正常结束"，于是 ask/stream 把残缺答案当成功交给业务方 ——
     * 现在必须判为失败。</p>
     */
    @Test
    void ask遇流被截断时抛异常而不是返回半截答案() {
        scripted = Flux.just(
                AgentEvent.token(SESSION, "订单 "),
                AgentEvent.token(SESSION, "FR2024001 已"));

        DomainPlaneException ex = assertThrows(DomainPlaneException.class,
                () -> agentOf("customer").ask(SESSION, "查订单"));
        assertEquals(ErrorCode.EXTERNAL_SERVICE_TIMEOUT.getCode(), ex.getErrorCode().getCode());
    }

    @Test
    void stream遇流被截断时补一个错误() {
        scripted = Flux.just(AgentEvent.token(SESSION, "说到一半"));

        List<String> emitted = new ArrayList<>();
        Throwable error = null;
        try {
            agentOf("customer").stream(SESSION, "在吗").doOnNext(emitted::add).blockLast();
        } catch (Throwable e) {
            error = e;
        }

        assertEquals(List.of("说到一半"), emitted, "截断前的内容仍然已经流出去了");
        assertNotNull(error, "截断必须以错误收尾，否则半截答案会被当成流正常结束");
        assertTrue(error instanceof DomainPlaneException, "应是带码异常，实际: " + error);
    }

    @Test
    void 收到终止事件即视为完整不报截断() {
        scripted = Flux.just(
                AgentEvent.token(SESSION, "完整答案"),
                AgentEvent.done(SESSION));

        assertEquals("完整答案", agentOf("customer").ask(SESSION, "在吗"));
    }

    @Test
    void resume自动带上绑定域() {
        DomainPlaneAgent agent = agentOf("admin");

        agent.resume(SESSION, true);
        assertEquals("admin", resumes.get(0));

        assertTrue(agent.stop(SESSION));
        assertEquals(SESSION, stops.get(0));
        assertEquals("admin", stopDomains.get(0), "stop 必须带上门面绑定的域，服务端才能定位 (域, sessionId)");
    }

    // ==================== 审批清单不可用时不得放行 ====================
    // 反向用例：这三种 payload 以前都按"空清单"处理，宿主拿到空确认框照样能点批准，
    // 于是危险动作在用户没看到任何内容的情况下执行了。

    @Test
    void 审批清单解析失败时抛异常而不是交出空清单() {
        scripted = Flux.just(AgentEvent.interrupt(SESSION, "{不是 JSON"));

        DomainPlaneException ex = assertThrows(DomainPlaneException.class,
                () -> agentOf("admin").ask(SESSION, "关掉 FR2024001"));
        assertFalse(ex.getMessage().contains("不是 JSON"), "异常消息不得回显原始 payload（工具参数会落到日志里）");
    }

    @Test
    void 审批清单为空时抛异常而不是当作无需审批() {
        scripted = Flux.just(AgentEvent.interrupt(SESSION, "{\"tools\":[]}"));

        assertThrows(DomainPlaneException.class, () -> agentOf("admin").ask(SESSION, "关掉 FR2024001"));
    }

    @Test
    void 审批清单缺payload时抛异常() {
        scripted = Flux.just(AgentEvent.interrupt(SESSION, null));

        assertThrows(DomainPlaneException.class, () -> agentOf("admin").ask(SESSION, "关掉 FR2024001"));
    }

    @Test
    void 审批清单含无名条目时抛异常() {
        scripted = Flux.just(AgentEvent.interrupt(SESSION, "{\"tools\":[{\"arguments\":{}}]}"));

        assertThrows(DomainPlaneException.class, () -> agentOf("admin").ask(SESSION, "关掉 FR2024001"));
    }

    @Test
    void stream遇审批清单不可用时同样以错误收尾() {
        scripted = Flux.just(AgentEvent.token(SESSION, "准备关单"), AgentEvent.interrupt(SESSION, "{\"tools\":[]}"));

        List<String> emitted = new ArrayList<>();
        Throwable error = null;
        try {
            agentOf("admin").stream(SESSION, "关单").doOnNext(emitted::add).blockLast();
        } catch (Throwable e) {
            error = e;
        }
        assertEquals(List.of("准备关单"), emitted);
        assertTrue(error instanceof DomainPlaneException, "清单不可用必须以错误收尾，实际: " + error);
    }

    @Test
    void 同一域复用同一门面且空域归一化为根域() {
        DomainPlaneAgentFactory factory = new DefaultDomainPlaneAgentFactory(fakeTransport);

        assertSame(factory.forDomain("default.customer"), factory.forDomain("default.customer"));
        assertSame(factory.forDomain("  default.customer  "), factory.forDomain("default.customer"),
                "首尾空白不该造出第二个门面");

        DomainPlaneAgent fallback = factory.forDomain(null);
        assertEquals(Domains.DEFAULT, fallback.domainId());
        assertSame(fallback, factory.forDomain("  "), "null 与空白都落到根域同一个门面");
        assertSame(fallback, factory.forDomain(Domains.DEFAULT));
    }

    @Test
    void 缺少底层通道直接失败() {
        assertThrows(NullPointerException.class, () -> new DefaultDomainPlaneAgentFactory(null));
        assertThrows(NullPointerException.class, () -> new DefaultDomainPlaneAgent(null, "customer"));
    }

    // ==================== 整轮等待上限 ====================

    /**
     * 服务端完全不响应（线程池满 / 网关挂起 / 连上了但不吐帧）时，
     * 无上限的 {@code block()} 会让业务线程永久挂住。
     */
    @Test
    void 服务端无响应时按answerTimeout收场而不是永久阻塞() {
        scripted = Flux.never();

        long start = System.currentTimeMillis();
        DomainPlaneException ex = assertThrows(DomainPlaneException.class,
                () -> new DefaultDomainPlaneAgent(fakeTransport, "customer", Duration.ofMillis(150))
                        .ask(SESSION, "在吗"));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(ErrorCode.EXTERNAL_SERVICE_TIMEOUT.getCode(), ex.getErrorCode().getCode());
        assertTrue(elapsed < 5_000, "应在上限内收场，实际耗时 " + elapsed + "ms");
    }

    @Test
    void answerTimeout非法值时回落默认上限而不是变成无上限() {
        // 0 / 负数都意味着"立刻放弃"或"无界"，两者都会让 ask 失去保护。
        // 不能真跑一次 30 分钟的等待 —— 用反射读回落后的实际值断言
        for (Duration bad : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), null}) {
            DefaultDomainPlaneAgent agent = new DefaultDomainPlaneAgent(fakeTransport, "customer", bad);
            assertEquals(DefaultDomainPlaneAgent.DEFAULT_ANSWER_TIMEOUT, answerTimeoutOf(agent),
                    "非法值 " + bad + " 应回落默认值");
        }
    }

    /** 读出构造后真正生效的上限 —— 靠它避免测试真的去等默认的 30 分钟 */
    private static Duration answerTimeoutOf(DefaultDomainPlaneAgent agent) {
        try {
            var field = DefaultDomainPlaneAgent.class.getDeclaredField("answerTimeout");
            field.setAccessible(true);
            return (Duration) field.get(agent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 answerTimeout 字段，字段名或可见性变了", e);
        }
    }
}
