package com.zzkingcc.domainplane.server.capability;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.StoredAttachment;
import com.zzkingcc.domainplane.server.model.DefaultModelResolver;
import com.zzkingcc.domainplane.server.model.ModelEndpointClient;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * transcribeAudio —— 把一段音频交给同域绑定的语音识别模型，转成文字。
 *
 * <p><b>只认引用名、不认地址</b>：与 {@code seeImage} 同一口径。音频字节只能来自
 * 平台自己的附件暂存区：接受一个模型给出的地址，就等于让服务端去取那个地址，
 * 而那个地址可能是被话术诱导现编的，能指向任意内网地址或超大文件。字节来源必须可信。</p>
 *
 * <p><b>只处理音频</b>：附件清单里还会有图片与视频的引用名，模型完全可能拿错。
 * 对非音频显式拒绝并说明原因：放过去就是把那段字节按错的模态推给上游，
 * 上游可能报错、也可能静默返回胡话。</p>
 *
 * <p>返回值是识别出的文字本身（不落暂存区）：ASR 的产物就是文本，
 * 主模型拿到就能直接用，没有必要再绕一圈引用名。</p>
 *
 * @author zzkingcc
 */
@Component
public class TranscribeTool extends CapabilityToolSupport {

    private static final String TOOL_NAME = "transcribeAudio";

    public TranscribeTool(DefaultModelResolver resolver,
                          ModelEndpointClient client,
                          MediaAttachmentStore store,
                          CapabilityCallExecutor executor) {
        super(resolver, client, store, executor);
    }

    @Tool(value = TOOL_NAME,
            desc = "把音频转写成文字（语音识别 / ASR）。"
                    + "当用户发来一段音频、并要求知道里面说了什么，"
                    + "或你需要音频中的具体内容才能回答时调用。"
                    + "参数 audio 只能填本轮附件清单里形如 aud_xxxxxxxx 的引用名（不接受音频地址）。"
                    + "返回音频里说出的文字内容。"
                    + "凡涉及音频中的具体内容而你又听不到时，必须先调用本工具取证，不要凭猜测作答。",
            effect = Tool.Effect.READ)
    public String transcribe(@ToolParam("本轮附件清单里的音频引用名（形如 aud_xxxxxxxx）；"
            + "不接受音频地址") String audio,
                             @ToolParam(value = "音频语言，如 zh / en；"
                                     + "留空由服务商自动判断", required = false) String language) {

        if (isBlank(audio)) {
            return "没有指定要识别的音频。请传入本轮附件清单里的引用名（形如 aud_xxxxxxxx）。";
        }
        String reference = audio.trim();
        if (isHttpUrl(reference)) {
            return "本工具只接受附件清单里的引用名（形如 aud_xxxxxxxx），不接受音频地址。"
                    + "请改用清单里的引用名；清单里没有用户提到的那段音频时，"
                    + "如实告知用户你无法听取它。";
        }

        /* 取一次附件，"是不是音频 / 有没有字节"两件事共用这一次读取。顺序刻意排在
           "解析识别模型"之前：拿一个图片引用名来问，回"本域没有识别模型"是错误诊断。 */
        StoredAttachment stored = store.get(reference).orElse(null);
        if (stored == null) {
            return "找不到附件 " + reference + "：它可能已过期（附件是临时暂存的），"
                    + "或这个引用不存在。请让用户重新发送。";
        }
        if (stored.kind() != Kind.AUDIO) {
            return "引用名 " + reference + " 是一段" + stored.kind().label() + "，本工具只能识别音频。"
                    + "请如实告知用户你无法听取这段" + stored.kind().label() + "，不要猜测其中的内容。";
        }
        if (!stored.hasBytes()) {
            /* 以 URL 形式暂存的音频：识别需要把字节上传给服务商，而平台不去回连用户给的地址
               （与 seeImage 拒绝地址是同一条理由）。如实说明，而不是发一个空请求出去。 */
            return "附件 " + reference + " 只登记了地址、没有音频数据，无法送去做语音识别。"
                    + "请让用户以上传文件的方式重新发送这段音频。";
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stored.base64());
        } catch (IllegalArgumentException e) {
            return "附件 " + reference + " 的数据已损坏，无法识别。请让用户重新发送。";
        }
        String trimmedLanguage = isBlank(language) ? null : language.trim();

        return run(TOOL_NAME, "识别语音", ModelProfile.EP_ASR, (profile, domain) -> {
            ModelEndpointClient.TranscriptionResult result = client.transcribe(profile,
                    new ModelEndpointClient.TranscriptionRequest(bytes, filenameOf(stored.mimeType()),
                            trimmedLanguage, null, null));
            String text = result.text();
            if (isBlank(text)) {
                return "语音识别模型没有返回文字内容（这段音频可能是静音或无法识别）。"
                        + "请如实告知用户。";
            }
            return "音频 " + reference + " 的内容是：" + text.trim();
        });
    }

    /** 内容类型 → 给服务商的文件名后缀（部分服务商按扩展名判格式，不认内容类型） */
    private static String filenameOf(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (mime.contains("mpeg") || mime.contains("mp3")) {
            return "audio.mp3";
        }
        if (mime.contains("wav")) {
            return "audio.wav";
        }
        if (mime.contains("ogg") || mime.contains("opus")) {
            return "audio.ogg";
        }
        if (mime.contains("flac")) {
            return "audio.flac";
        }
        if (mime.contains("mp4") || mime.contains("m4a")) {
            return "audio.m4a";
        }
        return "audio.wav";
    }

    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
