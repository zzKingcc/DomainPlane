package com.zzkingcc.domainplane.clientcore.exception;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 错误翻译：传输层异常与服务端报文 → 带客户端码的 {@link DomainPlaneException}。
 *
 * <p>分工是硬边界：</p>
 * <ul>
 *   <li><b>我这一侧的失败</b>（连不上、超时、凭证被拒、响应读不懂）→ 客户端码
 *       {@link ClientErrorCode}，文案由 SDK 给出可操作提示。</li>
 *   <li><b>服务端返回的业务错误</b> → {@link ServerRejectedException}，码与文案
 *       <b>原样透传</b>，不翻译成本地枚举、不用本地文案重组、不自行推导可重试性。</li>
 * </ul>
 *
 * <p>唯一还"看服务端"的地方是 HTTP 状态码（401/403/409 等）：那是传输/握手层的事实，
 * 属于客户端自己的关注面，与报文里的业务码无关。</p>
 *
 * @author zzkingcc
 */
public final class DomainPlaneErrors {

    /** 解析服务端错误报文用；报文很小且结构固定，不需要做成 Bean */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 没有 HTTP 状态可用时的占位（如 SSE 事件通道） */
    private static final int HTTP_STATUS_UNKNOWN = -1;

    private DomainPlaneErrors() {
    }

    /**
     * 传输层异常 → 带客户端码的异常。
     */
    public static DomainPlaneException fromTransport(Throwable error) {
        if (error instanceof DomainPlaneStartupException startup) {
            // 凭证换取失败仍带 StartupException 类型（登录失败就是登录失败，码与文案都准），
            // 但对业务调用方而言它就是一次普通的远端不可用：归一成父类，
            // 免得接入方为了 catch 一次"服务端没启动"而依赖启动期的异常类型。
            return new DomainPlaneException(startup.getClientCode(), startup.getMessage(), startup);
        }
        if (error instanceof DomainPlaneException domainplaneException) {
            return domainplaneException;
        }

        WebClientResponseException response = find(error, WebClientResponseException.class);
        if (response != null) {
            int status = response.getStatusCode().value();
            DomainPlaneException fromBody = fromResponseBody(response.getResponseBodyAsString(), status);
            if (fromBody != null) {
                return fromBody;
            }
            if (status == 401 || status == 403) {
                return invalidCredential(String.valueOf(status), error);
            }
            return new DomainPlaneException(ClientErrorCode.UNEXPECTED,
                    "DomainPlane 服务端返回 HTTP " + status + "：" + abbreviate(response.getResponseBodyAsString()),
                    error);
        }

        String status = ClientCredential.extractHttpStatus(error);
        if ("401".equals(status) || "403".equals(status)) {
            return invalidCredential(status, error);
        }
        if (isTimeout(error)) {
            return new DomainPlaneException(ClientErrorCode.TIMEOUT,
                    "等待 DomainPlane 服务端响应超时：请检查服务端负载与网络，或调大超时配置", error);
        }
        if (hasCause(error, ConnectException.class, UnknownHostException.class,
                UnresolvedAddressException.class)) {
            return new DomainPlaneException(ClientErrorCode.CONNECTION_REFUSED,
                    "无法连接 DomainPlane 服务端：请确认 domainplane.server 指向的地址与端口正确、服务端已启动", error);
        }
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new DomainPlaneException(ClientErrorCode.UNEXPECTED, "调用 DomainPlane 服务端失败：" + detail, error);
    }

    /**
     * 是否为超时类异常。
     */
    public static boolean isTimeout(Throwable error) {
        return hasCause(error, SocketTimeoutException.class, TimeoutException.class,
                io.netty.handler.timeout.TimeoutException.class);
    }

    /** 响应体 JSON 文本 → 异常；不是 DomainPlane 错误报文（或解析失败）返回 {@code null} */
    private static DomainPlaneException fromResponseBody(String json, int httpStatus) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                return null;
            }
            return fromResponseBody(MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {
            }), httpStatus);
        } catch (Exception e) {
            // 网关 / 反向代理回的 HTML 错误页、或截断的报文，都不是 DomainPlane 错误报文
            return null;
        }
    }

    private static DomainPlaneException invalidCredential(String status, Throwable error) {
        return new DomainPlaneException(ClientErrorCode.CREDENTIAL_REJECTED,
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
     * 服务端错误报文 → {@link ServerRejectedException}（原样透传）。
     *
     * @param body 服务端响应体；{@code null} / 不含 code / code 为 0 视为成功
     */
    public static DomainPlaneException fromResponseBody(Map<String, Object> body) {
        return fromResponseBody(body, HTTP_STATUS_UNKNOWN);
    }

    /**
     * 同上，并带上这次响应的 HTTP 状态（排障时能看出是 4xx 还是 5xx 通道回来的）。
     */
    public static DomainPlaneException fromResponseBody(Map<String, Object> body, int httpStatus) {
        if (body == null) {
            return null;
        }
        Object rawCode = body.get("code");
        if (!(rawCode instanceof Number number) || number.intValue() == 0) {
            return null;
        }
        int code = number.intValue();
        String codeName = text(body.get("codeName"));
        String message = text(body.get("error"));
        String detail = text(body.get("detail"));
        String traceId = text(body.get("traceId"));
        return new ServerRejectedException(code, codeName, message, detail, traceId, httpStatus,
                ServerRejectedException.retryableOf(body.get("retryable")));
    }

    /**
     * 登录失败的客户端码。
     *
     * <p>只按 HTTP 状态码与异常类型分：这些是传输层/握手层的事实，属于客户端自己的
     * 关注面；报文里的业务码一个都不读。</p>
     */
    public static ClientErrorCode forLoginFailure(Throwable error) {
        String status = ClientCredential.extractHttpStatus(error);
        if ("401".equals(status)) {
            return ClientErrorCode.CREDENTIAL_REJECTED;
        }
        if ("409".equals(status)) {
            return ClientErrorCode.SERVER_NOT_INITIALIZED;
        }
        if ("403".equals(status)) {
            return ClientErrorCode.ACCESS_DENIED;
        }
        if (hasCause(error, SocketTimeoutException.class, TimeoutException.class,
                io.netty.handler.timeout.TimeoutException.class)) {
            return ClientErrorCode.TIMEOUT;
        }
        return ClientErrorCode.CONNECTION_REFUSED;
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
