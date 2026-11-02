package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.config.ElasticsearchProperties;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.config.RedisProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeStatsService;
import com.zzkingcc.domainplane.server.knowledge.LiveKnowledgeBackendRegistry;
import com.zzkingcc.domainplane.server.knowledge.RetrievalPlan;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSettingsStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 知识库页两个只读聚合接口的拼装行为面。
 *
 * 重点钉三类容易分叉的判据：槽位能力位（关键字槽指到 Qdrant 不能显示"可用"）、
 * writable 与运行期 writeStore 同源（扇出判定一致）、孤儿按索引名反推
 * （不按切片 metadata，孤儿索引里的老文档 metadata 还带着已删的域）。
 *
 * @author zzkingcc
 */
class KnowledgeOverviewControllerTest {

    @TempDir
    Path tempDir;

    private DomainStore domainStore;

    private InfraSettingsHolder holder;

    /** 存储对象永远不被调用（writeStore 只做解析与 instanceof 判定），动态代理顶住接口 */
    private static KnowledgeStore stubStore() {
        return (KnowledgeStore) Proxy.newProxyInstance(KnowledgeStore.class.getClassLoader(),
                new Class<?>[]{KnowledgeStore.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    // 基本类型必须给默认值：FanOutStore 构造器用 LinkedHashSet 去重，
                    // 会调 hashCode()——返回 null 会在拆箱时 NPE，被误判成 BODY_UNUSABLE
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (List.class.isAssignableFrom(rt)) {
                        return List.of();
                    }
                    return null;
                });
    }

    private static KnowledgeBackend backend(String id, boolean keyword, boolean vector, String location) {
        return new KnowledgeBackend() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String location() {
                return location;
            }

            @Override
            public StoreCapabilities capabilities() {
                return new StoreCapabilities(keyword, vector, true, true, true);
            }

            @Override
            public Optional<KnowledgeStore> store() {
                return Optional.of(stubStore());
            }

            @Override
            public Optional<KeywordSearcher> keyword() {
                return Optional.empty();
            }

            @Override
            public Optional<VectorSearcher> vector() {
                return Optional.empty();
            }
        };
    }

    private KnowledgeOverviewController controller(KnowledgeStatsService.Overview canned,
                                                   KnowledgeProperties.Strategy strategy) {
        domainStore = new DomainStore(storageLocations());
        holder = new InfraSettingsHolder(
                new ElasticsearchProperties(), new RedisProperties(),
                new KnowledgeProperties(), new InfraSettingsStore(storageLocations()));

        InfraSettings s = holder.current();
        InfraSettings.Connection es = new InfraSettings.Connection();
        es.setId("es-main");
        es.setName("es-main");
        es.setType(InfraSettings.TYPE_ELASTICSEARCH);
        es.setEs(new InfraSettings.Elasticsearch());
        es.getEs().setHost("es.internal");
        InfraSettings.Connection qd = new InfraSettings.Connection();
        qd.setId("qdrant-a");
        qd.setName("qdrant-a");
        qd.setType(InfraSettings.TYPE_QDRANT);
        qd.setVector(new InfraSettings.VectorStore());
        qd.getVector().setUri("http://qdrant.internal:6333");
        s.getConnections().put("es-main", es);
        s.getConnections().put("qdrant-a", qd);

        s.getBodies().put("kb-sales", body("kb-sales", "销售知识", "es-main", "qdrant-a"));
        // 两路都指一台没有的连接 → dead
        s.getBodies().put("kb-dead", body("kb-dead", "死体", "es-gone", null));
        // 关键字槽指到 Qdrant → 能力不匹配（保存校验应拦，这里模拟手改配置的漏网）
        s.getBodies().put("kb-legacy", body("kb-legacy", "错配体", "qdrant-a", null));

        domainStore.setBody("default.sales", "kb-sales");
        domainStore.setBody("default.old", "kb-ghost");   // 绑的体已不在配置里
        domainStore.setBody("default.legacy", "kb-legacy");

        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales", true);
        registry.create("default.old", true);
        registry.create("default.legacy", true);
        registry.create("default.ops", true);   // 登记了但没绑体

        LiveKnowledgeBackendRegistry backends = new LiveKnowledgeBackendRegistry();
        backends.replace(List.of(
                backend("es-main", true, true, "http://es.internal:9200"),
                backend("qdrant-a", false, true, "http://qdrant.internal:6333")));

        // 域 → 体的解析照 DomainStore + 配置现算，与 KnowledgeBodyResolver 的纯读口径一致
        RetrievalPlan.BodyLookup lookup = new RetrievalPlan.BodyLookup() {
            @Override
            public List<RetrievalPlan.BodyBinding> bodiesOf(String domain) {
                InfraSettings.KnowledgeBody b = holder.current().body(domainStore.bodyOf(domain));
                List<RetrievalPlan.BodyBinding> out = new ArrayList<>();
                if (b == null) {
                    return out;
                }
                if (b.hasKeyword()) {
                    out.add(new RetrievalPlan.BodyBinding(b.getId(), "keyword", b.keywordId(), 5));
                }
                if (b.hasVector()) {
                    out.add(new RetrievalPlan.BodyBinding(b.getId(), "vector", b.vectorId(), 5));
                }
                return out;
            }

            @Override
            public RetrievalPlan.BodyBinding platformBody(String kind) {
                return null;
            }
        };
        RetrievalPlan plan = new RetrievalPlan(strategy, backends, lookup);

        KnowledgeStatsService stats = new KnowledgeStatsService(null, null, null) {
            @Override
            public Overview overview() {
                return canned;
            }
        };
        return new KnowledgeOverviewController(stats, holder, domainStore,
                registry, backends, plan, new RagProperties());
    }

    private static InfraSettings.KnowledgeBody body(String id, String name, String keyword, String vector) {
        InfraSettings.KnowledgeBody b = new InfraSettings.KnowledgeBody();
        b.setId(id);
        b.setName(name);
        b.setKeyword(keyword);
        b.setVector(vector);
        return b;
    }

    /** 与 KbIndexes.nameOf 同源的索引名（测试里只关心 key 一致性，具体 hash 不重要） */
    private static KnowledgeStatsService.Overview cannedOverview() {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        owners.put(indexOf("default.sales"), List.of("es-main", "qdrant-a"));
        owners.put(indexOf("default.old"), List.of("es-main"));
        owners.put("domainplane_kb_default.gone_1a2b3c4d", List.of("es-main"));
        Map<String, long[]> byBody = new LinkedHashMap<>();
        byBody.put("kb-sales", new long[]{2, 7});
        byBody.put("kb-ghost", new long[]{1, 2});
        Map<String, long[]> byDomain = new LinkedHashMap<>();
        byDomain.put("default.sales", new long[]{2, 7});
        byDomain.put("default.old", new long[]{1, 2});
        return new KnowledgeStatsService.Overview(owners, byBody, byDomain,
                Set.copyOf(owners.keySet()), 3, 9, false);
    }

    /** 与被测接口同源的索引名派生（孤儿判定就是拿它反推的） */
    private static String indexOf(String domain) {
        return KbIndexes.nameOf(domain);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyRow(Map<String, Object> overview, String bodyId) {
        return ((List<Map<String, Object>>) overview.get("bodies")).stream()
                .filter(b -> bodyId.equals(b.get("id")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slot(Map<String, Object> row, String kind) {
        return ((List<Map<String, Object>>) row.get("slots")).stream()
                .filter(s -> kind.equals(s.get("kind")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("overview：槽位五态、状态判定、孤儿按索引名反推、悬空体入列")
    void overviewShapesBodiesSlotsAndOrphans() {
        Map<String, Object> overview = controller(cannedOverview(),
                KnowledgeProperties.Strategy.HYBRID).overview();

        Map<String, Object> sales = bodyRow(overview, "kb-sales");
        assertEquals("ok", sales.get("status"));
        assertEquals(Boolean.TRUE, sales.get("fanOut"), "两槽分属两台 = 写两份");
        assertEquals(2L, sales.get("documents"));
        assertEquals(7L, sales.get("chunks"));
        assertEquals(1L, sales.get("indexCount"));
        assertEquals("ok", slot(sales, "keyword").get("state"));
        assertEquals(Boolean.TRUE, slot(sales, "keyword").get("usable"));
        assertEquals("ok", slot(sales, "vector").get("state"));

        Map<String, Object> dead = bodyRow(overview, "kb-dead");
        assertEquals("dead", dead.get("status"), "两路连接都不可用的体 = dead");
        assertEquals("missing", slot(dead, "keyword").get("state"), "连接已从配置里删掉");
        assertEquals("absent", slot(dead, "vector").get("state"));

        Map<String, Object> legacy = bodyRow(overview, "kb-legacy");
        assertEquals("incapable", slot(legacy, "keyword").get("state"),
                "关键字槽指到 Qdrant：连接可用但能力位不匹配，不能显示 ok");
        assertEquals(Boolean.FALSE, slot(legacy, "keyword").get("usable"));

        // 悬空绑定：域还绑着一个配置里已不存在的体，必须显示出来而不是静默消失
        Map<String, Object> ghost = bodyRow(overview, "kb-ghost");
        assertEquals("missing", ghost.get("status"));
        assertEquals(Boolean.TRUE, ghost.get("name") == null, "体都没了不该有展示名");
        assertEquals(1L, ghost.get("documents"), "旧文档按当前绑定归到这个 id 名下");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orphans = (List<Map<String, Object>>) overview.get("orphans");
        assertEquals(1, orphans.size(), "只有不属于任何现存域的索引才算孤儿");
        assertEquals("domainplane_kb_default.gone_1a2b3c4d", orphans.get(0).get("name"));

        // 体清单 = 配置 3 个 + 只在绑定里出现的悬空体 kb-ghost（必须可见，不能静默消失）
        assertEquals(4, ((Map<?, ?>) overview.get("platform")).get("bodies"));
        assertEquals(3, ((Map<?, ?>) overview.get("platform")).get("bodiesInUse"),
                "kb-sales / kb-ghost / kb-legacy 三个都被域绑着；kb-dead 没绑不算");
    }

    @Test
    @DisplayName("target：可写域给落点与扇出；未绑体与悬空体给原因")
    void targetMatchesRuntimeWriteStore() {
        KnowledgeOverviewController c = controller(cannedOverview(), KnowledgeProperties.Strategy.HYBRID);

        Map<String, Object> ok = c.target("default.sales");
        assertEquals(Boolean.TRUE, ok.get("domainExists"));
        assertEquals(Boolean.TRUE, ok.get("writable"), "判据必须来自 writeStore 本体，不能另写一份");
        assertEquals(Boolean.TRUE, ok.get("fanOut"));
        @SuppressWarnings("unchecked")
        List<String> backends = (List<String>) ok.get("writeBackends");
        assertEquals(List.of("es-main", "qdrant-a"), backends);
        assertEquals(Boolean.TRUE, ok.get("limits") != null);

        Map<String, Object> unbound = c.target("default.ops");
        assertEquals(Boolean.FALSE, unbound.get("writable"));
        assertEquals("NO_BODY", unbound.get("reason"));

        Map<String, Object> dangling = c.target("default.old");
        assertEquals(Boolean.FALSE, dangling.get("writable"));
        assertEquals("BODY_MISSING", dangling.get("reason"));

        Map<String, Object> dead = c.target("default.missing");
        assertEquals(Boolean.FALSE, dead.get("domainExists"));
        assertEquals("DOMAIN_NOT_FOUND", dead.get("reason"));
    }

    @Test
    @DisplayName("target：策略闸门要体现在 effectiveModes 里（KEYWORD 时混合体只剩关键字）")
    void targetReflectsGlobalStrategy() {
        Map<String, Object> t = controller(cannedOverview(),
                KnowledgeProperties.Strategy.KEYWORD).target("default.sales");
        @SuppressWarnings("unchecked")
        List<String> effective = (List<String>) t.get("effectiveModes");
        assertEquals(List.of("keyword"), effective, "策略 KEYWORD 时向量路根本不开");
        @SuppressWarnings("unchecked")
        List<String> modes = (List<String>) t.get("modes");
        assertEquals(List.of("keyword", "vector"), modes, "配置模式原样保留，两个口径不能混");
    }

    private StorageLocations storageLocations() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new StorageLocations(env);
    }
}
