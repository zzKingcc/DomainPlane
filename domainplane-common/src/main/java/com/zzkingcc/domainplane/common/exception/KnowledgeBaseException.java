package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;

/**
 * 知识库相关异常
 *
 * @author zzkingcc
 */
public class KnowledgeBaseException extends BaseException {

    public KnowledgeBaseException(ErrorCode errorCode) {
        super(errorCode);
    }

    public KnowledgeBaseException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public KnowledgeBaseException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
