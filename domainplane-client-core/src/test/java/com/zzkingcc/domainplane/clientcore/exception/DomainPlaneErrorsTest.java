package com.zzkingcc.domainplane.clientcore.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误翻译：对话 SDK 与知识库 SDK 共用同一套码，接入方只写一个异常处理器。
 *
 * <p>判定依据必须是 HTTP 状态码与报文里的 {@code code}，不是异常文案 —— 后者换个版本就会失效。</p>
 */
class DomainPlaneErrorsTest {

    private static WebClientResponseException response(int status, String body) {
        return WebClientResponseException.create(status, "status-" + status, HttpHeaders.EMPTY,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    // ==================== 报文 → 码 ====================

    @Test
    void 业务报文里的code还原成带码异常() {
        DomainPlaneException ex = DomainPlaneErrors.fromResponseBody(
                Map.of("code", 60005, "detail", "同名文档已存在"));

        assertEquals(ErrorCode.KNOWLEDGE_DOCUMENT_DUPLICATE, ex.getErrorCode());
        assertEquals(60005, ex.getCode());
        assertTrue(ex.getMessage().contains("同名文档已存在"), "服务端的 detail 必须带出来，否则排查只能靠猜");
    }

    @Test
    void code为0或缺失视为成功() {
        assertNull(DomainPlaneErrors.fromResponseBody(null));
        assertNull(DomainPlaneErrors.fromResponseBody(Map.of("code", 0, "success", true)));
        assertNull(DomainPlaneErrors.fromResponseBody(Map.of("documents", java.util.List.of())),
                "不含 code 的普通响应体不是错误报文");
    }

    @Test
    void 未定义错误码兜底成通用错误但保留原文案() {
        DomainPlaneException ex = DomainPlaneErrors.fromResponseBody(Map.of("code", 99999, "error", "服务端新加的码"));

        assertEquals(ErrorCode.UNEXPECTED_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("99999"));
        assertTrue(ex.getMessage().contains("服务端新加的码"));
    }

    // ==================== 传输层异常 → 码 ====================

    @Test
    void 连不上与解析不了都归为服务端不可达() {
        assertEquals(ErrorCode.SERVER_UNREACHABLE,
                DomainPlaneErrors.fromTransport(new ConnectException("Connection refused")).getErrorCode());
        assertEquals(ErrorCode.SERVER_UNREACHABLE,
                DomainPlaneErrors.fromTransport(new RuntimeException(new UnknownHostException("nope"))).getErrorCode(),
                "Netty 会把根因包几层，判定要沿异常链找");
    }

    @Test
    void 超时归为外部服务超时() {
        assertTrue(DomainPlaneErrors.isTimeout(new SocketTimeoutException("read timed out")));
        assertEquals(ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                DomainPlaneErrors.fromTransport(new SocketTimeoutException("read timed out")).getErrorCode());
        assertTrue(!DomainPlaneErrors.isTimeout(new IllegalStateException("不是超时")));
    }

    @Test
    void 服务端错误报文优先于HTTP状态码() {
        DomainPlaneException ex = DomainPlaneErrors.fromTransport(
                response(500, "{\"code\":60002,\"detail\":\"解析失败\"}"));

        assertEquals(ErrorCode.KNOWLEDGE_INGEST_ERROR, ex.getErrorCode(),
                "报文里有 code 就按业务码走，不要被 500 盖成通用错误");
    }

    @Test
    void 状态401无报文时归为凭证失效() {
        DomainPlaneException ex = DomainPlaneErrors.fromTransport(response(401, ""));

        assertEquals(ErrorCode.AUTH_REQUIRED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("domainplane.username"));
    }

    @Test
    void 网关回的HTML错误页也翻译成带码异常而不是把HTML抛出去() {
        DomainPlaneException ex = DomainPlaneErrors.fromTransport(response(502, "<html><body>Bad Gateway</body></html>"));

        assertEquals(ErrorCode.UNEXPECTED_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("502"));
        assertTrue(ex.getMessage().contains("Bad Gateway"), "正文要截断带出，方便判断是谁回的错误页");
    }

    @Test
    void 已经是带码异常就原样返回不重复翻译() {
        DomainPlaneException original = new DomainPlaneException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");

        assertSame(original, DomainPlaneErrors.fromTransport(original));
    }

    // ==================== 登录失败的码 ====================

    @Test
    void 登录失败按HTTP状态码分码() {
        assertEquals(ErrorCode.AUTH_FAILED, DomainPlaneErrors.forLoginFailure(response(401, "")));
        assertEquals(ErrorCode.AUTH_NOT_INITIALIZED, DomainPlaneErrors.forLoginFailure(response(409, "")));
        assertEquals(ErrorCode.PERMISSION_DENIED, DomainPlaneErrors.forLoginFailure(response(403, "")));
        assertEquals(ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                DomainPlaneErrors.forLoginFailure(new SocketTimeoutException("timeout")));
        assertEquals(ErrorCode.SERVER_UNREACHABLE, DomainPlaneErrors.forLoginFailure(new ConnectException("refused")));
    }
}
