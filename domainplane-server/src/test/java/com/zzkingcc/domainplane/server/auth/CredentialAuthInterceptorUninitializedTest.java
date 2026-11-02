package com.zzkingcc.domainplane.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.server.testsupport.FakeHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-05：服务端还没有任何账号时，不能"全放行"。
 *
 * <p>旧行为是打一条 warn 然后对所有受保护路径返回 true。问题在于：没有账号就没有任何
 * 可归属的主体，放行等于把编排、工具注册、知识库写入全部开放给任何能连上端口的人。
 * 现在只放行健康探测（客户端启动期的连通性校验），其余一律 409
 * {@code AUTH_NOT_INITIALIZED}。</p>
 *
 * <p>初始化入口（{@code /admin/init}、{@code /admin/session}、静态资源）在
 * {@code WebMvcConfig} 的 exclude 清单里，根本不进拦截器，所以收紧这里不会堵住引导路径。</p>
 *
 * @author zzkingcc
 */
@DisplayName("CredentialAuthInterceptor：未初始化时 fail-closed")
class CredentialAuthInterceptorUninitializedTest {

    /** 从未初始化过：文件不存在、也没有历史（首启且种子未落盘） */
    private static final class NeverInitialized extends AuthService {

        NeverInitialized() {
            super(null, null);
        }

        @Override
        public boolean isStoreCorrupted() {
            return false;
        }

        @Override
        public boolean isInitialized() {
            return false;
        }

        @Override
        public boolean hasEverBeenInitialized() {
            return false;
        }
    }

    private CredentialAuthInterceptor interceptor(CredentialAuthInterceptor.Carrier carrier) {
        return new CredentialAuthInterceptor(new NeverInitialized(), new ObjectMapper(), carrier);
    }

    @Test
    @DisplayName("未初始化 + 对话请求：拒绝（409 AUTH_NOT_INITIALIZED），不再放行")
    void chatRejectedWhenNeverInitialized() throws Exception {
        FakeHttp.Captured captured = new FakeHttp.Captured();

        boolean passed = interceptor(CredentialAuthInterceptor.Carrier.HEADER).preHandle(
                FakeHttp.request("POST", "/api/agent/chat", "10.0.0.9", Map.of()),
                FakeHttp.response(captured), new Object());

        assertFalse(passed, "没有账号就没有可归属的主体，不能放行");
        assertEquals(ErrorCode.AUTH_NOT_INITIALIZED.getHttpStatus(), captured.status());
        assertTrue(captured.body().contains("AUTH_NOT_INITIALIZED"),
                "响应体要带错误码名，实际：" + captured.body());
        assertTrue(captured.body().contains("首次初始化"),
                "文案要指向引导路径，实际：" + captured.body());
    }

    @Test
    @DisplayName("未初始化 + 工具注册：同样拒绝（这条旧行为等于开放工具注册）")
    void toolRegistrationRejectedWhenNeverInitialized() throws Exception {
        FakeHttp.Captured captured = new FakeHttp.Captured();

        boolean passed = interceptor(CredentialAuthInterceptor.Carrier.HEADER).preHandle(
                FakeHttp.request("POST", "/api/agent/tools/register", "10.0.0.9", Map.of()),
                FakeHttp.response(captured), new Object());

        assertFalse(passed);
        assertEquals(ErrorCode.AUTH_NOT_INITIALIZED.getHttpStatus(), captured.status());
    }

    @Test
    @DisplayName("未初始化 + 管控台接口：拒绝（引导入口走 exclude 清单，不经过这里）")
    void adminApiRejectedWhenNeverInitialized() throws Exception {
        FakeHttp.Captured captured = new FakeHttp.Captured();

        boolean passed = interceptor(CredentialAuthInterceptor.Carrier.COOKIE).preHandle(
                FakeHttp.request("POST", "/admin/domains", "10.0.0.9", Map.of()),
                FakeHttp.response(captured), new Object());

        assertFalse(passed);
        assertEquals(ErrorCode.AUTH_NOT_INITIALIZED.getHttpStatus(), captured.status());
    }

    @Test
    @DisplayName("未初始化 + 健康探测：放行（SDK 启动期连通性校验，无副作用）")
    void healthStillAllowedWhenNeverInitialized() throws Exception {
        assertTrue(interceptor(CredentialAuthInterceptor.Carrier.HEADER).preHandle(
                        FakeHttp.request("GET", "/api/agent/health", "10.0.0.9", Map.of()),
                        FakeHttp.response(new FakeHttp.Captured()), new Object()),
                "健康探测不需要任何权限");
    }

    @Test
    @DisplayName("OPTIONS 预检仍然无条件放行：拦下它浏览器就发不出真实请求")
    void preflightStillAllowed() throws Exception {
        assertTrue(interceptor(CredentialAuthInterceptor.Carrier.COOKIE).preHandle(
                FakeHttp.request("OPTIONS", "/admin/domains", "10.0.0.9", Map.of()),
                FakeHttp.response(new FakeHttp.Captured()), new Object()));
    }
}
