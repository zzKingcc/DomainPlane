package com.zzkingcc.domainplane.runtime.usage;

import com.zzkingcc.domainplane.common.util.CjkWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 用量统计工具
 * @author zzkingcc
 */
public final class TokenUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecorder.class);

    private static final ThreadLocal<TokenStats> STATS = ThreadLocal.withInitial(TokenStats::new);

    private TokenUsageRecorder() {}

    /** 开始新一轮统计（清除上一轮残留） */
    public static void begin() {
        STATS.remove();
        STATS.set(new TokenStats());
    }

    /** 记录 LLM 输出 token（流式输出的每个 chunk） */
    public static void recordLlmOutputChunk(String chunk) {
        TokenStats s = STATS.get();
        s.llmOutputTokens += CjkWidth.estimateTokens(chunk);
    }

    /** 直接累加预计算的 LLM 输出 token 数（用于跨线程回调场景） */
    public static void addLlmOutputTokens(int tokens) {
        TokenStats s = STATS.get();
        s.llmOutputTokens += tokens;
    }

    /**
     * 估算一段文本的 token 数。
     *
     * <p>流式回调在<b>模型线程</b>上调用它累加计数，而 {@link #recordLlmOutputChunk(String)}
     * 记的是当前线程的 ThreadLocal —— 两者不是同一个统计桶，所以这里只提供估算、
     * 不落状态，累加仍由调用方拿到累计值自己 {@code addAndGet}。</p>
     *
     * <p>宽度判定只有 {@link CjkWidth} 一份实现，避免"两处各判一次"再次分叉。</p>
     */
    public static int estimateTokens(String text) {
        return CjkWidth.estimateTokens(text);
    }

    /** 记录 Tool 调用 token（输入参数 + 输出结果） */
    public static void recordToolCall(String toolName, String input, String output) {
        TokenStats s = STATS.get();
        int inputTokens = CjkWidth.estimateTokens(input);
        int outputTokens = CjkWidth.estimateTokens(output);
        s.toolInputTokens += inputTokens;
        s.toolOutputTokens += outputTokens;
        s.toolCallCount++;
        log.debug("[Token统计] Tool[{}] 输入≈{} tokens，输出≈{} tokens", toolName, inputTokens, outputTokens);
    }

    /** 打印统计并清除 ThreadLocal，防止内存泄漏 */
    public static void finishAndLog() {
        TokenStats s = STATS.get();
        int total = s.llmOutputTokens + s.toolInputTokens + s.toolOutputTokens;
        log.info("[Token统计] 本次请求 — LLM输出≈{} tokens | Tool调用{}次(输入≈{} + 输出≈{} tokens) | 合计≈{} tokens",
                s.llmOutputTokens, s.toolCallCount, s.toolInputTokens, s.toolOutputTokens, total);
        STATS.remove();
    }

    private static class TokenStats {
        int llmOutputTokens;
        int toolCallCount;
        int toolInputTokens;
        int toolOutputTokens;
    }
}
