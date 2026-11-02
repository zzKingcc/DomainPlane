package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两道"忙"闸门各自代表一件不同的事，混用会让排障南辕北辙：
 *
 * <ul>
 *   <li>{@code SESSION_BUSY} —— 同会话已有一轮在跑，是<b>调用方</b>的问题（别并发同一会话）；</li>
 *   <li>{@code SYSTEM_BUSY} —— 编排队列已满/闸门已满，是<b>平台</b>的问题（要扩容或退避）。</li>
 * </ul>
 */
@DisplayName("F07 串行闸门与过载背压")
class OrchestrationBusyTest {

    @Test
    @DisplayName("同会话第二轮：SESSION_BUSY，且只给被拒的那一个事件")
    void concurrentSecondRoundRejectedWithSessionBusy() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().answer("这一轮不会被跑到");
        // 手动标记"本会话正在执行"，等价于第一轮在跑的情况下第二轮进来
        assertTrue(harness.cancellation().tryMarkRunning(harness.key()), "前置条件：标记执行中必须成功");

        List<AgentEvent> events = harness.chat("第二轮提问");

        assertEquals(1, events.size(), "被拒的请求不应再多产事件：" + events);
        assertEquals(ErrorCode.SESSION_BUSY.getCode(), events.get(0).getCode(), events.toString());
        assertTrue(harness.memory().isEmpty(), "被拒的轮次不写记忆：" + harness.memory());
    }

    @Test
    @DisplayName("被拒时不写记忆、不建断点：停掉仍在跑的那一轮后会话可继续")
    void rejectedRoundLeavesNoTrace() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.cancellation().tryMarkRunning(harness.key());
        harness.chat("第二轮提问");

        harness.cancellation().unmarkRunning(harness.key());
        harness.model().answer("恢复正常");
        List<AgentEvent> events = harness.chat("再来一次");

        assertEquals(2, harness.memory().size(), "恢复后应照常跑完一轮：" + harness.memory());
        assertEquals(1, events.stream()
                .filter(e -> "DONE".equals(e.getType().name())).count(), events.toString());
    }

    @Test
    @DisplayName("提交被线程池拒绝时下发 SYSTEM_BUSY，而不是把异常冒泡给调用方")
    void rejectedSubmissionReportsSystemBusy() {
        OrchestrationHarness harness = new OrchestrationHarness(
                command -> { throw new RejectedExecutionException("模拟池满"); });
        harness.model().answer("不会被跑到");

        List<AgentEvent> events = harness.chat("提问");

        assertEquals(1, events.size(), "只产一个 ERROR：" + events);
        assertEquals(ErrorCode.SYSTEM_BUSY.getCode(), events.get(0).getCode(), events.toString());
        assertTrue(harness.memory().isEmpty(), "没能提交的任务不写记忆：" + harness.memory());
        assertEquals(0, harness.service().activeStreamCount(),
                "提交失败必须把自己注销掉，否则会留下一个永不释放的流上下文");
    }
}
