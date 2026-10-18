package com.zzkingcc.domainplane.api.model;

/**
 * 用户上行内容块的类型。
 *
 * <p>一次用户输入不再限于纯文本：可以带图片、视频、音频。
 * 类型放在 {@code api} 模块，因为 SDK（{@code chat-client}）与服务端要共用同一份契约，
 * 各自拷一份必然分叉。</p>
 *
 * <p>音频 / 视频为什么从"留位不实现"转为实现：langchain4j 1.18.1 的 OpenAI 适配层
 * 已原生把 {@code AudioContent} 映射为 {@code input_audio} 部件、
 * {@code VideoContent} 映射为 {@code video_url} 部件（base64 自动拼 data URL），
 * 平台侧只差契约放行。服务商是否真的收，由域上绑的模型决定：不支持的服务商
 * 会在请求阶段返回明确错误，由调用方处置。</p>
 *
 * @author zzkingcc
 */
public enum ContentType {

    /** 纯文本块 */
    TEXT,

    /** 图片，公网可达 URL */
    IMAGE_URL,

    /** 图片，base64 + mime */
    IMAGE_BASE64,

    /** 音频，公网可达 URL */
    AUDIO_URL,

    /** 音频，base64 + mime */
    AUDIO_BASE64,

    /** 视频，公网可达 URL */
    VIDEO_URL,

    /** 视频，base64 + mime */
    VIDEO_BASE64;

    /** 是否音频类 */
    public boolean isAudio() {
        return this == AUDIO_URL || this == AUDIO_BASE64;
    }

    /** 是否图片类 */
    public boolean isImage() {
        return this == IMAGE_URL || this == IMAGE_BASE64;
    }

    /** 是否视频类 */
    public boolean isVideo() {
        return this == VIDEO_URL || this == VIDEO_BASE64;
    }

    /** 是否媒体类（图 / 音 / 视）—— 容量判定与落库区别对待的判据 */
    public boolean isMedia() {
        return isImage() || isAudio() || isVideo();
    }
}
