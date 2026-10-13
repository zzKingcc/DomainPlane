package com.zzkingcc.domainplane.chatclient.autoconfigure;

import com.zzkingcc.domainplane.chatclient.client.AgentServiceClient;
import com.zzkingcc.domainplane.chatclient.client.DefaultDomainPlaneAgentFactory;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgentFactory;
import com.zzkingcc.domainplane.clientcore.autoconfigure.DomainPlaneClientAutoConfiguration;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * DomainPlane 对话 SDK 自动装配。
 *
 * 本模块只多一个 Bean：{@code domainplaneAgentFactory}。WebClient 与凭证
 * 由 {@link DomainPlaneClientAutoConfiguration} 提供：这些与"引了哪个 SDK"无关，
 * 知识库 SDK 引的是同一份，两个 SDK 同时在场也只会装配一次。
 *
 * 本模块不做启动期连通性校验：服务端未启动不阻断接入方启动，
 * 远端不可达由首次 {@code chat} / {@code stream} 调用暴露成带码的 {@code DomainPlaneException}。
 *
 * @author zzkingcc
 */
@AutoConfiguration(after = DomainPlaneClientAutoConfiguration.class)
public class ChatAutoConfiguration {

    /**
     * 对话的唯一入口：域门面工厂。
     *
     * {@code forDomain("customer")} 拿到绑定域的门面，之后 {@code ask} / {@code stream} /
     * {@code events} / {@code resume} / {@code stop} 都不必再提域：域是接线动作，不是每次调用都要记得传的参数。
     *
     * 底层远程通道（{@code AgentServiceClient}）由这里内部持有，不单独暴露 Bean：
     * 对外只留这一个入口。要看工具调用 / 审批中断 / 错误码用 {@code agent.events(...)}。
     */
    @Bean
    @ConditionalOnMissingBean
    public DomainPlaneAgentFactory domainplaneAgentFactory(WebClient domainplaneWebClient,
                                                    DomainPlaneProperties server,
                                                    ClientCredential domainplaneClientCredential,
                                                    ClientProperties clientProperties) {
        return new DefaultDomainPlaneAgentFactory(
                new AgentServiceClient(domainplaneWebClient, server, domainplaneClientCredential),
                clientProperties.getAnswerTimeout());
    }
}
