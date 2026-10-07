package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;

/**
 * 会话状态键：把域编进所有"按会话索引"的状态里。
 *
 * @author zzkingcc
 */
public final class SessionKeys {

    /** 域与 sessionId 的分隔符 */
    public static final char SEPARATOR = '|';

    private SessionKeys() {}

    /**
     * 拼出会话状态键。
     *
     * @param domain    域（{@code null} / 空白 → 根域）
     * @param sessionId 调用方给出的会话标识，不得为空、不得含 {@link #SEPARATOR}
     * @throws IllegalArgumentException sessionId 非法（调用方应先用 {@link #validateSessionId} 给出友好报错）
     */
    public static String of(String domain, String sessionId) {
        return Domains.normalize(domain) + SEPARATOR + require(sessionId);
    }

    /**
     * 校验 sessionId 是否可用。
     *
     * @return {@code null} = 合法；否则返回原因
     */
    public static String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "sessionId 不能为空";
        }
        if (sessionId.indexOf(SEPARATOR) >= 0) {
            return "sessionId 不能包含 '" + SEPARATOR + "'（它被用作状态键的分隔符）";
        }
        return null;
    }

    /** 状态键里的域部分；不是本类拼出来的键（历史数据）返回 {@code null} */
    public static String domainOf(String key) {
        if (key == null) {
            return null;
        }
        int at = key.indexOf(SEPARATOR);
        return at < 0 ? null : key.substring(0, at);
    }

    /**
     * 状态键里的 sessionId 部分。
     */
    public static String sessionIdOf(String key) {
        if (key == null) {
            return null;
        }
        int at = key.indexOf(SEPARATOR);
        return at < 0 ? key : key.substring(at + 1);
    }

    private static String require(String sessionId) {
        String reason = validateSessionId(sessionId);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        return sessionId;
    }
}
