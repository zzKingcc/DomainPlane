package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一轮对话的 token 用量。
 *
 * <p><b>两个来源合流</b>（M4 起）：服务商上报过用量就用真实值，一次都没上报才整体回落平台估算，
 * 所以同一份归档里，有的轮次是实报、有的是估算，{@code promptTokens} 也只在实报时才有值。
 * 三个字段都可为 {@code null}：没有数据就不编。</p>
 *
 * <p>{@code totalTokens} 的口径随来源而变：实报时 = 服务商的 prompt + completion
 * （<b>不含</b>工具估算，工具用量已计入下一轮 prompt，重复相加会虚高）；
 * 估算时 = LLM 输出 + 工具输入 + 工具输出（没有 prompt 侧数据可用）。</p>
 *
 * @param promptTokens     提示侧用量；实报时才有值，估算口径下为 {@code null}
 * @param completionTokens 生成侧用量；实报优先，未上报时回落估算
 * @param totalTokens      合计；口径见类注释
 * @author zzkingcc
 */
public record TokenUsage(
        @JsonProperty("promptTokens") Integer promptTokens,
        @JsonProperty("completionTokens") Integer completionTokens,
        @JsonProperty("totalTokens") Integer totalTokens) {

    @JsonCreator
    public TokenUsage(@JsonProperty("promptTokens") Integer promptTokens,
                      @JsonProperty("completionTokens") Integer completionTokens,
                      @JsonProperty("totalTokens") Integer totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }
}
