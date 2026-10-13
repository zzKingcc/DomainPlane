package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;

/**
 * 多模态端点（生图 / 重排 / 语音合成 / 语音识别）调用失败，或该档案的协议类型根本不提供此端点的错误。
 *
 * 与对话链路区分开：对话走 langchain4j 适配器（{@link ModelClientFactory}），
 * 而这四个族 langchain4j 没有抽象，由 {@link ModelEndpointClient} 直接走 JDK HttpClient。
 * 调用失败（HTTP 非 2xx / 网络异常）与"协议不支持"是两类不同结论，但都收拢到这一个异常，
 * 由 {@link #getErrorCode()} 区分 —— 上层（管控台控制器）据此决定是"提示服务商报错"
 * 还是"提示该协议暂未适配"。
 *
 * @author zzkingcc
 */
public class ModelEndpointException extends BaseException {

    public ModelEndpointException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ModelEndpointException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public ModelEndpointException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
