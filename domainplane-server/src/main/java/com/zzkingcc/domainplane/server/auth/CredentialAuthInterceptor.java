package com.zzkingcc.domainplane.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.runtime.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.server.audit.AuditLog;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 凭证校验拦截器。
 * @author zzkingcc
 */
public class CredentialAuthInterceptor implements HandlerInterceptor {

    /** 凭证载具 */
    public enum Carrier {
        /** 请求头（客户端接入） */
        HEADER,
        /** HttpOnly Cookie（管控台） */
        COOKIE
    }

    private static final Logger log = LoggerFactory.getLogger(CredentialAuthInterceptor.class);

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final Carrier carrier;

    /** "无账号，已放行"只提示一次，避免每请求刷屏 */
    private final AtomicBoolean uninitializedWarned = new AtomicBoolean(false);

    public CredentialAuthInterceptor(AuthService authService, ObjectMapper objectMapper, Carrier carrier) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.carrier = carrier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 跨域预检（OPTIONS）无条件放行：浏览器发真实请求前先探路，这一步不带任何凭证，
        // 拦下它会让浏览器直接判跨域失败，真实请求根本发不出来。预检本身也无副作用。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (authService.isStoreCorrupted()) {
            log.error("[鉴权] 账号文件存在但无法解析，拒绝请求：{} {}", request.getMethod(), request.getRequestURI());
            reject(response, ErrorCode.AUTH_STORE_CORRUPTED,
                    "账号文件存在但无法解析，服务端拒绝一切受保护请求：请修复或删除该文件后重启服务");
            return false;
        }

        if (!authService.isInitialized()) {
            if (uninitializedWarned.compareAndSet(false, true)) {
                log.warn("[鉴权] 服务端尚无账号，本次按【不鉴权】放行（{}）。"
                        + "请打开管控台完成初始化后即自动生效", request.getRequestURI());
            }
            return true;
        }

        String credential = resolve(request);
        if (authService.verify(credential)) {
            return true;
        }

        boolean absent = credential == null || credential.isBlank();
        log.warn("[鉴权] 拒绝请求：{} {}，原因={}",
                request.getMethod(), request.getRequestURI(),
                absent ? "未携带凭证" : "凭证无效（可能已被改密码失效）");
        reject(response, ErrorCode.AUTH_REQUIRED,
                absent ? "未登录：请先登录以获取凭证" : "凭证已失效：服务端可能修改过密码，请重新登录");
        return false;
    }

    /**
     * 请求计数与审计——只在这一处做，避免每个 Controller 各写一遍。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if (uri.startsWith("/api/agent/")) {
            MetricsRegistry.request(uri);
        }

        if (isMutating(method) && (uri.startsWith("/admin/") || uri.startsWith("/api/agent/tools/"))) {
            AuditLog.record(method + " " + uri, operator(request), String.valueOf(response.getStatus()));
        }
    }

    /** GET/HEAD/OPTIONS 之外的都算变更类请求 */
    private static boolean isMutating(String method) {
        return !("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
    }

    /**
     * 审计里的"操作者"。
     */
    private String operator(HttpServletRequest request) {
        try {
            if (!authService.isInitialized()) {
                return "未初始化";
            }
            Object name = authService.sessionInfo(resolve(request)).get("username");
            return name == null ? "未知" : name.toString();
        } catch (Exception e) {
            return "未知";
        }
    }

    private String resolve(HttpServletRequest request) {
        if (carrier == Carrier.HEADER) {
            return request.getHeader(CredentialService.CREDENTIAL_HEADER);
        }
        // 管控台：优先 Cookie（HttpOnly，JS 读不到，降低 XSS 偷凭证的面）。
        // Cookie 缺失时回退读凭证头——程序化调用方（starter 的上传方法、ETL 脚本）
        // 走的是"用户名密码换凭证"，拿不到登录 Cookie，只能带头。
        // 两种载具共用同一套签发与校验逻辑，回退不扩大权限面。
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (CredentialService.CREDENTIAL_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return request.getHeader(CredentialService.CREDENTIAL_HEADER);
    }

    /** 只含状态码与可读原因，不回显任何凭证内容 */
    private void reject(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.getCode());
        body.put("codeName", code.name());
        body.put("error", code.getMessage());
        body.put("detail", detail);
        body.put("retryable", code.isRetryable());
        body.put("action", code.getAction());
        body.put("traceId", TraceId.currentOrNew());
        body.put("timestamp", System.currentTimeMillis());

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
