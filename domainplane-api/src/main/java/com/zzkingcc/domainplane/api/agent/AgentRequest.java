package com.zzkingcc.domainplane.api.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

/**
 * Agent 请求
 *
 * 三个字段都必填。校验与去空白只发生在 {@link #of(String, String, String)} ——
 * {@link #builder()} 的 {@code build()} 也落到那里；反序列化走 {@link JsonCreator} 那条，不做归一化。
 *
 * @author zzkingcc
 */

@Getter
@ToString(onlyExplicitlyIncluded = true)
public final class AgentRequest {

    /** 会话 ID */
    @ToString.Include
    private final String sessionId;
    /** 用户输入 */
    private final String message;
    /** 选择工作域 */
    @ToString.Include
    private final String domain;

    @JsonCreator
    private AgentRequest(@JsonProperty("sessionId") String sessionId,
                         @JsonProperty("message") String message,
                         @JsonProperty("domain") String domain) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.message = Objects.requireNonNull(message, "message");
        this.domain = domain;
    }

    /**
     * 必填项校验 / 去空白的唯一入口。
     */
    @Builder
    public static AgentRequest of(String sessionId, String message, String domain) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain 不能为空：请显式指定本轮所处的域");
        }
        return new AgentRequest(sessionId, message, domain.trim());
    }

    /** 日志只记长度 */
    @ToString.Include(name = "messageLength")
    private int messageLength() {
        return message.length();
    }
}
