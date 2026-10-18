package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.MediaRef;
import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读侧渲染：{@code read_history} 给模型看的那段文本。
 *
 * <p>M4-R4 锁的一条新契约：带附件名的轮次必须把引用名写出来并指向 {@code seeImage}，
 * 否则模型会以为图永远取不回而直接放弃，那正是附件长期化要消掉的失效模式。
 * 反过来，没有附件名的轮次仍要明确"不可取回"，免得模型对着一个取不到的名字空转。</p>
 *
 * <p>2026-10-16 起音视频也带引用名，"两类附件共用一句话"就不成立了：三条通道各不相同：
 * 图片走 {@code seeImage}，音频走 {@code transcribeAudio}（只拿得到文字），
 * 视频没有任何取回通道。措辞必须分开写，且谁也不能串到别人那句里。</p>
 *
 * @author zzkingcc
 */
@DisplayName("读侧渲染：附件可回取时的文本口径（M4-R4）")
class TurnViewTest {

    private static TurnRecord turnWithMedia(List<MediaRef> media) {
        return new TurnRecord(7, "default.sales|s-1",
                Instant.parse("2026-10-14T10:00:00Z"), Instant.parse("2026-10-14T10:00:05Z"),
                UserInput.of("这张图的金额对吗", media), List.of(), "对，88 元",
                new TokenUsage(1, 2, 3), new TurnFlags(false, false, false, null), "问图答价");
    }

    @Test
    @DisplayName("有附件名：写出引用名并指向 seeImage，且不再说「不可取回」")
    void rendersFetchableAttachment() {
        String text = TurnView.renderForModel(turnWithMedia(List.of(
                new MediaRef("image", "image/png", "base64(image/png, 约 3000 字节)",
                        "字节存附件区", "img_abc123"))));

        assertTrue(text.contains("img_abc123"), "引用名必须给到模型：" + text);
        assertTrue(text.contains("seeImage"), "要指路到取图工具：" + text);
        assertFalse(text.contains("不可取回"), "还能取回时不该说不可取回：" + text);
    }

    @Test
    @DisplayName("无附件名（URL 图 / 老档案）：仍明确「仅存档引用，不可取回」")
    void rendersNonFetchableAttachment() {
        String text = TurnView.renderForModel(turnWithMedia(List.of(
                new MediaRef("image", "image/png", "https://example.com/a.png", "URL 引用"))));

        assertTrue(text.contains("不可取回"), "没有引用名就得说清楚取不回来：" + text);
        assertFalse(text.contains("seeImage"), "取不回来就别指路：" + text);
    }

    @Test
    @DisplayName("音频：指到 transcribeAudio，且不能说「没有取回内容的工具」")
    void rendersAudioWithTranscribeChannel() {
        String text = TurnView.renderForModel(turnWithMedia(List.of(
                new MediaRef("audio", "audio/wav", "base64(audio/wav, 约 3000 字节)",
                        "字节存附件区", "aud_abc123"))));

        assertTrue(text.contains("aud_abc123"), "音频引用名要给到模型：" + text);
        assertTrue(text.contains("transcribeAudio"), "音频要指到转写工具：" + text);
        assertFalse(text.contains("没有取回内容的工具"), "音频有转写通道，不能再说取不回来：" + text);
        assertFalse(text.contains("seeImage"), "音频不能把模型引到 seeImage：" + text);
    }

    @Test
    @DisplayName("视频：写出名字，但明确没有取回内容的工具")
    void rendersVideoAttachmentWithoutChannel() {
        String text = TurnView.renderForModel(turnWithMedia(List.of(
                new MediaRef("video", "video/mp4", "base64(video/mp4, 约 9000 字节)",
                        "字节存附件区", "vid_def456"))));

        assertTrue(text.contains("vid_def456"), "视频引用名也要给到模型（归档定位用）：" + text);
        assertTrue(text.contains("没有取回内容的工具"), "得说清视频取不回来：" + text);
        assertFalse(text.contains("seeImage"), "视频不能把模型引到 seeImage：" + text);
        assertFalse(text.contains("transcribeAudio"), "视频没有转写通道：" + text);
    }

    @Test
    @DisplayName("图音混排：图片指向 seeImage、音频指向 transcribeAudio，两句话不能串")
    void rendersMixedMediaWithSeparateWording() {
        String text = TurnView.renderForModel(turnWithMedia(List.of(
                new MediaRef("image", "image/png", "base64(image/png, 约 3000 字节)",
                        "字节存附件区", "img_1"),
                new MediaRef("audio", "audio/wav", "base64(audio/wav, 约 3000 字节)",
                        "字节存附件区", "aud_1"))));

        assertTrue(text.contains("图片可凭引用名 img_1 用 seeImage 取回"), text);
        assertFalse(text.contains("aud_1 用 seeImage"), "音频名不能被写进 seeImage 那句：" + text);
        assertTrue(text.contains("音频可凭引用名 aud_1 用 transcribeAudio"), text);
        assertTrue(text.contains("aud_1"), "音频名仍要出现（它是归档里唯一的定位线索）：" + text);
    }

    @Test
    @DisplayName("无媒体：不出现「附件：」段")
    void noMediaNoAttachmentSection() {
        String text = TurnView.renderForModel(turnWithMedia(List.of()));
        assertFalse(text.contains("附件："));
    }
}
