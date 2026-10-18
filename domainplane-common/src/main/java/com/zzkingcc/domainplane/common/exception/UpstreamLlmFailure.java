package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;

import java.util.Locale;

/**
 * 把"上游大模型调用失败"定性成一个 {@link ErrorCode} + 一条原因。
 *
 * <p>编排层的通用异常兜底曾经把所有非"未配置"的失败一律归到
 * {@link ErrorCode#ORCHESTRATION_FAILED}（可重试，"任务执行失败，请重试"）。
 * 但上游 4xx 里有一类是"重试也不会好"的：账号欠费、凭证无效、请求被拒。
 * 塌成可重试的通用码，前端只会一次次重发同一个必然失败的请求，
 * 而用户永远看不到真正的原因（例如 DeepSeek 把欠费报成
 * {@code invalid_request_error} + {@code Insufficient Balance}）。</p>
 *
 * <p>本类不引 JSON 依赖（common 只依赖 api 契约层）：上游报文若原样塞在异常 message 里，
 * 按关键词识别，并顺带扫描出 {@code "message"} 字段的值。</p>
 *
 * @author zzkingcc
 */
public final class UpstreamLlmFailure {

    /** 原因展示上限，超出截断 */
    private static final int MAX_REASON = 200;

    /** 异常链遍历上限，防御性 */
    private static final int MAX_DEPTH = 10;

    private UpstreamLlmFailure() {
    }

    /**
     * @param code   该失败对应的 DomainPlane 错误码
     * @param reason 上游给出的原因；可能为 {@code null}，此时由码的对外文案兜底
     */
    public record Diagnosis(ErrorCode code, String reason) {
    }

    /**
     * 定性一条上游失败。
     *
     * @return 识别得出时返回定性结果；不是上游模型异常时返回 {@code null}，
     *         调用方维持原有兜底（保持"认不出就别乱归类"的保守姿态）
     */
    public static Diagnosis diagnose(Throwable e) {
        Throwable cur = e;
        for (int depth = 0; cur != null && depth < MAX_DEPTH; depth++) {
            String msg = cur.getMessage();
            // 必须排在 InvalidRequestException 之前：ContentFilteredException 是它的子类，
            // 顺序反了会把"内容被安全过滤"误报成"上游拒绝请求"
            if (cur instanceof ContentFilteredException) {
                return new Diagnosis(ErrorCode.INPUT_REJECTED, reason(msg));
            }
            if (cur instanceof InvalidRequestException) {
                // 同样是 400：欠费与"参数不对"必须分开，前者充值前重试无意义
                return looksLikeQuotaExhausted(msg)
                        ? new Diagnosis(ErrorCode.LLM_QUOTA_EXHAUSTED, reason(msg))
                        : new Diagnosis(ErrorCode.LLM_REQUEST_REJECTED, reason(msg));
            }
            if (cur instanceof RateLimitException) {
                // OpenAI 系把"余额耗尽"也报成 429（code=insufficient_quota），需再择一次
                return looksLikeQuotaExhausted(msg)
                        ? new Diagnosis(ErrorCode.LLM_QUOTA_EXHAUSTED, reason(msg))
                        : new Diagnosis(ErrorCode.LLM_RATE_LIMITED, reason(msg));
            }
            if (cur instanceof AuthenticationException) {
                return new Diagnosis(ErrorCode.LLM_AUTH_FAILED, reason(msg));
            }
            if (cur instanceof ModelNotFoundException) {
                return new Diagnosis(ErrorCode.LLM_REQUEST_REJECTED, reason(msg));
            }
            if (cur instanceof TimeoutException) {
                return new Diagnosis(ErrorCode.LLM_TIMEOUT, reason(msg));
            }
            if (cur instanceof InternalServerException || cur instanceof UnresolvedModelServerException) {
                return new Diagnosis(ErrorCode.LLM_UNAVAILABLE, reason(msg));
            }
            Throwable cause = cur.getCause();
            if (cause == cur) {
                break;
            }
            cur = cause;
        }
        return null;
    }

    /**
     * 上游报文是否在说"余额 / 额度不足"。
     *
     * <p>{@code public} 以便管控台侧的失败描述器复用同一份关键词：两边各维护一套，
     * 迟早只改一处。</p>
     */
    public static boolean looksLikeQuotaExhausted(String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("insufficient balance")
                || t.contains("insufficient_quota")
                || t.contains("insufficient quota")
                || t.contains("quota exceeded")
                || t.contains("exceeded your current quota")
                || t.contains("余额")
                || t.contains("欠费")
                || t.contains("额度不足");
    }

    /** 上游报文是否在说"凭证 / 鉴权有问题"；同样供管控台侧复用 */
    public static boolean looksLikeAuthFailure(String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("invalid api key")
                || t.contains("incorrect api key")
                || t.contains("api key not valid")
                || t.contains("authentication")
                || t.contains("unauthorized")
                || t.contains("permission denied");
    }

    /** 上游原因：能抠出报文的 message 字段就抠，抠不到用原文；压成一行并截断 */
    private static String reason(String msg) {
        if (msg == null) {
            return null;
        }
        String text = extractMessageField(msg);
        if (text == null) {
            text = msg;
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON) + "…";
    }

    /**
     * 从 {@code {"error":{"message":"..."}}} / {@code {"message":"..."}} 里抠出 message 值。
     *
     * <p>只取一个字符串字段，手写扫描足够，也让 common 不必背上 JSON 依赖。</p>
     */
    private static String extractMessageField(String raw) {
        int key = raw.indexOf("\"message\"");
        if (key < 0) {
            return null;
        }
        int colon = raw.indexOf(':', key + 9);
        if (colon < 0) {
            return null;
        }
        int open = raw.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = open + 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                sb.append(raw.charAt(++i));
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }
}
