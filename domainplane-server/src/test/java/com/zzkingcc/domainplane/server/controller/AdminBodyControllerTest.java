package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.config.AiProperties;
import com.zzkingcc.domainplane.server.config.ElasticsearchProperties;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.config.RedisProperties;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBodyResolver;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeStatsService;
import com.zzkingcc.domainplane.server.knowledge.LiveKnowledgeBackendRegistry;
import com.zzkingcc.domainplane.server.knowledge.RetrievalPlan;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSettingsStore;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import com.zzkingcc.domainplane.server.settings.LlmSettingsStore;
import com.zzkingcc.domainplane.server.storage.BlobStoreRegistry;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库页服务端（{@code /admin/bodies}）的拼装与守卫。
 *
 * <p>钉四类容易分叉的判据：</p>
 * <ol>
 *   <li><b>槽位能力位</b>：关键字槽指到 Qdrant 不能显示"可用"（运行期会静默跳过那一路）；</li>
 *   <li><b>writable 与运行期同源</b>：直接来自 {@code writeStoreOfBody}，扇出判定一致；</li>
 *   <li><b>索引名冻结</b>：新建时按唯一名派生并写进体；改唯一名被拒（否则等于换集合）；</li>
 *   <li><b>删体守卫</b>：要输名确认、有绑定默认拒绝、force 才解绑。</li>
 * </ol>
 *
 * @author zzkingcc
 */
class AdminBodyControllerTest {

    @TempDir
    Path tempDir;

    private DomainStore domainStore;
    private InfraSettingsHolder holder;
    private KnowledgeBodyResolver resolver;
    private final List<String> calls = new ArrayList<>();

    /** 存储对象永远不被调用（writeStore 只做解析与 instanceof 判定），动态代理顶住接口 */
    private static KnowledgeStore stubStore() {
        return (KnowledgeStore) Proxy.newProxyInstance(KnowledgeStore.class.getClassLoader(),
                new Class<?>[]{KnowledgeStore.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
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

    private AdminBodyController controller(KnowledgeStatsService.Overview canned,
                                           KnowledgeProperties.Strategy strategy,
                                           boolean embeddingConfigured) {
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
        // 配置里登记了、但地址还没填（实例还没上线）：保存校验能过（连接存在），
        // 页面上该槽必须显示 blank、整条体显示 dead，而不是 ok
        InfraSettings.Connection gone = new InfraSettings.Connection();
        gone.setId("es-gone");
        gone.setName("es-gone");
        gone.setType(InfraSettings.TYPE_ELASTICSEARCH);
        gone.setEs(new InfraSettings.Elasticsearch());
        s.getConnections().put("es-gone", gone);

        /* 三个体：正常的（两路分属两台 → 扇出）、一路连接还没填地址的、关键字槽错配到 Qdrant 的。
           唯一名与索引都按真实规则给，索引=冻结值（新建 / 迁移都该如此）。 */
        s.getBodies().put("kb-sales", body("kb-sales", "sales-kb", "销售知识", "es-main", "qdrant-a"));
        s.getBodies().put("kb-dead", body("kb-dead", "dead-kb", "死体", "es-gone", null));
        s.getBodies().put("kb-legacy", body("kb-legacy", "legacy-kb", "错配体", "qdrant-a", null));

        domainStore.setBody("default.sales", "kb-sales");
        domainStore.setBody("default.old", "kb-ghost");        // 绑的体已不在配置里
        domainStore.setBody("default.legacy", "kb-legacy");
        domainStore.setBody("default.dead", "kb-dead");

        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales", true);
        registry.create("default.old", true);
        registry.create("default.legacy", true);
        registry.create("default.dead", true);
        registry.create("default.ops", true);   // 登记了但没绑体

        LiveKnowledgeBackendRegistry backends = new LiveKnowledgeBackendRegistry();
        backends.replace(List.of(
                backend("es-main", true, true, "http://es.internal:9200"),
                backend("qdrant-a", false, true, "http://qdrant.internal:6333")));

        KnowledgeBodyResolver realResolver = new KnowledgeBodyResolver(holder, domainStore,
                new KnowledgeProperties(), new RetrievalProperties());
        resolver = realResolver;
        RetrievalPlan plan = new RetrievalPlan(strategy, backends, realResolver);

        KnowledgeStatsService stats = new KnowledgeStatsService(null, null, null) {
            @Override
            public Overview overview() {
                return canned;
            }

            @Override
            public void invalidate() {
                calls.add("stats.invalidate");
            }
        };
        return new AdminBodyController(stats, new StubKnowledgeBase(plan, realResolver), realResolver,
                holder, domainStore, registry, backends, plan, new RagProperties(),
                modelHolder(embeddingConfigured), storageLocations(),
                new BlobStoreRegistry(new LocalBlobStore(tempDir.resolve("blobs")), holder));
    }

    private AdminBodyController controller(KnowledgeStatsService.Overview canned,
                                           KnowledgeProperties.Strategy strategy) {
        return controller(canned, strategy, true);
    }

    /** 只顶掉删体 / 换落点这几个动作：其余路径不碰存储 */
    private class StubKnowledgeBase extends KnowledgeBaseService {
        private final KnowledgeBodyResolver resolver;

        StubKnowledgeBase(RetrievalPlan plan, KnowledgeBodyResolver r) {
            super(null, new RagProperties(), null, null, plan, r, null, null);
            this.resolver = r;
        }

        @Override
        public BodyDeleteResult deleteBody(String bodyId, boolean keepRaw) {
            calls.add("kb.deleteBody:" + bodyId + ":" + keepRaw);
            return new BodyDeleteResult(bodyId, "idx-" + bodyId, true, keepRaw, 3);
        }

        /* 换落点的两个动作要连真实存储（读档案、写目标集合），静态桩只验证
           controller 侧的顺序纪律：预演不切指针、执行成功才切、旧连接如实回报。 */
        @Override
        public BodyRoutePlan planRoute(String bodyId, String kind, String targetConnectionId) {
            InfraSettings.KnowledgeBody b = resolver.body(bodyId);
            String current = InfraSettings.KIND_KEYWORD.equals(kind) ? b.keywordId() : b.vectorId();
            boolean blocked = targetConnectionId == null || targetConnectionId.equals(current);
            return new BodyRoutePlan(bodyId, indexOf(bodyId), kind, current, targetConnectionId,
                    !blocked, blocked ? "这一路已经在目标连接上" : null,
                    8, 8, 0, !blocked, 1024, null, null);
        }

        @Override
        public BodyRouteMigration migrateRoute(String bodyId, String kind,
                                               String targetConnectionId, int dimensions) {
            InfraSettings.KnowledgeBody b = resolver.body(bodyId);
            String from = InfraSettings.KIND_KEYWORD.equals(kind) ? b.keywordId() : b.vectorId();
            calls.add("kb.migrateRoute:" + bodyId + ":" + kind + ":" + targetConnectionId);
            return new BodyRouteMigration(bodyId, indexOf(bodyId), kind, from, targetConnectionId,
                    96, 8, true, from);
        }

        @Override
        public void shutdown() { /* 不关别人的池 */ }
    }

    private LlmModelHolder modelHolder(boolean configured) {
        return new LlmModelHolder(new AiProperties(), new LlmSettingsStore(storageLocations())) {
            @Override
            public boolean isEmbeddingConfigured() {
                return configured;
            }

            /* 真实实现在没启用档案时会去探测维度并抛 NotConfiguredException；
               这条用例只验证 controller 的顺序纪律，给个固定维度就够。 */
            @Override
            public int effectiveEmbeddingDimension() {
                return 1024;
            }
        };
    }

    private static InfraSettings.KnowledgeBody body(String id, String slug, String name,
                                                    String keyword, String vector) {
        InfraSettings.KnowledgeBody b = new InfraSettings.KnowledgeBody();
        b.setId(id);
        b.setSlug(slug);
        b.setName(name);
        b.setKeyword(keyword);
        b.setVector(vector);
        b.setIndex(KbIndexes.nameOfBody(slug, id));
        return b;
    }

    /** 快照：kb-sales 的索引真实存在（两台上都有），kb-dead / kb-legacy 没有 */
    private KnowledgeStatsService.Overview cannedOverview() {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        owners.put(KbIndexes.nameOfBody("sales-kb", "kb-sales"), List.of("es-main", "qdrant-a"));
        owners.put("domainplane_kb_gone_1a2b3c4d", List.of("es-main"));
        Map<String, long[]> byBody = new LinkedHashMap<>();
        byBody.put("kb-sales", new long[]{2, 7});
        byBody.put("kb-ghost", new long[]{1, 2});
        return new KnowledgeStatsService.Overview(owners, byBody, Set.copyOf(owners.keySet()), 3, 9, false);
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

    private static AdminBodyController.BodyRequest request(String slug, String name,
                                                           String keyword, String vector) {
        AdminBodyController.BodyRequest r = new AdminBodyController.BodyRequest();
        r.setSlug(slug);
        r.setName(name);
        r.setKeyword(keyword);
        r.setVector(vector);
        return r;
    }

    // ==================== 快照 ====================

    @Test
    @DisplayName("list：槽位五态、状态判定、按体的索引判孤儿、悬空体入列、未绑定计数")
    void listShapesBodiesSlotsAndOrphans() {
        Map<String, Object> overview = controller(cannedOverview(),
                KnowledgeProperties.Strategy.HYBRID).list();

        Map<String, Object> sales = bodyRow(overview, "kb-sales");
        assertEquals("ok", sales.get("status"));
        assertEquals(Boolean.TRUE, sales.get("fanOut"), "两槽分属两台 = 写两份");
        assertEquals(2L, sales.get("documents"));
        assertEquals(7L, sales.get("chunks"));
        assertEquals(Boolean.TRUE, sales.get("existsIndex"));
        assertEquals("ok", slot(sales, "keyword").get("state"));
        assertEquals("ok", slot(sales, "vector").get("state"));
        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) sales.get("domains");
        assertEquals(List.of("default.sales"), domains);
        assertEquals("local", ((Map<?, ?>) sales.get("raw")).get("location"));

        Map<String, Object> dead = bodyRow(overview, "kb-dead");
        assertEquals("dead", dead.get("status"), "一路连接还没填地址 = 这个体现在干不了活");
        assertEquals("blank", slot(dead, "keyword").get("state"),
                "连接在配置里但没填地址：既不是 ok 也不是 missing");
        assertEquals(Boolean.FALSE, slot(dead, "keyword").get("usable"));
        assertEquals("absent", slot(dead, "vector").get("state"));

        Map<String, Object> legacy = bodyRow(overview, "kb-legacy");
        assertEquals("incapable", slot(legacy, "keyword").get("state"),
                "关键字槽指到 Qdrant：连接可用但能力位不匹配，不能显示 ok");
        assertEquals(Boolean.FALSE, slot(legacy, "keyword").get("usable"));

        // 悬空绑定：域还绑着一个配置里已不存在的体，必须显示出来而不是静默消失
        Map<String, Object> ghost = bodyRow(overview, "kb-ghost");
        assertEquals("missing", ghost.get("status"));
        assertEquals(1L, ghost.get("documents"), "旧文档按当前绑定归到这个 id 名下");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orphans = (List<Map<String, Object>>) overview.get("orphans");
        assertEquals(1, orphans.size(), "只有不属于任何已登记体的索引才算无主");
        assertEquals("domainplane_kb_gone_1a2b3c4d", orphans.get(0).get("name"));

        // 体清单 = 配置 3 个 + 只在绑定里出现的悬空体（必须可见）
        assertEquals(4, ((Map<?, ?>) overview.get("platform")).get("bodies"));
        assertEquals(4, ((Map<?, ?>) overview.get("platform")).get("bodiesInUse"),
                "kb-sales / kb-ghost / kb-legacy / kb-dead 都被域绑着");
        assertEquals(0, ((Map<?, ?>) overview.get("platform")).get("bodiesUnbound"));

        // 原文落点选项如实反映"现在能选什么"（只有 local）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawOptions = (List<Map<String, Object>>) overview.get("rawOptions");
        assertEquals(1, rawOptions.size());
        assertEquals("local", rawOptions.get(0).get("id"));
    }

    // ==================== 上传预检 ====================

    @Test
    @DisplayName("target：可写体给落点与扇出；体缺失 / 空壳 / 连接全坏 / 缺模型都给原因")
    void targetMatchesRuntimeWriteStore() {
        AdminBodyController c = controller(cannedOverview(), KnowledgeProperties.Strategy.HYBRID);

        Map<String, Object> ok = c.target("kb-sales");
        assertEquals(Boolean.TRUE, ok.get("exists"));
        assertEquals(Boolean.TRUE, ok.get("writable"), "判据必须来自 writeStoreOfBody 本体");
        assertEquals(Boolean.TRUE, ok.get("embeddingReady"));
        assertEquals(Boolean.TRUE, ok.get("fanOut"));
        @SuppressWarnings("unchecked")
        List<String> backends = (List<String>) ok.get("writeBackends");
        assertEquals(List.of("es-main", "qdrant-a"), backends);
        assertNotNull(ok.get("limits"));

        Map<String, Object> missing = c.target("kb-nope");
        assertEquals(Boolean.FALSE, missing.get("writable"));
        assertEquals("BODY_MISSING", missing.get("reason"));

        Map<String, Object> dead = c.target("kb-dead");
        assertEquals(Boolean.FALSE, dead.get("writable"));
        assertEquals("BODY_UNUSABLE", dead.get("reason"));

        AdminBodyController noModel = controller(cannedOverview(),
                KnowledgeProperties.Strategy.HYBRID, false);
        Map<String, Object> noEmbedding = noModel.target("kb-sales");
        assertEquals(Boolean.FALSE, noEmbedding.get("writable"));
        assertEquals("EMBEDDING_NOT_CONFIGURED", noEmbedding.get("reason"));

        // 没有体参数 = 明确拒绝，不猜一个默认体
        Map<String, Object> none = c.target(null);
        assertEquals("NO_BODY", none.get("reason"));
    }

    @Test
    @DisplayName("target：策略只开关键字时，混合体也照常可写（生效模式与实际一致）")
    void targetHonoursStrategyGate() {
        AdminBodyController c = controller(cannedOverview(), KnowledgeProperties.Strategy.KEYWORD);
        Map<String, Object> t = c.target("kb-sales");

        @SuppressWarnings("unchecked")
        List<String> effective = (List<String>) t.get("effectiveModes");
        assertEquals(List.of("keyword"), effective, "策略 KEYWORD 时向量路根本不开");
        @SuppressWarnings("unchecked")
        List<String> modes = (List<String>) t.get("modes");
        assertEquals(List.of("keyword", "vector"), modes, "配置模式原样保留，两个口径不能混");
    }

    // ==================== 新建 / 修改 / 删除 ====================

    @Test
    @DisplayName("创建：索引名按唯一名派生并冻结；非法名 / 重名 / id 不一致 / 空壳都拒绝")
    void createFreezesIndexAndRejectsBadInput() {
        AdminBodyController c = controller(cannedOverview(), KnowledgeProperties.Strategy.HYBRID);

        BaseException badSlug = assertThrows(BaseException.class,
                () -> c.create(request("A!", "坏名字", "es-main", null)));
        assertEquals(ErrorCode.INVALID_PARAMETER, badSlug.getErrorCode());

        BaseException dup = assertThrows(BaseException.class,
                () -> c.create(request("sales-kb", "重名", "es-main", null)));
        assertTrue(dup.getMessage().contains("唯一名"), "文案要点名唯一名冲突：" + dup.getMessage());

        AdminBodyController.BodyRequest mismatch = request("new-kb", "新体", "es-main", null);
        mismatch.setId("other-id");
        BaseException idMismatch = assertThrows(BaseException.class, () -> c.create(mismatch));
        assertTrue(idMismatch.getMessage().contains("唯一名"), "id 必须等于唯一名：" + idMismatch.getMessage());

        BaseException noSlot = assertThrows(BaseException.class,
                () -> c.create(request("new-kb", "新体", null, null)));
        assertTrue(noSlot.getMessage().contains("至少"), "必须选一路：" + noSlot.getMessage());

        Map<String, Object> created = c.create(request("new-kb", "新体", "es-main", "qdrant-a"));
        assertEquals("created", created.get("action"));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) created.get("body");
        assertEquals(KbIndexes.nameOfBody("new-kb", "new-kb"), row.get("index"),
                "索引创建时冻结，之后永不重新派生");
        assertEquals("new-kb", row.get("slug"));
        assertEquals("local", ((Map<?, ?>) row.get("raw")).get("location"));
        // 落盘后能被解析器看到（知识库页 / 检索共用同一份配置）
        assertEquals(KbIndexes.nameOfBody("new-kb", "new-kb"), resolver.indexOf("new-kb"));
    }

    @Test
    @DisplayName("修改：唯一名不许改（等于换集合）；换落点不许直接改（必须走 /routes 迁移）")
    void updateRejectsSlugChangeAndRouteChange() {
        AdminBodyController c = controller(cannedOverview(), KnowledgeProperties.Strategy.HYBRID);

        AdminBodyController.BodyRequest rename = request("renamed-kb", "改个名", null, null);
        BaseException e = assertThrows(BaseException.class, () -> c.update("kb-sales", rename));
        assertTrue(e.getMessage().contains("不可修改"), "改名要明确拒绝：" + e.getMessage());

        // 只改展示名：不该触发重灌
        AdminBodyController.BodyRequest niceName = request(null, "销售知识库", null, null);
        Map<String, Object> renamed = c.update("kb-sales", niceName);
        assertEquals(Boolean.FALSE, renamed.get("needsRebuild"), "只改展示名不需要重建");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) renamed.get("body");
        assertEquals("销售知识库", row.get("name"));
        assertEquals("sales-kb", row.get("slug"), "唯一名不动");

        /* 换向量落点：直接改指针会让检索立刻指向一个还没有数据的集合，
           所以这里必须拒，并指向 /routes（先写满、校验条数，再切）。 */
        AdminBodyController.BodyRequest moveVector = request(null, null, null, "es-main");
        BaseException routeChange = assertThrows(BaseException.class,
                () -> c.update("kb-sales", moveVector));
        assertTrue(routeChange.getMessage().contains("迁移"),
                "换落点要指向迁移端点：" + routeChange.getMessage());
        assertEquals("qdrant-a", holder.current().body("kb-sales").vectorId(),
                "被拒之后配置一个字都不能动");

        // 回传原值（前端保存表单会把两路一起带上）= 幂等，不能拦
        AdminBodyController.BodyRequest same = request("sales-kb", "销售知识库", "es-main", "qdrant-a");
        assertEquals(Boolean.FALSE, c.update("kb-sales", same).get("needsRebuild"));
    }

    @Test
    @DisplayName("换落点：预演不切指针；执行成功才切，索引名不变")
    void routeMigrationSwitchesPointerOnlyAfterDataWritten() {
        AdminBodyController c = controller(cannedOverview(), KnowledgeProperties.Strategy.HYBRID);

        AdminBodyController.RouteRequest q = new AdminBodyController.RouteRequest();
        q.setKind("vector");
        q.setConnection("es-main");

        // 预演说不能迁（这里取"目标就是当前那台"这条最便宜的判据）：只回 ready=false，配置不动
        AdminBodyController.RouteRequest sameTarget = new AdminBodyController.RouteRequest();
        sameTarget.setKind("vector");
        sameTarget.setConnection("qdrant-a");
        Map<String, Object> blocked = c.routes("kb-sales", sameTarget, true);
        assertEquals(Boolean.FALSE, blocked.get("ready"));
        assertNotNull(blocked.get("reason"));
        assertEquals("qdrant-a", holder.current().body("kb-sales").vectorId(), "预演不切指针");
        assertTrue(calls.isEmpty(), "预演不该碰任何写动作：" + calls);

        // 预演通过 → 执行 → 指针切到新连接，索引名仍是创建时冻结的那个
        Map<String, Object> done = c.routes("kb-sales", q, false);
        assertEquals("route-migrated", done.get("action"));
        assertEquals(KbIndexes.nameOfBody("sales-kb", "kb-sales"), done.get("index"));
        assertEquals("es-main", holder.current().body("kb-sales").vectorId());
        assertEquals("es-main", holder.current().body("kb-sales").keywordId(), "另一路不受影响");
        assertEquals("qdrant-a", done.get("legacyConnection"), "旧连接要如实回报（集合没删）");
    }

    @Test
    @DisplayName("删除：要输名确认；有绑定默认拒绝；force 才解绑并删体")
    void deleteRequiresConfirmationAndForceWhenBound() {
        AdminBodyController c = controller(cannedOverview(), KnowledgeProperties.Strategy.HYBRID);

        BaseException noConfirm = assertThrows(BaseException.class,
                () -> c.delete("kb-sales", true, null, false));
        assertTrue(noConfirm.getMessage().contains("确认"), "必须要输名：" + noConfirm.getMessage());
        assertTrue(calls.isEmpty(), "确认失败不该删任何东西：" + calls);

        BaseException bound = assertThrows(BaseException.class,
                () -> c.delete("kb-sales", true, "销售知识", false));
        assertTrue(bound.getMessage().contains("默认") || bound.getMessage().contains("绑"),
                "有域绑着要拒绝：" + bound.getMessage());
        assertTrue(calls.isEmpty(), "拒绝时不该删任何东西：" + calls);

        Map<String, Object> deleted = c.delete("kb-sales", true, "销售知识", true);
        assertEquals("deleted", deleted.get("action"));
        assertEquals("idx-kb-sales", deleted.get("index"));
        assertEquals(Boolean.TRUE, deleted.get("rawKept"), "默认保留原文");
        assertEquals(List.of("default.sales"), deleted.get("unboundDomains"));
        assertTrue(calls.contains("kb.deleteBody:kb-sales:true"), "删数据在前：" + calls);
        // 体已从配置移除
        assertEquals(null, holder.current().body("kb-sales"));
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
