package com.zzkingcc.domainplane.server.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Elasticsearch 配置类（内置适配es）。
 *
 * @author zzkingcc
 */
@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class EsClientConfiguration {

    /**
     * 平台私有 ES 通信层（委派代理，可热替换）。
     */
    @Bean(name = "domainplaneElasticsearchTransport", destroyMethod = "close")
    public SwappableElasticsearchTransport domainplaneElasticsearchTransport(InfraSettingsHolder infraHolder) {
        return new SwappableElasticsearchTransport(infraHolder);
    }

    /**
     * 平台私有 ES 解析封装客户端（懒加载：不随启动建立连接，首次使用时才创建）。
     */
    @Bean(name = "domainplaneElasticsearchClient")
    @Lazy
    public ElasticsearchClient domainplaneElasticsearchClient(
            @Qualifier("domainplaneElasticsearchTransport") SwappableElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
