package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 域 → 知识体：把"这个域用哪套存储"翻译成检索计划能用的绑定。
 *
 * <p>三件事都在这里汇合，而它们各自的真相源不同：</p>
 * <ul>
 *   <li><b>域绑了哪个知识体</b> —— {@link DomainStore}（域数据，随域配置版本化）；</li>
 *   <li><b>知识体连的是哪台机器</b> —— {@link InfraSettingsHolder}（基础设施配置，管控台可改）；</li>
 *   <li><b>这一路召回多少条</b> —— yaml 的 {@code domainplane.knowledge.backends.<连接id>}。</li>
 * </ul>
 *
 * <p>三者都是<b>现取</b>：每次检索都重新解析，所以管控台改完绑定或知识体，下一次检索
 * 就按新配置走。这也意味着这里不做缓存——缓存的失效点会散落在三个地方（保存存储配置、
 * 保存域、yaml 热更新），漏一个就是"改了不生效"，而这里的解析成本只是几次 map 查表。</p>
 *
 * <p><b>不继承</b>：没绑知识体的域返回空列表，不去父域借。域链只决定"查得到谁的数据"，
 * 不决定"用谁的知识体"——借体会让子域的文档写进父域的库，且用户完全看不出来。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class KnowledgeBodyResolver implements RetrievalPlan.BodyLookup {

    private final InfraSettingsHolder infra;
    private final DomainStore domainStore;
    private final KnowledgeProperties knowledgeProperties;
    private final RetrievalProperties retrievalProperties;

    public KnowledgeBodyResolver(InfraSettingsHolder infra,
                                 DomainStore domainStore,
                                 KnowledgeProperties knowledgeProperties,
                                 RetrievalProperties retrievalProperties) {
        this.infra = infra;
        this.domainStore = domainStore;
        this.knowledgeProperties = knowledgeProperties;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 该域绑定的知识体开出的召回通道：<b>0 ~ 2 条</b>。
     *
     * <p>一个体里两路各可选，于是"这个域走几路"完全由体回答：两路都配 = 混合召回，
     * 只配一路 = 单路。逐槽判断而不是整体判断，是因为一路的连接被删时另一路还能用
     * （关键字体那台 ES 停了，向量路不该跟着整块消失）。</p>
     *
     * <p>顺序<b>向量在前、关键字在后</b>：与 {@code RetrievalPlan.fixed()} 一致。
     * 顺序不影响结果，但通道列表是排障第一眼看的，没必要因为重构换个样子。</p>
     */
    @Override
    public List<RetrievalPlan.BodyBinding> bodiesOf(String domain) {
        if (domain == null || domain.isBlank()) {
            return List.of();
        }
        String bodyId = domainStore.bodyOf(domain);
        if (bodyId == null || bodyId.isBlank()) {
            return List.of();
        }
        InfraSettings.KnowledgeBody body = infra.current().body(bodyId);
        if (body == null) {
            log.debug("[知识体] 域 {} 绑的知识体 {} 已不存在（可能刚被删），该域本轮不检索",
                    domain, bodyId);
            return List.of();
        }
        List<RetrievalPlan.BodyBinding> bindings = new ArrayList<>(2);
        addBinding(bindings, domain, bodyId, InfraSettings.KIND_VECTOR, body.vectorId());
        addBinding(bindings, domain, bodyId, InfraSettings.KIND_KEYWORD, body.keywordId());
        if (bindings.isEmpty()) {
            log.debug("[知识体] 域 {} 的知识体 {} 没有可用的一路（未配或连接已失效），该域本轮不检索",
                    domain, bodyId);
        }
        return bindings;
    }

    /** 该路配了且连接可用才开通道；配了但连接没了只留 debug，不整块失效 */
    private void addBinding(List<RetrievalPlan.BodyBinding> out, String domain, String bodyId,
                            String kind, String connectionId) {
        if (connectionId == null) {
            return;
        }
        if (infra.current().usableConnection(connectionId) == null) {
            log.debug("[知识体] 域 {} 的知识体 {} 的{}连接 {} 不可用，该路跳过",
                    domain, bodyId, InfraSettings.kindLabel(kind), connectionId);
            return;
        }
        out.add(new RetrievalPlan.BodyBinding(bodyId, kind, connectionId,
                topK(connectionId, InfraSettings.KIND_VECTOR.equals(kind))));
    }

    /**
     * 平台默认体：没有域上下文的场景（未绑域的通配检索、诊断、对账）用它。
     *
     * <p>取第一个「这一路配了、且那一路的连接可用」的知识体。顺序依赖 {@code LinkedHashMap}
     * 的插入顺序，也就是管控台列表里的顺序——这不是精确语义，只是"有个能用的就行"。</p>
     */
    @Override
    public RetrievalPlan.BodyBinding platformBody(String kind) {
        for (Map.Entry<String, InfraSettings.KnowledgeBody> entry
                : infra.current().bodiesOrEmpty().entrySet()) {
            InfraSettings.KnowledgeBody body = entry.getValue();
            if (body == null) {
                continue;
            }
            String connectionId = InfraSettings.KIND_KEYWORD.equals(kind)
                    ? body.keywordId() : body.vectorId();
            if (connectionId == null || infra.current().usableConnection(connectionId) == null) {
                continue;
            }
            return new RetrievalPlan.BodyBinding(entry.getKey(), kind, connectionId,
                    topK(connectionId, InfraSettings.KIND_VECTOR.equals(kind)));
        }
        return null;
    }

    /** 该域绑的知识体 id（页面展示 / 排障用）；没绑返回 {@code null} */
    public String bodyIdOf(String domain) {
        return domainStore.bodyOf(domain);
    }

    /**
     * 该路 topK：优先连接自己在 yaml 里声明的，其次默认后端声明的，最后回落到废弃兼容键。
     *
     * <p>与 {@code KnowledgeBackendsConfiguration} 装配期那套口径一致，只是挪到这里按
     * 连接现算——现在每个域可能用不同连接，装配期算一次已经不够用了。</p>
     */
    private int topK(String connectionId, boolean vector) {
        Integer declared = declaredTopK(connectionId, vector);
        if (declared != null) {
            return declared;
        }
        Integer fallback = declaredTopK(KnowledgeProperties.DEFAULT_BACKEND_ID, vector);
        if (fallback != null) {
            return fallback;
        }
        return vector ? retrievalProperties.getVectorTopK() : retrievalProperties.getKeywordTopK();
    }

    private Integer declaredTopK(String backendId, boolean vector) {
        Map<String, KnowledgeProperties.Backend> backends = knowledgeProperties.getBackends();
        if (backendId == null || backends == null) {
            return null;
        }
        KnowledgeProperties.Backend backend = backends.get(backendId);
        if (backend == null) {
            return null;
        }
        return vector
                ? (backend.getVector() == null ? null : backend.getVector().getTopK())
                : (backend.getKeyword() == null ? null : backend.getKeyword().getTopK());
    }
}
