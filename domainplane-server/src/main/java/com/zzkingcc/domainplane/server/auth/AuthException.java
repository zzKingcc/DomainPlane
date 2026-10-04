package com.zzkingcc.domainplane.server.auth;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;

/**
 * 账号 / 凭证类异常。
 * @author zzkingcc
 */
public class AuthException extends BaseException {

    public AuthException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AuthException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
