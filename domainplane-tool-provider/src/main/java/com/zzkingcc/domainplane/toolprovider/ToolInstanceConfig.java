package com.zzkingcc.domainplane.toolprovider;

/**
 * 工具实例的接入参数（不可变，链式 {@code with*} 返回新实例）
 *
 * @author zzkingcc
 * @param serverHost             DomainPlane 服务端主机（主机名或 IP，不含协议与端口，如 {@code 10.0.0.9}）
 * @param serverPort             DomainPlane 服务端端口（服务端固定 9527）
 * @param username                接入账号（服务端账号，默认种子是 {@code domainplane/domainplane}）
 * @param password                接入密码
 * @param instanceId              实例标识；<b>重连必须沿用同一个</b>，否则会留下一个摘不掉的旧副本
 * @param endpoint                本实例的<b>工具调用回流地址</b>（服务端 POST 到它来执行工具），
 *                                必须是服务端能访问到的地址；由配置层解析（显式配置优先，留空则按本进程端口推导）
 * @param heartbeatIntervalSeconds 心跳周期（秒）。服务端判死窗默认 35s = 它的 7 倍
 * @param maxBackoffSeconds       心跳连续失败时的退避上限（秒）。默认 20s，<b>必须小于判死窗</b>
 *                                （否则实例在被判死前都来不及重连）
 * @param requestTimeoutMillis    单次 HTTP 超时（毫秒）
 */
public record ToolInstanceConfig(String serverHost,
                                 int serverPort,
                                 String username,
                                 String password,
                                 String instanceId,
                                 String endpoint,
                                 int heartbeatIntervalSeconds,
                                 int maxBackoffSeconds,
                                 int requestTimeoutMillis) {

    public static final int DEFAULT_HEARTBEAT_SECONDS = 5;
    public static final int DEFAULT_MAX_BACKOFF_SECONDS = 20;
    public static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 10000;

    public ToolInstanceConfig {
        serverHost = required(serverHost, "serverHost");
        if (serverPort <= 0) {
            throw new IllegalArgumentException("工具实例缺少必填配置: serverPort（必须为正端口）");
        }
        username = required(username, "username");
        password = required(password, "password");
        instanceId = required(instanceId, "instanceId");
        endpoint = required(endpoint, "endpoint");
        heartbeatIntervalSeconds = heartbeatIntervalSeconds <= 0
                ? DEFAULT_HEARTBEAT_SECONDS : heartbeatIntervalSeconds;
        maxBackoffSeconds = maxBackoffSeconds <= 0 ? DEFAULT_MAX_BACKOFF_SECONDS : maxBackoffSeconds;
        requestTimeoutMillis = requestTimeoutMillis <= 0
                ? DEFAULT_REQUEST_TIMEOUT_MILLIS : requestTimeoutMillis;
    }

    /** 由 {@link #serverHost} 与 {@link #serverPort} 拼出的服务端 baseUrl（{@code http://host:port}） */
    public String serverUrl() {
        return "http://" + serverHost + ":" + serverPort;
    }

    public static ToolInstanceConfig of(String serverHost, int serverPort, String username, String password,
                                        String instanceId, String endpoint) {
        return new ToolInstanceConfig(serverHost, serverPort, username, password, instanceId, endpoint,
                DEFAULT_HEARTBEAT_SECONDS, DEFAULT_MAX_BACKOFF_SECONDS, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public ToolInstanceConfig withHeartbeatIntervalSeconds(int seconds) {
        return new ToolInstanceConfig(serverHost, serverPort, username, password, instanceId, endpoint,
                seconds, maxBackoffSeconds, requestTimeoutMillis);
    }

    public ToolInstanceConfig withMaxBackoffSeconds(int seconds) {
        return new ToolInstanceConfig(serverHost, serverPort, username, password, instanceId, endpoint,
                heartbeatIntervalSeconds, seconds, requestTimeoutMillis);
    }

    public ToolInstanceConfig withRequestTimeoutMillis(int millis) {
        return new ToolInstanceConfig(serverHost, serverPort, username, password, instanceId, endpoint,
                heartbeatIntervalSeconds, maxBackoffSeconds, millis);
    }

    /** 工具注册 / 心跳端点（注册与心跳合一，只有一个端点） */
    public String registerUrl() {
        return serverUrl() + "/api/agent/tools/register";
    }

    /** 换凭证端点（免鉴权，用账号密码换签名凭证） */
    public String loginUrl() {
        return serverUrl() + "/api/agent/login";
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("工具实例缺少必填配置: " + field);
        }
        return value.trim();
    }
}
