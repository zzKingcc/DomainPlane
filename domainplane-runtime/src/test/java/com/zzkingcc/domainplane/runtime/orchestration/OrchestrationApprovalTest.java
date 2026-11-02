package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批中断与 resume —— 编排里唯一会"跨两次调用"的一段状态机。
 *
 * <p>这条路径此前<b>被认为测不了</b>（"要 Redis 存断点"），真正的阻碍只是没有内存版断点存档；
 * langgraph4j-core 自带的 {@code MemorySaver} 就够，于是它能进单元测试。</p>
 */
@DisplayName("F07 审批中断与 resume")
class OrchestrationApprovalTest {

    @Test
    @DisplayName("需授权工具调用会中断在 review，并下发待审批清单")
    void approvalToolInterruptsWithPendingList() {
        OrchestrationHarness harness = new OrchestrationHarness().tool("deploy", "部署完成", true);
        harness.model().toolCall("deploy", "{\"env\":\"prod\"}");

        List<AgentEvent> events = harness.chat("帮我部署到生产环境");

        AgentEvent interrupt = events.stream()
                .filter(e -> e.getType() == AgentEventType.INTERRUPT).findFirst()
                .orElseThrow(() -> new AssertionError("应当中断于审批点：" + events));
        assertTrue(interrupt.getPayload().contains("deploy"),
                "清单里必须能看见待授权的工具名：" + interrupt.getPayload());
        assertTrue(interrupt.getPayload().contains("true"),
                "必须标出该调用需要人工确认，消费方据此渲染审批按钮");
    }

    @Test
    @DisplayName("批准后执行工具，并把下一次模型的输出作为最终回答")
    void resumeApprovedExecutesTool() {
        OrchestrationHarness harness = new OrchestrationHarness().tool("deploy", "部署完成", true);
        harness.model().toolCall("deploy", "{}").answer("已经部署好了");

        harness.chat("帮我部署");
        List<AgentEvent> events = harness.resume(true);

        assertEquals(AgentEventType.DONE, events.get(events.size() - 1).getType(), events.toString());
        dev.langchain4j.data.message.ChatMessage last =
                harness.memory().get(harness.memory().size() - 1);
        assertEquals("已经部署好了", ((dev.langchain4j.data.message.AiMessage) last).text());
    }

    @Test
    @DisplayName("拒绝后把拒绝结果喂回模型重入 agent，且不留下挂起的开放轮")
    void resumeRejectedReentersAgent() {
        OrchestrationHarness harness = new OrchestrationHarness().tool("deploy", "部署完成", true);
        harness.model().toolCall("deploy", "{}").answer("好的，那我换一种方式");

        harness.chat("帮我部署");
        List<AgentEvent> events = harness.resume(false);

        assertEquals(AgentEventType.DONE, events.get(events.size() - 1).getType(), events.toString());
        dev.langchain4j.model.chat.request.ChatRequest last = harness.model().lastRequest();
        String requests = String.valueOf(last.messages());
        assertTrue(requests.contains("用户拒绝了此工具调用"),
                "拒绝必须作为工具结果喂回模型，否则它不知道自己被拒了：" + requests);
    }

    @Test
    @DisplayName("没有断点时 resume 报会话不存在，而不是笼统的执行失败")
    void resumeWithoutCheckpointReportsSessionNotFound() {
        OrchestrationHarness harness = new OrchestrationHarness();

        List<AgentEvent> events = harness.resume(true);

        assertEquals(1, events.size(), "只应产一个 ERROR 事件：" + events);
        assertEquals("SESSION_NOT_FOUND", events.get(0).getCodeName(), events.toString());
    }

    @Test
    @DisplayName("被审批打断的那一轮，恢复后只写入一次最终回答")
    void resumedTurnWritesFinalAnswerOnlyOnce() {
        OrchestrationHarness harness = new OrchestrationHarness().tool("deploy", "部署完成", true);
        harness.model().toolCall("deploy", "{}").answer("已部署");

        harness.chat("帮我部署");
        harness.resume(true);

        assertEquals(2, harness.memory().size(),
                "一轮 Interruption + resume 只应留下 提问 + 回答 两条：" + harness.memory());
    }
}
