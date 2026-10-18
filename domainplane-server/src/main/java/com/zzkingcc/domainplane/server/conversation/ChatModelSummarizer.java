package com.zzkingcc.domainplane.server.conversation;

import com.zzkingcc.domainplane.runtime.conversation.RollingSummaryService.Summarizer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用非流式 {@link ChatModel} 生成滚动摘要（方案 §5.4）。
 *
 * <p>三个"刻意"：</p>
 * <ul>
 *   <li><b>非流式</b>：摘要没有"边生成边展示"的消费方，流式只会多一条回调链路；</li>
 *   <li><b>无工具</b>：素材已经在提示里给全了，不给模型任何工具，也不该给，
 *       否则摘要调用可能反过来去查库、跑出一串副作用；</li>
 *   <li><b>输出上限</b>：摘要段的预算本身只有 ~2000 token，不设上限就可能生成一篇长文
 *       把预算吃掉。上限由装配侧配置（{@code domainplane.memory.summary.max-output-tokens}）。</li>
 * </ul>
 *
 * <p>实现放在服务端而不是内核：内核只声明"给一段旧摘要和一段新素材，还我新摘要"
 * 这个契约（{@link Summarizer}），用哪个模型、走什么协议是部署决策，
 * 与 {@code ModelResolver}、{@code RedisMediaAttachmentStore} 同一条分工线。</p>
 *
 * @author zzkingcc
 */
public class ChatModelSummarizer implements Summarizer {

    private static final Logger log = LoggerFactory.getLogger(ChatModelSummarizer.class);

    /**
     * 摘要指令。
     *
     * <p>要求"只压缩不创作"与"保留可检索的硬信息"（编号、时间、结论、关键数据）：
     * 摘要是给下一轮模型当背景用的，掺进原文没有的东西比漏掉细节更危险，
     * 模型会把编出来的内容当成真实发生过的历史。</p>
     */
    private static final String INSTRUCTION = """
            你是会话摘要器。把下面给出的对话片段压缩成中文要点，供后续轮次作为背景参考。
            要求：
            1. 只压缩、不补充：不得加入片段里没有出现的事实；
            2. 保留硬信息：编号、名称、时间、金额、结论、以及工具返回的关键数据；
            3. 合并重复内容，去掉寒暄与过程性废话；
            4. 直接输出摘要正文，不要开场白、不要分点编号之外的格式、不要复述本条要求。""";

    private final ChatModel model;
    private final int maxOutputTokens;

    public ChatModelSummarizer(ChatModel model, int maxOutputTokens) {
        this.model = model;
        this.maxOutputTokens = Math.max(1, maxOutputTokens);
    }

    @Override
    public String summarize(String previousSummary, String material) {
        if (model == null) {
            return null;
        }
        String prompt = buildPrompt(previousSummary, material);
        try {
            ChatRequestParameters parameters = ChatRequestParameters.builder()
                    .maxOutputTokens(maxOutputTokens)
                    .build();
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .parameters(parameters)
                    .build());
            if (response == null || response.aiMessage() == null) {
                return null;
            }
            String text = response.aiMessage().text();
            // 空回答等同于没生成：交给调用方保留旧摘要与进度，别把流程推进到一个空摘要上
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            // 只告警不抛：摘要失败不影响对话，调用方（RollingSummaryService）会保留旧摘要与进度
            log.warn("[会话摘要] 模型调用失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 提示词 = 指令 + （已有摘要）+ （新增片段）。
     *
     * <p>做成"增量合并"而不是"把历史重写一遍"：只把新滑出窗口的轮次并进现有摘要，
     * 单次调用的输入量因此与"这一批新增"成正比，而不是与整个会话长度成正比。</p>
     */
    static String buildPrompt(String previousSummary, String material) {
        StringBuilder sb = new StringBuilder();
        sb.append(INSTRUCTION);
        boolean hasPrevious = previousSummary != null && !previousSummary.isBlank();
        sb.append("\n\n【已有摘要】\n").append(hasPrevious
                ? previousSummary.trim()
                : "（无，这是第一次生成）");
        sb.append("\n\n【新增对话片段】\n").append(material == null ? "" : material);
        sb.append("\n\n【输出】把已有摘要与新增片段合并成一份新的摘要正文：");
        return sb.toString();
    }
}
