package com.zzkingcc.domainplane.server.conversation;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.runtime.conversation.TurnView;
import com.zzkingcc.domainplane.runtime.tool.RoundSearchBudget;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * recall_history —— 让模型用一句话把相关的老轮次捞回来。
 *
 * <p>与 {@link HistoryReadTool} 的分工：那边按<b>轮号</b>取原文（知道是哪一轮），
 * 这边按<b>内容</b>找轮号（只记得聊过什么）。两者共用同一个每轮检索预算桶
 * （{@link RoundSearchBudget}），合起来每轮 3 次，防的是把检索当抛球游戏。</p>
 *
 * <p><b>只回卡片不回全文</b>：卡片是"轮号 + 时间 + 摘要 + 工具"，
 * 细节让模型再调 {@code read_history}。都塞全文就等于没选取，上下文又会胀回去。</p>
 *
 * <p><b>会话隔离是硬边界</b>：与 {@code read_history} 同一套做法，
 * 工具签名里一个会话参数都不带，会话键由编排层绑在 {@link ToolInvocationContext} 上。</p>
 *
 * @author zzkingcc
 */
@Component
public class HistoryRecallTool {

    private static final Logger log = LoggerFactory.getLogger(HistoryRecallTool.class);

    private static final String TOOL_NAME = "recall_history";

    /** 单次返回的卡片数上限（与装配层默认 k 一致） */
    private static final int MAX_LIMIT = 10;

    private final HistorySearch historySearch;

    public HistoryRecallTool(HistorySearch historySearch) {
        this.historySearch = historySearch;
    }

    @Tool(value = TOOL_NAME,
            desc = "按内容检索本会话早前的轮次，返回轮号 / 时间 / 摘要 / 用过哪些工具（不含全文）。"
                    + "只记得聊过什么、想不起是哪一轮时用它；已经知道轮号就用 read_history 取原文。"
                    + "只检索当前会话，查不到别的会话。"
                    + "每轮调用次数有限，用完请基于已有内容回答。",
            effect = Tool.Effect.READ)
    public String recall(@ToolParam("用一句话描述要找的内容（中文即可）") String query,
                         @ToolParam("返回条数，默认 5，最多 " + MAX_LIMIT) int limit) {

        /* 预算先扣：参数填错也算一次，与 read_history 同口径，
           否则"反复传非法参数试探"会变成一条免费通道。 */
        RoundSearchBudget budget = ToolInvocationContext.searchBudget();
        if (budget != null && !budget.tryAcquire()) {
            MetricsRegistry.recallBudgetBlocked();
            log.info("[历史检索] 本轮检索预算已用尽（上限 {} 次）", RoundSearchBudget.MAX_CALLS);
            return "已达本轮历史检索上限（每轮最多 " + RoundSearchBudget.MAX_CALLS
                    + " 次）。请基于已有内容回答，不要反复调用本工具。";
        }

        if (query == null || query.isBlank()) {
            return "query 不能为空：用一句话描述你要找的内容。";
        }
        int k = limit <= 0 ? 5 : Math.min(limit, MAX_LIMIT);

        String sessionId = ToolInvocationContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[历史检索] 工具上下文中没有 sessionId，拒绝检索归档");
            return "无法确定当前会话（工具上下文缺失），历史检索不可用。请基于已有内容回答。";
        }
        String domain = RetrievalScope.current();
        String sessionKey = SessionKeys.of(domain, sessionId);

        List<HistoryCard> cards;
        try {
            MetricsRegistry.recallToolCall();
            cards = historySearch.search(sessionKey, query, k);
        } catch (Exception e) {
            // 检索失败不让工具抛：抛了模型只看到一句笼统的"工具执行失败"，
            // 而它真正需要的是"这一步没拿到、请基于已有内容作答"
            log.error("[历史检索] 会话[{}] 倒排检索失败：{}", sessionId, e.getMessage());
            return "检索历史失败，暂时查不到。请基于已有内容回答，不要编造这段历史。";
        }
        if (cards.isEmpty()) {
            return "没有找到与「" + query + "」相关的历史轮次（可能确实没聊过，"
                    + "或相关轮次已被清理）。请基于已有内容回答。";
        }
        log.info("[历史检索] 会话[{}] 检索「{}」命中 {} 轮", sessionId, query, cards.size());
        return TurnView.renderCards(cards);
    }
}
