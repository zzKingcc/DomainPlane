package com.zzkingcc.domainplane.clientcore.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;

/**
 * DomainPlane 客户端异常
 *
 * <p>对话 SDK 与知识库 SDK 共用同一个异常类型 —— 接入方写一个全局异常处理器即可覆盖两者。</p>
 *
 * @author zzkingcc
 */
public class DomainPlaneException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainPlaneException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DomainPlaneException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 业务状态码（= errorCode.getCode()） */
    public int getCode() {
        return errorCode.getCode();
    }

    /** 状态枚举名，前端应以此定义常量而非硬编码数字 */
    public String getCodeName() {
        return errorCode.name();
    }

    /** 是否可重试 */
    public boolean isRetryable() {
        return errorCode.isRetryable();
    }
}
