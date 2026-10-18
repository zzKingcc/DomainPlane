package com.zzkingcc.domainplane.server.mcp;

/**
 * MCP server 返回认证挑战：HTTP 401 且带 {@code WWW-Authenticate} 头（RFC 6750 / RFC 9728）。
 *
 * 与"静态 token 配错"的普通 401 区分开：不带挑战头的 401 抛 {@link IllegalStateException}，
 * 调用方提示"去改请求头"；本异常表示 server 主动声明了认证方案，必须走 OAuth 授权
 * （{@code AdminMcpController} 的 oauth/start → 管理员浏览器登录 → 回调换 token）。
 *
 * @author zzkingcc
 */
public class McpAuthChallengeException extends RuntimeException {

    /** WWW-Authenticate 头的完整值，如 {@code Bearer resource_metadata="https://host/.well-known/oauth-protected-resource"} */
    private final String challenge;

    public McpAuthChallengeException(String challenge, String message) {
        super(message);
        this.challenge = challenge;
    }

    public String challenge() {
        return challenge;
    }
}
