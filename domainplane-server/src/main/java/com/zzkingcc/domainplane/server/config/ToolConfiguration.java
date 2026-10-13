package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.DomainPlaneToolProvider;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.AnnotatedToolScanner;
import com.zzkingcc.domainplane.runtime.tool.ToolExecutorService;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry.Registered;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 工具装配：把服务端内部的工具注册进内核。
 * @author zzkingcc
 */
@Configuration
public class ToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolConfiguration.class);

    /**
     * 工具注册表：扫描所有工具
     *
     * 两侧写法统一：方法上有 {@code @Tool} 就算工具，不要求类实现
     * {@link DomainPlaneToolProvider}：该接口只是可选标记（实现了照样被扫到，
     * 不实现也一样），与工具实例 SDK（{@code domainplane-tool-instance}）的扫描规则一致。
     * 同一段工具代码在服务端进程与业务进程之间搬迁，不用改一个字。
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ObjectProvider<DomainPlaneToolProvider> providers,
                                     ListableBeanFactory beanFactory,
                                     DomainRegistry domainRegistry) {
        // 注入域注册表：工具声明的域会在注册时沿链派生进域树
        ToolRegistry registry = new ToolRegistry(domainRegistry);
        // 按实例身份去重：实现了 DomainPlaneToolProvider 的 Bean 会同时命中两轮扫描，
        // 而 ToolRegistry 对重名是直接抛异常的
        Set<Object> scanned = Collections.newSetFromMap(new IdentityHashMap<>());

        int providerCount = 0;
        for (DomainPlaneToolProvider provider : providers) {
            scanned.add(provider);
            providerCount++;
            registry.registerAll(AnnotatedToolScanner.scan(provider));
        }

        int beanCount = 0;
        for (String beanName : beanFactory.getBeanNamesForType(Object.class, true, false)) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null || isFramework(type)) {
                continue;
            }
            // 先看类型再决定是否实例化：不为扫描而提前初始化整个容器
            if (!hasAnnotatedMethod(ClassUtils.getUserClass(type))) {
                continue;
            }
            Object bean = beanFactory.getBean(beanName);
            if (!scanned.add(bean)) {
                continue;
            }
            List<Registered> registered = AnnotatedToolScanner.scan(bean);
            if (registered.isEmpty()) {
                // 类型上有注解、实例上扫不到：几乎只有被代理（且注解没留在代理方法上）时会发生
                log.warn("[工具装配] {} 上有 @Tool 方法但从实例上扫不到（可能被代理），已跳过。"
                        + "如需注册，请让该类实现 DomainPlaneToolProvider 或改用类代理", type.getName());
                continue;
            }
            registry.registerAll(registered);
            beanCount++;
        }

        log.info("[工具装配] 扫描到 {} 个工具提供者、{} 个带注解的 Bean，注册 {} 个工具；需授权工具 {} 个",
                providerCount, beanCount, registry.size(), registry.toolsRequiringApproval());
        if (registry.isEmpty()) {
            // "一个工具都还没提供"是合法初始态（服务端本身不带示例工具），按约定只做状态陈述，不用 WARN：
            // 它既不是故障也不影响启动，WARN 只会让每次冷启动都像是出了问题。
            log.info("[工具装配] 未注册任何工具 —— 在任意 Spring Bean 的方法上标注 @Tool 即可注册；"
                            + "此时可用的域只有根域 {}（工具声明留空、调用未指定域都落到它）；"
                            + "其余域需由工具声明或在管控台创建，否则入口报 10004",
                    Domains.DEFAULT);
        }
        return registry;
    }

    /** 跳过框架自身的 Bean：扫它们只有噪音，且可能触发不该触发的初始化 */
    private static boolean isFramework(Class<?> type) {
        String name = type.getName();
        return name.startsWith("org.springframework.") || name.startsWith("java.");
    }

    /** 类（含其接口）上是否存在 @Tool 方法 */
    private static boolean hasAnnotatedMethod(Class<?> userClass) {
        for (Method method : userClass.getMethods()) {
            if (method.getAnnotation(Tool.class) != null) {
                return true;
            }
        }
        for (Class<?> itf : ClassUtils.getAllInterfacesForClass(userClass)) {
            for (Method method : itf.getMethods()) {
                if (method.getAnnotation(Tool.class) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 工具路由器（内核唯一的能力调用入口）。
     *
     * 注入域注册表：域有两个来源（注册表登记的内置/人工域、工具声明派生的域），
     * 判定与展示都需要合并两者。
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolRouter toolRouter(ToolRegistry toolRegistry, DomainRegistry domainRegistry,
                                 ToolExecutorService toolExecutorService) {
        return new ToolRouter(toolRegistry, domainRegistry, toolExecutorService);
    }

    /**
     * 本地工具执行隔离层：有界池 + 超时上限，让卡死的工具不会占住编排线程。
     *
     * 容量取编排池上限（{@code domainplane.agent.max-pool-size}）的同量级而不是同一份配置：
     * 两者服务的是不同资源（编排池是会话并发，工具池是单次调用并发），
     * 但工具池必须至少能同时承接编排池能发出的调用数，否则正常负载下就会被自己的池拒。
     * 队列为零：满即拒，理由见 {@link ToolExecutorService#create}。
     *
     * {@code destroyMethod = "close"}：Spring 关闭时 {@code shutdownNow}，
     * 不留非守护线程挂着进程。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ToolExecutorService toolExecutorService(
            AgentProperties agentProperties,
            @Value("${domainplane.tool.invoke-timeout-ms:30000}") long timeoutMs) {
        int threads = Math.max(8, agentProperties.getMaxPoolSize());
        ToolExecutorService service = ToolExecutorService.create(threads, timeoutMs);
        log.info("[工具路由] 本地工具执行隔离池 {} 线程（零队列，满即拒），单次调用上限 {}ms",
                threads, service.timeoutMs());
        return service;
    }
}
