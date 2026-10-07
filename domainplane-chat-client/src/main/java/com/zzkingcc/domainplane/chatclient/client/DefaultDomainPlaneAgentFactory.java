package com.zzkingcc.domainplane.chatclient.client;

import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgent;
import com.zzkingcc.domainplane.api.agent.DomainPlaneAgentFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DomainPlaneAgentFactory} 的默认实现：按域缓存门面，同一个域永远拿到同一个实例。
 *
 * 缓存键是归一化后的域名 —— {@code null} / 空白 / {@code " default "} 都落到同一个
 * 门面，不会因为写法不同而造出多个等价门面。门面本身无状态，缓存它只是为了省去重复 new。
 *
 * @author zzkingcc
 */
public class DefaultDomainPlaneAgentFactory implements DomainPlaneAgentFactory {

    private final AgentService transport;
    private final Map<String, DomainPlaneAgent> agents = new ConcurrentHashMap<>();
    private final Duration answerTimeout;

    public DefaultDomainPlaneAgentFactory(AgentService transport) {
        this(transport, null);
    }

    /**
     * @param answerTimeout {@code ask} 等待整轮答案的上限；{@code null} / 非法值走默认
     */
    public DefaultDomainPlaneAgentFactory(AgentService transport, Duration answerTimeout) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.answerTimeout = answerTimeout;
    }

    @Override
    public DomainPlaneAgent forDomain(String domainId) {
        String normalized = Domains.normalize(domainId);
        return agents.computeIfAbsent(normalized,
                domain -> new DefaultDomainPlaneAgent(transport, domain, answerTimeout));
    }
}
