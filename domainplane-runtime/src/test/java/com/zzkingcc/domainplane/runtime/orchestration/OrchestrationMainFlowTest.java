package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排主流程：一轮对话从"提问进记忆"到"回答进记忆"的完整闭环。
 *
 * <p>这是 F07 全部结构改动的<b>行为基线</b>：后面每做一步拆分都要重跑它，
 * 验收标准是"行数变小"之后的逐条一致，而不是"看起来还在跑"。</p>
 */
@DisplayName("F07 编排主流程")
class OrchestrationMainFlowTest {

    @Test
    @DisplayName("纯文本一轮：增量 + DONE，记忆留提问与回答两条")
    void textTurnEndsWithDoneAndTwoMemoryMessages() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("你好，有什么可以帮你？", "你好，", "有什么可以帮你？");

        List<AgentEvent> events = harness.chat("在吗");

        List<AgentEvent> tokens = events.stream()
                .filter(e -> e.getType() == AgentEventType.TOKEN).toList();
        assertEquals(2, tokens.size(), "两个增量应当各发一个 TOKEN 事件：" + events);
        AgentEvent last = events.get(events.size() - 1);
        assertEquals(AgentEventType.DONE, last.getType(), "正常结束必须以 DONE 收尾：" + events);

        List<dev.langchain4j.data.message.ChatMessage> memory = harness.memory();
        assertEquals(2, memory.size(), "一轮应当在记忆里留下 提问 + 回答 两条：" + memory);
        assertInstanceOf(UserMessage.class, memory.get(0));
        assertInstanceOf(AiMessage.class, memory.get(1));
        assertEquals("你好，有什么可以帮你？", ((AiMessage) memory.get(1)).text());
    }

    @Test
    @DisplayName("工具往返：模型调工具后回到 agent，最终回答用的是第二次模型的输出")
    void toolRoundTripReentersAgent() {
        OrchestrationHarness harness = new OrchestrationHarness().tool("echo", "已执行", false);
        harness.model()
                .toolCall("echo", "{\"text\":\"hi\"}")
                .answer("工具已经跑完了");

        List<AgentEvent> events = harness.chat("帮我跑一下 echo");

        assertEquals(AgentEventType.DONE, events.get(events.size() - 1).getType(), events.toString());
        assertEquals(2, harness.model().requests().size(),
                "应当是两次模型调用：一次给出工具调用、一次给出最终回答");
        List<dev.langchain4j.data.message.ChatMessage> memory = harness.memory();
        assertEquals("工具已经跑完了", ((AiMessage) memory.get(memory.size() - 1)).text());
    }

    @Test
    @DisplayName("本轮提问会作为最后一条 UserMessage 进请求体，且与写进记忆的是同一份")
    void userMessageIsSharedBetweenMemoryAndRequest() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("收到");

        harness.chat("这是一句提问");

        dev.langchain4j.model.chat.request.ChatRequest request = harness.model().lastRequest();
        List<dev.langchain4j.data.message.ChatMessage> messages = request.messages();
        UserMessage lastUser = null;
        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof UserMessage user) {
                lastUser = user;
            }
        }
        assertTrue(lastUser != null && "这是一句提问".equals(lastUser.singleText()),
                "请求体里必须能看到本轮提问原文：" + messages);
        assertInstanceOf(UserMessage.class, harness.memory().get(0));
    }

    @Test
    @DisplayName("第二轮带着第一轮的记忆继续：窗口里有上一轮的原文")
    void secondRoundCarriesPreviousWindow() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("第一轮回答").answer("第二轮回答");

        harness.chat("第一轮提问");
        harness.chat("第二轮提问");

        assertEquals(4, harness.memory().size(), "两轮 = 四条消息：" + harness.memory());
        dev.langchain4j.model.chat.request.ChatRequest request = harness.model().lastRequest();
        assertTrue(request.messages().stream()
                        .anyMatch(m -> m instanceof AiMessage ai && "第一轮回答".equals(ai.text())),
                "第二轮的请求体里必须能看到第一轮的回答（近况窗口）：" + request.messages());
    }

    @Test
    @DisplayName("THINKING 走独立通道：不并入答案文本")
    void thinkingIsASeparateChannel() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().enqueue(ScriptedTurn.answer("答案")
                .thinking("让我先想一下")
                .partial("答"));

        List<AgentEvent> events = harness.chat("问一句");

        assertEquals(1, events.stream().filter(e -> e.getType() == AgentEventType.THINKING).count(),
                "思考应当单独发一帧 THINKING：" + events);
        String tokens = events.stream().filter(e -> e.getType() == AgentEventType.TOKEN)
                .map(AgentEvent::getContent).reduce("", String::concat);
        assertEquals("答", tokens, "思考文本不得混进答案增量：" + tokens);
    }
}
