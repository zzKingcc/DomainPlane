package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 一轮对话的完整档案：用户输入（含媒体引用）+ 全流程 + 最终回答 + 用量 + 标记 + 摘要。
 *
 * <p>设计口径（方案 §4.1）：</p>
 * <ul>
 *   <li><b>无封顶无淘汰</b>：一条会话的轮记录一直往上加，不存在"会话满了"；</li>
 *   <li><b>开放轮 / 闭轮</b>：{@code finishedAt == null} 即开放轮（轮开始就落档、
 *       回答慢慢跑）。崩溃恢复靠它识别"上一轮没闭上"；归档里不允许出现两条开放轮；</li>
 *   <li><b>只存引用不存字节</b>：{@code user.media} 是 {@link MediaRef} 引用，
 *       base64 像素/字节不进归档；</li>
 *   <li><b>单元素截断</b>：单个 TOOL_RESULT 超 8K 字符存截断版 +
 *       {@code flags.truncated = true}。归档不是日志系统的替身。</li>
 * </ul>
 *
 * @param turnId     会话内单调递增（1 起）；存储里 index = turnId - 1
 * @param sessionKey 域|sessionId（{@code SessionKeys.of} 的产物）
 * @param startedAt  轮开始时间
 * @param finishedAt 轮闭合时间；开放轮为 null
 * @param user       本轮用户输入
 * @param flow       按序的流程事件（THINKING / TOOL_CALL / TOOL_RESULT / ASSISTANT_DELTA / ERROR / APPROVAL）
 * @param answer     最终回答文本；开放轮 / 中止轮为 null
 * @param usage      用量：服务商上报优先，未上报回落平台估算（见 {@link TokenUsage}）
 * @param flags      标记位
 * @param digest     一句话摘要：召回卡片与倒排索引用它（M3 起进索引）
 * @author zzkingcc
 */
public record TurnRecord(
        @JsonProperty("turnId") long turnId,
        @JsonProperty("sessionKey") String sessionKey,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("finishedAt") Instant finishedAt,
        @JsonProperty("user") UserInput user,
        @JsonProperty("flow") List<FlowEvent> flow,
        @JsonProperty("answer") String answer,
        @JsonProperty("usage") TokenUsage usage,
        @JsonProperty("flags") TurnFlags flags,
        @JsonProperty("digest") String digest) {

    @JsonCreator
    public TurnRecord(@JsonProperty("turnId") long turnId,
                      @JsonProperty("sessionKey") String sessionKey,
                      @JsonProperty("startedAt") Instant startedAt,
                      @JsonProperty("finishedAt") Instant finishedAt,
                      @JsonProperty("user") UserInput user,
                      @JsonProperty("flow") List<FlowEvent> flow,
                      @JsonProperty("answer") String answer,
                      @JsonProperty("usage") TokenUsage usage,
                      @JsonProperty("flags") TurnFlags flags,
                      @JsonProperty("digest") String digest) {
        this.turnId = turnId;
        this.sessionKey = sessionKey;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.user = user;
        this.flow = flow == null ? List.of() : List.copyOf(flow);
        this.answer = answer;
        this.usage = usage;
        this.flags = flags;
        this.digest = digest;
    }

    /**
     * 开放轮 = 还没闭上（{@code finishedAt} 为空）。中止轮也是闭轮，只是 {@code flags.aborted()}。
     *
     * <p>{@code @JsonIgnore} 是必需项，不是洁癖：Jackson 会把 {@code isXxx()} 当成
     * {@code "xxx"} 属性的 getter，于是序列化时多写出一个 {@code "open"} 字段；
     * 而它不在构造器参数里，反序列化时会被判为未知字段并整条失败：
     * 结果就是<b>归档里每一条轮记录都读不出来</b>。派生值不该进 JSON。</p>
     */
    @JsonIgnore
    public boolean isOpen() {
        return finishedAt == null;
    }
}
