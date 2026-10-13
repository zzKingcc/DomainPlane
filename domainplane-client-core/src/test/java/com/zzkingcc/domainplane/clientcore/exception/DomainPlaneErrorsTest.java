package com.zzkingcc.domainplane.clientcore.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误翻译：客户端只管自己这一侧，服务端给的东西原样带回来。
 *
 * <p>判定依据必须是 <b>客户端码</b>（{@link ClientErrorCode}）与服务端<b>原样透传</b>的字段，
 * 不是异常文案：后者换个版本就会失效。也不再有任何一条断言依赖服务端的枚举类型：
 * 如果哪天测试又 import 了 {@code api.code.ErrorCode}，说明边界又被打破了。</p>
 */
class DomainPlaneErrorsTest {

    private static WebClientResponseException response(int status, String body) {
        return WebClientResponseException.create(status, "status-" + status, HttpHeaders.EMPTY,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    // ==================== 服务端报文：原样透传，不翻译 ====================

    @Test
    void 业务报文里的code原样带出不翻译成本地枚举() {
        ServerRejectedException ex = (ServerRejectedException) DomainPlaneErrors.fromResponseBody(
                Map.of("code", 60005, "codeName", "KNOWLEDGE_DOCUMENT_DUPLICATE",
                        "error", "知识库已存在同名文档", "detail", "员工手册.pdf",
                        "traceId", "trace-1"));

        assertEquals(ClientErrorCode.SERVER_REJECTED, ex.getClientCode(), "客户端侧只归类为'服务端拒绝'");
        assertEquals(60005, ex.getServerCode(), "服务端的码一个字不改地带出来");
        assertEquals("KNOWLEDGE_DOCUMENT_DUPLICATE", ex.getServerCodeName());
        assertEquals("知识库已存在同名文档", ex.getServerMessage());
        assertEquals("trace-1", ex.getTraceId());
        assertTrue(ex.getMessage().contains("员工手册.pdf"), "detail 要带出来，否则排查只能靠猜");
    }

    @Test
    void code为0或缺失视为成功() {
        assertNull(DomainPlaneErrors.fromResponseBody(null));
        assertNull(DomainPlaneErrors.fromResponseBody(Map.of("code", 0, "success", true)));
        assertNull(DomainPlaneErrors.fromResponseBody(Map.of("documents", java.util.List.of())),
                "不含 code 的普通响应体不是错误报文");
    }

    /**
     * 这是"翻译服务端码"最致命的地方：服务端加码之后，老 SDK 只能把它兜底成"未预期错误"，
     * 宿主从此分不清"服务端说了什么"与"我这边没认出来"。透传没有这个问题。
     */
    @Test
    void 未定义错误码也原样带出而不是兜底成通用错误() {
        ServerRejectedException ex = (ServerRejectedException) DomainPlaneErrors.fromResponseBody(
                Map.of("code", 99999, "error", "服务端新加的码"));

        assertEquals(ClientErrorCode.SERVER_REJECTED, ex.getClientCode());
        assertEquals(99999, ex.getServerCode(), "不认识也照样带出来，由宿主决定怎么办");
        assertTrue(ex.getMessage().contains("服务端新加的码"));
    }

    @Test
    void 服务端的retryable原样生效没给就不替它下结论() {
        ServerRejectedException retryable = (ServerRejectedException) DomainPlaneErrors.fromResponseBody(
                Map.of("code", 30000, "error", "任务执行失败", "retryable", true));
        assertTrue(retryable.isRetryable(), "服务端说能重试，就照它说的");

        ServerRejectedException silent = (ServerRejectedException) DomainPlaneErrors.fromResponseBody(
                Map.of("code", 30000, "error", "任务执行失败"));
        assertFalse(silent.isRetryable(), "服务端没说，就不猜 —— 猜错比不重试更糟");
    }

    // ==================== 传输层：客户端自己的码 ====================

    @Test
    void 连不上归为连接被拒() {
        assertEquals(ClientErrorCode.CONNECTION_REFUSED,
                DomainPlaneErrors.fromTransport(new ConnectException("Connection refused")).getClientCode());
        assertEquals(ClientErrorCode.CONNECTION_REFUSED,
                DomainPlaneErrors.fromTransport(new RuntimeException(new UnknownHostException("nope"))).getClientCode(),
                "Netty 会把根因包几层，判定要沿异常链找");
        assertTrue(DomainPlaneErrors.fromTransport(new ConnectException("x")).isRetryable(),
                "连不上值得重试：通常只是服务端还没起来");
    }

    @Test
    void 超时归为客户端超时() {
        assertTrue(DomainPlaneErrors.isTimeout(new SocketTimeoutException("read timed out")));
        assertEquals(ClientErrorCode.TIMEOUT,
                DomainPlaneErrors.fromTransport(new SocketTimeoutException("read timed out")).getClientCode());
        assertTrue(!DomainPlaneErrors.isTimeout(new IllegalStateException("不是超时")));
    }

    @Test
    void 服务端错误报文优先于HTTP状态码() {
        DomainPlaneException ex = DomainPlaneErrors.fromTransport(
                response(500, "{\"code\":60002,\"detail\":\"解析失败\"}"));

        ServerRejectedException rejected = (ServerRejectedException) ex;
        assertEquals(60002, rejected.getServerCode(),
                "报文里有 code 就按服务端码走，不要被 500 盖成客户端的通用错误");
        assertEquals(500, rejected.getHttpStatus());
    }

    @Test
    void 状态401无报文时归为凭证被拒() {
        DomainPlaneException ex = DomainPlaneErrors.fromTransport(response(401, ""));

        assertEquals(ClientErrorCode.CREDENTIAL_REJECTED, ex.getClientCode());
        assertTrue(ex.getMessage().contains("domainplane.username"));
    }

    @Test
    void 网关回的HTML错误页也翻译成带码异常而不是把HTML抛出去() {
        DomainPlaneException ex = DomainPlaneErrors.fromTransport(response(502, "<html><body>Bad Gateway</body></html>"));

        assertEquals(ClientErrorCode.UNEXPECTED, ex.getClientCode());
        assertTrue(ex.getMessage().contains("502"));
        assertTrue(ex.getMessage().contains("Bad Gateway"), "正文要截断带出，方便判断是谁回的错误页");
    }

    @Test
    void 已经是带码异常就原样返回不重复翻译() {
        DomainPlaneException original = new DomainPlaneException(ClientErrorCode.CONNECTION_REFUSED, "连不上");

        assertSame(original, DomainPlaneErrors.fromTransport(original));
    }

    // ==================== 登录失败：只看 HTTP 状态，不读业务码 ====================

    @Test
    void 登录失败按HTTP状态码分客户端码() {
        assertEquals(ClientErrorCode.CREDENTIAL_REJECTED, DomainPlaneErrors.forLoginFailure(response(401, "")));
        assertEquals(ClientErrorCode.SERVER_NOT_INITIALIZED, DomainPlaneErrors.forLoginFailure(response(409, "")));
        assertEquals(ClientErrorCode.ACCESS_DENIED, DomainPlaneErrors.forLoginFailure(response(403, "")));
        assertEquals(ClientErrorCode.TIMEOUT,
                DomainPlaneErrors.forLoginFailure(new SocketTimeoutException("timeout")));
        assertEquals(ClientErrorCode.CONNECTION_REFUSED, DomainPlaneErrors.forLoginFailure(new ConnectException("refused")));
    }

    // ==================== 客户端码表 ====================

    @Test
    void 客户端码与服务端码不共用数字区间() {
        for (ClientErrorCode code : ClientErrorCode.values()) {
            assertTrue(code.getCode() >= 95000 && code.getCode() < 96000,
                    "客户端码必须落在 95xxx：与服务端 10xxx–91xxx 分开，任一方加码都不会撞车");
            assertSame(code, ClientErrorCode.of(code.getCode()));
        }
        assertNull(ClientErrorCode.of(10004), "服务端码不该被认成客户端码");
    }
}
