package com.zzkingcc.domainplane.server.conversation;

import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
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
 * M3 recall_history：只检本会话、每轮限次、失败不抛。
 *
 * <p>与 {@link HistoryReadToolTest} 同两条硬边界（会话隔离 / 每轮限次），
 * 外加一条倒排特有的：<b>检索失败必须降级成一句人话</b>：
 * 抛给框架的话模型只看到"工具执行失败"，它真正需要知道的是
 * "这一步没拿到，别编造"。</p>
 *
 * @author zzkingcc
 */
@DisplayName("M3 recall_history：只检本会话、每轮限次、失败给一句人话")
class HistoryRecallToolTest {

    private static class StubHistorySearch implements HistorySearch {

        final Map<String, List<HistoryCard>> byKey = new LinkedHashMap<>();
        final List<String> searchKeys = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        RuntimeException failure;

        @Override public void indexTurn(String sessionKey, TurnRecord record) {}
        @Override public int deleteByDomain(String domain) { return 0; }

        @Override public List<HistoryCard> search(String sessionKey, String query, int limit) {
            searchKeys.add(sessionKey);
            queries.add(query);
            limits.add(limit);
            if (failure != null) {
                throw failure;
            }
            return byKey.getOrDefault(sessionKey, List.of());
        }
    }

    private static HistoryCard card(long turnId, String digest, String... tools) {
        return new HistoryCard(turnId, Instant.now(), digest, List.of(tools));
    }

    @AfterEach
    void unbind() {
        ToolInvocationContext.clear();
        RetrievalScope.clear();
    }

    @Test
    @DisplayName("只检本会话：域 + sessionId 合成会话键，别的会话一条都检不到")
    void searchesOnlyBoundSession() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A",
                List.of(card(2, "聊过物流的事", "query_logistics")));
        search.byKey.put("default.sales|s-B", List.of(card(1, "B 的秘密")));
        HistoryRecallTool tool = new HistoryRecallTool(search);

        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");
        String out = tool.recall("物流", 5);

        assertEquals(List.of("default.sales|s-A"), search.searchKeys, "会话键必须是 域|sessionId");
        assertTrue(out.contains("聊过物流的事"));
        assertFalse(out.contains("B 的秘密"), "跨会话内容是越权口子：" + out);
    }

    @Test
    @DisplayName("域不同、sessionId 相同：检的是另一把键，查不到就如实说")
    void differentDomainIsDifferentSession() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A", List.of(card(1, "销售域的秘密")));
        HistoryRecallTool tool = new HistoryRecallTool(search);

        RetrievalScope.bind("default.support");
        ToolInvocationContext.bind("t-1", "s-A");
        String out = tool.recall("订单", 5);

        assertEquals(List.of("default.support|s-A"), search.searchKeys);
        assertFalse(out.contains("销售域的秘密"), "换个域就能检到 = 会话隔离形同虚设：" + out);
        assertTrue(out.contains("没有找到"), "应当如实告知查不到：" + out);
    }

    @Test
    @DisplayName("没绑 sessionId：拒绝检索，绝不退化成「检全部」")
    void refusesWithoutSessionId() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A", List.of(card(1, "问")));
        HistoryRecallTool tool = new HistoryRecallTool(search);

        RetrievalScope.bind("default.sales");
        ToolInvocationContext.clear();
        String out = tool.recall("订单", 5);

        assertTrue(search.searchKeys.isEmpty(), "不该发任何检索请求");
        assertTrue(out.contains("无法确定当前会话"), "必须明确告知不可用：" + out);
    }

    // ==================== 限次 ====================

    @Test
    @DisplayName("每轮预算：第 4 次被拦下，且不会去检索")
    void budgetStopsAfterMaxCalls() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A", List.of(card(1, "聊过", "query_order")));
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A", new RoundSearchBudget());

        for (int i = 0; i < RoundSearchBudget.MAX_CALLS; i++) {
            assertFalse(tool.recall("订单", 5).contains("上限"), "第 " + (i + 1) + " 次应该在预算内");
        }
        int callsBefore = search.searchKeys.size();
        String blocked = tool.recall("订单", 5);

        assertTrue(blocked.contains("已达本轮历史检索上限"), "超限要给出明确提示：" + blocked);
        assertEquals(callsBefore, search.searchKeys.size(), "被拦下的那次不该再检索");
    }

    @Test
    @DisplayName("参数填错也占预算：否则反复传空 query 就是一条免费试探通道")
    void invalidArgumentsStillConsumeBudget() {
        StubHistorySearch search = new StubHistorySearch();
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        RoundSearchBudget budget = new RoundSearchBudget();
        ToolInvocationContext.bind("t-1", "s-A", budget);

        assertTrue(tool.recall("", 5).contains("query 不能为空"));
        assertTrue(tool.recall(null, 5).contains("query 不能为空"));
        assertEquals(1, budget.remaining(), "两次非法调用应各占一次预算");

        assertTrue(tool.recall("  ", 5).contains("query 不能为空"));
        assertFalse(budget.tryAcquire(), "三次非法调用应把预算耗到只剩 0");
    }

    @Test
    @DisplayName("limit 归一化：缺省 5，超过上限截到 10")
    void limitIsClamped() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A", List.of(card(1, "聊过")));
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        tool.recall("订单", 0);
        tool.recall("订单", 99);
        assertEquals(List.of(5, 10), search.limits);
    }

    // ==================== 容错与呈现 ====================

    @Test
    @DisplayName("检索失败不抛给模型，返回一句「别编造」的提示")
    void searchFailureIsNotAnException() {
        StubHistorySearch search = new StubHistorySearch();
        search.failure = new IllegalStateException("Redis 挂了");
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        String out = tool.recall("订单", 5);
        assertTrue(out.contains("检索历史失败") && out.contains("不要编造"), out);
    }

    @Test
    @DisplayName("查无结果：如实说没找到，并提醒基于已有内容回答")
    void emptyResultSaysNotFound() {
        StubHistorySearch search = new StubHistorySearch();
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        String out = tool.recall("退款", 5);
        assertTrue(out.contains("没有找到与「退款」相关的历史轮次"), out);
        assertTrue(out.contains("请基于已有内容回答"), out);
    }

    @Test
    @DisplayName("命中：渲染卡片（轮号 + 摘要 + 工具），不塞全文")
    void rendersCardsWithoutFullText() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A",
                List.of(card(7, "订单与物流查询", "query_order", "query_logistics")));
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        String out = tool.recall("订单 物流", 5);
        assertTrue(out.contains("【可能相关的历史轮次】"), out);
        assertTrue(out.contains("第 7 轮"), out);
        assertTrue(out.contains("订单与物流查询"), "摘要是卡片的正文：" + out);
        assertTrue(out.contains("query_order") && out.contains("query_logistics"), "工具名要带上：" + out);
        assertTrue(out.contains("用 read_history 按轮号取原文"),
                "要指引模型去取原文，而不是在这里塞全文：" + out);
    }

    @Test
    @DisplayName("返回条数由检索方决定：工具只传 limit，不自己截")
    void passesLimitThrough() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A", List.of(card(1, "聊过")));
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        tool.recall("订单", 3);
        assertEquals(List.of(3), search.limits);
        assertEquals(List.of("订单"), search.queries);
    }

    // ==================== 可观测（M4 治理） ====================

    @SuppressWarnings("unchecked")
    private static long counter(String key) {
        Map<String, Object> counters =
                (Map<String, Object>) MetricsRegistry.snapshot().get("counters");
        return ((Number) counters.get(key)).longValue();
    }

    @Test
    @DisplayName("工具层指标：实际检索计 toolCalls，被预算拦下计 budgetBlocked（不算检索）")
    void reportsToolCallsAndBudgetBlocks() {
        StubHistorySearch search = new StubHistorySearch();
        search.byKey.put("default.sales|s-A", List.of(card(1, "聊过", "query_order")));
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A", new RoundSearchBudget());

        long calls = counter("recallToolCalls");
        long blocked = counter("recallBudgetBlocked");

        for (int i = 0; i < RoundSearchBudget.MAX_CALLS; i++) {
            tool.recall("订单", 5);
        }
        assertEquals(calls + RoundSearchBudget.MAX_CALLS, counter("recallToolCalls"));
        assertEquals(blocked, counter("recallBudgetBlocked"), "预算内不该计拦截");

        tool.recall("订单", 5);
        assertEquals(calls + RoundSearchBudget.MAX_CALLS, counter("recallToolCalls"),
                "被拦下的那次不该计成一次检索");
        assertEquals(blocked + 1, counter("recallBudgetBlocked"));
    }

    @Test
    @DisplayName("工具层指标：非法参数不占 toolCalls（还没走到检索）")
    void invalidArgumentsDoNotCountAsToolCall() {
        StubHistorySearch search = new StubHistorySearch();
        HistoryRecallTool tool = new HistoryRecallTool(search);
        RetrievalScope.bind("default.sales");
        ToolInvocationContext.bind("t-1", "s-A");

        long calls = counter("recallToolCalls");
        tool.recall("", 5);
        assertEquals(calls, counter("recallToolCalls"), "参数不合法不是一次检索");
    }
}
