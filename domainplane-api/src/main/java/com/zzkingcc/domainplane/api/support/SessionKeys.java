package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;

/**
 * 会话状态键：把<b>域</b>编进所有"按会话索引"的状态里。
 *
 * <p>记忆、图检查点、取消登记、流注册都按会话索引，若只用裸 {@code sessionId}，同一个 sessionId
 * 在两个域下会互相踩：A 域的会话能 resume 出 B 域的断点，记忆与流注册互相顶掉，
 * "同会话串行"也会误伤另一个域。</p>
 *
 * <p>键 = {@code 域 + '|' + sessionId} 之后，隔离由键本身保证，无需再维护一张
 * "这个会话属于哪个域"的进程内映射表（那种表重启即丢，丢了校验就静默失效）。</p>
 *
 * <p>分隔符 {@code |} 不会出现在域路径里（单段只允许 {@code [A-Za-z0-9_-]}）；
 * sessionId 由调用方给出，因此入口会校验它不含该字符。</p>
 *
 * @author zzkingcc
 */
public final class SessionKeys {

    /** 域与 sessionId 的分隔符 */
    public static final char SEPARATOR = '|';

    private SessionKeys() {
    }

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
     *
     * <p>不含分隔符时原样返回 —— 这样历史上按裸 sessionId 存的检查点仍能被识别出会话标识
     * （虽然它的域信息已经无从得知，域隔离升级后也读不出来）。</p>
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
