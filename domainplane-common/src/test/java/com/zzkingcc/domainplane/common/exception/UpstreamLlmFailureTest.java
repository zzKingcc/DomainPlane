package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游失败定性：欠费 / 鉴权 / 被拒这三类"重试也不会好"，必须与"限流 / 超时 / 上游 5xx"分开。
 *
 * <p>全都塌进可重试的通用码，前端会一直重发同一个必然失败的请求，
 * 而真因（DeepSeek 把欠费报成 {@code invalid_request_error} + {@code Insufficient Balance}）
 * 永远传不到用户眼前。</p>
 */
class UpstreamLlmFailureTest {

    @Test
    void 欠费塞在通用400里也要认出来() {
        UpstreamLlmFailure.Diagnosis d =
                UpstreamLlmFailure.diagnose(new InvalidRequestException("Insufficient Balance"));

        assertNotNull(d);
        assertSame(ErrorCode.LLM_QUOTA_EXHAUSTED, d.code());
        assertEquals("Insufficient Balance", d.reason());
        assertFalse(d.code().isRetryable(), "欠费重试无意义，必须不可重试");
    }

    @Test
    void 上游原样报文里抠出message字段当原因() {
        String body = "{\"error\":{\"message\":\"Insufficient Balance\",\"code\":\"invalid_request_error\"}}";
        UpstreamLlmFailure.Diagnosis d = UpstreamLlmFailure.diagnose(new InvalidRequestException(body));

        assertSame(ErrorCode.LLM_QUOTA_EXHAUSTED, d.code());
        assertEquals("Insufficient Balance", d.reason(), "不该把整段 JSON 原样吐给用户");
    }

    @Test
    void 普通参数错归为上游拒绝且不可重试() {
        UpstreamLlmFailure.Diagnosis d =
                UpstreamLlmFailure.diagnose(new InvalidRequestException("max_tokens is too large"));

        assertSame(ErrorCode.LLM_REQUEST_REJECTED, d.code());
        assertFalse(d.code().isRetryable());
    }

    @Test
    void 凭证无效归为鉴权失败且不可重试() {
        UpstreamLlmFailure.Diagnosis d =
                UpstreamLlmFailure.diagnose(new AuthenticationException("Incorrect API key provided"));

        assertSame(ErrorCode.LLM_AUTH_FAILED, d.code());
        assertFalse(d.code().isRetryable());
    }

    @Test
    void 限流429里夹着欠费按欠费算其余仍可重试() {
        assertSame(ErrorCode.LLM_QUOTA_EXHAUSTED, UpstreamLlmFailure
                .diagnose(new RateLimitException("You exceeded your current quota")).code());

        UpstreamLlmFailure.Diagnosis rate =
                UpstreamLlmFailure.diagnose(new RateLimitException("Rate limit reached for requests"));
        assertSame(ErrorCode.LLM_RATE_LIMITED, rate.code());
        assertTrue(rate.code().isRetryable());
    }

    @Test
    void 内容被过滤要排在通用400之前识别() {
        // ContentFilteredException 是 InvalidRequestException 的子类，分支顺序写反就会归成"上游拒绝"
        assertSame(ErrorCode.INPUT_REJECTED,
                UpstreamLlmFailure.diagnose(new ContentFilteredException("blocked by safety filter")).code());
    }

    @Test
    void 超时与上游5xx维持可重试() {
        assertSame(ErrorCode.LLM_TIMEOUT,
                UpstreamLlmFailure.diagnose(new TimeoutException("read timed out")).code());
        assertSame(ErrorCode.LLM_UNAVAILABLE,
                UpstreamLlmFailure.diagnose(new InternalServerException("upstream 502")).code());
    }

    @Test
    void 异常被包装后仍能定性() {
        UpstreamLlmFailure.Diagnosis d = UpstreamLlmFailure.diagnose(
                new IllegalStateException("graph node failed", new InvalidRequestException("Insufficient Balance")));

        assertNotNull(d);
        assertSame(ErrorCode.LLM_QUOTA_EXHAUSTED, d.code());
    }

    @Test
    void 非上游异常不硬归类() {
        assertNull(UpstreamLlmFailure.diagnose(new IllegalStateException("boom")));
        assertNull(UpstreamLlmFailure.diagnose(new RuntimeException()));
        assertNull(UpstreamLlmFailure.diagnose(null));
    }
}
