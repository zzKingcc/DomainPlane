package com.zzkingcc.domainplane.api.model;

/**
 * 用户上行内容块的类型。
 *
 * <p>一次用户输入不再限于纯文本：可以带图片、视频、音频。
 * 类型放在 {@code api} 模块，因为 SDK（{@code chat-client}）与服务端要共用同一份契约，
 * 各自拷一份必然分叉。</p>
 *
 * <p><b>这只是上行契约，不是发给上游 LLM 的部件</b>：所有非文本块在编排入口
 * 统一存进媒体暂存区换成引用名，主模型消息里只有用户文字 + 一份附件清单，
 * 由域上绑定的能力工具按引用名按需取回。因此新增一种媒体类型时，这里加枚举、
 * 暂存区加 {@code Kind} 即可，不必等适配层支持对应的 {@code input_*} 部件；
 * 上游静默丢部件也不会再造成"文件明明发了、模型说没收到"。</p>
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
