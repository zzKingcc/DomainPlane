package com.zzkingcc.domainplane.domain.rag.store;

import java.util.List;

/**
 * 后端注册表：配置里的后端 id → {@link KnowledgeBackend}。
 *
 * 由 server 装配期（{@code KnowledgeBackendsConfiguration}）从
 * {@code domainplane.knowledge.backends} 构建；检索计划（{@code RetrievalPlan}）
 * 与写入侧（{@code KnowledgeBaseService}）都只认它，不直接依赖具体适配器。
 *
 * 多后端下的取值源纪律（设计 13，一审修订 B4）：向量维度只从 vector 后端读
 * （keyword-only 后端没有维度概念，遍历全部后端会把第一个有值的当全局值）；
 * "有哪些文档"以 keyword 后端为权威——"有哪些文档"是登记视角，不是检索视角。
 *
 * @author zzkingcc
 */
public interface KnowledgeBackendRegistry {

    /**
     * 按 id 取后端。
     *
     * @throws IllegalArgumentException 未注册：信息里列出全部可用 id，fail-fast。
     *         配置写错后端名必须在启动 / 保存时就炸，不能等到第一次检索才空指针
     */
    KnowledgeBackend require(String backendId);

    /** 全部已注册后端（注册序）。 */
    List<KnowledgeBackend> all();

    /** 是否一个后端都没有（未配置 / 装配失败）。 */
    boolean isEmpty();
}
