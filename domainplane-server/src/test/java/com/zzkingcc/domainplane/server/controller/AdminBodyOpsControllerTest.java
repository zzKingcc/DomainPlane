package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBodyResolver;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维区的三条安全纪律：诊断分类正确、认领不碰已有数据、清理不删在用集合。
 *
 * <p>这三个动作都越过正常的体级动线（直接看 / 改存储），所以判据必须钉死在测试里——
 * 尤其是"活体当前落点上的集合不许删"，判错一次就是一次静默数据销毁。</p>
 *
 * @author zzkingcc
 */
@DisplayName("运维区：诊断分类 / 认领守卫 / 清理守卫")
class AdminBodyOpsControllerTest {

    @TempDir
    Path tempDir;

    private InfraSettingsHolder holder;
    private final List<String> deleted = new ArrayList<>();

    /** ES 能同时做两路；Qdrant 只做向量 */
    private static KnowledgeBackend backend(String id, boolean keyword, boolean vector,
                                            String location, KnowledgeStore store) {
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
                return Optional.of(store);
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

    /** 只回答诊断要问的那几件事，其余按返回类型给默认值 */
    private KnowledgeStore store(List<String> collections) {
        return (KnowledgeStore) Proxy.newProxyInstance(KnowledgeStore.class.getClassLoader(),
                new Class<?>[]{KnowledgeStore.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "ping":
                            return true;
                        case "listCollections":
                            return collections;
                        case "count":
                            return 7L;
                        case "currentVectorDims":
                            return 1024;
                        case "exists":
                            return collections.contains(String.valueOf(args[0]));
                        case "deleteCollection":
                            deleted.add(String.valueOf(args[0]));
                            return true;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }

    private AdminBodyOpsController controller(List<String> esCollections) {
        DomainStore domainStore = new DomainStore(storageLocations());
        holder = new InfraSettingsHolder(
                new com.zzkingcc.domainplane.server.config.ElasticsearchProperties(),
                new com.zzkingcc.domainplane.server.config.RedisProperties(),
                new KnowledgeProperties(), new InfraSettingsStore(storageLocations()));

        InfraSettings s = holder.current();
        s.getConnections().put("es-main", conn("es-main", InfraSettings.TYPE_ELASTICSEARCH));
        s.getConnections().put("qdrant-a", conn("qdrant-a", InfraSettings.TYPE_QDRANT));

        // kb-live 的关键字落在 es-main（它的集合是"在用"）
        s.getBodies().put("kb-live", body("kb-live", "live-kb", "es-main", null));
        // kb-moved 已经换到 qdrant（它在 es-main 上的同名集合是"遗留"）
        s.getBodies().put("kb-moved", body("kb-moved", "moved-kb", null, "qdrant-a"));

        KnowledgeBodyResolver resolver = new KnowledgeBodyResolver(holder, domainStore,
                new KnowledgeProperties(), new RetrievalProperties());
        LiveKnowledgeBackendRegistry backends = new LiveKnowledgeBackendRegistry();
        backends.replace(List.of(
                backend("es-main", true, true, "http://es.internal:9200", store(esCollections)),
                backend("qdrant-a", false, true, "http://qdrant.internal:6333", store(List.of()))));
        RetrievalPlan plan = new RetrievalPlan(KnowledgeProperties.Strategy.HYBRID, backends, resolver);

        KnowledgeStatsService stats = new KnowledgeStatsService(null, null, null) {
            @Override
            public Overview overview() {
                return new Overview(Map.of(), Map.of(), java.util.Set.of(), 0, 0, false);
            }

            @Override
            public void invalidate() {
                // 只验证"被调过"，不关心内容
            }
        };
        return new AdminBodyOpsController(plan, resolver, holder, null, stats);
    }

    private static InfraSettings.Connection conn(String id, String type) {
        InfraSettings.Connection c = new InfraSettings.Connection();
        c.setId(id);
        c.setName(id);
        c.setType(type);
        if (InfraSettings.TYPE_ELASTICSEARCH.equals(type)) {
            InfraSettings.Elasticsearch es = new InfraSettings.Elasticsearch();
            es.setHost(id + ".internal");
            es.setPort(9200);
            c.setEs(es);
        } else {
            InfraSettings.VectorStore v = new InfraSettings.VectorStore();
            v.setUri("http://" + id + ".internal:6333");
            c.setVector(v);
        }
        return c;
    }

    private static InfraSettings.KnowledgeBody body(String id, String slug, String keyword, String vector) {
        InfraSettings.KnowledgeBody b = new InfraSettings.KnowledgeBody();
        b.setId(id);
        b.setSlug(slug);
        b.setName(id);
        b.setKeyword(keyword);
        b.setVector(vector);
        b.setIndex(KbIndexes.nameOfBody(slug, id));
        return b;
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

    // ==================== 诊断 ====================

    @Test
    @DisplayName("诊断：在用 / 遗留 / 无主三种集合分得清，各台的清单不混")
    void diagnosticsClassifiesCollections() {
        String liveIndex = KbIndexes.nameOfBody("live-kb", "kb-live");
        String movedIndex = KbIndexes.nameOfBody("moved-kb", "kb-moved");
        String orphanIndex = KbIndexes.nameOfBody("handmade", "handmade");

        AdminBodyOpsController c = controller(List.of(liveIndex, movedIndex, orphanIndex));
        Map<String, Object> result = c.diagnostics();

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(3, summary.get("collections"));
        assertEquals(1, summary.get("orphans"), "手工建的集合没有体认领");
        assertEquals(1, summary.get("legacy"), "体已换到 qdrant，es 上那份是遗留");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backends = (List<Map<String, Object>>) result.get("backends");
        /* 按 id 找那台 ES，不按位置取：后端注册表底层是 Map，顺序不是契约，
           用下标取会在"全量测试时类加载顺序不同"这种场景下静默取到另一台。 */
        Map<String, Object> esBackend = backends.stream()
                .filter(b -> "es-main".equals(b.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("诊断结果里没有 es-main：" + backends));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) esBackend.get("collections");
        Map<String, String> statusByName = new LinkedHashMap<>();
        cols.forEach(col -> statusByName.put(String.valueOf(col.get("name")), String.valueOf(col.get("status"))));
        assertEquals("ok", statusByName.get(liveIndex), statusByName.toString());
        assertEquals("legacy", statusByName.get(movedIndex), statusByName.toString());
        assertEquals("orphan", statusByName.get(orphanIndex), statusByName.toString());
    }

    // ==================== 认领 ====================

    @Test
    @DisplayName("认领：索引名原样冻结、两路按连接能力填；已有主的集合拒绝认领")
    void adoptFreezesIndexAndRejectsOwned() {
        String orphanIndex = KbIndexes.nameOfBody("handmade", "handmade");
        AdminBodyOpsController c = controller(List.of(orphanIndex));

        AdminBodyOpsController.AdoptRequest req = new AdminBodyOpsController.AdoptRequest();
        req.setConnection("es-main");
        req.setIndex(orphanIndex);
        req.setSlug("adopted-kb");
        req.setName("认领来的");

        Map<String, Object> result = c.adopt(req);
        assertEquals(orphanIndex, result.get("index"));
        InfraSettings.KnowledgeBody adopted = holder.current().body("adopted-kb");
        assertEquals(orphanIndex, adopted.getIndex(),
                "索引名必须沿用现名：重新派生等于换一个集合，旧数据既查不到也不会被清理");
        assertEquals("es-main", adopted.keywordId(), "ES 两路都能干，就都填上");
        assertEquals("es-main", adopted.vectorId());

        // 已有主的集合不能再被认领
        String liveIndex = KbIndexes.nameOfBody("live-kb", "kb-live");
        AdminBodyOpsController.AdoptRequest again = new AdminBodyOpsController.AdoptRequest();
        again.setConnection("es-main");
        again.setIndex(liveIndex);
        again.setSlug("other-kb");
        BaseException owned = assertThrows(BaseException.class, () -> c.adopt(again));
        assertTrue(owned.getMessage().contains("已经属于"), owned.getMessage());
    }

    @Test
    @DisplayName("认领：连接上根本没有这个集合时拒绝（不能凭空造一个空体）")
    void adoptRejectsMissingCollection() {
        AdminBodyOpsController c = controller(List.of());
        AdminBodyOpsController.AdoptRequest req = new AdminBodyOpsController.AdoptRequest();
        req.setConnection("es-main");
        req.setIndex(KbIndexes.nameOfBody("ghost", "ghost"));
        req.setSlug("ghost-kb");
        BaseException e = assertThrows(BaseException.class, () -> c.adopt(req));
        assertTrue(e.getMessage().contains("没有集合"), e.getMessage());
        assertNull(holder.current().body("ghost-kb"));
    }

    // ==================== 清理 ====================

    @Test
    @DisplayName("清理：活体当前落点上的集合拒删；无主集合输了名才删")
    void deleteRefusesLiveCollection() {
        String liveIndex = KbIndexes.nameOfBody("live-kb", "kb-live");
        String orphanIndex = KbIndexes.nameOfBody("handmade", "handmade");
        AdminBodyOpsController c = controller(List.of(liveIndex, orphanIndex));

        BaseException live = assertThrows(BaseException.class,
                () -> c.deleteCollection("es-main", liveIndex, liveIndex));
        assertTrue(live.getMessage().contains("正在") || live.getMessage().contains("使用"),
                "活体当前落点上的集合是数据不是残留：" + live.getMessage());
        assertTrue(deleted.isEmpty(), "被拒时不能真的删：" + deleted);

        // 输错名也不删
        assertThrows(BaseException.class,
                () -> c.deleteCollection("es-main", orphanIndex, "wrong-name"));
        assertTrue(deleted.isEmpty());

        Map<String, Object> ok = c.deleteCollection("es-main", orphanIndex, orphanIndex);
        assertEquals(Boolean.TRUE, ok.get("deleted"));
        assertEquals(List.of(orphanIndex), deleted);
    }
}
