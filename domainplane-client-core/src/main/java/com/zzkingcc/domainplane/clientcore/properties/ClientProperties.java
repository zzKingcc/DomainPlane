package com.zzkingcc.domainplane.clientcore.properties;

import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 客户端调用行为配置（{@code domainplane.client.*}）
 *
 * 只管"怎么调"，不管"调哪里"——服务端地址与账号在 {@link DomainPlaneProperties}。
 *
 * 对话 SDK 与知识库 SDK 读的是同一份：两者连的是同一个服务端、共用一个凭证，
 * 不存在"对话超时 10 分钟、知识库超时 5 秒"这类分头配置的用法。
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.client")
public class ClientProperties {

    /**
     * 换取访问凭证（首次业务调用时）的超时时间，默认 5s。
     *
     * 启动期不再做连通性探测 —— 服务端未启动不阻断接入方启动，远端不可达由首次业务调用暴露。
     * 因此本项现在只管登录这一次请求，不再有"启动探测超时"的第二个用途。
     */
    private Duration healthCheckTimeout = Duration.ofSeconds(5);

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 响应读取超时（SSE 为长连接，可适当放宽；0 表示不超时） */
    private Duration readTimeout = Duration.ofMinutes(10);

    /**
     * {@code ask} 等待整轮答案的上限（默认 30m）。
     *
     * 与 {@link #readTimeout} 是两件事：readTimeout 管"两个数据帧之间隔多久算断"，
     * 本项管"整轮从发出到收到终止事件最多等多久"。后者此前无界 ——
     * 服务端完全不响应时调用线程会永久阻塞。
     */
    private Duration answerTimeout = Duration.ofMinutes(30);
}
