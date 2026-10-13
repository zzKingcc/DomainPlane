package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.server.model.DefaultModelResolver;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelClientFactory;
import com.zzkingcc.domainplane.server.model.ModelEndpointClient;
import com.zzkingcc.domainplane.server.model.ModelMetadataReader;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * 模型档案装配：档案注册表 → 客户端工厂 → 解析器。
 *
 * 解析器以 {@link ModelResolver} 契约暴露给内核（{@code domainplane-runtime}），
 * 内核不感知档案、绑定这些概念。
 *
 * @author zzkingcc
 */
@Configuration
public class ModelProfileConfiguration {

    /**
     * 档案注册表：档案与「域 → 档案」绑定的唯一真相。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelProfileRegistry modelProfileRegistry(ModelProfileStore store) {
        return new ModelProfileRegistry(store);
    }

    /**
     * 服务商适配注册表：{@code classpath:adapters/*.json} 的加载与匹配权威。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelAdapterRegistry modelAdapterRegistry(ResourceLoader resourceLoader) {
        return new ModelAdapterRegistry(resourceLoader);
    }

    /**
     * 模型元数据读取器：读服务商 {@code GET /models} 清单，定端点族与输入 / 输出模态。
     *
     * <p>只发 GET、不发推理请求，因此零 token：这也是它取代"发最小请求试探"的原因。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelMetadataReader modelMetadataReader() {
        return new ModelMetadataReader();
    }

    /**
     * 客户端工厂：按档案指纹构建并缓存模型客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelClientFactory modelClientFactory(ModelAdapterRegistry adapterRegistry) {
        return new ModelClientFactory(adapterRegistry);
    }

    /**
     * 多模态端点客户端：生图 / 重排 / 语音合成 / 语音识别四族。
     *
     * <p>这四族 langchain4j 没有抽象，由它直接走 JDK HttpClient。无状态、每次请求现建连接，
     * 单例即可（管控台的端点联通性测试与业务侧共用同一个）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelEndpointClient modelEndpointClient() {
        return new ModelEndpointClient();
    }

    /**
     * 模型解析器：对话模型只来自用户自建的模型档案，没有内置 default。
     *
     * <p>返回具体类型 {@link DefaultModelResolver} 而不是 {@link ModelResolver}：能力族解析
     * （生图 / 语音 / 视频，{@code endpointModel}）不在内核契约里（内核不认识这些族），
     * 只有服务端的工具要用它。按具体类暴露后，工具可直接注入，同时内核按
     * {@link ModelResolver} 接口注入仍然命中同一个 bean。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ModelResolver.class)
    public DefaultModelResolver modelResolver(ModelProfileRegistry registry,
                                              ModelClientFactory factory) {
        return new DefaultModelResolver(registry, factory);
    }
}
