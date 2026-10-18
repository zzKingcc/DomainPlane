package com.zzkingcc.domainplane.server.mcp;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 授权会话暂存：state → 会话（连接名 + code_verifier + clientId + tokenEndpoint）。
 *
 * 授权流程跨两次 HTTP 请求（start 生成 state/verifier → 浏览器回来带 code+state），
 * 中间态必须暂存。平台单实例部署，放内存即可；重启丢失只影响"授权到一半"的流程，
 * 重新点「授权」即可，不丢任何持久化状态。
 *
 * state 一次性消费（consume 即删），防重放；5 分钟过期，purge 在每次 create 时顺带做。
 *
 * @author zzkingcc
 */
@Component
public class McpOAuthSessionStore {

    private static final long TTL_MS = 5 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Session(String connectionName, String state, String codeVerifier,
                          String clientId, String redirectUri, String scope,
                          String tokenEndpoint, long expiresAtMs) {

        boolean expired(long now) {
            return now > expiresAtMs;
        }
    }

    private final Map<String, Session> byState = new ConcurrentHashMap<>();

    /** 生成并暂存一个授权会话，返回带 state 的会话 */
    public Session create(String connectionName, String codeVerifier, String clientId,
                          String redirectUri, String scope, String tokenEndpoint) {
        purgeExpired();
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        Session session = new Session(connectionName, sb.toString(), codeVerifier, clientId,
                redirectUri, scope, tokenEndpoint, System.currentTimeMillis() + TTL_MS);
        byState.put(session.state(), session);
        return session;
    }

    /** 一次性消费：取出并删除；不存在 / 已过期返回 null */
    public Session consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        Session session = byState.remove(state);
        if (session == null || session.expired(System.currentTimeMillis())) {
            return null;
        }
        return session;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        byState.values().removeIf(s -> s.expired(now));
    }
}
