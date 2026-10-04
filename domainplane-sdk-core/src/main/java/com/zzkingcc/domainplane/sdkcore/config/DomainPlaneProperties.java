package com.zzkingcc.domainplane.sdkcore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * DomainPlane 消费侧<b>顶层</b>配置（{@code domainplane.*}）
 *
 * <p>按使用者意图组织，不按内部模块划分：</p>
 * <ul>
 *   <li>{@code server}：一个 URL 取代旧的 host + port，且 https 场景不必再解释 scheme；</li>
 *   <li>{@code username} / {@code password}：接入账号，与地址并列而非藏在 server 组里；</li>
 *   <li>{@code tools}：是否把本进程的 {@code @Tool} 注册给服务端。</li>
 * </ul>
 *
 * <p>对话 SDK、知识库 SDK 与工具 SDK 读的是同一份前缀：服务端只有一个账号、工具注册表也只有一份，
 * 因此不存在"对话连一个服务端、工具实例连另一个"的用法 —— 地址与账号只写一份。</p>
 *
 *
 * @author zzkingcc
 */
@ConfigurationProperties(prefix = "domainplane")
public class DomainPlaneProperties {

    /** 服务端地址（含协议与端口），如 {@code http://localhost:9527} */
    private String server = "http://localhost:9527";

    /** 接入账号（服务端账号，默认种子是 domainplane / domainplane） */
    private String username = "domainplane";

    /** 接入密码 */
    private String password = "domainplane";

    /**
     * 是否启用工具实例（默认 false：引了 jar 不等于要当工具提供方）。
     * 开启会在客户应用里起一个心跳线程并暴露一个 HTTP 端点，属于显式选择。
     */
    private boolean tools = false;

    /** 服务端 baseUrl（即 {@link #server}） */
    public String getServerUrl() {
        return server;
    }

    /**
     * 从 server URL 解析出的主机（供工具实例回调地址推导等复用）。
     * 解析失败时回落 {@code localhost}。
     */
    public String getHost() {
        try {
            String host = URI.create(server).getHost();
            return host != null ? host : "localhost";
        } catch (RuntimeException e) {
            return "localhost";
        }
    }

    /**
     * 从 server URL 解析出的端口。URL 未显式写端口时按协议取默认值（http=80 / https=443）；
     * 解析失败时回落 9527。
     */
    public int getPort() {
        try {
            URI uri = URI.create(server);
            if (uri.getPort() != -1) {
                return uri.getPort();
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        } catch (RuntimeException e) {
            return 9527;
        }
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isTools() {
        return tools;
    }

    public void setTools(boolean tools) {
        this.tools = tools;
    }
}
