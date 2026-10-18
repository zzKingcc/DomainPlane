package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 轮记录的标记位。
 *
 * @param truncated        本轮有工具结果被截断存档（单元素超 8K 字符）
 * @param aborted          本轮被停止 / 异常 / 崩溃恢复中止，现场保留，但不算"可引用的完成轮"
 * @param indexed          本轮已写入倒排索引（M3；M1 恒为 false）
 * @param approvalOutcome  审批结果：REJECTED = 用户未确认审批并开了新对话；其余为空
 * @author zzkingcc
 */
public record TurnFlags(
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("aborted") boolean aborted,
        @JsonProperty("indexed") boolean indexed,
        @JsonProperty("approvalOutcome") String approvalOutcome) {

    @JsonCreator
    public TurnFlags(@JsonProperty("truncated") boolean truncated,
                     @JsonProperty("aborted") boolean aborted,
                     @JsonProperty("indexed") boolean indexed,
                     @JsonProperty("approvalOutcome") String approvalOutcome) {
        this.truncated = truncated;
        this.aborted = aborted;
        this.indexed = indexed;
        this.approvalOutcome = approvalOutcome;
    }

    /** 开放轮（刚开、没闭）的标记 */
    public static TurnFlags open() {
        return new TurnFlags(false, false, false, null);
    }
}
