package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F03 软删三阶段·阶段一的入口闸门：软删域拒绝新开轮，但结构判据不受影响。
 *
 * <p>钉死两件事，它们合起来才是完整的阶段一语义：</p>
 * <ol>
 *   <li><b>chat 拦</b>：软删域的新对话以 {@code DOMAIN_PENDING_DELETE} 拒，
 *       不进编排、不写记忆、不建断点；</li>
 *   <li><b>拦的是生命周期不是结构</b>：同一道 {@code checkDomain} 管结构
 *       （不存在 → DOMAIN_NOT_FOUND、装配节点 → DOMAIN_NOT_CALLABLE），
 *       软删只多一道独立闸门。若把 pendingDelete 并进 isCallable，
 *       resume 收尾会被误报成 DOMAIN_NOT_CALLABLE——那正是本测试存在的理由。</li>
 * </ol>
 *
 * <p>闸门放行方向的证明看事件流：编排装配（图 / 记忆 / 线程池）在本用例里全是 null，
 * 放行的请求会照常进编排、然后败在装配缺失上（{@code ORCHESTRATION_FAILED}）；
 * 被拦的请求则在进编排之前就只有一个 {@code DOMAIN_PENDING_DELETE}。
 * 两类事件码不会同时出现，放行与否由此可判。</p>
 *
 * @author zzkingcc
 */
class AgentChatPendingDeleteGateTest {

    private DomainRegistry registry;

    private ToolRouter toolRouter;

    private AgentOrchestrationService service;

    @BeforeEach
    void setUp() {
        registry = new DomainRegistry();
        // 只要叶子域：再建一个子域会把 default.sales 变成装配节点，
        // 那道结构闸门（DOMAIN_NOT_CALLABLE）会先于软删闸门开火，测的就不是同一个东西了
        registry.create("default.sales");
        toolRouter = new ToolRouter(new ToolRegistry(), registry);
        // 编排装配全部留空：本用例只走 chat() 的前两道闸门，越过了才会碰到它们。
        // 例外是构造期强制非空的三项：promptResolver / executor / stateSerializer，
        // 给最小桩（序列化器同 RedisCheckpointSaverTest 的用法，纯构造不注册）
        service = new AgentOrchestrationService(
                null, null, toolRouter, domainId -> "", null,
                new ObjectStreamStateSerializer<MessagesState<ChatMessage>>(MessagesState::new),
                new CancellationRegistry(), Runnable::run);
    }

    private static AgentRequest request(String domain) {
        return AgentRequest.builder()
                .sessionId("s-1")
                .message("在吗")
                .domain(domain)
                .build();
    }

    private static List<AgentEvent> collect(Flux<AgentEvent> flux) {
        return flux.collectList().block();
    }

    @Test
    void 软删域的新开轮被拒且错误码是待删除而不是不可调用() {
        long now = System.currentTimeMillis();
        registry.markPendingDelete("default.sales", now);

        List<AgentEvent> events = collect(service.chat(request("default.sales")));

        assertEquals(1, events.size(), "闸门拒绝只产一个 ERROR 事件：" + events);
        AgentEvent error = events.get(0);
        assertEquals(10011, error.getCode(), "必须是 DOMAIN_PENDING_DELETE（软删），"
                + "而不是 DOMAIN_NOT_CALLABLE（结构）：后者会误伤 resume 收尾");
        assertEquals("DOMAIN_PENDING_DELETE", error.getCodeName());
        assertTrue(error.getContent().contains("default.sales"), "文案要点到是哪个域");
    }

    @Test
    void 软删不改结构判据域仍是可调用单元() {
        registry.markPendingDelete("default.sales", System.currentTimeMillis());

        assertTrue(registry.isCallable("default.sales"),
                "软删是生命周期闸门，不是结构判据：isCallable 必须原样，"
                        + "否则宽限期内的 resume 会被 checkDomain 误杀");
        assertTrue(toolRouter.isCallableDomain("default.sales"));
        assertTrue(registry.callableIds().contains("default.sales"),
                "客户端清单仍应看到它（展示层另行过滤），判据层不替展示层做决定");
    }

    @Test
    void 未软删的域不被这道闸门误伤() {
        // 闸门放行的证据：订阅后事件流里不出现 DOMAIN_PENDING_DELETE（10011）。
        // 编排装配全空，这一轮必然败在编排里（ORCHESTRATION_FAILED）——
        // 败在编排恰恰说明它越过了软删闸门，而不是被闸门拦在门外
        List<AgentEvent> events = collect(service.chat(request("default.sales")));

        assertTrue(events.stream().noneMatch(e -> e.getCode() == 10011),
                "未软删的域不得被这道闸门拦下：" + events);
        assertTrue(events.stream().anyMatch(
                        e -> e.getCode() == ErrorCode.ORCHESTRATION_FAILED.getCode()),
                "应进入编排并败在装配缺失上（而非败在闸门）：" + events);
    }

    @Test
    void 结构校验先于生命周期闸门() {
        // 不存在的域：即使打了软删标记也先报"域不存在"——结构事实优先于生命周期状态
        registry.markPendingDelete("default.ghost", System.currentTimeMillis());

        List<AgentEvent> events = collect(service.chat(request("default.ghost")));

        assertEquals(10004, events.get(0).getCode(), "先过 checkDomain：DOMAIN_NOT_FOUND");
    }

    @Test
    void 撤销软删后新开轮立即恢复() {
        registry.markPendingDelete("default.sales", System.currentTimeMillis());
        registry.clearPendingDelete("default.sales");

        // 撤销后闸门必须放行：证据同上——事件里没有 10011，且照常进入了编排
        List<AgentEvent> events = collect(service.chat(request("default.sales")));

        assertTrue(events.stream().noneMatch(e -> e.getCode() == 10011),
                "撤销软删后不得再被闸门拦下：" + events);
        assertTrue(events.stream().anyMatch(
                        e -> e.getCode() == ErrorCode.ORCHESTRATION_FAILED.getCode()),
                "撤销后应照常进编排（败在装配缺失上）：" + events);
    }
}
