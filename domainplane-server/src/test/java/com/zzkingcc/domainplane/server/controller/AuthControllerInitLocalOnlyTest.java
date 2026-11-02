package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.server.auth.AuthException;
import com.zzkingcc.domainplane.server.auth.AuthService;
import com.zzkingcc.domainplane.server.testsupport.FakeHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-05：首次初始化只允许本机发起。
 *
 * <p>{@code /admin/init} 在账号不存在时免鉴权。放它公网可达，等于"谁先到谁当管理员"。
 * 这里钉死三条：公网直连拒、经反代进来的公网请求也拒（{@code X-Forwarded-For} 不能当免死金牌）、
 * 本机放行。</p>
 *
 * @author zzkingcc
 */
@DisplayName("AuthController：/admin/init 只接受本机请求")
class AuthControllerInitLocalOnlyTest {

    /** 只记录是否真的执行了初始化 */
    private static final class RecordingAuthService extends AuthService {

        private boolean initialized;

        RecordingAuthService() {
            super(null, null);
        }

        @Override
        public com.zzkingcc.domainplane.server.auth.AccountSettings initialize(String username, String password) {
            this.initialized = true;
            return null;
        }
    }

    private static AuthController.Credentials body() {
        AuthController.Credentials credentials = new AuthController.Credentials();
        credentials.setUsername("admin");
        credentials.setPassword("s3cret-pass");
        return credentials;
    }

    @Test
    @DisplayName("公网直连：403 PERMISSION_DENIED，且不会真的建号")
    void remoteInitRejected() {
        RecordingAuthService auth = new RecordingAuthService();
        AuthController controller = new AuthController(auth, false);

        AuthException error = assertThrows(AuthException.class, () -> controller.init(
                body(), FakeHttp.request("POST", "/admin/init", "203.0.113.7", Map.of())));

        assertEquals(ErrorCode.PERMISSION_DENIED, error.getErrorCode());
        assertFalse(auth.initialized, "被拒的请求不得产生任何账号");
    }

    @Test
    @DisplayName("经反向代理进来的公网请求：连接是 127.0.0.1 也要拒（看 X-Forwarded-For 首跳）")
    void proxiedRemoteInitRejected() {
        RecordingAuthService auth = new RecordingAuthService();
        AuthController controller = new AuthController(auth, false);

        assertThrows(AuthException.class, () -> controller.init(body(),
                FakeHttp.request("POST", "/admin/init", "127.0.0.1",
                        Map.of("X-Forwarded-For", "203.0.113.7, 127.0.0.1"))));
        assertFalse(auth.initialized);
    }

    @Test
    @DisplayName("本机直连：放行")
    void localInitAllowed() {
        RecordingAuthService auth = new RecordingAuthService();
        AuthController controller = new AuthController(auth, false);

        Map<String, Object> result = controller.init(body(),
                FakeHttp.request("POST", "/admin/init", "127.0.0.1", Map.of()));

        assertEquals(0, result.get("code"));
        assertTrue(auth.initialized, "本机请求应真的执行初始化");
    }

    @Test
    @DisplayName("IPv6 回环同样算本机")
    void ipv6LoopbackAllowed() {
        RecordingAuthService auth = new RecordingAuthService();
        AuthController controller = new AuthController(auth, false);

        controller.init(body(), FakeHttp.request("POST", "/admin/init", "0:0:0:0:0:0:0:1", Map.of()));

        assertTrue(auth.initialized);
    }

    @Test
    @DisplayName("显式打开 allow-remote-init 时远程可初始化（部署方的选择，不是默认）")
    void remoteInitAllowedWhenExplicitlyEnabled() {
        RecordingAuthService auth = new RecordingAuthService();
        AuthController controller = new AuthController(auth, true);

        controller.init(body(), FakeHttp.request("POST", "/admin/init", "203.0.113.7", Map.of()));

        assertTrue(auth.initialized);
    }
}
