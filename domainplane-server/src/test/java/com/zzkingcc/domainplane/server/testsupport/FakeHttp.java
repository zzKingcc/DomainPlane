package com.zzkingcc.domainplane.server.testsupport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * 极简 HTTP 请求/响应替身。
 *
 * <p>本项目没有引入 spring-test（无 {@code MockHttpServletRequest}），而拦截器与控制器测试
 * 只需要三五个方法。用动态代理按需实现比手写 80 个空方法可读得多：调用了没实现的
 * 方法会直接抛 {@link UnsupportedOperationException}，不会静默返回 null 掩盖问题。</p>
 *
 * @author zzkingcc
 */
public final class FakeHttp {

    private FakeHttp() {
    }

    /**
     * @param headers 需要读取的请求头；为 null 视为没有任何头
     */
    public static HttpServletRequest request(String method, String uri, String remoteAddr,
                                             Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getMethod":
                            return method;
                        case "getRequestURI":
                            return uri;
                        case "getRemoteAddr":
                            return remoteAddr;
                        case "getHeader":
                            return headers == null ? null : headers.get((String) args[0]);
                        case "getCookies":
                            return null;
                        case "toString":
                            return "FakeRequest[" + method + " " + uri + "]";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
    }

    /** 记录状态码 / Content-Type / 响应体的响应对象 */
    public static final class Captured {

        private int status = 200;
        private String contentType;
        private final StringWriter body = new StringWriter();
        private final PrintWriter writer = new PrintWriter(body, true);

        public int status() {
            return status;
        }

        public String contentType() {
            return contentType;
        }

        public String body() {
            return body.toString();
        }
    }

    public static HttpServletResponse response(Captured captured) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "setStatus":
                            captured.status = (Integer) args[0];
                            return null;
                        case "getStatus":
                            return captured.status;
                        case "setContentType":
                            captured.contentType = (String) args[0];
                            return null;
                        case "getContentType":
                            return captured.contentType;
                        case "getWriter":
                            return captured.writer;
                        case "toString":
                            return "FakeResponse[" + captured.status + "]";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
    }
}
