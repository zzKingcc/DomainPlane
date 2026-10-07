package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.domain.capability.knowledge.RetrievalLimits;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code domainplane.retrieval.inject-top-n / max-context-chars} 注入检索结果注入侧的上限。
 *
 * 需要这一层是因为 {@code KnowledgeSearchService} 在 domain 模块、拿不到 server 的配置对象，
 * 而这两个值又要参与"给模型喂多少内容"的计算。与切片参数（{@code TxtChunkingConfiguration}）
 * 是同一套路。
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
public class RetrievalLimitsConfiguration {

    public RetrievalLimitsConfiguration(RetrievalProperties retrievalProperties) {
        RetrievalLimits.configure(retrievalProperties.getInjectTopN(), retrievalProperties.getMaxContextChars());
        log.info("[检索配置] 注入上限已就绪：inject-top-n={}，max-context-chars={}",
                RetrievalLimits.injectTopN(), RetrievalLimits.maxContextChars());
    }
}
