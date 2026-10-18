package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.FlowEvent;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.MediaRef;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.model.MessageContent;
import com.zzkingcc.domainplane.common.util.TextClip;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮记录的抽取助手：内容块 → 媒体引用、图状态消息切片 → 流程事件、摘要生成。
 *
 * <p>全部是纯函数（无 IO、无状态），放在这里而不是编排层内部是为了可单测：
 * 抽取规则是归档正确性的一部分，值得有测试盯着。</p>
 *
 * @author zzkingcc
 */
public final class TurnRecorder {

    /**
     * 单个 TOOL_RESULT 的存档上限（字符）。
     *
     * <p>防的是"一次工具返回把整条轮记录撑爆"，归档不是日志系统的替身。
     * 超限存截断版并置 {@code flags.truncated}。取值引自
     * {@link ConversationArchive#MAX_ELEMENT_CHARS}：落库侧用同一个数。</p>
     */
    public static final int TOOL_RESULT_MAX_CHARS = ConversationArchive.MAX_ELEMENT_CHARS;

    private TurnRecorder() {
    }

    /**
     * 内容块 → 媒体引用列表。<b>只搬非文本块，且绝不搬字节。</b>
     *
     * <p>base64 块只记"类型 + 约多少字节"：归档无限增长，把 base64 图/音/视频
     * 原样再复制一份进去，量级上是事故（方案 §4.4 / §10-R4）。</p>
     *
     * <p>等价于 {@code mediaRefsOf(contents, List.of())}：没有附件名，URL 媒体、
     * 或没装配附件区的部署都走这一条。</p>
     */
    public static List<MediaRef> mediaRefsOf(List<MessageContent> contents) {
        return mediaRefsOf(contents, List.of());
    }

    /**
     * 内容块 → 媒体引用列表，并把附件区的引用名带上（M4-R4）。
     *
     * <p>{@code mediaAttachmentIds} 与内容块里的<b>媒体块</b>按出现顺序一一对应：
     * 图片、音频、视频都占一个号（由 {@code storeAttachments} 以同一顺序产出，
     * 没暂存成功的那一块占一个 {@code null}）。三种媒体的引用名都带上：
     * 归档于是从"曾有过一段音频"升级为"可按名定位到那份字节"，
     * 这是音视频不再"发完就没了"的<b>归档侧</b>一半。</p>
     *
     * <p>推进下标的口径是"每一块媒体"，而不是"每一张图"：只对图片取号的话，
     * 将来加一种媒体就要在产出侧与这里各改一次对号规则，错位的表现是
     * <b>引用名张冠李戴</b>，图片 A 的名字记到了音频 B 上，全程不报错。</p>
     *
     * @param contents           本轮原始内容块（含媒体；<b>不是</b>剥掉图片后的那一份）
     * @param mediaAttachmentIds 附件名，按媒体块顺序、失败处为 {@code null}；可为 {@code null}（视为无）
     */
    public static List<MediaRef> mediaRefsOf(List<MessageContent> contents,
                                             List<String> mediaAttachmentIds) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<String> ids = mediaAttachmentIds == null ? List.of() : mediaAttachmentIds;
        List<MediaRef> out = new ArrayList<>();
        int index = 0;
        for (MessageContent block : contents) {
            if (block.type() == null || !block.type().isMedia()) {
                continue;
            }
            // kind 词表只有一个来源（kind.wire() 与 MediaRef.kind 同义），
            // 不在这里再写一遍三元判断：两处各写一份必然分叉
            String kind = Kind.of(block.type()).wire();
            String attachmentId = index < ids.size() ? ids.get(index) : null;
            index++;
            String mime = block.mime();
            if (block.carriesBytes()) {
                // base64 长度 → 字节数的粗算（4 字符 3 字节，含 padding 误差，只为给个量级）
                long bytes = block.base64().length() * 3L / 4L;
                out.add(new MediaRef(kind, mime,
                        "base64(" + (mime == null ? "?" : mime) + ", 约 " + bytes + " 字节)",
                        attachmentId == null
                                ? "base64 内联数据，字节未随归档保存"
                                : "字节存附件区，可凭引用名取回（不落归档）",
                        attachmentId));
            } else if (block.url() != null && !block.url().isBlank()) {
                out.add(new MediaRef(kind, mime, block.url().trim(), "URL 引用", attachmentId));
            }
            // 两手空的媒体块：入口校验已拦，这里静默跳过（双保险，不重复报错）
        }
        return List.copyOf(out);
    }

    /**
     * 从图状态消息里抽取本轮流程。
     *
     * @param messages         图状态的完整消息列表
     * @param fromExclusive    本轮提问之后的第一条消息下标（切片起点，不含提问自身）
     * @param finalAnswerIndex 最终回答的下标；它单列 {@link TurnRecord#answer()}，不在 flow 里重复
     * @return 流程事件与"是否有截断"
     */
    public static FlowSlice extractFlow(List<ChatMessage> messages, int fromExclusive, int finalAnswerIndex) {
        if (messages == null || fromExclusive < 0 || fromExclusive > messages.size()) {
            return new FlowSlice(List.of(), false);
        }
        List<FlowEvent> events = new ArrayList<>();
        // 待配对的工具调用：ToolExecutionResultMessage 只带调用 id 不带工具名，
        // 按"最近的 AiMessage 工具请求依序出现"配对（图状态里结果必跟在请求之后）
        List<ToolExecutionRequest> pendingCalls = new ArrayList<>();
        boolean truncated = false;
        for (int i = fromExclusive; i < messages.size(); i++) {
            if (i == finalAnswerIndex) {
                continue;
            }
            ChatMessage m = messages.get(i);
            if (m instanceof AiMessage ai) {
                String text = ai.text();
                if (text != null && !text.isBlank()) {
                    events.add(FlowEvent.of(FlowEventType.ASSISTANT_DELTA, text));
                }
                if (ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        events.add(FlowEvent.ofTool(FlowEventType.TOOL_CALL, req.name(), req.arguments()));
                        pendingCalls.add(req);
                    }
                }
            } else if (m instanceof ToolExecutionResultMessage tr) {
                String toolName = pendingCalls.isEmpty() ? null : pendingCalls.remove(0).name();
                String text = tr.text();
                if (text != null && text.length() > TOOL_RESULT_MAX_CHARS) {
                    text = clip(text, TOOL_RESULT_MAX_CHARS);
                    truncated = true;
                }
                events.add(FlowEvent.ofTool(FlowEventType.TOOL_RESULT, toolName, text));
            }
            // SystemMessage / UserMessage：轮开始前的上下文，不属于本轮流程（提问由 user 字段承载）
        }
        return new FlowSlice(List.copyOf(events), truncated);
    }

    /**
     * 一句话摘要：召回卡片与倒排索引用它（M3 起进索引）。
     *
     * <p>M1 是截断式拼装（无 LLM 调用）：问 60 字 + 答 140 字。
     * M2 的滚动摘要、M3 的倒排都消费这个字段，将来可升级成模型生成。</p>
     */
    public static String digestOf(String userText, String answer) {
        String q = clip(userText, 60);
        String a = clip(answer, 140);
        return "问：" + (q.isBlank() ? "（无文本）" : q)
                + " ｜ 答：" + (a.isBlank() ? "（无回答）" : a);
    }

    /**
     * 截断到指定字符数：换行压成空格（摘要/卡片必须单行），超长加省略号。
     *
     * <p>实现落在 {@link TextClip}：基础设施层的归档实现也要用同一口径截断，
     * 而它看不到运行层。</p>
     */
    public static String clip(String text, int max) {
        return TextClip.clip(text, max);
    }

    /** 抽取结果：流程事件 + 是否有元素被截断 */
    public record FlowSlice(List<FlowEvent> events, boolean truncated) {
    }
}
