package com.zzkingcc.domainplane.clientcore.exception;

/**
 * DomainPlane 客户端异常
 *
 * <p>对话 SDK 与知识库 SDK 共用同一个异常类型：接入方写一个全局异常处理器即可覆盖两者。</p>
 *
 * <p>异常携带的码是 <b>客户端码</b>（{@link ClientErrorCode}），只回答"这次调用在我这一侧
 * 出了什么事"。服务端返回的业务错误不是这个类能表达的东西，它是
 * {@link ServerRejectedException}（本类的子类），把服务端给的码与文案原样带回来。</p>
 *
 * @author zzkingcc
 */
public class DomainPlaneException extends RuntimeException {

    private final ClientErrorCode clientCode;

    public DomainPlaneException(ClientErrorCode clientCode, String message) {
        super(message);
        this.clientCode = clientCode == null ? ClientErrorCode.UNEXPECTED : clientCode;
    }

    public DomainPlaneException(ClientErrorCode clientCode, String message, Throwable cause) {
        super(message, cause);
        this.clientCode = clientCode == null ? ClientErrorCode.UNEXPECTED : clientCode;
    }

    /** 客户端侧归类 */
    public ClientErrorCode getClientCode() {
        return clientCode;
    }

    /** 客户端状态码（= clientCode.getCode()） */
    public int getCode() {
        return clientCode.getCode();
    }

    /** 状态枚举名，接入方应以此定义常量而非硬编码数字 */
    public String getCodeName() {
        return clientCode.name();
    }

    /**
     * 是否值得重试。
     *
     * <p>这是<b>客户端视角</b>的判断：连不上、超时值得重试；参数错、凭证错重试也没用。
     * 服务端业务错误的可重试性由 {@link ServerRejectedException#isRetryable()} 覆盖，
     * 以服务端自己的声明为准。</p>
     */
    public boolean isRetryable() {
        return clientCode.isRetryable();
    }

    /** 排障用的一行描述：码名(码)：文案 */
    public String describe() {
        return getCodeName() + "(" + getCode() + ")：" + getMessage();
    }
}
