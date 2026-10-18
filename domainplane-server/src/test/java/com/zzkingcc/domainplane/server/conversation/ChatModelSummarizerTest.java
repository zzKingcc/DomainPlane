package com.zzkingcc.domainplane.server.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-R1 摘要器：提示词形态与失败降级。
 *
 * <p>这里不测模型输出质量（那是模型的事），只锁两条：</p>
 * <ul>
 *   <li><b>增量合并的提示词</b>：已有摘要 + 新增片段一起给，且明确"只压缩不补充"，
 *       并注明"第一次生成"，否则模型会对着空摘要编出不存在的历史；</li>
 *   <li><b>失败只告警</b>：模型抛异常 / 返回空都返回 {@code null}，绝不外溢：
 *       摘要失败不该让一轮对话失败，调用方会保留旧摘要与进度。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M4-R1 摘要器：提示词与失败降级")
class ChatModelSummarizerTest {

    /** 只实现 chat(ChatRequest)，其余走接口默认方法 */
    private static ChatModel model(java.util.function.Function<ChatRequest, ChatResponse> fn) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return fn.apply(request);
            }
        };
    }

    // ==================== 提示词 ====================

    @Test
    @DisplayName("buildPrompt：第一次生成时标注「无」，并把新增片段原样带上")
    void promptMarksFirstGeneration() {
        String prompt = ChatModelSummarizer.buildPrompt(null, "第 1 轮：用户咨询退款");

        assertTrue(prompt.contains("（无，这是第一次生成）"), "首次要显式标注，防模型凭空编造历史");
        assertTrue(prompt.contains("第 1 轮：用户咨询退款"));
        assertTrue(prompt.contains("只压缩"), "必须要求只压缩不补充");
        assertTrue(prompt.contains("不得加入片段里没有出现的事实"));
    }

    @Test
    @DisplayName("buildPrompt：已有摘要时把它一并带上（增量合并，不重写历史）")
    void promptCarriesPreviousSummary() {
        String prompt = ChatModelSummarizer.buildPrompt("  第 1-3 轮：已受理  ", "第 4 轮：追问进度");

        assertTrue(prompt.contains("第 1-3 轮：已受理"), "已有摘要要去掉首尾空白后带上");
        assertFalse(prompt.contains("这是第一次生成"), "不是首次就不该标「无」");
        assertTrue(prompt.contains("第 4 轮：追问进度"));
        assertTrue(prompt.contains("合并"), "输出要求是「合并成一份新的摘要正文」");
    }

    @Test
    @DisplayName("buildPrompt：素材为空也构成合法提示词（不抛）")
    void promptWithNullMaterialIsSafe() {
        assertNotNull(ChatModelSummarizer.buildPrompt("旧摘要", null));
        assertNotNull(ChatModelSummarizer.buildPrompt(null, null));
    }

    // ==================== 调用与降级 ====================

    @Test
    @DisplayName("正常路径：把提示词交给模型，返回其回答文本")
    void summarizeReturnsModelText() {
        ChatRequest[] captured = new ChatRequest[1];
        ChatModelSummarizer summarizer = new ChatModelSummarizer(model(req -> {
            captured[0] = req;
            return ChatResponse.builder().aiMessage(AiMessage.from("第 1-4 轮：合并摘要")).build();
        }), 400);

        String out = summarizer.summarize("旧摘要", "第 4 轮：追问进度");

        assertEquals("第 1-4 轮：合并摘要", out);
        assertNotNull(captured[0]);
        assertEquals(400, captured[0].parameters().maxOutputTokens(), "输出上限必须透传到请求参数");
        assertTrue(captured[0].toolSpecifications() == null || captured[0].toolSpecifications().isEmpty(),
                "摘要调用不带任何工具");
    }

    @Test
    @DisplayName("模型抛异常：只告警并返回 null，绝不外溢")
    void modelFailureReturnsNull() {
        ChatModelSummarizer summarizer = new ChatModelSummarizer(
                model(req -> { throw new RuntimeException("模型网关 502"); }), 400);
        assertNull(summarizer.summarize("旧摘要", "片段"));
    }

    @Test
    @DisplayName("模型返回空文本 / 无响应：返回 null（调用方保留旧摘要与进度）")
    void blankOrEmptyResponseReturnsNull() {
        assertNull(new ChatModelSummarizer(model(req -> ChatResponse.builder()
                .aiMessage(AiMessage.from("")).build()), 400).summarize(null, "片段"),
                "空回答等同没生成");
        assertNull(new ChatModelSummarizer(model(req -> ChatResponse.builder()
                .aiMessage(AiMessage.from("   \n ")).build()), 400).summarize(null, "片段"),
                "纯空白等同没生成");
        assertNull(new ChatModelSummarizer(model(req -> null), 400).summarize(null, "片段"),
                "无响应返回 null");
    }

    @Test
    @DisplayName("未配到模型（null）：直接返回 null，不抛")
    void nullModelReturnsNull() {
        assertNull(new ChatModelSummarizer(null, 400).summarize("旧", "新"));
    }

    @Test
    @DisplayName("输出上限下限为 1（0 / 负数被夹住，不能生成无上限请求）")
    void maxOutputTokensIsClamped() {
        ChatRequest[] captured = new ChatRequest[1];
        ChatModelSummarizer summarizer = new ChatModelSummarizer(model(req -> {
            captured[0] = req;
            return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
        }), 0);

        summarizer.summarize(null, "片段");
        assertEquals(1, captured[0].parameters().maxOutputTokens());
    }
}
