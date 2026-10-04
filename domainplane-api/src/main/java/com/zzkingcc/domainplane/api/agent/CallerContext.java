package com.zzkingcc.domainplane.api.agent;

/**
 * 调用方身份 —— 域 / 租户 / 用户 不可变三元组。
 *
 * @author zzkingcc
 * @param profile  本轮所处的域
 * @param tenantId 租户标识（optional）
 * @param userId   用户标识（optional）
 */
public record CallerContext(String profile, String tenantId, String userId) {

    /** 只带域的快捷构造 */
    public static CallerContext of(String profile) {
        return new CallerContext(profile, null, null);
    }

    public static CallerContext of(String profile, String tenantId, String userId) {
        return new CallerContext(profile, tenantId, userId);
    }

    /**
     * 从请求中提取身份。请求为 {@code null} 时返回 {@code null}——入口层必须自己做必填校验。
     */
    public static CallerContext from(AgentRequest request) {
        if (request == null) {
            return null;
        }
        return new CallerContext(request.getProfile(), request.getTenantId(), request.getUserId());
    }

    /** 域名（去空白）；未指定时返回 {@code null} */
    public String normalizedProfile() {
        return profile == null || profile.isBlank() ? null : profile.trim();
    }
}
