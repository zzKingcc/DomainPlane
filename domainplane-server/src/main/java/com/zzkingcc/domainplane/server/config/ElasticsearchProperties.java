package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 存储配置模型
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.elasticsearch")
public class ElasticsearchProperties {

    /** 服务端主机地址（IP 或域名）。为空 = 未配置（启动期跳过灌库，可到管控台补填） */
    private String host;

    /** 服务端 HTTP 端口 */
    private int port = 9200;

    /** 连接协议 */
    private String scheme = "http";

    /** 连接用户名，未启用安全认证时可为 null */
    private String username;

    /** 连接密码，未启用安全认证时可为 null */
    private String password;

    /** 连接超时（毫秒） */
    private int connectTimeout = 5000;

    /** 读写超时（毫秒） */
    private int socketTimeout = 10000;

}
