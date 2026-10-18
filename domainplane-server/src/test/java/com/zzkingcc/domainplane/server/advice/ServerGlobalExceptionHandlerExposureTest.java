package com.zzkingcc.domainplane.server.advice;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对外错误出口只该发出去两类东西：对外文案 + traceId。
 *
 * <p>这里锁死两件事：① 终端用户看到的是 {@code getUserMessage()} 而不是运维文案；
 * ② 5xx 不带 detail —— 底层异常原文（Redis / ES 地址端口、驱动报错）只进日志。</p>
 */
class ServerGlobalExceptionHandlerExposureTest {

    private final ServerGlobalExceptionHandler handler = new ServerGlobalExceptionHandler();

    private Map<String, Object> bodyOf(ErrorCode code, String detail) {
        ResponseEntity<Map<String, Object>> response = handler.build(code, detail);
        return response.getBody();
    }

    @Test
    void 五xx只发对外文案且不带detail() {
        Map<String, Object> body = bodyOf(ErrorCode.CHECKPOINT_ERROR,
                "读取检查点列表失败: Unable to connect to 10.0.0.7:6379");

        assertEquals(ErrorCode.CHECKPOINT_ERROR.getUserMessage(), body.get("error"));
        assertNull(body.get("detail"), "5xx 的 detail 多半是底层异常原文，不能给调用方");
        assertEquals(70004, body.get("code"));
        assertEquals("CHECKPOINT_ERROR", body.get("codeName"));
    }

    @Test
    void 检查点错误的对外文案里不出现图字() {
        String shown = (String) bodyOf(ErrorCode.CHECKPOINT_ERROR, "x").get("error");

        assertFalse(shown.contains("图"),
                "对外写'图检查点'会被读成图片故障——这里的'图'是编排状态图，用户看不懂: " + shown);
        assertTrue(shown.contains("会话"), "得说清楚是哪一块出了问题: " + shown);
    }

    @Test
    void 四xx保留detail因为那是给调用方的修改指引() {
        Map<String, Object> body = bodyOf(ErrorCode.KNOWLEDGE_DOCUMENT_DUPLICATE, "员工手册.pdf 已存在");

        assertEquals("员工手册.pdf 已存在", body.get("detail"));
    }

    @Test
    void 响应体里始终带可重试标记与traceId() {
        Map<String, Object> body = bodyOf(ErrorCode.ORCHESTRATION_FAILED, null);

        assertEquals(true, body.get("retryable"));
        assertTrue(body.containsKey("traceId"), "排障靠 traceId 回查日志，不能省");
    }

    @Test
    void 没显式给对外文案的码就退回原文案() {
        // 4xx 的 message 本来就是写给人看的，不需要两份文案
        assertEquals(ErrorCode.DOMAIN_NOT_FOUND.getMessage(),
                ErrorCode.DOMAIN_NOT_FOUND.getUserMessage());
        assertEquals("指定的域不存在", bodyOf(ErrorCode.DOMAIN_NOT_FOUND, null).get("error"));
    }
}
