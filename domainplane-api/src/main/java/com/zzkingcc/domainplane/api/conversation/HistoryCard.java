package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 「可能相关的历史轮次」卡片：老轮次进上下文的唯一形态。
 *
 * <p>设计口径（方案 §5.1）：卡片<b>不附全文</b>，只给轮号 / 时间 / 一句话摘要 / 涉及工具。
 * 模型要细节走 {@code read_history} 按轮号取原文：这是"选取召回"与"自主深挖"的分工：
 * 卡片塞全文就等于没选取，上下文又会线性胀回去。</p>
 *
 * @param turnId  轮号（与 {@link TurnRecord#turnId()} 同口径，模型拿它去调 read_history）
 * @param at      该轮开始时间（卡片上显示"3 天前"这类相对时间）
 * @param digest  一句话摘要（闭轮时生成，见 {@code TurnRecorder.digestOf}）
 * @param tools   该轮调用过的工具名（去重、按出现序）；空表示纯文本轮
 * @author zzkingcc
 */
public record HistoryCard(
        @JsonProperty("turnId") long turnId,
        @JsonProperty("at") Instant at,
        @JsonProperty("digest") String digest,
        @JsonProperty("tools") List<String> tools) {

    @JsonCreator
    public HistoryCard(@JsonProperty("turnId") long turnId,
                       @JsonProperty("at") Instant at,
                       @JsonProperty("digest") String digest,
                       @JsonProperty("tools") List<String> tools) {
        this.turnId = turnId;
        this.at = at;
        this.digest = digest;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
