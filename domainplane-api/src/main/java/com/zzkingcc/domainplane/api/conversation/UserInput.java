package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一轮的用户输入：文本 + 媒体引用。
 *
 * <p>只发图不发字是合法输入，此时 {@code text} 为空串而非 {@code null}；
 * {@code media} 永不为 {@code null}（无媒体为空列表）。</p>
 *
 * @author zzkingcc
 */
public record UserInput(
        @JsonProperty("text") String text,
        @JsonProperty("media") List<MediaRef> media) {

    @JsonCreator
    public UserInput(@JsonProperty("text") String text,
                     @JsonProperty("media") List<MediaRef> media) {
        this.text = text == null ? "" : text;
        this.media = media == null ? List.of() : List.copyOf(media);
    }

    public static UserInput of(String text, List<MediaRef> media) {
        return new UserInput(text, media);
    }
}
