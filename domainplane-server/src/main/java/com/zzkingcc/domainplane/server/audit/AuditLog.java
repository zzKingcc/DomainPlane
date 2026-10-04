package com.zzkingcc.domainplane.server.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 管理操作审计。
 * @author zzkingcc
 */
public final class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private AuditLog() {}

    /**
     * @param action   动作，形如 {@code POST /admin/settings}
     * @param operator 操作者账号（单账号体系下即配置里的那个账号）
     * @param result   结果，形如 HTTP 状态码
     */
    public static void record(String action, String operator, String result) {
        // 固定字段顺序与 key=value 形式：采集侧按空格/等号切分即可解析，不必写正则
        audit.info("action={} operator={} result={} ip={}", action, operator, result, clientIp());
    }

    private static String clientIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                HttpServletRequest request = attributes.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    // 取第一段：代理链的原始客户端
                    return forwarded.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception ignored) {
            // 审计是旁路，取不到来源不影响请求本身
        }
        return "unknown";
    }
}
