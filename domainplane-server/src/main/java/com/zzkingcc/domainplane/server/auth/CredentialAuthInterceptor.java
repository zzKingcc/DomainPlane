package com.zzkingcc.domainplane.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
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

    /**
     * 服务端还没有任何账号时仍可访问的路径。
     *
     * <p>只有健康探测：客户端 SDK 用它做启动期连通性校验，不需要任何权限、也不产生副作用。
     * 其余接口在这一态下一律拒绝——"没有账号"不等于"谁都可以用"。</p>
     */
    private static final java.util.Set<String> UNINITIALIZED_PUBLIC_PATHS = java.util.Set.of(
            "/api/agent/health");

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
            if (authService.hasEverBeenInitialized()) {
                /* 曾经可读、现在读不到了（文件被删 / 卷被卸载 / 权限被改）。
                   这种状态绝不能按"未初始化"放行：初始化入口是免检的，放行等于允许
                   任何人在此状态下重建管理员账号、直接接管实例。宁可全站不可用。 */
                log.error("[鉴权] 账号文件在运行期消失，拒绝请求：{} {}",
                        request.getMethod(), request.getRequestURI());
                reject(response, ErrorCode.AUTH_STORE_CORRUPTED,
                        "账号文件在运行期消失（本实例曾完成初始化，现已不可读）。"
                                + "为避免任何人在此状态下重建账号接管实例，服务端拒绝一切请求："
                                + "请恢复账号文件后重启服务");
                return false;
            }
            /* 从未初始化（首启且种子未落盘成功）。
               这一态同样不能"全放行"：没有账号就没有任何可归属的主体，放行等于把编排、
               工具注册、知识库写入全部开放给任何能连上端口的人。只留健康探测——
               它是客户端启动期的连通性校验，不需要任何权限。
               初始化入口（/admin/init、/admin/session、静态资源）走的是 exclude 清单，
               根本不会进到这个拦截器里，因此收紧这里不会堵住引导路径。 */
            if (UNINITIALIZED_PUBLIC_PATHS.contains(request.getRequestURI())) {
                if (uninitializedWarned.compareAndSet(false, true)) {
                    log.warn("[鉴权] 服务端尚无账号，仅放行健康探测（{}）。"
                            + "请打开管控台（本机）完成首次初始化", request.getRequestURI());
                }
                return true;
            }
            log.error("[鉴权] 服务端尚无账号，拒绝请求：{} {}", request.getMethod(), request.getRequestURI());
            reject(response, ErrorCode.AUTH_NOT_INITIALIZED,
                    "服务端还没有账号：请在本机打开管控台完成首次初始化后再调用接口");
            return false;
        }

        String credential = resolve(request);
        if (authService.verify(credential)) {
            if (mustChangeDefaultCredential(request)) {
                log.warn("[鉴权] 管理员仍在使用初始默认口令，拒绝变更类请求：{} {}",
                        request.getMethod(), request.getRequestURI());
                reject(response, ErrorCode.AUTH_DEFAULT_CREDENTIAL,
                        "服务端仍在使用初始默认口令。请先在管控台修改管理员密码，"
                                + "修改后变更类操作自动恢复（读取类请求不受影响）");
                return false;
            }
            /* 变更类管理请求先把操作者放进请求属性：控制器要写"具体动了什么"的补充审计
               （试跑了哪个工具、撤销了哪条授权），而 Cookie / 凭证头的解析逻辑只应有一份。
               只对变更类请求解析，读取类请求不承担这次会话查找的开销。 */
            if (isMutating(request.getMethod()) && request.getRequestURI().startsWith("/admin/")) {
                request.setAttribute(AuditLog.OPERATOR_ATTR, operator(request));
            }
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
     * 初始默认口令未修改时，只放行"读取 + 改密"两类请求。
     *
     * 种子口令写在仓库里，等于公开密码。只提示不阻断时，任何能访问到端口的人
     * 都能用它登录并改配置：这属于部署方还没做任何决定就已经被接管。
     * 但不整体封死：读取类请求留着，管控台才能正常打开并弹出改密入口；
     * 改密接口必须可达，否则处置路径被自己堵住。
     *
     * @return true = 本次请求应被拒绝
     */
    private boolean mustChangeDefaultCredential(HttpServletRequest request) {
        // 只约束管控台（Cookie 载具）。API 载具是程序化调用，改密不在它的职责范围内，
        // 在此拦下只会让存量客户端在升级后集体报错，而安全收益为零，口令本就是公开的。
        if (carrier != Carrier.COOKIE) {
            return false;
        }
        if (!isMutating(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/admin/password")) {
            return false;
        }
        return authService.isDefaultCredential();
    }

    /**
     * 请求计数与审计：只在这一处做，避免每个 Controller 各写一遍。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if (uri.startsWith("/api/agent/")) {
            MetricsRegistry.request(uri);
        }

        if (isMutating(method) && (uri.startsWith("/admin/") || uri.startsWith("/api/agent/tools/"))) {
            // preHandle 已经解析过的直接复用，省掉一次会话查找
            Object resolved = request.getAttribute(AuditLog.OPERATOR_ATTR);
            String operator = resolved == null ? operator(request) : String.valueOf(resolved);
            AuditLog.record(method + " " + uri, operator, String.valueOf(response.getStatus()));
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
        // Cookie 缺失时回退读凭证头：程序化调用方（starter 的上传方法、ETL 脚本）
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
