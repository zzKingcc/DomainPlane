package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识体解析：域 ↔ 知识体的唯一换算处，也是"以知识体为中心"的管理链路入口。
 *
 * <p>三件事在这里汇合，真相源各不相同：</p>
 * <ul>
 *   <li><b>域绑了哪个知识体</b> —— {@link DomainStore}（域数据，随域配置版本化）；</li>
 *   <li><b>知识体连的是哪台机器、索引叫什么</b> —— {@link InfraSettingsHolder}（管控台可改）；</li>
 *   <li><b>这一路召回多少条</b> —— yaml 的 {@code domainplane.knowledge.backends.<连接id>}。</li>
 * </ul>
 *
 * <p>三者都是<b>现取</b>：每次检索都重新解析，所以管控台改完绑定或知识体，下一次检索
 * 就按新配置走；这里不做缓存（失效点会散落在三处，漏一个就是"改了不生效"）。</p>
 *
 * <p><b>不继承</b>：没绑知识体的域返回空列表，不去父域借。域链只决定"查得到谁的数据"，
 * 不决定"用谁的知识体"——借体会让子域的文档写进父域的库，且用户完全看不出来。</p>
 *
 * <p><b>共用是常态</b>：一个体可以被多个域绑，那些域查的是同一个索引——这正是"共用不麻烦"
 * 的实现。检索计划按 {@code locationId} 去重，所以共用不会把同一条通道查两遍。</p>
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

    @Override
    public List<RetrievalPlan.BodyBinding> bodiesOf(String domain) {
        String bodyId = bodyIdOf(domain);
        return bodyId == null ? List.of() : bodiesOfBody(bodyId);
    }

    /**
     * 某个知识体开出的召回通道：<b>0 ~ 2 条</b>。
     *
     * <p>一个体里两路各可选，于是"这个体走几路"完全由体回答：两路都配 = 混合召回，
     * 只配一路 = 单路。逐槽判断而不是整体判断，是因为一路的连接被删时另一路还能用
     * （关键字体那台 ES 停了，向量路不该跟着整块消失）。</p>
     *
     * <p>顺序<b>向量在前、关键字在后</b>：与 {@code RetrievalPlan.fixed()} 一致。
     * 顺序不影响结果，但通道列表是排障第一眼看的，没必要因为重构换个样子。</p>
     */
    @Override
    public List<RetrievalPlan.BodyBinding> bodiesOfBody(String bodyId) {
        InfraSettings.KnowledgeBody body = body(bodyId);
        if (body == null) {
            log.debug("[知识体] 知识体 {} 已不存在（可能刚被删），本轮不检索", bodyId);
            return List.of();
        }
        String index = body.effectiveIndex();
        List<RetrievalPlan.BodyBinding> bindings = new ArrayList<>(2);
        addBinding(bindings, bodyId, index, InfraSettings.KIND_VECTOR, body.vectorId());
        addBinding(bindings, bodyId, index, InfraSettings.KIND_KEYWORD, body.keywordId());
        if (bindings.isEmpty()) {
            log.debug("[知识体] 知识体 {} 没有可用的一路（未配或连接已失效），本轮不检索", bodyId);
        }
        return bindings;
    }

    /** 该路配了且连接可用才开通道；配了但连接没了只留 debug，不整块失效 */
    private void addBinding(List<RetrievalPlan.BodyBinding> out, String bodyId, String index,
                            String kind, String connectionId) {
        if (connectionId == null) {
            return;
        }
        if (infra.current().usableConnection(connectionId) == null) {
            log.debug("[知识体] 知识体 {} 的{}连接 {} 不可用，该路跳过",
                    bodyId, InfraSettings.kindLabel(kind), connectionId);
            return;
        }
        out.add(new RetrievalPlan.BodyBinding(bodyId, kind, connectionId,
                topK(connectionId, InfraSettings.KIND_VECTOR.equals(kind)), index));
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
                    topK(connectionId, InfraSettings.KIND_VECTOR.equals(kind)), body.effectiveIndex());
        }
        return null;
    }

    /** 该域绑的知识体 id（页面展示 / 排障用）；没绑返回 {@code null} */
    @Override
    public String bodyIdOf(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        String bodyId = domainStore.bodyOf(domain);
        return bodyId == null || bodyId.isBlank() ? null : bodyId.trim();
    }

    /** 知识体定义；不存在返回 {@code null} */
    public InfraSettings.KnowledgeBody body(String bodyId) {
        if (bodyId == null || bodyId.isBlank()) {
            return null;
        }
        return infra.current().body(bodyId.trim());
    }

    /**
     * 知识体的冻结索引名；体不存在返回 {@code null}。
     *
     * <p>这是全平台<b>唯一</b>把"知识体"翻成"索引名"的地方：新体按唯一名派生，
     * 迁移体带着旧名，两者都从这里出。</p>
     */
    public String indexOf(String bodyId) {
        InfraSettings.KnowledgeBody body = body(bodyId);
        return body == null ? null : body.effectiveIndex();
    }

    /** 索引名 → 知识体 id（对账 / 档案 / 诊断的反查）；不属于任何体返回 {@code null} */
    public String bodyIdOfIndex(String index) {
        if (index == null || index.isBlank()) {
            return null;
        }
        for (Map.Entry<String, InfraSettings.KnowledgeBody> entry
                : infra.current().bodiesOrEmpty().entrySet()) {
            if (entry.getValue() != null && index.trim().equals(entry.getValue().effectiveIndex())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 全部知识体（插入顺序 = 页面顺序） */
    public Map<String, InfraSettings.KnowledgeBody> bodies() {
        return infra.current().bodiesOrEmpty();
    }

    /** 全部知识体的冻结索引名（对账的权威清单） */
    public Set<String> allIndices() {
        Set<String> out = new LinkedHashSet<>();
        for (InfraSettings.KnowledgeBody body : infra.current().bodiesOrEmpty().values()) {
            if (body != null) {
                out.add(body.effectiveIndex());
            }
        }
        return out;
    }

    /** 绑定域反查：知识体 id → 绑定它的域（插入顺序按域绑定表） */
    public Map<String, List<String>> domainsByBody() {
        Map<String, List<String>> reversed = new LinkedHashMap<>();
        domainStore.bodies().forEach((domain, bodyId) -> {
            if (bodyId == null || bodyId.isBlank()) {
                return;
            }
            reversed.computeIfAbsent(bodyId.trim(), key -> new ArrayList<>()).add(domain);
        });
        return reversed;
    }

    /** 未绑定任何域的知识体 id（删域保留下来的那一批，需要被看见） */
    public List<String> unboundBodyIds() {
        Set<String> bound = new LinkedHashSet<>(domainStore.bodies().values());
        bound.removeIf(id -> id == null || id.isBlank());
        List<String> out = new ArrayList<>();
        for (String id : infra.current().bodiesOrEmpty().keySet()) {
            if (!bound.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 该路 topK：优先连接自己在 yaml 里声明的，其次默认后端声明的，最后回落到废弃兼容键。
     *
     * <p>与 {@code KnowledgeBackendsConfiguration} 装配期那套口径一致，只是挪到这里按
     * 连接现算——现在每个体可能用不同连接，装配期算一次已经不够用了。</p>
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
