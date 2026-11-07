package com.zzkingcc.domainplane.server.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 管理操作审计。
 *
 * <p>只写 AUDIT 日志：完整历史以日志为准。内存环曾作为查询副本供概览
 * 「最近管理操作」展示，该组件已下架，环一并移除——无消费方的缓存
 * 与死代码无异。</p>
 *
 * @author zzkingcc
 */
public final class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    /**
     * 请求属性名：鉴权拦截器在 preHandle 里把解析好的操作者放进 request，
     * 控制器写补充审计（如"哪个工具被试跑过"）时直接取，不必各自再解析一遍 Cookie / 凭证头。
     */
    public static final String OPERATOR_ATTR = "domainplane.audit.operator";

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

    /**
     * 补充审计：操作者取 {@link #OPERATOR_ATTR}（缺省 unknown）。
     *
     * <p>拦截器已经为每个变更类请求记了一行"方法 + URI + 操作者"，那行看不到请求体。
     * 需要记清"具体动了什么"（试跑了哪个工具、撤销了哪条授权）的动作，用这个重载再记一行。</p>
     */
    public static void record(String action, String result) {
        record(action, currentOperator(), result);
    }

    /** 当前请求的操作者；不在请求线程里（定时任务等）返回 unknown */
    public static String currentOperator() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                Object v = attributes.getRequest().getAttribute(OPERATOR_ATTR);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            }
        } catch (Exception ignored) {
            // 审计是旁路
        }
        return "unknown";
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
