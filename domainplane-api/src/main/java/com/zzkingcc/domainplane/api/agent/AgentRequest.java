package com.zzkingcc.domainplane.api.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 请求
 *
 * @author zzkingcc
 */
public final class AgentRequest {

    /** 会话 ID */
    private final String sessionId;
    /** 用户输入 */
    private final String message;
    /** 工作配置 */
    private final String profile;
    /** Optional: Tenant ID */
    private final String tenantId;
    /** Optional: User ID */
    private final String userId;
    /** Optional: Attributes */
    private final Map<String, Object> attributes;

    @JsonCreator
    private AgentRequest(@JsonProperty("sessionId") String sessionId,
                         @JsonProperty("message") String message,
                         @JsonProperty("profile") String profile,
                         @JsonProperty("tenantId") String tenantId,
                         @JsonProperty("userId") String userId,
                         @JsonProperty("attributes") Map<String, Object> attributes) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.message = Objects.requireNonNull(message, "message");
        this.profile = profile;
        this.tenantId = tenantId;
        this.userId = userId;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** 必填项快捷构造 */
    public static AgentRequest of(String sessionId, String message, String profile) {
        return builder().sessionId(sessionId).message(message).profile(profile).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 基于当前实例派生一个可修改的 Builder。
     */
    public Builder toBuilder() {
        return new Builder()
                .sessionId(sessionId)
                .message(message)
                .profile(profile)
                .tenantId(tenantId)
                .userId(userId)
                .attributes(new LinkedHashMap<>(attributes));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 本轮工作的的域。
     */
    public String getProfile() {
        return profile;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "AgentRequest{sessionId='" + sessionId + '\''
                + ", messageLength=" + message.length()
                + ", profile='" + profile + '\''
                + ", tenantId='" + tenantId + '\''
                + ", userId='" + userId + '\''
                + ", attributes=" + attributes.keySet() + '}';
    }

    public static final class Builder {

        private String sessionId;
        private String message;
        private String profile;
        private String tenantId;
        private String userId;
        private Map<String, Object> attributes;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /**
         * 本轮所处的域（必填）。
         */
        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        /** 单个扩展属性 */
        public Builder attribute(String key, Object value) {
            if (this.attributes == null) {
                this.attributes = new LinkedHashMap<>();
            }
            this.attributes.put(key, value);
            return this;
        }

        public AgentRequest build() {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId 不能为空");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message 不能为空");
            }
            if (profile == null || profile.isBlank()) {
                throw new IllegalArgumentException("profile 不能为空：请显式指定本轮所处的域");
            }
            return new AgentRequest(sessionId, message, profile.trim(), tenantId, userId, attributes);
        }
    }
}
