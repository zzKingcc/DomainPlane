package com.zzkingcc.domainplane.clientcore.exception;

import java.util.Locale;

/**
 * 服务端明确拒绝了这次调用。
 *
 * <p>它只做一件事：<b>把服务端给的码与文案原样带回来</b>。SDK 不把服务端的码翻译成
 * 本地枚举、不用本地文案去重组服务端的话、也不替调用方推导"能不能重试"：
 * 重试与否以服务端报文里的 {@code retryable} 为准，服务端没说就按不可重试处理。</p>
 *
 * <p>为什么不翻译：翻译要求 SDK 与服务端共用一份枚举，于是两边被绑在同一条发版线上。
 * 服务端加一个新码，老 SDK 就只能把它兜底成"未预期错误"，调用方从此分不清
 * "服务端说了什么"和"我这边没认出来"。透传则没有这个问题：新码照样原样到达宿主。</p>
 *
 * <p>客户端侧的归类统一是 {@link ClientErrorCode#SERVER_REJECTED}，真正要读的是
 * {@link #getServerCode()} / {@link #getServerCodeName()} / {@link #getServerMessage()}。</p>
 *
 * @author zzkingcc
 */
public class ServerRejectedException extends DomainPlaneException {

    /** 服务端原始状态码 */
    private final int serverCode;
    /** 服务端状态码名（可能为空：报文里没带、或服务端给了本 SDK 不认识的码） */
    private final String serverCodeName;
    /** 服务端原始文案 */
    private final String serverMessage;
    /** 服务端补充细节（可能为 null） */
    private final String detail;
    /** 本轮排障标识（可能为 null） */
    private final String traceId;
    /** 服务端响应的 HTTP 状态；{@code -1} = 未知（如 SSE 事件通道） */
    private final int httpStatus;
    /** 服务端声明的是否可重试；{@code null} = 服务端没说 */
    private final Boolean serverRetryable;

    public ServerRejectedException(int serverCode, String serverCodeName, String serverMessage,
                                   String detail, String traceId, int httpStatus, Boolean serverRetryable) {
        super(ClientErrorCode.SERVER_REJECTED, compose(serverCode, serverCodeName, serverMessage,
                detail, traceId));
        this.serverCode = serverCode;
        this.serverCodeName = serverCodeName;
        this.serverMessage = serverMessage;
        this.detail = detail;
        this.traceId = traceId;
        this.httpStatus = httpStatus;
        this.serverRetryable = serverRetryable;
    }

    /**
     * 拼展示文案：服务端说人话就用服务端的话，服务端没给文案才退化成码名/数字。
     *
     * <p>traceId 并进文案，与日志同源，方便拿着界面上的报错直接去查服务端日志。</p>
     */
    private static String compose(int serverCode, String serverCodeName, String serverMessage,
                                  String detail, String traceId) {
        String base = serverMessage == null || serverMessage.isBlank()
                ? (serverCodeName == null || serverCodeName.isBlank()
                ? "服务端返回错误码 " + serverCode
                : serverCodeName)
                : serverMessage;
        StringBuilder sb = new StringBuilder(base);
        if (detail != null && !detail.isBlank() && !detail.equals(base)) {
            sb.append("：").append(detail);
        }
        if (traceId != null && !traceId.isBlank()) {
            sb.append("（traceId=").append(traceId).append("）");
        }
        return sb.toString();
    }

    public int getServerCode() {
        return serverCode;
    }

    public String getServerCodeName() {
        return serverCodeName;
    }

    public String getServerMessage() {
        return serverMessage;
    }

    public String getDetail() {
        return detail;
    }

    public String getTraceId() {
        return traceId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 服务端声明的可重试性；没声明时为 {@code null}，此时按 {@link #isRetryable()} 的默认走 */
    public Boolean getServerRetryable() {
        return serverRetryable;
    }

    /**
     * 是否可重试：服务端说了算，没说就退回客户端侧的默认（不可重试）。
     *
     * <p>不自行从服务端码推导：那是"替服务端下结论"，正是这次要去掉的东西。</p>
     */
    @Override
    public boolean isRetryable() {
        return serverRetryable != null ? serverRetryable : super.isRetryable();
    }

    /** 排障用的一行描述：客户端归类 + 服务端原始码 + 文案 */
    @Override
    public String describe() {
        String server = (serverCodeName == null || serverCodeName.isBlank())
                ? String.valueOf(serverCode)
                : serverCodeName + "(" + serverCode + ")";
        return getCodeName() + "(" + getCode() + ")/服务端 " + server + "：" + getMessage();
    }

    /** 报文里的布尔字段（Lettuce/Jackson 可能给字符串）→ 三态布尔 */
    static Boolean retryableOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        return Boolean.parseBoolean(text);
    }
}
