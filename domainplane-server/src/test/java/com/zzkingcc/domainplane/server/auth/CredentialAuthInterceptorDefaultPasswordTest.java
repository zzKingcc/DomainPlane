package com.zzkingcc.domainplane.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #13：种子默认口令 {@code domainplane/domainplane} 只提示不阻断，等于把实例拱手让人。
 *
 * <p>口令写在仓库里就是公开的。修复后的口径：Cookie 载具（管控台）在管理员仍在使用
 * 默认口令时，拒绝一切变更类请求（403 + {@code AUTH_DEFAULT_CREDENTIAL}），
 * 只留读取类与 {@code /admin/password}：页面打得开、改密入口通得了。</p>
 *
 * <p>API 载具（请求头）不拦：那是程序化调用，改密不在它的职责里，
 * 在此拦下只会让存量客户端集体报错，而口令本就是公开的，安全收益为零。</p>
 *
 * @author zzkingcc
 */
@DisplayName("CredentialAuthInterceptor：默认口令未改时封死管控台变更类请求")
class CredentialAuthInterceptorDefaultPasswordTest {

    /** 只实现本测试用得到的几个方法，其余一律不支持 */
    private static final class FakeRequest implements HttpServletRequest {
        private final String method;
        private final String uri;
        private final String header;
        private final Cookie[] cookies;

        FakeRequest(String method, String uri, String header, Cookie... cookies) {
            this.method = method;
            this.uri = uri;
            this.header = header;
            this.cookies = cookies;
        }

        @Override public String getMethod() { return method; }
        @Override public String getRequestURI() { return uri; }
        @Override public StringBuffer getRequestURL() { return new StringBuffer("https://domainplane.test" + uri); }
        @Override public String getHeader(String name) {
            return CredentialService.CREDENTIAL_HEADER.equals(name) ? header : null;
        }
        @Override public Cookie[] getCookies() { return cookies; }

        @Override public jakarta.servlet.ServletContext getServletContext() { throw new UnsupportedOperationException(); }
        @Override public String getAuthType() { throw new UnsupportedOperationException(); }
        @Override public String getContextPath() { throw new UnsupportedOperationException(); }
        @Override public String getQueryString() { throw new UnsupportedOperationException(); }
        @Override public String getPathInfo() { throw new UnsupportedOperationException(); }
        @Override public String getPathTranslated() { throw new UnsupportedOperationException(); }
        @Override public String getRemoteUser() { throw new UnsupportedOperationException(); }
        @Override public boolean isUserInRole(String role) { throw new UnsupportedOperationException(); }
        @Override public java.security.Principal getUserPrincipal() { throw new UnsupportedOperationException(); }
        @Override public String getRequestedSessionId() { throw new UnsupportedOperationException(); }
        @Override public String getServletPath() { throw new UnsupportedOperationException(); }
        @Override public String getScheme() { throw new UnsupportedOperationException(); }
        @Override public String getProtocol() { throw new UnsupportedOperationException(); }
        @Override public String getServerName() { throw new UnsupportedOperationException(); }
        @Override public int getServerPort() { throw new UnsupportedOperationException(); }
        @Override public String getRemoteAddr() { throw new UnsupportedOperationException(); }
        @Override public String getLocalAddr() { throw new UnsupportedOperationException(); }
        @Override public String getRemoteHost() { throw new UnsupportedOperationException(); }
        @Override public Object getAttribute(String name) { throw new UnsupportedOperationException(); }
        @Override public void setAttribute(String name, Object o) { throw new UnsupportedOperationException(); }
        @Override public void removeAttribute(String name) { throw new UnsupportedOperationException(); }
        @Override public Enumeration<String> getAttributeNames() { throw new UnsupportedOperationException(); }
        @Override public String getCharacterEncoding() { throw new UnsupportedOperationException(); }
        @Override public void setCharacterEncoding(String env) { throw new UnsupportedOperationException(); }
        @Override public int getContentLength() { throw new UnsupportedOperationException(); }
        @Override public long getContentLengthLong() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { throw new UnsupportedOperationException(); }
        @Override public ServletInputStream getInputStream() { throw new UnsupportedOperationException(); }
        @Override public String getParameter(String name) { throw new UnsupportedOperationException(); }
        @Override public String[] getParameterValues(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Map<String, String[]> getParameterMap() { throw new UnsupportedOperationException(); }
        @Override public Enumeration<String> getParameterNames() { throw new UnsupportedOperationException(); }
        @Override public Enumeration<Locale> getLocales() { throw new UnsupportedOperationException(); }
        @Override public Locale getLocale() { throw new UnsupportedOperationException(); }
        @Override public boolean isSecure() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.http.HttpSession getSession(boolean create) { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.http.HttpSession getSession() { throw new UnsupportedOperationException(); }
        @Override public String changeSessionId() { throw new UnsupportedOperationException(); }
        @Override public boolean isRequestedSessionIdValid() { throw new UnsupportedOperationException(); }
        @Override public boolean isRequestedSessionIdFromCookie() { throw new UnsupportedOperationException(); }
        @Override public boolean isRequestedSessionIdFromURL() { throw new UnsupportedOperationException(); }
        @Override public boolean authenticate(jakarta.servlet.http.HttpServletResponse response) { throw new UnsupportedOperationException(); }
        @Override public void login(String username, String password) { throw new UnsupportedOperationException(); }
        @Override public void logout() { throw new UnsupportedOperationException(); }
        @Override public Collection<jakarta.servlet.http.Part> getParts() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.http.Part getPart(String name) { throw new UnsupportedOperationException(); }
        @Override public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { throw new UnsupportedOperationException(); }
        @Override public long getDateHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public int getIntHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public Enumeration<String> getHeaders(String name) { throw new UnsupportedOperationException(); }
        @Override public Enumeration<String> getHeaderNames() { throw new UnsupportedOperationException(); }
        @Override public Map<String, String> getTrailerFields() { throw new UnsupportedOperationException(); }
        @Override public boolean isTrailerFieldsReady() { throw new UnsupportedOperationException(); }
        @Override public String getProtocolRequestId() { throw new UnsupportedOperationException(); }
        @Override public String getLocalName() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.ServletConnection getServletConnection() { throw new UnsupportedOperationException(); }
        @Override public int getLocalPort() { throw new UnsupportedOperationException(); }
        @Override public int getRemotePort() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.AsyncContext startAsync() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest req,
                                                                jakarta.servlet.ServletResponse res) {
            throw new UnsupportedOperationException();
        }
        @Override public jakarta.servlet.AsyncContext getAsyncContext() { throw new UnsupportedOperationException(); }
        @Override public boolean isAsyncStarted() { throw new UnsupportedOperationException(); }
        @Override public boolean isAsyncSupported() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.DispatcherType getDispatcherType() { throw new UnsupportedOperationException(); }
        @Override public String getRequestId() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) { throw new UnsupportedOperationException(); }
        @Override public BufferedReader getReader() { return new BufferedReader(new StringReader("")); }
    }

    /** 记录状态码与响应体 */
    private static final class FakeResponse implements HttpServletResponse {
        private int status;
        private String contentType;
        private final StringWriter body = new StringWriter();

        @Override public void setStatus(int sc) { this.status = sc; }
        @Override public int getStatus() { return status; }
        @Override public void setContentType(String type) { this.contentType = type; }
        @Override public String getContentType() { return contentType; }
        @Override public Locale getLocale() { throw new UnsupportedOperationException(); }
        @Override public void setLocale(Locale loc) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getWriter() { return new PrintWriter(body); }
        String bodyText() { return body.toString(); }

        @Override public void addCookie(Cookie cookie) { throw new UnsupportedOperationException(); }
        @Override public boolean containsHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public String encodeURL(String url) { throw new UnsupportedOperationException(); }
        @Override public String encodeRedirectURL(String url) { throw new UnsupportedOperationException(); }
        @Override public void sendError(int sc, String msg) { throw new UnsupportedOperationException(); }
        @Override public void sendError(int sc) { throw new UnsupportedOperationException(); }
        @Override public void sendRedirect(String location) { throw new UnsupportedOperationException(); }
        @Override public void setDateHeader(String name, long date) { throw new UnsupportedOperationException(); }
        @Override public void addDateHeader(String name, long date) { throw new UnsupportedOperationException(); }
        @Override public void setHeader(String name, String value) { throw new UnsupportedOperationException(); }
        @Override public void addHeader(String name, String value) { throw new UnsupportedOperationException(); }
        @Override public void setIntHeader(String name, int value) { throw new UnsupportedOperationException(); }
        @Override public void addIntHeader(String name, int value) { throw new UnsupportedOperationException(); }
        @Override public String getHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Collection<String> getHeaders(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Collection<String> getHeaderNames() { throw new UnsupportedOperationException(); }
        @Override public void setCharacterEncoding(String charset) { throw new UnsupportedOperationException(); }
        @Override public String getCharacterEncoding() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.ServletOutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public void setContentLength(int len) { throw new UnsupportedOperationException(); }
        @Override public void setContentLengthLong(long len) { throw new UnsupportedOperationException(); }
        @Override public void setBufferSize(int size) { throw new UnsupportedOperationException(); }
        @Override public int getBufferSize() { throw new UnsupportedOperationException(); }
        @Override public void flushBuffer() { throw new UnsupportedOperationException(); }
        @Override public void resetBuffer() { throw new UnsupportedOperationException(); }
        @Override public boolean isCommitted() { throw new UnsupportedOperationException(); }
        @Override public void reset() { throw new UnsupportedOperationException(); }
    }

    /** AuthService 桩：账号已初始化、凭证有效、仍是默认口令 */
    private static final class StubAuthService extends AuthService {
        private final boolean defaultCredential;
        private final boolean corrupted;
        private final boolean initialized;

        StubAuthService(boolean defaultCredential, boolean corrupted, boolean initialized) {
            super(null, null);
            this.defaultCredential = defaultCredential;
            this.corrupted = corrupted;
            this.initialized = initialized;
        }

        @Override public boolean isStoreCorrupted() { return corrupted; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public boolean hasEverBeenInitialized() { return initialized; }
        @Override public boolean verify(String credential) { return credential != null; }
        @Override public boolean isDefaultCredential() { return defaultCredential; }
        @Override public java.util.Map<String, Object> sessionInfo(String credential) {
            return java.util.Map.of("username", "admin");
        }
    }

    private CredentialAuthInterceptor cookieInterceptor(boolean defaultCredential) {
        return new CredentialAuthInterceptor(
                new StubAuthService(defaultCredential, false, true),
                new ObjectMapper(), CredentialAuthInterceptor.Carrier.COOKIE);
    }

    private CredentialAuthInterceptor headerInterceptor(boolean defaultCredential) {
        return new CredentialAuthInterceptor(
                new StubAuthService(defaultCredential, false, true),
                new ObjectMapper(), CredentialAuthInterceptor.Carrier.HEADER);
    }

    @Test
    @DisplayName("默认口令 + 变更类请求：403 AUTH_DEFAULT_CREDENTIAL")
    void mutatingRequestRejectedWhileDefaultCredential() throws Exception {
        CredentialAuthInterceptor interceptor = cookieInterceptor(true);
        FakeResponse resp = new FakeResponse();

        boolean passed = interceptor.preHandle(
                new FakeRequest("POST", "/admin/domains", "token",
                        new Cookie(CredentialService.CREDENTIAL_COOKIE, "token")),
                resp, new Object());

        assertFalse(passed, "默认口令下不许改配置");
        assertEquals(ErrorCode.AUTH_DEFAULT_CREDENTIAL.getHttpStatus(), resp.getStatus());
        assertTrue(resp.bodyText().contains("AUTH_DEFAULT_CREDENTIAL"),
                "响应体要带错误码名，实际：" + resp.bodyText());
        assertTrue(resp.bodyText().contains("初始默认口令"),
                "文案要指向改密，实际：" + resp.bodyText());
    }

    @Test
    @DisplayName("默认口令 + 读取类请求：放行，管控台才打得开")
    void readOnlyRequestAllowedWhileDefaultCredential() throws Exception {
        CredentialAuthInterceptor interceptor = cookieInterceptor(true);

        assertTrue(interceptor.preHandle(
                new FakeRequest("GET", "/admin/settings", "token",
                        new Cookie(CredentialService.CREDENTIAL_COOKIE, "token")),
                new FakeResponse(), new Object()), "读取不受影响");
    }

    @Test
    @DisplayName("默认口令 + 改密接口：放行，处置路径不能自己堵住")
    void passwordChangeAllowedWhileDefaultCredential() throws Exception {
        CredentialAuthInterceptor interceptor = cookieInterceptor(true);

        assertTrue(interceptor.preHandle(
                new FakeRequest("PUT", "/admin/password", "token",
                        new Cookie(CredentialService.CREDENTIAL_COOKIE, "token")),
                new FakeResponse(), new Object()), "改密必须可达");
    }

    @Test
    @DisplayName("改过口令后变更类请求恢复放行")
    void mutatingRequestAllowedAfterPasswordChanged() throws Exception {
        CredentialAuthInterceptor interceptor = cookieInterceptor(false);

        assertTrue(interceptor.preHandle(
                new FakeRequest("POST", "/admin/domains", "token",
                        new Cookie(CredentialService.CREDENTIAL_COOKIE, "token")),
                new FakeResponse(), new Object()), "已改口令就恢复正常");
    }

    @Test
    @DisplayName("API 载具（请求头）不受默认口令限制：程序化调用没有改密职责")
    void headerCarrierNotRestrictedByDefaultCredential() throws Exception {
        CredentialAuthInterceptor interceptor = headerInterceptor(true);

        assertTrue(interceptor.preHandle(
                new FakeRequest("POST", "/api/agent/chat", "token"),
                new FakeResponse(), new Object()),
                "在此拦下只会让存量客户端集体报错，口令本就是公开的");
    }

    @Test
    @DisplayName("OPTIONS 预检无条件放行：不带凭证，拦了浏览器就发不出真实请求")
    void preflightAlwaysAllowed() throws Exception {
        assertTrue(cookieInterceptor(true).preHandle(
                new FakeRequest("OPTIONS", "/admin/domains", null),
                new FakeResponse(), new Object()));
    }
}
