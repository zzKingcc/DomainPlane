package com.zzkingcc.domainplane.server.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.FlowEvent;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.runtime.tool.RoundSearchBudget;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1c read_history：会话隔离与每轮限次。
 *
 * <p>这个工具返回的是<b>全量对话原文</b>，所以它的两条边界不是"体验优化"而是安全边界：</p>
 * <ul>
 *   <li><b>会话隔离</b>：工具签名里没有 sessionId / 域参数，全靠编排层在线程上绑。
 *       一旦绑漏或读错，模型就能读到别人的整段对话：比知识库越权重得多。</li>
 *   <li><b>每轮限次</b>：防的是模型把检索当抛球游戏，一轮里反复翻档直到烧光上下文。</li>
 * </ul>
 *
 * <p>两条都靠 ThreadLocal，测的是"绑什么就读什么"，而不是"传什么就读什么"。</p>
 *
 * @author zzkingcc
 */
@DisplayName("M1c read_history：只读本会话、每轮限次")
class HistoryReadToolTest {

    /** 内存归档：只记 turnsBetween 的入参与返回，够本用例用 */
    private static class StubArchive implements ConversationArchive {

        final Map<String, List<TurnRecord>> data = new LinkedHashMap<>();
        final List<String> readKeys = new ArrayList<>();
        RuntimeException failure;

        @Override public long openTurn(String sessionKey, UserInput user) { return 0; }
        @Override public void closeTurn(String sessionKey, long turnId, TurnRecord record) {}
        @Override public void abortTurn(String sessionKey, long turnId, String reason, String partialAnswer) {}
        @Override public void rejectTurn(String sessionKey, long turnId) {}
        @Override public void deleteTurn(String sessionKey, long turnId) {}
        @Override public Optional<TurnRecord> findOpenTurn(String sessionKey) { return Optional.empty(); }
        @Override public Optional<TurnRecord> turnAt(String sessionKey, long turnId) { return Optional.empty(); }
        @Override public List<TurnRecord> recentTurns(String sessionKey, int limit) { return List.of(); }
        @Override public int recoverStaleOpenTurn(String sessionKey) { return 0; }
        @Override public void clearSession(String sessionKey) {}
        @Override public int deleteByDomain(String domain) { return 0; }

        @Override public List<TurnRecord> turnsBetween(String sessionKey, long fromInclusive, long toInclusive) {
            readKeys.add(sessionKey);
            if (failure != null) {
                throw failure;
            }
            List<TurnRecord> all = data.getOrDefault(sessionKey, List.of());
            List<TurnRecord> out = new ArrayList<>();
            for (TurnRecord t : all) {
                if (t.turnId() >= fromInclusive && t.turnId() <= toInclusive) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    private static TurnRecord turn(long id, String key, String question, String answer) {
        return new TurnRecord(id, key, Instant.parse("2026-10-14T10:00:00Z"),
                Instant.parse("2026-10-14T10:00:08Z"), UserInput.of(question, List.of()),
                List.of(FlowEvent.ofTool(FlowEventType.TOOL_CALL, "query_order", "{}")),
                answer, new TokenUsage(1, 2, 3), new TurnFlags(false, false, false, null), "摘要" + id);
    }

    @AfterEach
    void unbind() {
        ToolInvocationContext.clear();
        RetrievalScope.clear();
    }

    @Test
    @DisplayName("只读本会话：域 + sessionId 合成会话键，别的会话一条都读不到")
    void readsOnlyBoundSession() {
        StubArchive archive = new StubArchive();
        archive.data.put("default.sales|s-A", List.of(
                turn(1, "default.sales|s-A", "A 的第一句", "A 的第一个回答"),
                turn(2, "default.sales|s-A", "A 的第二句", "A 的第二个回答")));
        archive.data.put("default.sales|s-B", List.of(
                turn(1, "default.sales|s-B", "B 的秘密", "B 的机密回答")));
        HistoryReadTool tool = new HistoryReadTool(archive);

        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");
        String out = tool.read(1, 2);

        assertEquals(List.of("default.sales|s-A"), archive.readKeys, "会话键必须是 域|sessionId");
        assertTrue(out.contains("A 的第一句") && out.contains("A 的第二句"));
        assertFalse(out.contains("B 的秘密"), "跨会话内容是越权口子：" + out);
    }

    @Test
    @DisplayName("域不同、sessionId 相同：同样读不到（会话键含域）")
    void differentDomainIsDifferentSession() {
        StubArchive archive = new StubArchive();
        archive.data.put("default.sales|s-A", List.of(turn(1, "default.sales|s-A", "销售域的问题", "答")));
        HistoryReadTool tool = new HistoryReadTool(archive);

        RetrievalScope.bind("default.support");
        ToolInvocationContext.bind("t-1", "s-A");
        String out = tool.read(1, 1);

        assertFalse(out.contains("销售域的问题"), "换个域就能读到 = 会话隔离形同虚设：" + out);
        assertTrue(out.contains("没有已归档的记录"), "应当如实告知查不到：" + out);
    }

    @Test
    @DisplayName("没绑 sessionId：拒绝读取，绝不退化成「读全部」")
    void refusesWithoutSessionId() {
        StubArchive archive = new StubArchive();
        archive.data.put("default.sales|s-A", List.of(turn(1, "default.sales|s-A", "问", "答")));
        HistoryReadTool tool = new HistoryReadTool(archive);

        RetrievalScope.bind("default.sales");
        ToolInvocationContext.clear();
        String out = tool.read(1, 1);

        assertTrue(archive.readKeys.isEmpty(), "不该发任何读档请求");
        assertTrue(out.contains("无法确定当前会话"), "必须明确告知不可用：" + out);
    }

    // ==================== 限次 ====================

    @Test
    @DisplayName("每轮预算：第 4 次被拦下，且不会去读档")
    void budgetStopsAfterMaxCalls() {
        StubArchive archive = new StubArchive();
        archive.data.put("default.sales|s-A", List.of(turn(1, "default.sales|s-A", "问", "答")));
        HistoryReadTool tool = new HistoryReadTool(archive);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A", new RoundSearchBudget());

        for (int i = 0; i < RoundSearchBudget.MAX_CALLS; i++) {
            assertFalse(tool.read(1, 1).contains("上限"), "第 " + (i + 1) + " 次应该在预算内");
        }
        int readsBefore = archive.readKeys.size();
        String blocked = tool.read(1, 1);

        assertTrue(blocked.contains("已达本轮历史检索上限"), "超限要给出明确提示：" + blocked);
        assertEquals(readsBefore, archive.readKeys.size(), "被拦下的那次不该再读档");
    }

    @Test
    @DisplayName("参数填错也占预算：否则反复传非法参数就是一条免费试探通道")
    void invalidArgumentsStillConsumeBudget() {
        StubArchive archive = new StubArchive();
        HistoryReadTool tool = new HistoryReadTool(archive);
        RetrievalScope.bind("default.sales");
        RoundSearchBudget budget = new RoundSearchBudget();
        ToolInvocationContext.bind("t-1", "s-A", budget);

        assertTrue(tool.read(0, 3).contains("轮号从 1 开始"));
        assertTrue(tool.read(5, 3).contains("结束轮号不能小于"));
        assertTrue(tool.read(1, 99).contains("一次最多读"));

        assertFalse(budget.tryAcquire(), "三次非法调用应把预算耗到只剩 0");
    }

    // ==================== 跨度与容错 ====================

    @Test
    @DisplayName("跨度超限要提示分批，而不是静默截断")
    void rejectsTooWideSpan() {
        StubArchive archive = new StubArchive();
        HistoryReadTool tool = new HistoryReadTool(archive);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        assertTrue(tool.read(1, 11).contains("一次最多读"));
        assertTrue(archive.readKeys.isEmpty(), "被拒的请求不该落到 Redis");
    }

    @Test
    @DisplayName("读档失败不抛给模型，返回一句「别编造」的提示")
    void readFailureIsNotAnException() {
        StubArchive archive = new StubArchive();
        archive.failure = new IllegalStateException("Redis 挂了");
        HistoryReadTool tool = new HistoryReadTool(archive);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        String out = tool.read(1, 1);
        assertTrue(out.contains("读取历史归档失败") && out.contains("不要编造"), out);
    }

    @Test
    @DisplayName("中止轮也要回给模型，并带「未完成」标记")
    void abortedTurnIsRenderedWithMarker() {
        StubArchive archive = new StubArchive();
        archive.data.put("default.sales|s-A", List.of(new TurnRecord(1, "default.sales|s-A",
                Instant.now(), Instant.now(), UserInput.of("问到一半", List.of()),
                List.of(), null, null, new TurnFlags(false, true, false, null), null)));
        HistoryReadTool tool = new HistoryReadTool(archive);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        String out = tool.read(1, 1);
        assertTrue(out.contains("未完成"), "模型需要知道那一轮没答完：" + out);
        assertTrue(out.contains("问到一半"));
    }
}
