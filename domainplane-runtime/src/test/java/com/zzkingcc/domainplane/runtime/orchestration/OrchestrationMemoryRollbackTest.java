package com.zzkingcc.domainplane.runtime.orchestration;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 记忆回滚的哨兵语义 —— 一处看着无害、错起来不可逆的实现细节。
 *
 * <p>{@code memorySizeBefore} 必须是 {@code null} 而不是 0：</p>
 * <ul>
 *   <li>{@code null} = "还没量过"（本轮还没往记忆里写过东西）；</li>
 *   <li>{@code 0} = "量到的是空的"（记忆本来就是空）。</li>
 * </ul>
 *
 * <p>两者混用的后果：异常发生在测量之前时，"没有量过"被误判成"记忆是空的"，
 * 于是回滚把记忆清成 0 —— 那一次清空会把上一轮的内容一起抹掉，且不可恢复。</p>
 */
@DisplayName("F07 记忆回滚哨兵")
class OrchestrationMemoryRollbackTest {

    @Test
    @DisplayName("异常早于长度测量时不回滚：上一轮内容一条不少")
    void failureBeforeMeasuringSizeKeepsPreviousRounds() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("第一轮回答");
        harness.chat("第一轮提问");

        // 第二轮：取记忆这一步就炸 —— 此刻"写入前的条数"还没量过。
        // 若哨兵用 0 而不是 null，这里会把会话清空，且不可逆。
        harness.failNextMemoryLookup();
        harness.chat("第二轮提问");

        assertEquals(2, harness.memory().size(),
                "没量过长度就失败时绝不能清空记忆，那会把上一轮一起抹掉：" + harness.memory());
        assertInstanceOf(UserMessage.class, harness.memory().get(0));
        assertInstanceOf(AiMessage.class, harness.memory().get(1));
        assertEquals("第一轮回答", ((AiMessage) harness.memory().get(1)).text());
    }

    @Test
    @DisplayName("测得长度时只回滚本轮：记忆精确回到提问前的条数")
    void rollbackRestoresExactPreviousLength() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("第一轮回答");
        harness.chat("第一轮提问");
        assertEquals(2, harness.memory().size());

        harness.model().enqueue(ScriptedTurn.answer("第二轮会被掐掉").hook(harness::stop));
        harness.chat("第二轮提问");

        assertEquals(2, harness.memory().size(), "回滚必须精确回到 2 条：" + harness.memory());
        assertEquals("第一轮回答", ((AiMessage) harness.memory().get(1)).text());
    }
}
