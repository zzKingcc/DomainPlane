package com.zzkingcc.domainplane.server.code;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两套文案的分工：{@code message} 给运维排障（可以有内部术语），
 * {@code userMessage} 给终端用户（只说发生了什么 + 该做什么）。
 */
class ErrorCodeUserMessageTest {

    @Test
    void 检查点错误的两套文案分工明确() {
        assertTrue(ErrorCode.CHECKPOINT_ERROR.getMessage().contains("图"),
                "运维文案保留技术描述：这里的'图'是编排状态图");
        assertFalse(ErrorCode.CHECKPOINT_ERROR.getUserMessage().contains("图"),
                "对外文案不提'图'：带图会话里报这个错会被读成图片故障");
        assertTrue(ErrorCode.CHECKPOINT_ERROR.getUserMessage().contains("会话"));
        assertTrue(ErrorCode.CHECKPOINT_ERROR.getUserMessage().contains("新会话"),
                "要说清楚兜底动作：换个新会话");
    }

    @Test
    void 没显式给对外文案的码退回原文案() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(code.getUserMessage() != null && !code.getUserMessage().isBlank(),
                    code + " 的对外文案不能为空");
        }
        assertEquals(ErrorCode.INVALID_PARAMETER.getMessage(), ErrorCode.INVALID_PARAMETER.getUserMessage());
    }

    @Test
    void 服务端故障码都有独立的对外文案() {
        // 这几个的 message 是内部术语，直接展示用户看不懂
        assertFalse(ErrorCode.KNOWLEDGE_DEDUP_ERROR.getUserMessage()
                .equals(ErrorCode.KNOWLEDGE_DEDUP_ERROR.getMessage()));
        assertFalse(ErrorCode.CHAT_MEMORY_ERROR.getUserMessage()
                .equals(ErrorCode.CHAT_MEMORY_ERROR.getMessage()));
        assertFalse(ErrorCode.TOOL_DUPLICATE.getUserMessage()
                .equals(ErrorCode.TOOL_DUPLICATE.getMessage()));
        assertFalse(ErrorCode.STORAGE_UNAVAILABLE.getUserMessage()
                .equals(ErrorCode.STORAGE_UNAVAILABLE.getMessage()));
    }

    @Test
    void 无视觉模型错误不给用户看运维指引() {
        // 回归：MODEL_NO_VISION 曾是 5 参构造（无对外文案），抛出点又手工把
        // 「请到管控台「域空间」绑定模型」拼进 SSE 事件：终端用户够不着管控台，
        // 看到这句只剩茫然。现在码带对外文案，抛出点改走 3 参工厂。
        assertFalse(ErrorCode.MODEL_NO_VISION.getUserMessage().contains("管控台"),
                "对外文案不出现「管控台」：终端用户没有那里入口，看到了也无从行动");
        assertFalse(ErrorCode.MODEL_NO_VISION.getUserMessage().contains("视觉模型"),
                "对外文案不暴露内部概念");
        assertFalse(ErrorCode.MODEL_NO_VISION.getUserMessage()
                .equals(ErrorCode.MODEL_NO_VISION.getMessage()));
        assertTrue(ErrorCode.MODEL_NO_VISION.getUserMessage().contains("文字"),
                "要留一条用户自己就能做的事：换文字提问");
        assertFalse(ErrorCode.MODEL_NO_VISION.isRetryable(),
                "配置问题重试无用，保持不可重试");
    }

    @Test
    void 依赖未配置错误不把去哪一页填带给用户() {
        // 同型回归：handleNotConfigured 曾用 4 参工厂把
        // 「请先在管控台××页填写」原样发进 SSE 事件体。
        assertFalse(ErrorCode.DEPENDENCY_NOT_CONFIGURED.getUserMessage().contains("管控台"),
                "去哪一页配置是运维信息，不进对外文案");
        assertFalse(ErrorCode.DEPENDENCY_NOT_CONFIGURED.getUserMessage()
                .equals(ErrorCode.DEPENDENCY_NOT_CONFIGURED.getMessage()));
        assertTrue(ErrorCode.DEPENDENCY_NOT_CONFIGURED.getUserMessage().contains("管理员"),
                "用户做不了配置，告诉他该找谁");
    }
}
