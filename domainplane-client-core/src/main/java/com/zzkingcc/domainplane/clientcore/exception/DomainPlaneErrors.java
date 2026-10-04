package com.zzkingcc.domainplane.clientcore.exception;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 错误翻译：传输层异常与业务报文 → 带码的 {@link DomainPlaneException}。
 *
 * @author zzkingcc
 */
public final class DomainPlaneErrors {

    /** 解析服务端错误报文用；报文很小且结构固定，不需要做成 Bean */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DomainPlaneErrors() {
    }

    /**
     * 传输层异常 → 带码的客户端异常。
     */
    public static DomainPlaneException fromTransport(Throwable error) {
        if (error instanceof DomainPlaneException domainplaneException) {
            return domainplaneException;
        }

        WebClientResponseException response = find(error, WebClientResponseException.class);
        if (response != null) {
            int status = response.getStatusCode().value();
            DomainPlaneException fromBody = fromResponseBody(response.getResponseBodyAsString());
            if (fromBody != null) {
                return fromBody;
            }
            if (status == 401 || status == 403) {
                return invalidCredential(String.valueOf(status), error);
            }
            return new DomainPlaneException(ErrorCode.UNEXPECTED_ERROR,
                    "DomainPlane 服务端返回 HTTP " + status + "：" + abbreviate(response.getResponseBodyAsString()),
                    error);
        }

        String status = ClientCredential.extractHttpStatus(error);
        if ("401".equals(status) || "403".equals(status)) {
            return invalidCredential(status, error);
        }
        if (isTimeout(error)) {
            return new DomainPlaneException(ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                    "等待 DomainPlane 服务端响应超时：请检查服务端负载与网络，或调大超时配置", error);
        }
        if (hasCause(error, ConnectException.class, UnknownHostException.class,
                UnresolvedAddressException.class)) {
            return new DomainPlaneException(ErrorCode.SERVER_UNREACHABLE,
                    "无法连接 DomainPlane 服务端：请确认 domainplane.server 指向的地址与端口正确、服务端已启动", error);
        }
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new DomainPlaneException(ErrorCode.UNEXPECTED_ERROR, "调用 DomainPlane 服务端失败：" + detail, error);
    }

    /**
     * 是否为超时类异常。
     */
    public static boolean isTimeout(Throwable error) {
        return hasCause(error, SocketTimeoutException.class, TimeoutException.class,
                io.netty.handler.timeout.TimeoutException.class);
    }

    /** 响应体 JSON 文本 → 异常；不是 DomainPlane 错误报文（或解析失败）返回 {@code null} */
    private static DomainPlaneException fromResponseBody(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                return null;
            }
            return fromResponseBody(MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            // 网关 / 反向代理回的 HTML 错误页、或截断的报文，都不是 DomainPlane 错误报文
            return null;
        }
    }

    private static DomainPlaneException invalidCredential(String status, Throwable error) {
        return new DomainPlaneException(ErrorCode.AUTH_REQUIRED,
                "服务端拒绝了凭证（HTTP " + status + "）：请确认 domainplane.username / domainplane.password"
                        + "与服务端当前账号一致（服务端改过密码会让全部旧凭证失效，改后需同步配置并重启）",
                error);
    }

    /** 在异常链上找某个类型的异常（Netty / Reactor 会包好几层） */
    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(空响应)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /**
     * 非流式响应体 → 异常。
     *
     * @param body 服务端响应体；{@code null} / 不含 code 视为成功
     */
    public static DomainPlaneException fromResponseBody(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object rawCode = body.get("code");
        if (!(rawCode instanceof Number number) || number.intValue() == 0) {
            return null;
        }
        int code = number.intValue();
        ErrorCode errorCode = ErrorCode.of(code);
        String detail = text(body.get("detail"));
        if (detail.isBlank()) {
            detail = text(body.get("error"));
        }
        String message = errorCode == null
                ? "服务端返回未定义错误码 " + code + (detail.isBlank() ? "" : "：" + detail)
                : errorCode.getMessage() + (detail.isBlank() ? "" : "：" + detail);
        return new DomainPlaneException(errorCode == null ? ErrorCode.UNEXPECTED_ERROR : errorCode,
                message, null);
    }

    /**
     * 登录失败的码。
     */
    public static ErrorCode forLoginFailure(Throwable error) {
        String status = ClientCredential.extractHttpStatus(error);
        if ("401".equals(status)) {
            return ErrorCode.AUTH_FAILED;
        }
        if ("409".equals(status)) {
            return ErrorCode.AUTH_NOT_INITIALIZED;
        }
        if ("403".equals(status)) {
            return ErrorCode.PERMISSION_DENIED;
        }
        if (hasCause(error, SocketTimeoutException.class, TimeoutException.class,
                io.netty.handler.timeout.TimeoutException.class)) {
            return ErrorCode.EXTERNAL_SERVICE_TIMEOUT;
        }
        return ErrorCode.SERVER_UNREACHABLE;
    }

    /** 异常链上是否出现过某类异常（Netty / Reactor 会把根因包好几层） */
    private static boolean hasCause(Throwable error, Class<?>... types) {
        Throwable current = error;
        while (current != null) {
            for (Class<?> type : types) {
                if (type.isInstance(current)) {
                    return true;
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
