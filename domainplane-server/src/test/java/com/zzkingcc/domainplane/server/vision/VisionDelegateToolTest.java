package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.StoredAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code seeImage} 的取回口径：<b>只处理图片、只认引用名</b>。
 *
 * <p>音视频也进附件区（归档要留一个可取的引用），于是它们的引用名同样会出现在附件清单里，
 * 模型完全可能拿一个音频名来调本工具。三条边界错了都不是"失败"，而是"发错了模态"或"给了错诊断"：</p>
 * <ul>
 *   <li>非图片必须<b>显式拒绝</b>，而且这句拒绝要排在"解析视觉模型"之前：
 *       拿音频名来问却回"本域没有视觉模型"，是错误诊断：真正的问题与域上绑没绑模型无关，
 *       模型会照着那句话去别处找路；</li>
 *   <li>只认引用名，不认图片地址：地址是模型（可能被话术诱导）现编的，
 *       服务端按它去取图就能打到任意内网地址；</li>
 *   <li>引用名取不到时如实回喂"请让用户重发"，而不是抛成一句笼统的工具执行失败。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("seeImage：只处理图片、只认引用名")
class VisionDelegateToolTest {

    /** 只读附件区：`put` 在本用例里用不到，调用即说明测错了方向 */
    private static MediaAttachmentStore storeOf(StoredAttachment... items) {
        Map<String, StoredAttachment> map = new LinkedHashMap<>();
        for (StoredAttachment item : items) {
            map.put(item.id(), item);
        }
        return new MediaAttachmentStore() {
            @Override
            public String put(Kind kind, String mimeType, String base64, String url) {
                throw new UnsupportedOperationException("本用例不暂存媒体");
            }

            @Override
            public Optional<StoredAttachment> get(String attachmentId) {
                return Optional.ofNullable(map.get(attachmentId));
            }
        };
    }

    /** 不带模型解析器 = 未装配模型档案；此时"域里有没有视觉模型"一律是"没有" */
    private static VisionDelegateTool toolWith(MediaAttachmentStore store) {
        return new VisionDelegateTool(store, null, null);
    }

    @Test
    @DisplayName("音频引用名：明确拒绝，且不能诊断成「本域没有视觉模型」")
    void audioReferenceIsRejectedAsNonImage() {
        VisionDelegateTool tool = toolWith(storeOf(
                new StoredAttachment("aud_1", Kind.AUDIO, "audio/wav", "BBBB", null)));

        String answer = tool.see("aud_1", "这段音频说了什么");

        assertTrue(answer.contains("音频"), "要说清它是什么：" + answer);
        assertTrue(answer.contains("只能查看图片"), "要说清本工具的范围：" + answer);
        assertFalse(answer.contains("没有配置可用的视觉模型"),
                "非图片的拒绝必须排在解析视觉模型之前，否则是错误诊断：" + answer);
    }

    @Test
    @DisplayName("视频引用名：同样拒绝")
    void videoReferenceIsRejectedAsNonImage() {
        VisionDelegateTool tool = toolWith(storeOf(
                new StoredAttachment("vid_1", Kind.VIDEO, null, null, "https://example.com/v.mp4")));

        String answer = tool.see("vid_1", "视频里发生了什么");

        assertTrue(answer.contains("视频"), answer);
        assertTrue(answer.contains("只能查看图片"), answer);
        assertFalse(answer.contains("没有配置可用的视觉模型"), answer);
    }

    @Test
    @DisplayName("引用名取不到：回喂「可能已过期，请重发」，而不是抛异常")
    void missingReferenceAsksUserToResend() {
        String answer = toolWith(storeOf()).see("img_gone0000", "金额多少");

        assertTrue(answer.contains("img_gone0000"), "要让模型知道是哪个名字取不到：" + answer);
        assertTrue(answer.contains("重新发送"), answer);
    }

    @Test
    @DisplayName("图片地址：单独给一句「本工具不接受地址」，不能落进「找不到附件」")
    void imageUrlIsRefusedExplicitly() {
        String answer = toolWith(storeOf()).see("https://example.com/a.png", "金额多少");

        assertTrue(answer.contains("不接受图片地址"), answer);
        assertFalse(answer.contains("找不到附件"),
                "回「找不到」模型不会意识到「传地址本身就不允许」，下一轮还会再传一次：" + answer);
    }

    @Test
    @DisplayName("空入参：分别提示缺引用名 / 缺问题")
    void blankArgumentsAreReportedSeparately() {
        VisionDelegateTool tool = toolWith(storeOf());

        assertTrue(tool.see("  ", "问什么").contains("没有指定要查看的图片"));
        assertTrue(tool.see("img_1", " ").contains("没有说明要从图片中获取什么"));
    }

    @Test
    @DisplayName("正常图片但域里没配视觉模型：如实说「本域没有视觉模型」，图片这条路径不受影响")
    void imageWithoutVisionModelSaysSo() {
        VisionDelegateTool tool = toolWith(storeOf(
                new StoredAttachment("img_1", Kind.IMAGE, "image/png", "AAAA", null)));

        String answer = tool.see("img_1", "金额多少");

        assertTrue(answer.contains("没有配置可用的视觉模型"), answer);
        assertFalse(answer.contains("本工具只能查看图片"), "这是图片，别走非图片那套文案：" + answer);
    }
}
