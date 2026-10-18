package com.zzkingcc.domainplane.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 一次用户上行的一个内容块。
 *
 * <p>与 {@code AgentRequest.message} 的关系：{@code message} 是旧字段，永久保留；
 * {@code contents} 是新字段，可空。两者并存而非替换：旧客户端只发 {@code message}，
 * 新客户端发 {@code contents}，服务端按"contents 为空则回落纯文本路径"消化，
 * 接入方零改动。</p>
 *
 * <p>字段与类型的对应不是"全都填"，而是按类型取用：</p>
 *
 * <table border="1">
 *   <caption>类型与字段对应</caption>
 *   <tr><th>type</th><th>text</th><th>url</th><th>base64</th><th>mime</th><th>detail</th></tr>
 *   <tr><td>TEXT</td><td>✓</td><td></td><td></td><td></td><td></td></tr>
 *   <tr><td>IMAGE_URL</td><td></td><td>✓</td><td></td><td></td><td>可选</td></tr>
 *   <tr><td>IMAGE_BASE64</td><td></td><td></td><td>✓</td><td>✓</td><td></td></tr>
 *   <tr><td>AUDIO_URL</td><td></td><td>✓</td><td></td><td></td><td></td></tr>
 *   <tr><td>AUDIO_BASE64</td><td></td><td></td><td>✓</td><td>✓</td><td></td></tr>
 *   <tr><td>VIDEO_URL</td><td></td><td>✓</td><td></td><td></td><td></td></tr>
 *   <tr><td>VIDEO_BASE64</td><td></td><td></td><td>✓</td><td>✓</td><td></td></tr>
 * </table>
 *
 * <p>{@code detail} 只对视觉有意义（low / high），空 = 由模型默认决定。
 * 不强制校验"多余的字段必须为空"：客户端多填一个用不到的值不该让整轮请求失败，
 * 而少填一个必须的（base64 媒体没有 mime）必须在服务端明确报错。</p>
 *
 * @param type    内容块类型
 * @param text    TEXT 块的文本
 * @param url     IMAGE_URL / AUDIO_URL / VIDEO_URL 块的地址
 * @param base64  IMAGE_BASE64 / AUDIO_BASE64 / VIDEO_BASE64 块的 base64 数据（不含 {@code data:} 前缀）
 * @param mime    base64 块的媒体类型，如 {@code image/png} / {@code audio/wav} / {@code video/mp4}
 * @param detail  视觉细节档位：low / high；空 = 模型默认
 * @author zzkingcc
 */
public record MessageContent(
        ContentType type,
        String text,
        String url,
        String base64,
        String mime,
        String detail) {

    @JsonCreator
    public MessageContent(@JsonProperty("type") ContentType type,
                          @JsonProperty("text") String text,
                          @JsonProperty("url") String url,
                          @JsonProperty("base64") String base64,
                          @JsonProperty("mime") String mime,
                          @JsonProperty("detail") String detail) {
        this.type = Objects.requireNonNull(type, "type 不能为空");
        this.text = text;
        this.url = url;
        this.base64 = base64;
        this.mime = mime;
        this.detail = detail;
    }

    /** 纯文本块 */
    public static MessageContent text(String text) {
        return new MessageContent(ContentType.TEXT, text, null, null, null, null);
    }

    /** 图片块（公网 URL） */
    public static MessageContent imageUrl(String url, String detail) {
        return new MessageContent(ContentType.IMAGE_URL, null, url, null, null, detail);
    }

    /** 图片块（base64） */
    public static MessageContent imageBase64(String base64, String mime) {
        return new MessageContent(ContentType.IMAGE_BASE64, null, null, base64, mime, null);
    }

    /** 音频块（公网 URL） */
    public static MessageContent audioUrl(String url) {
        return new MessageContent(ContentType.AUDIO_URL, null, url, null, null, null);
    }

    /** 音频块（base64） */
    public static MessageContent audioBase64(String base64, String mime) {
        return new MessageContent(ContentType.AUDIO_BASE64, null, null, base64, mime, null);
    }

    /** 视频块（公网 URL） */
    public static MessageContent videoUrl(String url) {
        return new MessageContent(ContentType.VIDEO_URL, null, url, null, null, null);
    }

    /** 视频块（base64） */
    public static MessageContent videoBase64(String base64, String mime) {
        return new MessageContent(ContentType.VIDEO_BASE64, null, null, base64, mime, null);
    }

    /** 本块是否自带媒体字节（base64）—— 落库与日志要按它区别对待 */
    public boolean carriesBytes() {
        return base64 != null && !base64.isBlank();
    }

    /** 归一后的 mime；base64 媒体块必须有，否则服务端无法构造 data URL */
    public String requireMime() {
        if (mime == null || mime.isBlank()) {
            throw new IllegalArgumentException("base64 内容块缺少 mime（如 image/png、audio/wav、video/mp4）");
        }
        return mime.trim();
    }

    /** 归一后的 URL；URL 块必须有 */
    public String requireUrl() {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 内容块缺少 url");
        }
        return url.trim();
    }
}
