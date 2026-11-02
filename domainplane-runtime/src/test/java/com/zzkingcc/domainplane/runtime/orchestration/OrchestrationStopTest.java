package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户停止 —— 编排里最容易"看起来还在跑"的一条支路。
 *
 * <p>停止检查实际有 5 处（三个模型回调 + {@code awaitAiMessage} 的分片等待 + {@code toolsNode}），
 * 比设计文档原先记的多两处。漏掉任何一处，现象都不是报错，而是"停止偶尔不灵"，
 * 所以这里<b>逐个检查点各钉一条用例</b>，而不是只测一个"能停"。</p>
 *
 * <p>用例统一由脚本钩子在执行到某个回调之前置停止标志：编排跑在 {@code Runnable::run} 上，
 * 测试线程无法插入流中间，靠钩子才能把执行点精确落在被测的那个检查点上。</p>
 */
@DisplayName("F07 用户停止")
class OrchestrationStopTest {

    @Test
    @DisplayName("答案增量回调里也能检出停止：记忆回滚、下发 STOPPED")
    void stopDetectedInPartialResponseRollsBackMemory() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().enqueue(ScriptedTurn.answer("这一句不会被接受")
                .partial("已经吐出来的半句")
                .hook(harness::stop));

        List<AgentEvent> events = harness.chat("问一个会被我掐掉的问题");

        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.STOPPED),
                "停止必须下发 STOPPED 事件：" + events);
        assertTrue(harness.memory().isEmpty(),
                "本轮提问没有回答，必须回滚掉；留着就是永久的“有问无答”：" + harness.memory());
    }

    @Test
    @DisplayName("只思考不吐字时也要能停（覆盖 onPartialThinking 这一处检查）")
    void stopWorksWhenModelOnlyThinks() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().enqueue(ScriptedTurn.answer("没有答案")
                .thinking("让我想想这个问题该怎么组织")
                .hook(harness::stop));

        List<AgentEvent> events = harness.chat("问点什么");

        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.STOPPED),
                "模型一直在想而没吐答案时，点了停止也必须有反应：" + events);
    }

    @Test
    @DisplayName("完成回调里的停止检查：一次性返回结果时也不能漏")
    void stopDetectedInCompleteResponse() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().enqueue(ScriptedTurn.answer("整段一次性返回")
                .hook(harness::stop));

        List<AgentEvent> events = harness.chat("问点什么");

        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.STOPPED), events.toString());
        assertTrue(harness.memory().isEmpty(), "一次性返回时若漏了这处检查，本轮会照旧写进记忆：" + harness.memory());
    }

    @Test
    @DisplayName("停止后上一轮的对话完整保留：回滚的是本轮追加的那几条")
    void stopRollsBackOnlyTheCurrentRound() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("第一轮回答");
        harness.chat("第一轮提问");

        harness.model().enqueue(ScriptedTurn.answer("第二轮会被掐掉").hook(harness::stop));
        harness.chat("第二轮提问");

        assertEquals(2, harness.memory().size(),
                "回滚只撤本轮追加的提问，上一轮的问答必须完好：" + harness.memory());
    }

    @Test
    @DisplayName("toolsNode 逐个工具检查停止：停在第 1 个之后就不再执行第 2 个")
    void stopInsideToolsNodeSkipsRemainingCalls() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.toolAction("step1", () -> {
            harness.stop();
            return "第一步完成";
        }, false);
        harness.toolAction("step2", () -> {
            throw new AssertionError("停止之后不该再执行第 2 个工具");
        }, false);
        harness.model().enqueue(ScriptedTurn.multiToolCalls(
                ToolExecutionRequest.builder().id("c1").name("step1").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("step2").arguments("{}").build()));

        List<AgentEvent> events = harness.chat("跑两个工具");

        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.STOPPED), events.toString());
        assertTrue(harness.memory().isEmpty(), "toolsNode 里被掐断同样要回滚本轮提问：" + harness.memory());
    }
}
