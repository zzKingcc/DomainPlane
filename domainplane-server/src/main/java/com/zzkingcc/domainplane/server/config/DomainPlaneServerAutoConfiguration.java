package com.zzkingcc.domainplane.server.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * DomainPlane 服务端总装自动配置。
 * @author zzkingcc
 */
@AutoConfiguration
@ComponentScan({
        "com.zzkingcc.domainplane.runtime.cancellation",
        "com.zzkingcc.domainplane.domain.capability.knowledge",
        "com.zzkingcc.domainplane.server.tools",
        "com.zzkingcc.domainplane.server.support"
})
@Import({
        ConfigValidationConfiguration.class,
        AiModelConfiguration.class,
        RedisClientConfiguration.class,
        MemoryConfiguration.class,
        EsClientConfiguration.class,
        RetrievalConfiguration.class,
        ToolConfiguration.class,
        InstanceConfiguration.class,
        GraphConfiguration.class
})
public class DomainPlaneServerAutoConfiguration {
}
