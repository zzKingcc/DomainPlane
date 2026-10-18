package com.zzkingcc.domainplane.server.conversation;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.runtime.conversation.TurnView;
import com.zzkingcc.domainplane.runtime.tool.RoundSearchBudget;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * read_history —— 让模型自己回到归档里挖细节。
 *
 * <p><b>它存在的理由</b>：召回层每轮只给"最近几轮原文 + 老轮次的一行卡片"，
 * 更早的细节模型看不到。需要时它按轮号把原文取回来，这是召回层的逃逸舱口，
 * 让"选取召回"不必为"万一要细节"而把全文一直塞在上下文里。</p>
 *
 * <p><b>会话隔离是硬边界</b>：只读<b>本会话</b>的档。域读 {@link RetrievalScope}、
 * sessionId 读 {@link ToolInvocationContext}（都由编排层在工具执行前绑好），
 * 工具签名里一个都不带，带参数就等于给模型一个"读别人的会话"的口子，
 * 而这个工具的返回内容是全量对话原文，越权读的后果比知识库越权重得多。</p>
 *
 * <p><b>限次</b>：每轮最多 {@link RoundSearchBudget#MAX_CALLS} 次（与 M3 的
 * {@code recall_history} 共用同一个预算桶），超了返回一句明确的上限提示，
 * 让模型基于已有内容回答，防的是把检索当抛球游戏。</p>
 *
 * <p><b>可见性</b>：挂根域（全树可见），与 {@code seeImage} 同一注册模式。</p>
 *
 * @author zzkingcc
 */
@Component
public class HistoryReadTool {

    private static final Logger log = LoggerFactory.getLogger(HistoryReadTool.class);

    /** 工具名：与调用约定一致，事件与提示里都用它 */
    private static final String TOOL_NAME = "read_history";

    /** 单次读取的跨度上限；超了提示模型分批读 */
    private static final int MAX_SPAN = 10;

    private final ConversationArchive archive;

    public HistoryReadTool(ConversationArchive archive) {
        this.archive = archive;
    }

    @Tool(value = TOOL_NAME,
            desc = "读取本会话早前轮次的完整内容。你的上下文里只有最近几轮的原文，"
                    + "更早的轮次只有一行摘要（或完全没有）—— 需要细节时用本工具按轮号取回。"
                    + "参数 turnFrom / turnTo 是轮号（从 1 开始，含两端），一次最多 " + MAX_SPAN
                    + " 轮；每轮内容有节流，过长会截断并标注。"
                    + "拿不准轮号时，先看上下文里【可能相关的历史轮次】卡片给出的轮号；"
                    + "中止（未完成）的轮次也会返回并带标记，那不是可以当结论引用的内容。"
                    + "每轮调用次数有限，用完请基于已有内容作答。",
            effect = Tool.Effect.READ)
    public String read(@ToolParam("起始轮号（从 1 开始，含这一轮）") int turnFrom,
                       @ToolParam("结束轮号（含这一轮）；与起始轮号的跨度不得超过 " + MAX_SPAN + " 轮") int turnTo) {

        /* 预算先扣：参数填错也算一次，反过来（只在真正读档时扣）会变成
           "模型反复传非法参数试探"的免费通道，而限次防的就是这种行为。 */
        RoundSearchBudget budget = ToolInvocationContext.searchBudget();
        if (budget != null && !budget.tryAcquire()) {
            log.info("[历史检索] 本轮检索预算已用尽（上限 {} 次）", RoundSearchBudget.MAX_CALLS);
            return "已达本轮历史检索上限（每轮最多 " + RoundSearchBudget.MAX_CALLS
                    + " 次）。请基于已有内容回答，不要反复调用本工具。";
        }

        if (turnFrom < 1) {
            return "轮号从 1 开始：请把 turnFrom 填成 1 或更大的数。";
        }
        if (turnTo < turnFrom) {
            return "结束轮号不能小于起始轮号（turnTo=" + turnTo + " < turnFrom=" + turnFrom + "）。";
        }
        if (turnTo - turnFrom + 1 > MAX_SPAN) {
            return "一次最多读 " + MAX_SPAN + " 轮（本次请求 " + (turnTo - turnFrom + 1)
                    + " 轮）。请缩小范围分批读取。";
        }

        String sessionId = ToolInvocationContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            // 不该发生：编排层每次工具执行前都会绑。它为空意味着工具绕过了编排层
            log.warn("[历史检索] 工具上下文中没有 sessionId，拒绝读取归档");
            return "无法确定当前会话（工具上下文缺失），历史检索不可用。请基于已有内容回答。";
        }
        String domain = RetrievalScope.current();
        String sessionKey = SessionKeys.of(domain, sessionId);

        List<TurnRecord> turns;
        try {
            turns = archive.turnsBetween(sessionKey, turnFrom, turnTo);
        } catch (Exception e) {
            // 读档失败不让工具抛：抛了模型只会看到一句笼统的"工具执行失败"，
            // 而它真正需要的是"这一步没拿到、请基于已有内容作答"
            log.error("[历史检索] 会话[{}] 读档失败（{}~{} 轮）：{}", sessionId, turnFrom, turnTo, e.getMessage());
            return "读取历史归档失败，暂时取不到这段内容。请基于已有内容回答，不要编造这段历史。";
        }
        if (turns.isEmpty()) {
            return "第 " + turnFrom + " 到 " + turnTo
                    + " 轮没有已归档的记录（会话可能还没那么长，或这段归档已被清理）。";
        }

        StringBuilder sb = new StringBuilder();
        for (TurnRecord turn : turns) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(TurnView.renderForModel(turn));
        }
        log.info("[历史检索] 会话[{}] 读取第 {}~{} 轮，命中 {} 轮", sessionId, turnFrom, turnTo, turns.size());
        return sb.toString();
    }
}
