package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规划前置（plan-and-execute）的行为钉子。
 *
 * <p>覆盖五条边界：计划注入请求体且位置在本轮提问之前、PLAN 事件先于第一个 TOKEN、
 * 域级展示开关只影响下发不影响执行、direct 模式零痕迹、规划失败/规划中停止的降级与收尾。</p>
 */
class OrchestrationPlanningTest {

    private static final String PLAN_JSON = "{\"mode\":\"plan\",\"goal\":\"查库存并回答\","
            + "\"steps\":[{\"what\":\"查库存\",\"tools\":[\"stock\"],\"dependsOn\":[]},"
            + "{\"what\":\"汇总回答\",\"tools\":[],\"dependsOn\":[1]}],\"risks\":\"\"}";

    private static final String PLAN_MARKER = "本轮执行计划";

    @Test
    @DisplayName("plan 模式：计划以草稿口径注入请求体、位置在本轮提问之前，PLAN 事件先于第一个 TOKEN")
    void planInjectedBeforeUserMessageAndEmittedFirst() {
        OrchestrationHarness harness = new OrchestrationHarness(
                Runnable::run, new org.bsc.langgraph4j.checkpoint.MemorySaver(),
                PlanningOptions.on(), domainId -> true);
        harness.model().answer(PLAN_JSON);
        harness.model().answer("按计划执行完毕", "按计划", "执行完毕");

        List<AgentEvent> events = harness.chat("帮我查一下库存");

        // PLAN 事件先于第一个 TOKEN，payload 是原样 JSON
        int planIdx = indexOf(events, AgentEventType.PLAN);
        int firstTokenIdx = indexOf(events, AgentEventType.TOKEN);
        assertTrue(planIdx >= 0, "应有 PLAN 事件");
        assertTrue(firstTokenIdx > planIdx, "PLAN 必须先于第一个 TOKEN，实际 "
                + planIdx + " vs " + firstTokenIdx + "：" + events);
        assertEquals(PLAN_JSON, events.get(planIdx).getPayload());

        // 第二次模型调用（agent 节点）的请求体里有计划，且在本轮提问之前
        List<ChatRequest> requests = harness.model().requests();
        assertEquals(2, requests.size(), "规划一次 + agent 一次");
        // 规划请求只能有一条 system：并列两条在 Anthropic 类"system 单独成字段"的协议上会被丢或报错，
        // 而协议是按域档案分派的 —— 这里钉住"合并成一条"这条不变量
        List<ChatMessage> plannerMessages = requests.get(0).messages();
        assertEquals(1, plannerMessages.stream().filter(m -> m instanceof SystemMessage).count(),
                "规划请求必须只有一条 system");
        assertTrue(((SystemMessage) plannerMessages.get(0)).text().contains("任务规划器"),
                "合并后的 system 里要保留规划指令");
        List<ChatMessage> agentMessages = requests.get(1).messages();
        int planMsg = indexOfSystem(agentMessages, PLAN_MARKER);
        assertTrue(planMsg >= 0, "agent 请求体应含计划系统消息");
        assertInstanceOf(UserMessage.class, agentMessages.get(planMsg + 1),
                "计划应紧贴在本轮提问之前");

        // 计划不进记忆：记忆里只有提问与最终回答，没有计划
        assertEquals(2, harness.memory().size(), "记忆 = 提问 + 回答");
        assertTrue(harness.text(events).contains("按计划执行完毕"));
    }

    @Test
    @DisplayName("域级展示开关关：规划照做（请求体仍含计划），但没有 PLAN 事件")
    void displayOffKeepsPlanSilent() {
        OrchestrationHarness harness = new OrchestrationHarness(
                Runnable::run, new org.bsc.langgraph4j.checkpoint.MemorySaver(),
                PlanningOptions.on(), domainId -> false);
        harness.model().answer(PLAN_JSON);
        harness.model().answer("按计划执行完毕");

        List<AgentEvent> events = harness.chat("帮我查一下库存");

        assertEquals(-1, indexOf(events, AgentEventType.PLAN), "展示开关关时不得有 PLAN 事件");
        List<ChatRequest> requests = harness.model().requests();
        assertTrue(indexOfSystem(requests.get(1).messages(), PLAN_MARKER) >= 0,
                "规划照做：agent 请求体仍应含计划");
    }

    @Test
    @DisplayName("direct 模式：模型自判无需规划 → 零痕迹（无 PLAN 事件、请求体无计划消息）")
    void directModeLeavesNoTrace() {
        OrchestrationHarness harness = new OrchestrationHarness(
                Runnable::run, new org.bsc.langgraph4j.checkpoint.MemorySaver(),
                PlanningOptions.on(), domainId -> true);
        harness.model().answer("{\"mode\":\"direct\"}");
        harness.model().answer("直接回答", "直接回答");

        List<AgentEvent> events = harness.chat("你好");

        assertEquals(-1, indexOf(events, AgentEventType.PLAN));
        assertEquals(-1, indexOfSystem(harness.model().requests().get(1).messages(), PLAN_MARKER));
        assertTrue(harness.text(events).contains("直接回答"));
    }

    @Test
    @DisplayName("规划调用失败（如输出不是 JSON）：降级直进 loop，本轮照常完成")
    void plannerFailureDegradesToDirectLoop() {
        OrchestrationHarness harness = new OrchestrationHarness(
                Runnable::run, new org.bsc.langgraph4j.checkpoint.MemorySaver(),
                PlanningOptions.on(), domainId -> true);
        harness.model().answer("这不是 JSON，规划器跑飞了");
        harness.model().answer("降级后的正常回答", "降级后", "的正常回答");

        List<AgentEvent> events = harness.chat("帮我查一下库存");

        assertEquals(-1, indexOf(events, AgentEventType.PLAN));
        assertTrue(harness.text(events).contains("降级后的正常回答"), "本轮必须照常完成");
        assertEquals(AgentEventType.DONE, events.get(events.size() - 1).getType());
    }

    @Test
    @DisplayName("规划中点停止：取消最迟一个切片生效，本轮以 STOPPED 收尾、不进 loop")
    void stopDuringPlanningEndsRound() {
        OrchestrationHarness harness = new OrchestrationHarness(
                Runnable::run, new org.bsc.langgraph4j.checkpoint.MemorySaver(),
                PlanningOptions.on(), domainId -> true);
        // 规划应答吐到一半时插入停止：钩子落在 planner 的增量检查点上
        harness.model().enqueue(ScriptedTurn.answer(PLAN_JSON, "part1")
                .hook(() -> assertTrue(harness.stop()))
                .partial("part2"));
        harness.model().answer("这轮不该被消费");

        List<AgentEvent> events = harness.chat("帮我查一下库存");

        assertEquals(AgentEventType.STOPPED, events.get(events.size() - 1).getType(),
                "规划中停止以 STOPPED 收尾：" + events);
        assertEquals(1, harness.model().requests().size(), "只应有一次模型调用（规划），不得进 loop");
    }

    @Test
    @DisplayName("规划未启用（默认装配）：编排行为与该能力不存在时分毫不变")
    void disabledByDefaultKeepsLegacyBehavior() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("普通回答", "普通", "回答");

        List<AgentEvent> events = harness.chat("你好");

        assertEquals(-1, indexOf(events, AgentEventType.PLAN));
        assertEquals(1, harness.model().requests().size(), "无规划调用");
        assertTrue(harness.text(events).contains("普通回答"));
    }

    private static int indexOf(List<AgentEvent> events, AgentEventType type) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getType() == type) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfSystem(List<ChatMessage> messages, String marker) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage s && s.text().contains(marker)) {
                return i;
            }
        }
        return -1;
    }
}
