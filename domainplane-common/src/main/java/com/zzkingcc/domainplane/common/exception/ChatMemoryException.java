package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;

/**
 * 会话记忆 / Redis 检查点相关异常
 *
 * @author zzkingcc
 */
public class ChatMemoryException extends BaseException {

    public ChatMemoryException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ChatMemoryException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public ChatMemoryException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
