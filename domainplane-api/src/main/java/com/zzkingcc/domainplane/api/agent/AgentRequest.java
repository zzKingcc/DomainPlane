package com.zzkingcc.domainplane.api.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zzkingcc.domainplane.api.model.ContentType;
import com.zzkingcc.domainplane.api.model.MessageContent;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent 请求
 *
 * <p>两个输入字段并存，不是两套协议：</p>
 *
 * <ul>
 *   <li>{@link #message} —— 旧字段，纯文本，永久保留；</li>
 *   <li>{@link #contents} —— 新字段，内容块列表（可带图 / 视频 / 音频），可空。</li>
 * </ul>
 *
 * <p>兼容规则只有一条：{@code contents} 为空 / 空列表时，整体回落到纯 {@code message} 路径，
 * 旧客户端行为分毫不变。反过来 {@code contents} 非空时，它是权威输入：
 * {@link #getMessage()} 会合并其中的 TEXT 块返回，调用方（入口护栏、编排层）继续
 * 用同一个 {@code getMessage()} 拿文本，不需要知道有几种形态。</p>
 *
 * <p>为什么 {@code message} 不因 {@code contents} 而非空就删掉：SDK 与接入方有大量
 * 只传字符串的重载，删字段等于一次性破坏全部存量接入。</p>
 *
 * @author zzkingcc
 */
@Getter
@ToString(onlyExplicitlyIncluded = true)
public final class AgentRequest {

    /** 会话 ID */
    @ToString.Include
    private final String sessionId;

    /**
     * 用户输入的纯文本部分。
     *
     * <p>允许空白：用户只发一张图不发字是完全正常的输入。但两者都空等于没有输入，
     * 那一格仍然拒绝。</p>
     */
    private final String message;

    /** 选择工作域 */
    @ToString.Include
    private final String domain;

    /**
     * 多模态内容块（可空）。
     *
     * <p>为空 / 空列表 = 纯文本请求，走旧路径。非空时按块序消费，
     * 其中的 TEXT 块由 {@link #getMessage()} 合并。</p>
     */
    private final List<MessageContent> contents;

    /**
     * 反序列化入口。
     *
     * <p>新增 {@code contents} 必须同步加在这个 creator 的 {@code @JsonProperty} 上：
     * 类是显式 creator，未声明的属性不会进构造器：只加字段不加这里，
     * 序列化有它、反序列化丢它，表现为"客户端发得出、服务端收不到"的断层，
     * 且两端都不报错。</p>
     */
    @JsonCreator
    private AgentRequest(@JsonProperty("sessionId") String sessionId,
                         @JsonProperty("message") String message,
                         @JsonProperty("domain") String domain,
                         @JsonProperty("contents") List<MessageContent> contents) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        // 与 of() 同一口径：域必填且去空白。
        // 反序列化路径此前漏了这一步，HTTP 入口可以把全空白域带进编排层，
        // 而下游一律按"域已归一化"假设工作：契约必须在唯一的构造点上落地。
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain 不能为空：请显式指定本轮所处的域");
        }
        this.domain = domain.trim();
        this.contents = normalize(contents);
        // message 的必填性与 of() 同一口径：两者都空才拒。
        // 不能写成 requireNonNull(message)：那会让"只发图不发字"在反序列化阶段就炸。
        if ((message == null || message.isBlank()) && this.contents.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        this.message = message;
    }

    /**
     * 必填项校验 / 去空白的入口（builder 也落到这里）。
     *
     * <p>与旧版唯一的差别：{@code message} 不再无条件必填。{@code contents} 非空时
     * 允许 message 空白：只发图不发字是合法输入。</p>
     */
    @Builder
    public static AgentRequest of(String sessionId, String message, String domain,
                                  List<MessageContent> contents) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain 不能为空：请显式指定本轮所处的域");
        }
        List<MessageContent> normalized = normalize(contents);
        if ((message == null || message.isBlank()) && normalized.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return new AgentRequest(sessionId, message, domain.trim(), normalized);
    }

    /**
     * 三轮重载的兼容入口：只传文本时内部转成空 contents，走同一条 {@link #of}。
     *
     * <p>保留它而不是让调用方自己拼 {@code List.of(MessageContent.text(...))}：
     * 那样每个调用点都要写一遍"空列表回落"的判断，迟早有一处漏掉。</p>
     */
    public static AgentRequest of(String sessionId, String message, String domain) {
        return of(sessionId, message, domain, null);
    }

    /**
     * 本轮输入的纯文本。
     *
     * <p>{@code contents} 含 TEXT 块时合并它们（按块序、换行相接）；否则回落 {@code message}。
     * 入口护栏（{@code InputSanitizer}）与编排层的封顶判定都只认这个方法，
     * 于是"文本在哪"只有一个答案。</p>
     */
    public String getMessage() {
        List<MessageContent> blocks = getContents();
        if (blocks.isEmpty()) {
            return message == null ? "" : message;
        }
        StringBuilder merged = new StringBuilder();
        for (MessageContent block : blocks) {
            if (block.type() == ContentType.TEXT && block.text() != null && !block.text().isBlank()) {
                if (merged.length() > 0) {
                    merged.append('\n');
                }
                merged.append(block.text());
            }
        }
        // contents 全是图、一个字都没有：回落 message（可能也是空的，此时返回空串而非 null）
        return merged.length() > 0 ? merged.toString() : (message == null ? "" : message);
    }

    /** 是否携带图片块 */
    public boolean hasImages() {
        return getContents().stream()
                .anyMatch(block -> block.type() != null && block.type().isImage());
    }

    /** 是否携带音频块 */
    public boolean hasAudio() {
        return getContents().stream()
                .anyMatch(block -> block.type() != null && block.type().isAudio());
    }

    /** 是否携带视频块 */
    public boolean hasVideo() {
        return getContents().stream()
                .anyMatch(block -> block.type() != null && block.type().isVideo());
    }

    /** 内容块视图（不可变；无内容时为空列表而非 null） */
    public List<MessageContent> getContents() {
        return contents == null ? List.of() : contents;
    }

    /** 去 null 项，保序 */
    private static List<MessageContent> normalize(List<MessageContent> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<MessageContent> out = new ArrayList<>(raw.size());
        for (MessageContent block : raw) {
            if (block != null && block.type() != null) {
                out.add(block);
            }
        }
        return List.copyOf(out);
    }

    /** 日志只记长度 */
    @ToString.Include(name = "messageLength")
    private int messageLength() {
        String text = getMessage();
        return text == null ? 0 : text.length();
    }
}
