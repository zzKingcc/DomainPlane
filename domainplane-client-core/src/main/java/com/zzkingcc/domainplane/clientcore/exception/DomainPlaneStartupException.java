package com.zzkingcc.domainplane.clientcore.exception;

/**
 * DomainPlane 客户端启动期异常
 *
 * <p>换凭证失败这类"还没发业务请求就失败了"的情形专用；被
 * {@link DomainPlaneErrors#fromTransport} 归一成父类后才交给接入方。</p>
 *
 * @author zzkingcc
 */
public class DomainPlaneStartupException extends DomainPlaneException {

    public DomainPlaneStartupException(ClientErrorCode clientCode, String message) {
        super(clientCode, message);
    }

    public DomainPlaneStartupException(ClientErrorCode clientCode, String message, Throwable cause) {
        super(clientCode, message, cause);
    }
}
