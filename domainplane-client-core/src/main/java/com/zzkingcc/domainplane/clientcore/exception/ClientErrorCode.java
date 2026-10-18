package com.zzkingcc.domainplane.clientcore.exception;

/**
 * 客户端状态码
 *
 * <p>只描述 <b>SDK 自己这一侧</b>发生的事：连不连得上、发不发得出、有没有超时、
 * 响应读不读得懂、本地参数合不合法。<b>服务端返回的业务错误不属于这张表</b>：
 * 那边由 {@link ServerRejectedException} 原样携带，SDK 不翻译、不重组、不猜测。</p>
 *
 * <p>与服务端 {@code api.code.ErrorCode} 的关系：两者是不同的命名空间，数字区间
 * 刻意分开（服务端 10xxx–91xxx，客户端 95xxx），避免任何一方加码时撞车或误判。
 * 客户端码随 SDK 版本演进，不需要与服务端同步发版：这正是拆开的目的。</p>
 *
 * @author zzkingcc
 */
public enum ClientErrorCode {

    /** 未归类的客户端失败：兜底用，文案里必须带上可排障的细节 */
    UNEXPECTED(95000, "调用 DomainPlane 服务端失败", false),

    /** 连不上：地址错、端口错、服务端没起、DNS 解析不了 */
    CONNECTION_REFUSED(95001, "无法连接 DomainPlane 服务端", true),

    /** 等不到响应：读超时、网关挂起、整轮等待超过上限 */
    TIMEOUT(95002, "等待 DomainPlane 服务端响应超时", true),

    /** 凭证被拒：账号密码错、密码改过、凭证过期 */
    CREDENTIAL_REJECTED(95003, "服务端拒绝了凭证", false),

    /** 服务端账号尚未初始化（登录回 409） */
    SERVER_NOT_INITIALIZED(95004, "服务端账号尚未初始化", false),

    /** 被访问控制挡下（登录或调用回 403） */
    ACCESS_DENIED(95005, "服务端拒绝访问", false),

    /** 响应读不出来：空响应、非 DomainPlane 报文、结构对不上、字段解析失败 */
    RESPONSE_UNREADABLE(95006, "无法解析服务端响应", false),

    /** 事件流在收到终止事件前结束：答案是半截，绝不能当成功交付 */
    STREAM_TRUNCATED(95007, "事件流被截断，内容不完整", true),

    /** 调用方自己传的参数不合法：在发出请求之前就被拦下 */
    INVALID_ARGUMENT(95008, "调用参数不合法", false),

    /**
     * 服务端明确回了一个业务错误。
     *
     * <p>这一项的语义只是"失败来自服务端那边"，具体的码与文案都在
     * {@link ServerRejectedException} 里原样挂着；是否可重试以服务端给的
     * {@code retryable} 为准（没给就取这里的 {@code false}）。</p>
     */
    SERVER_REJECTED(95009, "服务端返回业务错误", false);

    /** 面向调用方的稳定状态码 */
    private final int code;
    /** 默认可读文案（具体异常通常自带更可操作的文案） */
    private final String message;
    /** 客户端侧是否值得重试：连不上/超时值得，参数错与凭证错不值得 */
    private final boolean retryable;

    ClientErrorCode(int code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 按数字码查找，未定义返回 {@code null} */
    public static ClientErrorCode of(int code) {
        for (ClientErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
