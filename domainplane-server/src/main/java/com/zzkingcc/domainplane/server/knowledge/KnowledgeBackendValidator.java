package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties.Strategy;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 知识库后端装配校验：召回策略要求的能力，后端必须 declare 得出来。
 *
 * <p><b>能力不满足必须是可行动的报错，不是运行期空结果</b>（设计第 6 节）：运行期空结果
 * 会让"配错了"和"知识库是空的"长得一模一样，用户面对空结果只会反复换问法，
 * 不会去查配置。校验矩阵：</p>
 *
 * <table border="1">
 *   <tr><th>strategy</th><th>必需能力</th></tr>
 *   <tr><td>{@code keyword}</td><td>keyword 后端 {@code capabilities.keyword}</td></tr>
 *   <tr><td>{@code vector}</td><td>vector 后端 {@code capabilities.vector} + EmbeddingModel 可用</td></tr>
 *   <tr><td>{@code hybrid}</td><td>两者 + EmbeddingModel</td></tr>
 * </table>
 *
 * <p>执行时机：<b>管控台保存存储配置时</b>（{@code AdminController#saveInfra}，保存即校验、
 * 失败作为 warning 返回不拒绝保存）——<b>启动期不再校验</b>：知识库没配 / 配错不该让
 * 服务起不来，管控台、对话、模型管理都不依赖它；第一次真的用到知识库时（检索 / 上传）
 * 由 writeStore / readStore 的 60007 给出带指引的错误。</p>
 *
 * <p>EmbeddingModel 的"可用"判的是 bean 在不在（向量 / 混合策略的装配期依赖），
 * 不是"配没配模型"：没配模型时 {@code embed()} 在运行期抛，走 A2 降级（本轮向量路
 * 降级、关键词路照常），那是运行期语义，不该在启动期把整个应用按死。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class KnowledgeBackendValidator {

    private final RetrievalPlan plan;

    /** 可空：bean 缺失本身就是 vector / hybrid 策略的失败原因 */
    private final EmbeddingModel embeddingModel;

    public KnowledgeBackendValidator(RetrievalPlan plan, EmbeddingModel embeddingModel) {
        this.plan = plan;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 校验装配。不满足抛 {@link KnowledgeBaseException}（60007），文案点名
     * "哪个后端缺哪种能力"——运维拿着这条消息就能定位到配置的哪一行。
     */
    public void validate() {
        if (plan.backendsEmpty()) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "未配置任何知识库后端（domainplane.knowledge.backends 为空）；"
                            + "至少配置一个后端，如 es-main: {type: elasticsearch}");
        }
        // 两个 id 都解析一遍：配置写错后端名要当场报出来（保存存储配置时即反馈），
        // 不能等第一次检索才给一个空结果
        KnowledgeBackend keyword = resolve(plan::keywordBackend, plan.keywordBackendId(), "keyword-backend");
        KnowledgeBackend vector = resolve(plan::vectorBackend, plan.vectorBackendId(), "vector-backend");

        Strategy strategy = plan.strategy();
        log.info("[知识库装配] 校验：strategy={}，keyword-backend={}，vector-backend={}",
                strategy, plan.keywordBackendId(), plan.vectorBackendId());

        switch (strategy) {
            case KEYWORD -> requireCapability(keyword, plan.keywordBackendId(),
                    StoreCapabilities::keyword, "keyword", strategy,
                    "keyword 策略没有更低的档，只能改填具备该能力的后端");
            case VECTOR -> {
                requireCapability(vector, plan.vectorBackendId(),
                        StoreCapabilities::vector, "vector", strategy,
                        "只能改填具备该能力的后端");
                requireEmbeddingModel(strategy);
            }
            case HYBRID -> {
                requireCapability(keyword, plan.keywordBackendId(),
                        StoreCapabilities::keyword, "keyword", strategy,
                        "或把 strategy 降级为 vector");
                requireCapability(vector, plan.vectorBackendId(),
                        StoreCapabilities::vector, "vector", strategy,
                        "或把 strategy 降级为 keyword");
                requireEmbeddingModel(strategy);
            }
        }
    }

    private KnowledgeBackend resolve(Supplier<KnowledgeBackend> resolver,
                                     String backendId, String role) {
        try {
            return resolver.get();
        } catch (IllegalArgumentException e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "domainplane.knowledge." + role + " 指向的后端 " + backendId
                            + " 未注册：" + e.getMessage(), e);
        }
    }

    private void requireCapability(KnowledgeBackend backend, String backendId,
                                   Predicate<StoreCapabilities> capability,
                                   String capabilityName, Strategy strategy, String remedy) {
        if (capability.test(backend.capabilities())) {
            return;
        }
        StoreCapabilities c = backend.capabilities();
        throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                "召回策略 " + strategy + " 要求后端 " + backendId + " 具备 " + capabilityName
                        + " 能力，但其能力声明为 keyword=" + c.keyword() + ", vector=" + c.vector()
                        + ", wildcard=" + c.wildcard() + ", storesText=" + c.storesText()
                        + ", reindexInPlace=" + c.reindexInPlace() + "；" + remedy);
    }

    private void requireEmbeddingModel(Strategy strategy) {
        if (embeddingModel == null) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "召回策略 " + strategy + " 需要 EmbeddingModel，但容器里没有该 bean"
                            + "（查询向量无处可算）；请补齐 embedding 模型装配，"
                            + "或将 strategy 改为 keyword");
        }
    }
}
