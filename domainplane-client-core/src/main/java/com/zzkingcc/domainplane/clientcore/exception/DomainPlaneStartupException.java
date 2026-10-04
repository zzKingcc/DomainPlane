package com.zzkingcc.domainplane.clientcore.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;

/**
 * DomainPlane 客户端<b>启动期</b>异常
 *
 * @author zzkingcc
 */
public class DomainPlaneStartupException extends DomainPlaneException {

    public DomainPlaneStartupException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public DomainPlaneStartupException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
