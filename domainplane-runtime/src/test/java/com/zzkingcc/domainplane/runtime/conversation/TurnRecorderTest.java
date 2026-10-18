package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.FlowEvent;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.MediaRef;
import com.zzkingcc.domainplane.api.model.MessageContent;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1b 写侧抽取：图状态消息 → 轮记录。
 *
 * <p>这里锁的是归档的<b>内容口径</b>，三条边界错了就没法补救：</p>
 * <ul>
 *   <li><b>字节绝不进档</b>：base64 只留"类型 + 约多少字节"。归档无限增长，
 *       一张图的 base64 复制进去就是量级上的事故；</li>
 *   <li><b>最终回答不重复进 flow</b>：它单列 {@code answer}，进 flow 会把它算两遍；</li>
 *   <li><b>工具结果超限必须截断并置标记</b>：一次工具返回不能把整条轮记录撑爆。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M1b 轮记录抽取：媒体只留引用、回答不重复、结果超限截断")
class TurnRecorderTest {

    private static ToolExecutionRequest call(String name, String args) {
        return ToolExecutionRequest.builder().name(name).arguments(args).build();
    }

    /** 构建工具结果消息；{@code from(String, String)} 不存在同名重载，走 builder 明确三个字段 */
    private static ToolExecutionResultMessage result(String toolName, String text) {
        return ToolExecutionResultMessage.builder()
                .id("call-" + toolName).toolName(toolName).text(text).build();
    }

    // ==================== 媒体引用 ====================

    @Test
    @DisplayName("文本块不产生引用；URL 媒体记引用不记字节")
    void mediaRefsSkipTextAndKeepUrlOnly() {
        List<MediaRef> refs = TurnRecorder.mediaRefsOf(List.of(
                MessageContent.text("帮我看看这张图"),
                MessageContent.imageUrl("https://example.com/a.png", "high")));

        assertEquals(1, refs.size());
        assertEquals("image", refs.get(0).kind());
        assertEquals("https://example.com/a.png", refs.get(0).ref());
    }

    @Test
    @DisplayName("base64 媒体只留「约多少字节」，原始字节不进档")
    void mediaRefsNeverCarryBytes() {
        String base64 = "A".repeat(4000);
        List<MediaRef> refs = TurnRecorder.mediaRefsOf(List.of(
                MessageContent.imageBase64(base64, "image/png")));

        assertEquals(1, refs.size());
        MediaRef ref = refs.get(0);
        assertFalse(ref.ref().contains("AAAA"), "base64 本体绝不能落进归档：" + ref.ref());
        assertTrue(ref.ref().contains("约 3000 字节"), "应给出量级估算：" + ref.ref());
        assertTrue(ref.note().contains("未随归档保存"));
    }

    @Test
    @DisplayName("空 / null 内容块返回空列表，不抛")
    void mediaRefsHandleEmpty() {
        assertEquals(List.of(), TurnRecorder.mediaRefsOf(null));
        assertEquals(List.of(), TurnRecorder.mediaRefsOf(List.of()));
    }

    // ==================== 媒体引用：附件名（M4-R4） ====================

    @Test
    @DisplayName("M4：附件名按媒体块顺序对齐（图/音/视都占号），失败的那一块占号但无名字")
    void mediaRefsCarryAttachmentIdsInOrder() {
        List<MediaRef> refs = TurnRecorder.mediaRefsOf(List.of(
                        MessageContent.text("看看这些"),
                        MessageContent.imageBase64("AAAA", "image/png"),
                        MessageContent.audioUrl("https://example.com/a.wav"),
                        MessageContent.videoBase64("BBBB", "video/mp4"),
                        MessageContent.imageUrl("https://example.com/b.png", "low")),
                // 用 Arrays.asList 而不是 List.of：这里刻意含一个 null（失败的那一块占号）
                Arrays.asList("img_first", "aud_second", null, "img_third"));

        assertEquals(4, refs.size());
        assertEquals("img_first", refs.get(0).attachmentId(), "第一张图取第一个号");
        assertEquals("aud_second", refs.get(1).attachmentId(), "音频也占号，它的名字不再被丢掉");
        assertEquals("audio", refs.get(1).kind());
        assertNull(refs.get(2).attachmentId(), "该块没存进去 → 占号但无名字");
        assertEquals("img_third", refs.get(3).attachmentId(),
                "后面那块不能被失败的那一块顶掉（错位 = 引用名张冠李戴）");
        assertFalse(refs.get(0).ref().contains("AAAA"), "带上附件名也不能把字节带进档");
        assertTrue(refs.get(0).note().contains("可凭引用名取回"), refs.get(0).note());
        assertTrue(refs.get(2).note().contains("未随归档保存"),
                "没暂存成功的块仍是旧口径：" + refs.get(2).note());
    }

    @Test
    @DisplayName("M4：附件名少于媒体块 / 为 null 都不越界，缺号的块 attachmentId 为空")
    void mediaRefsTolerateMissingAttachmentIds() {
        List<MessageContent> contents = List.of(
                MessageContent.imageBase64("AAAA", "image/png"),
                MessageContent.audioBase64("CCCC", "audio/wav"));

        List<MediaRef> one = TurnRecorder.mediaRefsOf(contents, List.of("img_only"));
        assertEquals("img_only", one.get(0).attachmentId());
        assertNull(one.get(1).attachmentId(), "号用完了就是没有，不能张冠李戴");

        List<MediaRef> none = TurnRecorder.mediaRefsOf(contents, null);
        assertNull(none.get(0).attachmentId());
        assertTrue(none.get(0).note().contains("未随归档保存"), "无附件名时仍是旧口径");
    }

    @Test
    @DisplayName("归档 kind 词表与附件区的媒体类型同源（改一处不会两处分叉）")
    void kindVocabularyMatchesAttachmentStore() {
        List<MediaRef> refs = TurnRecorder.mediaRefsOf(List.of(
                MessageContent.imageBase64("A", "image/png"),
                MessageContent.audioBase64("B", "audio/wav"),
                MessageContent.videoBase64("C", "video/mp4")));

        assertEquals(
                List.of(Kind.IMAGE.wire(), Kind.AUDIO.wire(), Kind.VIDEO.wire()),
                refs.stream().map(MediaRef::kind).toList());
        assertEquals(List.of(true, false, false),
                refs.stream().map(MediaRef::isImage).toList(),
                "只有图片能指向 seeImage");
    }

    @Test
    @DisplayName("M4：单参重载等价于「没有附件名」，旧调用点行为不变")
    void singleArgOverloadHasNoAttachmentIds() {
        List<MediaRef> refs = TurnRecorder.mediaRefsOf(List.of(
                MessageContent.imageBase64("AAAA", "image/png")));
        assertNull(refs.get(0).attachmentId());
        assertFalse(refs.get(0).hasAttachment());
    }

    // ==================== 流程抽取 ====================

    @Test
    @DisplayName("工具调用与结果按序配对，最终回答不进 flow")
    void extractFlowPairsCallsAndSkipsFinalAnswer() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是助手"),
                UserMessage.from("查一下订单 123"),
                AiMessage.from(call("query_order", "{\"id\":123}")),
                result("query_order", "金额 88 元"),
                AiMessage.from("订单 123 金额 88 元"));

        // fromExclusive = 2（跳过 system 与 user），finalAnswerIndex = 4
        TurnRecorder.FlowSlice slice = TurnRecorder.extractFlow(messages, 2, 4);

        assertEquals(2, slice.events().size(), "回答单列 answer，不该再进 flow");
        assertEquals(FlowEventType.TOOL_CALL, slice.events().get(0).type());
        assertEquals("query_order", slice.events().get(0).toolName());
        assertEquals(FlowEventType.TOOL_RESULT, slice.events().get(1).type());
        assertEquals("query_order", slice.events().get(1).toolName(),
                "结果消息只带 id，工具名要靠配对找回");
        assertFalse(slice.truncated());
    }

    @Test
    @DisplayName("中间过程里的模型文字也留档（THINKING/ASSISTANT_DELTA 是现场的一部分）")
    void extractFlowKeepsIntermediateText() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("问"),
                AiMessage.from("我先查一下"),
                AiMessage.from(call("t", "{}")),
                result("t", "ok"),
                AiMessage.from("结论"));

        TurnRecorder.FlowSlice slice = TurnRecorder.extractFlow(messages, 1, 4);

        assertEquals(FlowEventType.ASSISTANT_DELTA, slice.events().get(0).type());
        assertEquals("我先查一下", slice.events().get(0).text());
        assertEquals(3, slice.events().size());
    }

    @Test
    @DisplayName("工具结果超限：截断 + 置 truncated，且截断发生在存档长度上")
    void extractFlowTruncatesHugeToolResult() {
        String huge = "x".repeat(TurnRecorder.TOOL_RESULT_MAX_CHARS + 500);
        List<ChatMessage> messages = List.of(
                UserMessage.from("问"),
                AiMessage.from(call("t", "{}")),
                result("t", huge),
                AiMessage.from("结论"));

        TurnRecorder.FlowSlice slice = TurnRecorder.extractFlow(messages, 1, 3);

        assertTrue(slice.truncated(), "超限必须置标记");
        FlowEvent result = slice.events().get(1);
        assertTrue(result.text().length() <= TurnRecorder.TOOL_RESULT_MAX_CHARS + 4,
                "存档文本不能无限长，实际 " + result.text().length());
        assertTrue(result.text().endsWith("…"), "截断要有省略标记");
    }

    @Test
    @DisplayName("结果多于请求（异常图状态）：工具名为空，不抛、不配对错人")
    void extractFlowToleratesUnpairedResult() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("问"),
                AiMessage.from(call("t1", "{}")),
                result("t1", "r1"),
                result("t2", "r2"),
                AiMessage.from("结论"));

        TurnRecorder.FlowSlice slice = TurnRecorder.extractFlow(messages, 1, 4);

        assertEquals("t1", slice.events().get(1).toolName());
        assertNull(slice.events().get(2).toolName(), "配不上就留空，不能张冠李戴");
    }

    @Test
    @DisplayName("非法切片下标返回空流程，不抛")
    void extractFlowHandlesBadRange() {
        assertEquals(List.of(), TurnRecorder.extractFlow(null, 0, 0).events());
        assertEquals(List.of(), TurnRecorder.extractFlow(List.of(), 0, 0).events());
        assertEquals(List.of(), TurnRecorder.extractFlow(List.of(UserMessage.from("问")), 99, 0).events());
    }

    // ==================== 摘要 ====================

    @Test
    @DisplayName("摘要：换行压成单行，问答分别截断")
    void digestIsSingleLineAndClipped() {
        String multiLine = "第一行\n第二行\n第三行";
        String digest = TurnRecorder.digestOf(multiLine, "答");

        assertFalse(digest.contains("\n"), "摘要必须单行（召回卡片按行渲染）");
        assertTrue(digest.startsWith("问：第一行 第二行 第三行"));
        assertTrue(digest.endsWith("｜ 答：答"));
    }

    @Test
    @DisplayName("摘要：空问 / 空答都有占位，不出现「问： ｜ 答：」这种空壳")
    void digestFillsPlaceholders() {
        assertTrue(TurnRecorder.digestOf(null, null).contains("（无文本）"));
        assertTrue(TurnRecorder.digestOf(null, null).contains("（无回答）"));
        assertTrue(TurnRecorder.digestOf("问", "").contains("（无回答）"));
    }

    @Test
    @DisplayName("摘要长度有上界：超长问答不会把索引条目撑爆")
    void digestIsBounded() {
        String longQ = "问".repeat(500);
        String longA = "答".repeat(500);
        String digest = TurnRecorder.digestOf(longQ, longA);

        assertTrue(digest.length() < 220, "摘要应当有界，实际 " + digest.length());
        assertTrue(digest.endsWith("…"));
    }

    @Test
    @DisplayName("clip：换行压空格、超长加省略号、null 与短文本原样")
    void clipBehavior() {
        assertEquals("", TurnRecorder.clip(null, 10), "null 归空串，摘要侧的 isBlank() 才有意义");
        assertEquals("短", TurnRecorder.clip("短", 10));
        assertEquals("a b", TurnRecorder.clip("a\nb", 10));
        assertTrue(TurnRecorder.clip("1234567890ABCD", 10).startsWith("1234567890"));
        assertTrue(TurnRecorder.clip("1234567890ABCD", 10).endsWith("…"));
    }

    @Test
    @DisplayName("一轮完整流程：事件顺序与消息顺序一致")
    void flowPreservesChronologicalOrder() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("问"),
                AiMessage.from(call("a", "{}")),
                result("a", "ra"),
                AiMessage.from(call("b", "{}")),
                result("b", "rb"),
                AiMessage.from("结论")));

        List<FlowEvent> events = TurnRecorder.extractFlow(messages, 1, 5).events();

        assertEquals(List.of(FlowEventType.TOOL_CALL, FlowEventType.TOOL_RESULT,
                        FlowEventType.TOOL_CALL, FlowEventType.TOOL_RESULT),
                events.stream().map(FlowEvent::type).toList());
        assertEquals(List.of("a", "a", "b", "b"),
                events.stream().map(FlowEvent::toolName).toList());
    }
}
