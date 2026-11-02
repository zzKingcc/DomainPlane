package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.server.auth.AuthException;
import com.zzkingcc.domainplane.server.auth.AuthService;
import com.zzkingcc.domainplane.server.auth.CredentialService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号与登录接口。
 * @author zzkingcc
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Cookie 作用域：整站。凭证要同时用于 /admin/** 页面与其接口 */
    private static final String COOKIE_PATH = "/";

    private final AuthService authService;

    /**
     * 是否允许远程（非本机）发起首次初始化。
     *
     * <p>默认 {@code false}：初始化入口免鉴权，放它公网可达等于"谁先到谁当管理员"。
     * 正常部署不需要它——种子账号首启就会落盘；这个入口只在账号文件被删、种子缺失的
     * 兜底场景下有用，而那时候人一定在机器旁边。确实要在远程初始化的部署可以打开
     * {@code domainplane.auth.allow-remote-init}。</p>
     */
    private final boolean allowRemoteInit;

    public AuthController(AuthService authService,
                          @Value("${domainplane.auth.allow-remote-init:false}") boolean allowRemoteInit) {
        this.authService = authService;
        this.allowRemoteInit = allowRemoteInit;
    }

    // ==================== 管控台 ====================

    /**
     * 首次初始化：设置管理员账号。
     *
     * <p>只接受本机发起的请求（见 {@link #allowRemoteInit}）：这个入口在账号不存在时免鉴权，
     * 若公网可达，任何先到的人都能把实例接管成自己的。</p>
     */
    @PostMapping("/admin/init")
    public Map<String, Object> init(@RequestBody Credentials body, HttpServletRequest request) {
        if (!allowRemoteInit && !isLocalRequest(request)) {
            log.warn("[账号] 拒绝来自 {} 的首次初始化请求（默认只允许本机；"
                    + "确需远程初始化请设 domainplane.auth.allow-remote-init=true）", request.getRemoteAddr());
            throw new AuthException(ErrorCode.PERMISSION_DENIED,
                    "首次初始化只允许在本机浏览器执行（服务端所在机器的 127.0.0.1）。"
                            + "若必须远程初始化，请由部署方开启 domainplane.auth.allow-remote-init");
        }
        if (body == null || body.getUsername() == null || body.getUsername().isBlank()) {
            throw new AuthException(ErrorCode.INVALID_PARAMETER, "账号不能为空");
        }
        authService.initialize(body.getUsername(), body.getPassword());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("success", true);
        result.put("message", "初始化完成，请使用新账号登录");
        return result;
    }

    /**
     * 管控台登录。
     */
    @PostMapping("/admin/login")
    public Map<String, Object> login(@RequestBody Credentials body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        if (body == null) {
            throw new AuthException(ErrorCode.INVALID_PARAMETER, "账号与密码必填");
        }
        String credential = authService.login(body.getUsername(), body.getPassword(), clientIp(request));
        writeCookie(response, credential);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("success", true);
        result.put("username", body.getUsername());
        result.put("credential", credential);
        // 提醒而非阻断：是否改密码由部署方决定
        result.put("defaultCredential", authService.isDefaultCredential());
        return result;
    }

    /** 登出：只清 Cookie。凭证本身不设 TTL、服务端无状态，故无需"作废"动作 */
    @PostMapping("/admin/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(CredentialService.CREDENTIAL_COOKIE, "");
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
        return Map.of("code", 0, "success", true);
    }

    /**
     * 当前登录态。
     */
    @GetMapping("/admin/session")
    public Map<String, Object> session(HttpServletRequest request) {
        return authService.sessionInfo(readCookie(request));
    }

    /**
     * 改密码（需登录 + 验证旧密码）。
     */
    @PostMapping("/admin/password")
    public Map<String, Object> changePassword(@RequestBody PasswordChange body,
                                              HttpServletResponse response) {
        if (body == null) {
            throw new AuthException(ErrorCode.INVALID_PARAMETER, "旧密码与新密码必填");
        }
        authService.changePassword(body.getOldPassword(), body.getNewPassword());

        // 顺手清掉当前 Cookie：反正它已经因密码变更而失效，留着只会让下一个请求白跑一次 401
        Cookie cookie = new Cookie(CredentialService.CREDENTIAL_COOKIE, "");
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("success", true);
        result.put("message", "密码已修改，全部旧登录态已失效，请重新登录");
        return result;
    }

    // ==================== 客户端接入 ====================

    /**
     * 客户端接入登录：用配置的账号密码换签名凭证（凭证在响应体里，不放 Cookie）
     */
    @PostMapping("/api/agent/login")
    public Map<String, Object> agentLogin(@RequestBody Credentials body, HttpServletRequest request) {
        if (body == null) {
            throw new AuthException(ErrorCode.INVALID_PARAMETER, "账号与密码必填");
        }
        String credential = authService.login(body.getUsername(), body.getPassword(), clientIp(request));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("success", true);
        result.put("username", body.getUsername());
        result.put("credential", credential);
        // 凭证不设过期时间，正常路径下 login 只在客户端启动时发生一次
        result.put("expiresAt", null);
        return result;
    }

    // ==================== 内部工具 ====================

    /** 凭证 Cookie：HttpOnly + SameSite=Lax；不设 Max-Age = 会话级（关浏览器即失效） */
    private void writeCookie(HttpServletResponse response, String credential) {
        Cookie cookie = new Cookie(CredentialService.CREDENTIAL_COOKIE, credential);
        cookie.setPath(COOKIE_PATH);
        cookie.setHttpOnly(true);
        // 不设 Secure：on-prem 常见 http 部署，设了会导致 Cookie 完全发不出去（登录看似成功却一直未登录）
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (CredentialService.CREDENTIAL_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** 取客户端 IP（只记日志，不做校验）；经反向代理时取 X-Forwarded-For 首个地址 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 请求是否来自本机。
     *
     * <p>先看连接对端：不是回环就一定不是本机，直接否——这一步不看任何请求头，
     * 伪造不了。再看代理链：反代部署时 {@code getRemoteAddr()} 常常是 127.0.0.1（代理自己），
     * 所以只要有 {@code X-Forwarded-For}，首跳也必须回环才算本机；
     * 这一步无论如何不会把"公网经代理进来"误判成本机。</p>
     */
    private static boolean isLocalRequest(HttpServletRequest request) {
        if (!isLoopbackLiteral(request.getRemoteAddr())) {
            return false;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return true;
        }
        int comma = forwarded.indexOf(',');
        String firstHop = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        return isLoopbackLiteral(firstHop);
    }

    private static boolean isLoopbackLiteral(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String normalized = address.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("127.")
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    /** 登录请求体 */
    @Data
    public static class Credentials {
        private String username;
        private String password;
    }

    /** 改密码请求体 */
    @Data
    public static class PasswordChange {
        private String oldPassword;
        private String newPassword;
    }
}
