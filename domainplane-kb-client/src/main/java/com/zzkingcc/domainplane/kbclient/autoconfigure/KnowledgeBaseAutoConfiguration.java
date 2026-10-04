package com.zzkingcc.domainplane.kbclient.autoconfigure;

import com.zzkingcc.domainplane.clientcore.autoconfigure.DomainPlaneClientAutoConfiguration;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.kbclient.KnowledgeBaseClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 知识库 SDK 自动装配。
 *
 * <p>只多一个 Bean：{@code domainplaneKnowledgeBaseClient}。WebClient / 凭证 / 启动探测
 * 由 {@link DomainPlaneClientAutoConfiguration} 提供 —— 引不引对话 SDK 都一样，因此
 * 单独引本模块也能开箱即用。</p>
 *
 * @author zzkingcc
 */
@AutoConfiguration(after = DomainPlaneClientAutoConfiguration.class)
public class KnowledgeBaseAutoConfiguration {

    /**
     * 知识库客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    public KnowledgeBaseClient domainplaneKnowledgeBaseClient(WebClient domainplaneWebClient,
                                                           ClientProperties properties,
                                                           ClientCredential domainplaneClientCredential) {
        return new KnowledgeBaseClient(domainplaneWebClient, properties, domainplaneClientCredential);
    }
}
