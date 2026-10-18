package com.zzkingcc.domainplane.server.capability;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.server.model.DefaultModelResolver;
import com.zzkingcc.domainplane.server.model.ModelEndpointClient;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Locale;

/**
 * synthesizeSpeech —— 把一段文字交给同域绑定的语音合成模型，产出一段音频。
 *
 * <p>与 {@code generateImage} 同一套形态：域读本轮上下文，模型沿域链解析 {@code tts} 族，
 * 产出以 {@code aud_xxxxxxxx} 存进暂存区、返回值只有引用名（音频字节绝不进返回值：
 * 它会顺着工具事件与会话记忆落库）。</p>
 *
 * <p><b>为什么要有这个工具</b>：主模型是文字模型，它"说得出话"但"发不出声"。
 * 用户在对话里要一段语音（朗读、播报、配音），平台得有一条真正产出音频的路，
 * 而不是让模型假装产出了。</p>
 *
 * @author zzkingcc
 */
@Component
public class SpeechTool extends CapabilityToolSupport {

    private static final String TOOL_NAME = "synthesizeSpeech";

    public SpeechTool(DefaultModelResolver resolver,
                      ModelEndpointClient client,
                      MediaAttachmentStore store,
                      CapabilityCallExecutor executor) {
        super(resolver, client, store, executor);
    }

    @Tool(value = TOOL_NAME,
            desc = "把一段文字合成为语音（文字转语音 / TTS），产出一段音频。"
                    + "当用户要求把文字念出来、生成语音 / 配音 / 朗读音频时调用。"
                    + "参数 text 填要念出来的文字内容，直接写最终要听到的文本，不要写'请朗读以下内容'之类的指令。"
                    + "结果会以音频编号返回，你可以在回答里提到它。"
                    + "本工具依赖当前域已绑定的语音合成模型；没有时会明确告知，此时不要假装已经生成了音频。",
            effect = Tool.Effect.WRITE)
    public String synthesize(@ToolParam("要合成为语音的文本内容（直接给最终要听到的字句）") String text,
                             @ToolParam(value = "音色名称（如 alloy / nova，或服务商自定义音色）；"
                                     + "不确定就留空，由服务商默认", required = false) String voice,
                             @ToolParam(value = "音频格式，如 mp3 / wav / opus；"
                                     + "留空由服务商默认", required = false) String format) {

        if (isBlank(text)) {
            return "没有要合成的文字。请把要念出来的文本写在 text 参数里。";
        }
        String trimmedVoice = isBlank(voice) ? null : voice.trim();
        String trimmedFormat = isBlank(format) ? null : format.trim().toLowerCase(Locale.ROOT);

        return run(TOOL_NAME, "合成语音", ModelProfile.EP_TTS, (profile, domain) -> {
            ModelEndpointClient.SpeechResult result = client.textToSpeech(profile,
                    new ModelEndpointClient.SpeechRequest(text.trim(), trimmedVoice, null, trimmedFormat));
            byte[] audio = result.audio();
            if (audio == null || audio.length == 0) {
                return "语音合成模型没有返回音频内容。请如实告知用户这一步没产出结果。";
            }
            String mime = mimeOf(trimmedFormat);
            String reference = store.put(Kind.AUDIO, mime,
                    Base64.getEncoder().encodeToString(audio), null);
            return "已合成语音，音频编号：" + reference
                    + "（约 " + (audio.length / 1024) + " KB）。"
                    + "编号就是这段音频本身，可以直接告诉用户。";
        });
    }

    /** 请求的格式 → 内容类型；未指定时按 OpenAI 兼容端点的默认 mp3 记 */
    private static String mimeOf(String format) {
        if (format == null) {
            return "audio/mpeg";
        }
        return switch (format) {
            case "wav" -> "audio/wav";
            case "opus", "ogg" -> "audio/ogg";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "pcm" -> "audio/pcm";
            default -> "audio/mpeg";
        };
    }
}
