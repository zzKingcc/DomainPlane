package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查点"数据损坏"必须与"Redis 连不上 / 超时"分开：
 * 前者重试无效、得清键自愈；后者重试可能就好。
 */
class CheckpointCorruptedExceptionTest {

    @Test
    void 是会话记忆异常的子类所以既有的catch照旧生效() {
        assertTrue(new CheckpointCorruptedException("x", null) instanceof ChatMemoryException);
        assertTrue(new CheckpointCorruptedException("x", null) instanceof BaseException);
    }

    @Test
    void 固定走检查点错误码并保留细节与原因() {
        RuntimeException cause = new IllegalStateException("stream corrupt");
        CheckpointCorruptedException ex = new CheckpointCorruptedException("检查点数据损坏", cause);

        assertSame(ErrorCode.CHECKPOINT_ERROR, ex.getErrorCode());
        assertEquals(70004, ex.getCode());
        assertEquals("CHECKPOINT_ERROR", ex.getCodeName());
        assertEquals("检查点数据损坏", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
