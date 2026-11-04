package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1 → v2 的启动期迁移：把"一域一索引"的存量索引登记成知识体，<b>零拷贝</b>。
 *
 * <p>v1 的数据身份是域：索引名由域路径派生（{@link KbIndexes#legacyNameOf}），没有"知识体"
 * 这个概念。v2 的数据身份是知识体，索引名由体的唯一名派生。迁移不搬数据、不重建索引，
 * 只做两件事：</p>
 * <ol>
 *   <li>为每个"没有主人的存量索引"登记一个知识体，并把 <b>旧索引名原样冻结</b>到
 *       {@code index} 字段——于是新代码查到的是同一份数据，一个字节都没动；</li>
 *   <li>把原本该看到那个索引的域（旧命名下"索引 → 域"是一一对应的）重新绑到这个体上，
 *       可见范围与 v1 完全一致（域链 + 绑体）。</li>
 * </ol>
 *
 * <p><b>幂等</b>：只处理"不属于任何已登记体"的索引；已经迁移过的索引会被
 * {@link KnowledgeBodyResolver#bodyIdOfIndex} 认领，第二轮什么也不做。</p>
 *
 * <p><b>失败不阻断启动</b>：迁移是可重试的运维动作，不是启动前置条件。存储不可达时
 * 打一条 WARN、下次启动再试——但绝不能因为迁移失败就让平台起不来。</p>
 *
 * <p><b>不删任何东西</b>：没有"迁移完就把旧索引删掉"的步骤。旧索引名就是新体的索引名，
 * 删了等于把数据删了。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class KnowledgeLegacyMigrator implements ApplicationRunner {

    private final KnowledgeBaseService knowledgeBase;
    private final KnowledgeBodyResolver bodyResolver;
    private final RetrievalPlan retrievalPlan;
    private final InfraSettingsHolder infraHolder;
    private final DomainStore domainStore;
    private final DomainRegistry domainRegistry;

    public KnowledgeLegacyMigrator(KnowledgeBaseService knowledgeBase,
                                   KnowledgeBodyResolver bodyResolver,
                                   RetrievalPlan retrievalPlan,
                                   InfraSettingsHolder infraHolder,
                                   DomainStore domainStore,
                                   DomainRegistry domainRegistry) {
        this.knowledgeBase = knowledgeBase;
        this.bodyResolver = bodyResolver;
        this.retrievalPlan = retrievalPlan;
        this.infraHolder = infraHolder;
        this.domainStore = domainStore;
        this.domainRegistry = domainRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("[知识库迁移] 存量索引登记未完成（不影响启动，下次启动会重试）：{}", e.getMessage());
        }
    }

    /**
     * 扫描全部后端，把无主的 {@code domainplane_kb_*} 索引登记成知识体并重绑域。
     *
     * @return 本次登记的知识体 id（空 = 没有需要迁移的数据）
     */
    public List<String> migrate() {
        List<String> indices;
        try {
            indices = knowledgeBase.existingIndices();
        } catch (Exception e) {
            log.warn("[知识库迁移] 枚举后端索引失败（下次启动重试）：{}", e.getMessage());
            return List.of();
        }
        Set<String> unowned = new LinkedHashSet<>();
        for (String index : indices) {
            if (index != null && index.startsWith(KbIndexes.PREFIX)
                    && bodyResolver.bodyIdOfIndex(index) == null) {
                unowned.add(index);
            }
        }
        if (unowned.isEmpty()) {
            return List.of();
        }
        log.info("[知识库迁移] 发现 {} 个没有对应知识体的存量索引：{}", unowned.size(), unowned);

        Map<String, InfraSettings.KnowledgeBody> bodies =
                new LinkedHashMap<>(infraHolder.current().bodiesOrEmpty());
        List<String> created = new ArrayList<>();
        Map<String, String> bindings = new LinkedHashMap<>();
        for (String index : unowned) {
            String domain = legacyDomainOf(index);
            String slug = uniqueSlug(bodies, domain == null ? "legacy" : domain);
            InfraSettings.KnowledgeBody body = new InfraSettings.KnowledgeBody();
            body.setId(slug);
            body.setSlug(slug);
            body.setName(domain == null ? "迁移索引 " + index : domain);
            body.setDescription("由 v1 的一域一索引迁移而来；索引名保持原样（零拷贝）"
                    + (domain == null ? "" : "，原归属域 " + domain));
            // 旧索引名原样冻结：不重新派生，这样数据不动、可见范围不变
            body.setIndex(index);
            body.setRaw(InfraSettings.RAW_LOCAL);
            fillSlots(body, index);
            bodies.put(slug, body);
            created.add(slug);
            if (domain != null) {
                bindings.put(domain, slug);
            }
            log.info("[知识库迁移] 已登记知识体 {}（索引 {}，关键字={}，向量={}）",
                    slug, index, body.keywordId(), body.vectorId());
        }
        infraHolder.replaceBodies(bodies);
        if (!bindings.isEmpty()) {
            bindings.forEach(domainStore::setBody);
            domainStore.saveDeclarations(domainRegistry.manualConfig());
            log.info("[知识库迁移] 已把 {} 个域重新绑到迁移后的知识体：{}", bindings.size(), bindings);
        }
        log.info("[知识库迁移] 完成：登记 {} 个知识体（未搬动任何数据）", created.size());
        return List.copyOf(created);
    }

    /**
     * 旧索引 → 域：反查全部已登记域的 v1 索引名。
     *
     * <p>不用"解析索引名"的办法：v1 的名字经过安全化与哈希，反解不出来；
     * 正着算一遍所有域的旧名再比对，是唯一可靠的判据。</p>
     */
    private String legacyDomainOf(String index) {
        for (String domain : domainRegistry.ids()) {
            if (domain != null && index.equals(KbIndexes.legacyNameOf(domain))) {
                return domain;
            }
        }
        return null;
    }

    /**
     * 给迁移体选两路落点：把<b>真的存着这个索引</b>的后端找出来，按能力位分配。
     *
     * <p>只从"已有这个集合的后端"里选，不从配置里随便挑一台：挑错的话，检索会去一台
     * 没有这份数据的机器上查，返回空、不报错。</p>
     */
    private void fillSlots(InfraSettings.KnowledgeBody body, String index) {
        for (KnowledgeBackend backend : retrievalPlan.backends()) {
            if (infraHolder.current().connection(backend.id()) == null) {
                continue;
            }
            boolean holds;
            try {
                KnowledgeStore store = retrievalPlan.storeOf(backend.id());
                holds = store != null && store.listCollections().contains(index);
            } catch (RuntimeException e) {
                log.debug("[知识库迁移] 后端 {} 不可用（跳过）: {}", backend.id(), e.getMessage());
                continue;
            }
            if (!holds) {
                continue;
            }
            if (backend.capabilities().keyword() && body.keywordId() == null) {
                body.setKeyword(backend.id());
            }
            if (backend.capabilities().vector() && body.vectorId() == null) {
                body.setVector(backend.id());
            }
        }
        if (!body.configured()) {
            // 一台都问不到（后端刚挂）：按平台默认的关键字连接登记，让体至少有个落点，
            // 等存储恢复后用户可以在知识库页改体或重建
            String fallback = infraHolder.current().platformConnectionId(InfraSettings.KIND_KEYWORD);
            if (fallback != null) {
                body.setKeyword(fallback);
            }
            log.warn("[知识库迁移] 索引 {} 的后端暂时不可达，知识体 {} 先按平台默认连接登记",
                    index, body.getId());
        }
    }

    /** 由域路径派生合法且不撞车的唯一名（{@code default.sales} → {@code default-sales}） */
    private static String uniqueSlug(Map<String, InfraSettings.KnowledgeBody> bodies, String domain) {
        String base = KbIndexes.normalizeSlug(domain.replace('.', '-'));
        if (base.length() < 2) {
            base = "legacy";
        }
        Set<String> taken = new LinkedHashSet<>(bodies.keySet());
        bodies.values().forEach(b -> {
            if (b != null) {
                taken.add(b.effectiveSlug());
            }
        });
        String slug = base;
        int i = 2;
        while (taken.contains(slug)) {
            String suffix = "-" + i++;
            slug = base.length() + suffix.length() > 40
                    ? base.substring(0, 40 - suffix.length()) + suffix
                    : base + suffix;
        }
        return slug;
    }
}
