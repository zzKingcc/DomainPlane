package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * 把模型连通性失败的原因翻译成一条简短中文，供管控台展示。
 * @author zzkingcc
 */
public final class LlmFailureDescriber {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 展示用原因的最大长度，超出截断（原始报文不进这里，走 detail 字段） */
    private static final int MAX_LEN = 200;

    private LlmFailureDescriber() {
    }

    /**
     * @return 一条简短中文原因；永远不返回 {@code null}（最差是原始文本的截断）
     */
    public static String describe(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String raw = rootMessage(e);
        String fromBody = describeBody(raw);
        if (fromBody != null) {
            return fromBody;
        }
        String network = describeNetwork(e);
        if (network != null) {
            return network;
        }
        return abbreviate(raw == null ? "未知错误" : raw);
    }

    /** 异常链最内层的 message；都没有则返回 {@code null}（由调用方决定兜底措辞） */
    public static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }

    /** 解析上游报文里的 {@code error.code} / {@code error.message} */
    private static String describeBody(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(raw.substring(start)).path("error");
            if (error.isMissingNode() || error.isNull()) {
                return null;
            }
            String mapped = mapCode(text(error, "code"));
            if (mapped != null) {
                return mapped;
            }
            String message = text(error, "message");
            return message == null ? null : abbreviate(message);
        } catch (Exception ignore) {
            // 不是 JSON / 结构不符 → 交给后面的网络层判断与原文兜底
            return null;
        }
    }

    /** 上游错误码 → 中文说明；认不出返回 {@code null} */
    private static String mapCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        switch (code.toLowerCase(Locale.ROOT)) {
            case "invalid_api_key":
            case "api_key_error":
            case "authentication_error":
            case "unauthorized":
                return "API Key 无效或已过期";
            case "access_denied":
            case "permission_denied":
            case "model_not_authorized":
                return "该 API Key 未被授权访问此模型";
            case "model_not_found":
            case "invalid_model":
                return "模型名不存在";
            case "model_not_supported":
            case "unsupported_model":
                return "该模型不支持此接口（可能把向量模型填到了对话、或反之）";
            case "invalid_parameter_error":
            case "invalid_request_error":
            case "bad_request":
                return "请求被服务商拒绝（模型名或接口地址不正确）";
            case "insufficient_quota":
            case "quota_exceeded":
                return "账户额度不足";
            case "rate_limit_exceeded":
            case "throttling":
            case "too_many_requests":
                return "请求过于频繁，请稍后重试";
            case "server_error":
            case "internal_error":
                return "服务商内部错误";
            default:
                return null;
        }
    }

    /** 网络层失败（压根没拿到响应）→ 中文说明；不是网络问题返回 {@code null} */
    private static String describeNetwork(Throwable e) {
        for (Throwable cur = e; cur != null && cur != cur.getCause(); cur = cur.getCause()) {
            if (cur instanceof UnknownHostException) {
                return "无法解析服务商地址（域名不存在或网络不通）";
            }
            if (cur instanceof ConnectException) {
                return "无法连接到服务商地址（地址或端口不通）";
            }
            if (cur instanceof SocketTimeoutException || cur instanceof java.net.http.HttpTimeoutException) {
                return "请求超时（服务商无响应）";
            }
            if (cur instanceof SSLException) {
                return "HTTPS 证书校验失败";
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return "未知错误";
        }
        return t.length() <= MAX_LEN ? t : t.substring(0, MAX_LEN) + "…";
    }
}
