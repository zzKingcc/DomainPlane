package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.model.MessageContent;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一附件通道的契约：除文字外，任何内容块都不能出现在主模型的消息里。
 *
 * <p>钉住的是 2026-11-06 那次故障的反面：音频以 input_audio 直发上游被静默丢弃，
 * 模型回答"没收到文件"，而归档与记忆里字节全在。现在字节只有一个落点（暂存区），
 * 模型只看到一份按类型分组的引用名清单。</p>
 */
@DisplayName("本轮入参：非文本统一走附件通道")
class TurnInputFactoryTest {

    @Test
    @DisplayName("图 / 音 / 视混合：字节进暂存区，主消息只有文字 + 分组清单")
    void allMediaGoThroughOneChannel() {
        StubStore store = new StubStore();
        TurnInputFactory factory = factory(store);
        List<MessageContent> contents = List.of(
                MessageContent.text("说了什么？"),
                MessageContent.imageBase64("RAW_IMAGE_BYTES", "image/png"),
                MessageContent.audioBase64("RAW_AUDIO_BYTES", "audio/mpeg"),
                MessageContent.videoBase64("RAW_VIDEO_BYTES", "video/mp4"));

        TurnInputFactory.StoredAttachments stored = factory.storeAttachments(contents);

        assertNull(stored.failure());
        assertEquals(3, stored.ids().size(), "三种媒体各占一个号");
        assertEquals(1, stored.idsByKind().get(Kind.IMAGE).size());
        assertEquals(1, stored.idsByKind().get(Kind.AUDIO).size());
        assertEquals(1, stored.idsByKind().get(Kind.VIDEO).size());
        assertEquals(3, store.putCalls.size(), "三类都真的存进了暂存区");

        String notice = factory.attachmentNotice(stored);
        assertNotNull(notice);
        UserMessage message = factory.buildUserMessage("说了什么？", notice);
        assertTrue(message.hasSingleText(), "主模型消息必须是纯文本，不能带任何媒体块");
        String text = message.singleText();
        assertTrue(text.contains("说了什么？"));
        for (String id : stored.ids()) {
            assertTrue(text.contains(id), "清单必须写出引用名：" + id);
        }
        assertTrue(text.contains("seeImage"), "图片要指到看图工具");
        assertTrue(text.contains("transcribeAudio"), "音频要指到转写工具");
        assertTrue(text.contains("没有取回内容的工具"), "视频不能骗模型说能取回");
        assertFalse(text.contains("RAW_IMAGE_BYTES"));
        assertFalse(text.contains("RAW_AUDIO_BYTES"));
        assertFalse(text.contains("RAW_VIDEO_BYTES"));
    }

    @Test
    @DisplayName("纯文本轮不产生清单，消息就是用户原话")
    void textOnly() {
        UserMessage message = factory(new StubStore()).buildUserMessage("你好", null);
        assertTrue(message.hasSingleText());
        assertEquals("你好", message.singleText());
    }

    @Test
    @DisplayName("URL 形态的图 / 视也走同一条通道")
    void urlMediaToo() {
        StubStore store = new StubStore();
        TurnInputFactory factory = factory(store);
        List<MessageContent> contents = List.of(
                MessageContent.imageUrl("https://example.com/a.png", null),
                MessageContent.videoUrl("https://example.com/v.mp4"));

        TurnInputFactory.StoredAttachments stored = factory.storeAttachments(contents);

        assertNull(stored.failure());
        assertEquals(2, stored.ids().size());
        assertTrue(store.putCalls.stream().anyMatch(c -> c.contains("https://example.com/a.png")));
        assertTrue(store.putCalls.stream().anyMatch(c -> c.contains("https://example.com/v.mp4")));
        String notice = factory.attachmentNotice(stored);
        assertTrue(factory.buildUserMessage("看看", notice).singleText().contains("没有取回内容的工具"));
    }

    @Test
    @DisplayName("任一暂存失败必须让本轮失败，且失败的媒体仍占号")
    void failureIsFatalAndKeepsSlot() {
        StubStore store = new StubStore();
        store.failKinds = Set.of(Kind.AUDIO);
        TurnInputFactory factory = factory(store);
        List<MessageContent> contents = List.of(
                MessageContent.imageBase64("AAA", "image/png"),
                MessageContent.audioBase64("BBB", "audio/wav"),
                MessageContent.videoBase64("CCC", "video/mp4"));

        TurnInputFactory.StoredAttachments stored = factory.storeAttachments(contents);

        assertNotNull(stored.failure(), "字节已不在消息里，失败必须上抛给本轮");
        assertTrue(stored.failure().contains("音频"));
        assertEquals(3, stored.ids().size(), "失败也占号，归档序号不能错位");
        assertNull(stored.ids().get(1));
        assertEquals(2, stored.idsByKind().values().stream().mapToInt(List::size).sum());
    }

    @Test
    @DisplayName("没有媒体时清单为空，不会给模型凭空造附件")
    void noMedia() {
        TurnInputFactory factory = factory(new StubStore());
        assertNull(factory.attachmentNotice(factory.storeAttachments(List.of())));
        assertEquals(0, factory.mediaCount(List.of(MessageContent.text("hi"))));
        assertEquals(1, factory.imageCount(List.of(MessageContent.imageBase64("AAA", "image/png"))));
    }

    @Test
    @DisplayName("入口校验：音频 URL 无字节可转写，直接拒；base64 形态放行")
    void validateContents() {
        TurnInputFactory factory = factory(new StubStore());
        String reason = factory.validateContents(List.of(MessageContent.audioUrl("https://example.com/a.mp3")));
        assertNotNull(reason);
        assertTrue(reason.contains("base64"));
        assertNull(factory.validateContents(List.of(
                MessageContent.audioBase64("QUJD", "audio/wav"),
                MessageContent.imageBase64("QUJD", "image/png"))));
        assertNull(factory.validateContents(null));
    }

    @Test
    @DisplayName("最小装配（无模型档案）不因视觉判定拦下这一轮")
    void minimalAssemblyIsNotBlocked() {
        assertTrue(factory(new StubStore()).visionAvailable("default"));
    }

    private static TurnInputFactory factory(StubStore store) {
        return new TurnInputFactory(null, store, domain -> null);
    }

    /** 只实现 put / get 的暂存区替身：记录每次存入的字节或地址，可按需让某类失败 */
    private static final class StubStore implements MediaAttachmentStore {

        private final Map<String, StoredAttachment> saved = new HashMap<>();
        private final List<String> putCalls = new ArrayList<>();
        private int seq;
        private Set<Kind> failKinds = Set.of();

        @Override
        public String put(Kind kind, String mimeType, String base64, String url) {
            putCalls.add(kind.wire() + ":" + (base64 != null ? base64 : url));
            if (failKinds.contains(kind)) {
                throw new IllegalStateException("redis down");
            }
            String id = kind.idPrefix() + (++seq);
            saved.put(id, new StoredAttachment(id, kind, mimeType, base64, url));
            return id;
        }

        @Override
        public Optional<StoredAttachment> get(String attachmentId) {
            return Optional.ofNullable(saved.get(attachmentId));
        }
    }
}
