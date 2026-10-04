package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;

/**
 * 外部必须三方组件尚未配置
 *
 * @author zzkingcc
 */
public class NotConfiguredException extends BaseException {

    public NotConfiguredException() {
        super(ErrorCode.DEPENDENCY_NOT_CONFIGURED);
    }

    public NotConfiguredException(String detail) {
        super(ErrorCode.DEPENDENCY_NOT_CONFIGURED, detail);
    }

    public NotConfiguredException(String detail, Throwable cause) {
        super(ErrorCode.DEPENDENCY_NOT_CONFIGURED, detail, cause);
    }

    /** 极少数需要换成更贴切码的场景 */
    public NotConfiguredException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
