package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;

/**
 * 全局异常体系基类
 *
 * @author zzkingcc
 */
public class BaseException extends RuntimeException {

    private final ErrorCode errorCode;
    private final int code;
    private final String codeName;

    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
        this.codeName = errorCode.name();
    }

    public BaseException(ErrorCode errorCode, String detail) {
        super(fill(detail, errorCode.getMessage()));
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
        this.codeName = errorCode.name();
    }

    public BaseException(ErrorCode errorCode, String detail, Throwable cause) {
        super(fill(detail, errorCode.getMessage()), cause);
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
        this.codeName = errorCode.name();
    }

    /** 优先使用 detail，为空时回退到枚举默认文案 */
    private static String fill(String detail, String fallback) {
        return (detail != null && !detail.isBlank()) ? detail : fallback;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 业务状态码 */
    public int getCode() {
        return code;
    }

    /** 枚举名 */
    public String getCodeName() {
        return codeName;
    }
}
