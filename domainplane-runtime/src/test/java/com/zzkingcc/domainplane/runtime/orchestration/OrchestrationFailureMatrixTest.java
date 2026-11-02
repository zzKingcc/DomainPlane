package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import com.zzkingcc.domainplane.common.exception.CheckpointCorruptedException;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败矩阵 —— 编排把失败分成几条完全不同的出路，每条对调用方的处置都不一样。
 *
 * <p>把它们钉在同一个测试里是因为它们共享同一段 {@code try/catch}：改一处就容易串到另一条，
 * 而串了之后的现场（错误码）会直接误导调用方——例如把"检查点读不出来"当成"内部故障"，
 * 用户就会被建议"重试"，而实际上他该换一个 sessionId。</p>
 */
@DisplayName("F07 失败矩阵")
class OrchestrationFailureMatrixTest {

    @Test
    @DisplayName("检查点数据损坏：清键自愈，本轮照常跑完")
    void corruptedCheckpointSelfHealsAndContinues() {
        // 只有开轮那一刻的读断点会失败（此刻复现"键读得出来、内容解不开"），
        // 之后的工作交给真实存档，验证的是"自愈之后还能正常跑完"
        AtomicInteger failuresLeft = new AtomicInteger(1);
        OrchestrationHarness harness = new OrchestrationHarness(Runnable::run,
                new FailingSaver(failuresLeft,
                        new CheckpointCorruptedException("坏键", new IllegalStateException("bad"))));
        harness.model().answer("自愈之后的回答");

        List<AgentEvent> events = harness.chat("提问");

        assertEquals(AgentEventType.DONE, events.get(events.size() - 1).getType(),
                "数据损坏应当清键后按'无待审批'继续，而不是整轮拒绝：" + events);
        assertEquals(2, harness.memory().size(), "本轮应正常写入记忆：" + harness.memory());
    }

    @Test
    @DisplayName("检查点瞬时故障：整轮拒绝并标记可重试，不写记忆")
    void transientCheckpointFailureRejectsWholeRound() {
        OrchestrationHarness harness = new OrchestrationHarness(Runnable::run,
                new FailingSaver(new AtomicInteger(Integer.MAX_VALUE),
                        new IllegalStateException("Redis 抖动")));
        harness.model().answer("不会被跑到");

        List<AgentEvent> events = harness.chat("提问");

        assertEquals(1, events.size(), "只产一个 ERROR：" + events);
        assertEquals(ErrorCode.CHECKPOINT_ERROR.getCode(), events.get(0).getCode(), events.toString());
        assertTrue(harness.memory().isEmpty(),
                "整轮被拒时不得写记忆，否则留下一条永远没有回答的提问：" + harness.memory());
    }

    @Test
    @DisplayName("未配置：走专用码，而不是笼统的内部故障")
    void notConfiguredUsesItsOwnCode() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().error(new NotConfiguredException("请先在管控台「模型档案」绑定对话模型"));

        List<AgentEvent> events = harness.chat("提问");

        AgentEvent error = OrchestrationHarness.only(events);
        assertEquals(ErrorCode.DEPENDENCY_NOT_CONFIGURED.getCode(), error.getCode(), error.toString());
        assertTrue(harness.memory().isEmpty(), "未配置同样要回滚本轮提问：" + harness.memory());
    }

    @Test
    @DisplayName("其他异常：ORCHESTRATION_FAILED，并把本轮提问从记忆里回滚掉")
    void unexpectedFailureRollsBackAndReportsGenericCode() {
        OrchestrationHarness harness = new OrchestrationHarness();
        harness.model().error(new IllegalStateException("未知故障"));

        List<AgentEvent> events = harness.chat("提问");

        AgentEvent error = OrchestrationHarness.only(events);
        assertEquals(ErrorCode.ORCHESTRATION_FAILED.getCode(), error.getCode(), error.toString());
        assertTrue(harness.memory().isEmpty(),
                "异常路径必须与 stop 路径同一口径地把记忆回滚：" + harness.memory());
    }

    /**
     * 会失败的检查点存档：前 {@code failuresLeft} 次读取抛给定异常，之后退回真实存档。
     *
     * <p>必须能在"失败若干次之后恢复"：否则自愈分支（清键之后继续跑）没法验证——
     * 那正是"自愈"这个词唯一有意义的地方。</p>
     */
    private static final class FailingSaver implements BaseCheckpointSaver {

        private final MemorySaver delegate = new MemorySaver();
        private final AtomicInteger failuresLeft;
        private final RuntimeException failure;

        FailingSaver(AtomicInteger failuresLeft, RuntimeException failure) {
            this.failuresLeft = failuresLeft;
            this.failure = failure;
        }

        @Override
        public Optional<Checkpoint> get(RunnableConfig config) {
            if (failuresLeft.getAndDecrement() > 0) {
                throw failure;
            }
            return delegate.get(config);
        }

        @Override
        public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
            return delegate.put(config, checkpoint);
        }

        @Override
        public Collection<Checkpoint> list(RunnableConfig config) {
            return delegate.list(config);
        }

        @Override
        public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
            return delegate.release(config);
        }
    }
}
