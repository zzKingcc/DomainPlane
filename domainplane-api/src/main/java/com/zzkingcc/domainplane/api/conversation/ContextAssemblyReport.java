package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 一轮请求的装配清单（F04-a：每轮召回可观测）。
 *
 * <p>装配是"这轮给模型看多少"的唯一决策点（见运行层 {@code ContextAssembler}），
 * 但决策过程此前不留痕：装配完只剩一串消息，用户说"它忘了"时无从判断
 * 是<b>没召回</b>（卡片段空）还是<b>召回了但被预算砍掉</b>（卡片被砍 / 窗口整轮丢）。
 * 本记录就是这两者的分水岭——每段给了多少、砍了多少，逐段落账。</p>
 *
 * <p><b>派生数据</b>：与倒排索引同类，丢了可重建（重放归档再装一次），
 * 所以写失败只告警、不抛；存储侧不必遵守"记忆/归档只增不减"那条硬约束
 * （那条约束管的是有真相价值的数据，这里只是排障用的账本，
 * 见 F04 附注：可删的只有派生数据）。</p>
 *
 * <p><b>不记内容</b>：只记条数 / token / 被砍的轮号，不记正文。
 * 正文在归档里（永久），账本再抄一份等于给"永久不删"多开一个口子。</p>
 *
 * @param turnId                 本轮轮号；开轮失败时为 0（清单仍落，因为它记的是装配而不是轮次）
 * @param at                     装配发生的时间
 * @param totalBudget            本轮总预算（已被模型窗口收窄后的值）
 * @param systemTokens           系统提示 token（不进预算，只作对照）
 * @param summaryTokens          摘要段 token（0 = 本轮没有摘要段）
 * @param summaryLines           摘要段行数（一行 = 一条老轮次的一句话摘要）
 * @param summaryDroppedLines    摘要段被按行截掉的行数（截的是最老的）
 * @param cardCount              卡片段条数
 * @param cardTokens             卡片段 token
 * @param droppedCardTurnIds     被预算砍掉的卡片轮号（相关度最低的那些）
 * @param windowMessages         近况窗口消息条数（装配后）
 * @param windowTokens           近况窗口 token
 * @param windowDroppedMessages  窗口被丢掉的消息条数（整轮丢，2 条一批）
 * @param currentTokens          本轮提问 token
 * @param totalTokens            装配结果总 token（各段之和，含系统提示）
 * @param degraded               是否发生过降级（任一段被砍即为 true）
 * @author zzkingcc
 */
public record ContextAssemblyReport(
        @JsonProperty("sessionKey") String sessionKey,
        @JsonProperty("turnId") long turnId,
        @JsonProperty("at") Instant at,
        @JsonProperty("totalBudget") int totalBudget,
        @JsonProperty("systemTokens") int systemTokens,
        @JsonProperty("summaryTokens") int summaryTokens,
        @JsonProperty("summaryLines") int summaryLines,
        @JsonProperty("summaryDroppedLines") int summaryDroppedLines,
        @JsonProperty("cardCount") int cardCount,
        @JsonProperty("cardTokens") int cardTokens,
        @JsonProperty("droppedCardTurnIds") List<Long> droppedCardTurnIds,
        @JsonProperty("windowMessages") int windowMessages,
        @JsonProperty("windowTokens") int windowTokens,
        @JsonProperty("windowDroppedMessages") int windowDroppedMessages,
        @JsonProperty("currentTokens") int currentTokens,
        @JsonProperty("totalTokens") int totalTokens,
        @JsonProperty("degraded") boolean degraded) {

    @JsonCreator
    public ContextAssemblyReport(@JsonProperty("sessionKey") String sessionKey,
                                 @JsonProperty("turnId") long turnId,
                                 @JsonProperty("at") Instant at,
                                 @JsonProperty("totalBudget") int totalBudget,
                                 @JsonProperty("systemTokens") int systemTokens,
                                 @JsonProperty("summaryTokens") int summaryTokens,
                                 @JsonProperty("summaryLines") int summaryLines,
                                 @JsonProperty("summaryDroppedLines") int summaryDroppedLines,
                                 @JsonProperty("cardCount") int cardCount,
                                 @JsonProperty("cardTokens") int cardTokens,
                                 @JsonProperty("droppedCardTurnIds") List<Long> droppedCardTurnIds,
                                 @JsonProperty("windowMessages") int windowMessages,
                                 @JsonProperty("windowTokens") int windowTokens,
                                 @JsonProperty("windowDroppedMessages") int windowDroppedMessages,
                                 @JsonProperty("currentTokens") int currentTokens,
                                 @JsonProperty("totalTokens") int totalTokens,
                                 @JsonProperty("degraded") boolean degraded) {
        this.sessionKey = sessionKey;
        this.turnId = turnId;
        this.at = at;
        this.totalBudget = totalBudget;
        this.systemTokens = systemTokens;
        this.summaryTokens = summaryTokens;
        this.summaryLines = summaryLines;
        this.summaryDroppedLines = summaryDroppedLines;
        this.cardCount = cardCount;
        this.cardTokens = cardTokens;
        this.droppedCardTurnIds = droppedCardTurnIds == null ? List.of() : List.copyOf(droppedCardTurnIds);
        this.windowMessages = windowMessages;
        this.windowTokens = windowTokens;
        this.windowDroppedMessages = windowDroppedMessages;
        this.currentTokens = currentTokens;
        this.totalTokens = totalTokens;
        this.degraded = degraded;
    }

    /** 装配是否把相关内容挤掉了（卡片被砍或窗口整轮丢）——"它为什么忘了"的第一嫌疑 */
    public boolean lostRecall() {
        return windowDroppedMessages > 0 || !droppedCardTurnIds.isEmpty();
    }
}
