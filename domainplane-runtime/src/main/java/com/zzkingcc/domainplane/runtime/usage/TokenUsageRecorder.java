package com.zzkingcc.domainplane.runtime.usage;

import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.common.util.CjkWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 用量统计工具。
 *
 * <p>两条口径并存：平台<b>估算</b>（流式分片逐段按字符宽度估、工具参数与结果按文本估）
 * 与服务商<b>上报</b>（{@code ChatResponse.tokenUsage()}）。估算永远有，上报看服务商给不给：
 * 归档落档时优先上报考实，缺了才回落估算（见 {@code AgentOrchestrationService#usageOf}）。</p>
 *
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
     * 流式回调在模型线程上调用它累加计数，而 {@link #recordLlmOutputChunk(String)}
     * 记的是当前线程的 ThreadLocal：两者不是同一个统计桶，所以这里只提供估算、
     * 不落状态，累加仍由调用方拿到累计值自己 {@code addAndGet}。
     *
     * 宽度判定只有 {@link CjkWidth} 一份实现，避免"两处各判一次"再次分叉。
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

    /**
     * 记录一次<b>服务商上报的真实用量</b>（M4 回填）。
     *
     * <p>一轮里模型可能被调用多次（agent → tools → agent），每次的用量都要累加；
     * 服务商不报某一侧时传 {@code null}，不报的绝不拿估算值冒充真实值。</p>
     *
     * <p>多模态委派（{@code seeImage}）走的是另一条独立调用，它的用量不进这里：
     * 那是"工具内部的子调用"，与"本轮对话的模型用量"不是一回事（已知偏差，见方案 §6.1）。</p>
     *
     * @param promptTokens     提示侧真实用量，未上报为 {@code null}
     * @param completionTokens 生成侧真实用量，未上报为 {@code null}
     */
    public static void recordRealUsage(Integer promptTokens, Integer completionTokens) {
        if (promptTokens == null && completionTokens == null) {
            return;
        }
        TokenStats s = STATS.get();
        if (promptTokens != null && promptTokens > 0) {
            s.realPromptTokens += promptTokens;
        }
        if (completionTokens != null && completionTokens > 0) {
            s.realCompletionTokens += completionTokens;
        }
        s.realUsageCalls++;
    }

    /**
     * 取当前累计值（不清零、不影响后续统计）。
     *
     * <p>归档层要在本轮结束时把用量一并落档，而 {@link #finishAndLog()} 是"打完就清"的收尾动作：
     * 在它之后读就是空的，之前读又可能少算最后几段流式输出。所以这里单独给一个只读快照，
     * 由调用方（闭轮处）在 {@code finishAndLog} 之前取。</p>
     */
    public static Snapshot snapshot() {
        TokenStats s = STATS.get();
        return new Snapshot(s.llmOutputTokens, s.toolCallCount, s.toolInputTokens, s.toolOutputTokens,
                s.realPromptTokens, s.realCompletionTokens, s.realUsageCalls);
    }

    /**
     * 把当前快照折算成归档用的用量（{@code TurnRecord.usage}）。
     *
     * <p><b>有真用真、无真用估</b>：服务商上报过用量就报真实值
     * （{@code promptTokens} 那一栏也是这时才有值），一次都没上报才整体回落估算。</p>
     *
     * <p>两条口径的 {@code totalTokens} 不一样，是有意的：真实分支不叠加工具估算：
     * 工具的入参与结果会在<em>下一次</em>请求里成为 prompt 的一部分，服务商的 prompt 计数
     * 已经含了它们，再加一遍就是重复计数。估算分支没有 prompt 侧数据，只能照旧
     * "LLM 输出 + 工具输入 + 工具输出"地加。</p>
     */
    public static TokenUsage toUsage() {
        return toUsage(snapshot());
    }

    /** 折算逻辑的纯函数形态（便于直接测，不依赖 ThreadLocal） */
    public static TokenUsage toUsage(Snapshot s) {
        if (s.realUsageCalls() > 0) {
            Integer prompt = s.realPromptTokens() > 0 ? s.realPromptTokens() : null;
            Integer completion = s.realCompletionTokens() > 0 ? s.realCompletionTokens() : s.llmOutputTokens();
            int total = (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
            return new TokenUsage(prompt, completion, total);
        }
        return new TokenUsage(null, s.llmOutputTokens(),
                s.llmOutputTokens() + s.toolInputTokens() + s.toolOutputTokens());
    }

    /**
     * 用量快照。
     *
     * <p>{@code llmOutputTokens} / {@code tool*} 是平台估算口径；
     * {@code real*} 是服务商上报口径（{@code realUsageCalls == 0} 表示整轮都没有真实数据）。</p>
     */
    public record Snapshot(int llmOutputTokens, int toolCallCount, int toolInputTokens, int toolOutputTokens,
                           int realPromptTokens, int realCompletionTokens, int realUsageCalls) {
    }

    /** 打印统计并清除 ThreadLocal，防止内存泄漏 */
    public static void finishAndLog() {
        TokenStats s = STATS.get();
        int total = s.llmOutputTokens + s.toolInputTokens + s.toolOutputTokens;
        log.info("[Token统计] 本次请求 — LLM输出≈{} tokens | Tool调用{}次(输入≈{} + 输出≈{} tokens) | 合计≈{} tokens"
                        + " | 服务商上报 prompt={} completion={}（{} 次调用）",
                s.llmOutputTokens, s.toolCallCount, s.toolInputTokens, s.toolOutputTokens, total,
                s.realUsageCalls == 0 ? "—" : s.realPromptTokens,
                s.realUsageCalls == 0 ? "—" : s.realCompletionTokens,
                s.realUsageCalls);
        STATS.remove();
    }

    private static class TokenStats {
        int llmOutputTokens;
        int toolCallCount;
        int toolInputTokens;
        int toolOutputTokens;
        int realPromptTokens;
        int realCompletionTokens;
        int realUsageCalls;
    }
}
