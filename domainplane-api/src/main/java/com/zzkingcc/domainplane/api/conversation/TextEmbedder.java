package com.zzkingcc.domainplane.api.conversation;

/**
 * 文本 → 向量的嵌入入口（F04-b：历史记忆语义化）。
 *
 * <p>内核定契约、服务端定模型：运行层与基础设施层只认这个函数，
 * 具体用哪个向量模型由服务端装配时注入（与 {@code RollingSummaryService.Summarizer} 同一套路）。
 * 这样"没有配嵌入模型"就只是"注入一个抛异常的实现"或干脆不注入，
 * 基础设施层不必知道模型的存在。</p>
 *
 * @author zzkingcc
 */
@FunctionalInterface
public interface TextEmbedder {

    /**
     * @param text 待嵌入文本（归档侧传的是一轮的 digest，不是全文）
     * @return 向量；{@code null} / 空数组表示本次嵌入不可用，调用方按降级处理
     * @throws Exception 允许抛出：嵌入是外部调用，是否兜底由调用方决定
     */
    float[] embed(String text) throws Exception;
}
