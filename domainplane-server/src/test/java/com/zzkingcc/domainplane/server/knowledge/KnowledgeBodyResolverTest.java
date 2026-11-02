package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.server.config.ElasticsearchProperties;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RedisProperties;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSettingsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域 → 知识体：解析规则。
 *
 * <p>钉五件事，每一件都是"错了不报错、只是查不到东西"的类型：</p>
 * <ol>
 *   <li><b>一个体决定走几路</b>：两路都配 = 两条通道（混合召回），只配一路 = 一条；</li>
 *   <li><b>不继承</b>：父域绑了体，子域拿不到——子域的索引是它自己那份；</li>
 *   <li><b>没绑就是空</b>：不去平台默认里捞一个顶上（那会把 A 域的知识写进 B 域的库）；</li>
 *   <li><b>体被删了当作没有</b>：绑定留着（可恢复），但本轮不检索；</li>
 *   <li><b>一路坏了不整块失效</b>：关键字体那台机器没了，向量路照走。</li>
 * </ol>
 *
 * @author zzkingcc
 */
@DisplayName("KnowledgeBodyResolver：域 → 知识体的解析与不继承")
class KnowledgeBodyResolverTest {

    @TempDir
    Path tempDir;

    private DomainStore domainStore;
    private InfraSettingsHolder holder;
    private KnowledgeBodyResolver resolver;

    @BeforeEach
    void setUp() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        StorageLocations storage = new StorageLocations(env);

        KnowledgeProperties knowledge = new KnowledgeProperties();
        KnowledgeProperties.Backend es = new KnowledgeProperties.Backend();
        KnowledgeProperties.Keyword kw = new KnowledgeProperties.Keyword();
        kw.setTopK(7);
        es.setKeyword(kw);
        knowledge.getBackends().put("es-main", es);

        holder = new InfraSettingsHolder(
                new ElasticsearchProperties(), new RedisProperties(), knowledge,
                new InfraSettingsStore(storage));
        holder.apply(settings());

        domainStore = new DomainStore(storage);
        domainStore.load();
        resolver = new KnowledgeBodyResolver(holder, domainStore, knowledge, new RetrievalProperties());
    }

    /**
     * 一台 ES（关键字体用）+ 一台 Qdrant（向量体用）+ 两台建了但没填地址的连接（坏连接）。
     *
     * <p>知识体：{@code kb-kw} 只配关键字、{@code kb-vec} 只配向量、
     * {@code kb-mixed} 两路都配、{@code kb-half} 关键字好向量坏、{@code kb-dead} 两路都坏。</p>
     *
     * <p>"两路都没配"的空壳体不在里面：{@code InfraSettings.validate()} 拒绝保存它（配了等于
     * 没配），所以那种形态到不了这一层。</p>
     */
    private static InfraSettings settings() {
        InfraSettings s = new InfraSettings();
        s.setConfigured(true);

        InfraSettings.Connection es = new InfraSettings.Connection();
        es.setId("es-main");
        es.setName("es-main");
        es.setType(InfraSettings.TYPE_ELASTICSEARCH);
        InfraSettings.Elasticsearch esCfg = new InfraSettings.Elasticsearch();
        esCfg.setHost("es.internal");
        es.setEs(esCfg);

        InfraSettings.Connection qd = new InfraSettings.Connection();
        qd.setId("qdrant-vec");
        qd.setName("qdrant-vec");
        qd.setType(InfraSettings.TYPE_QDRANT);
        InfraSettings.VectorStore vec = new InfraSettings.VectorStore();
        vec.setUri("http://qdrant.internal:6333");
        qd.setVector(vec);

        // 建了但没填地址：存在却不可用，模拟"连接被清空"而不是"被删"
        InfraSettings.Connection dead = new InfraSettings.Connection();
        dead.setId("qdrant-dead");
        dead.setName("qdrant-dead");
        dead.setType(InfraSettings.TYPE_QDRANT);
        dead.setVector(new InfraSettings.VectorStore());

        InfraSettings.Connection esDead = new InfraSettings.Connection();
        esDead.setId("es-dead");
        esDead.setName("es-dead");
        esDead.setType(InfraSettings.TYPE_ELASTICSEARCH);
        esDead.setEs(new InfraSettings.Elasticsearch());

        Map<String, InfraSettings.Connection> conns = new LinkedHashMap<>();
        conns.put(es.getId(), es);
        conns.put(qd.getId(), qd);
        conns.put(dead.getId(), dead);
        conns.put(esDead.getId(), esDead);
        s.setConnections(conns);

        Map<String, InfraSettings.KnowledgeBody> bodies = new LinkedHashMap<>();
        bodies.put("kb-kw", body("kb-kw", "es-main", null));
        bodies.put("kb-vec", body("kb-vec", null, "qdrant-vec"));
        bodies.put("kb-mixed", body("kb-mixed", "es-main", "qdrant-vec"));
        bodies.put("kb-half", body("kb-half", "es-main", "qdrant-dead"));
        bodies.put("kb-dead", body("kb-dead", "es-dead", "qdrant-dead"));
        s.setBodies(bodies);
        return s;
    }

    /** 双槽体：keyword / vector 各是一个连接 id，可空 */
    private static InfraSettings.KnowledgeBody body(String id, String keyword, String vector) {
        InfraSettings.KnowledgeBody b = new InfraSettings.KnowledgeBody();
        b.setId(id);
        b.setName(id);
        b.setKeyword(keyword);
        b.setVector(vector);
        return b;
    }

    /** 给域绑一个体并落盘（模拟管控台那一步） */
    private void bind(String domain, String bodyId) {        domainStore.setBody(domain, bodyId);
        Map<String, Boolean> callables = new LinkedHashMap<>();
        callables.put(domain, true);
        domainStore.saveDeclarations(callables);
    }

    @Test
    @DisplayName("只配关键字：解析出一条 keyword 绑定，topK 取连接自己声明的")
    void keywordOnlyBody_resolvesOneBinding() {
        bind("default.sales", "kb-kw");

        List<RetrievalPlan.BodyBinding> bodies = resolver.bodiesOf("default.sales");
        assertEquals(1, bodies.size());
        RetrievalPlan.BodyBinding binding = bodies.get(0);
        assertEquals("kb-kw", binding.bodyId());
        assertEquals(InfraSettings.KIND_KEYWORD, binding.kind());
        assertEquals("es-main", binding.connectionId());
        assertEquals(7, binding.topK());
    }

    @Test
    @DisplayName("两路都配：解析出两条绑定，向量在前、关键字在后")
    void hybridBody_resolvesBothModes() {
        bind("default.sales", "kb-mixed");

        List<RetrievalPlan.BodyBinding> bodies = resolver.bodiesOf("default.sales");
        assertEquals(2, bodies.size(), "两路都配就是混合召回，不该只开一条");

        RetrievalPlan.BodyBinding vector = bodies.get(0);
        assertEquals(InfraSettings.KIND_VECTOR, vector.kind(), "向量在前：与 RetrievalPlan.fixed() 的顺序一致");
        assertEquals("qdrant-vec", vector.connectionId());
        assertEquals("kb-mixed", vector.bodyId());

        RetrievalPlan.BodyBinding keyword = bodies.get(1);
        assertEquals(InfraSettings.KIND_KEYWORD, keyword.kind());
        assertEquals("es-main", keyword.connectionId());
        assertEquals(7, keyword.topK());
        assertEquals("kb-mixed", keyword.bodyId());
    }

    @Test
    @DisplayName("一路连接不可用：那一跳跳过，另一路照走（不整块失效）")
    void oneDeadSlot_keepsOtherSlot() {
        bind("default.sales", "kb-half");

        List<RetrievalPlan.BodyBinding> bodies = resolver.bodiesOf("default.sales");
        assertEquals(1, bodies.size());
        assertEquals(InfraSettings.KIND_KEYWORD, bodies.get(0).kind(),
                "向量那台的地址被清空了，关键字路不该跟着消失");
    }

    @Test
    @DisplayName("两路连接都不可用：空列表，域绑它等于没绑（工具也不该出现）")
    void allSlotsDead_resolvesToEmpty() {
        bind("default.sales", "kb-dead");

        assertTrue(resolver.bodiesOf("default.sales").isEmpty());
        assertNull(holder.current().usableBody("kb-dead"),
                "两路都不可用 = 这个体干不了活，绑它的域不该拿到检索工具");
    }

    @Test
    @DisplayName("没绑的域：空列表，不去平台默认里捞一个顶上")
    void unboundDomain_isEmpty() {
        assertTrue(resolver.bodiesOf("default.other").isEmpty());
    }

    @Test
    @DisplayName("不继承：父域绑了体，子域自己没绑 → 子域这边是空的")
    void childDoesNotInheritParentBody() {
        bind("default.sales", "kb-kw");

        assertTrue(resolver.bodiesOf("default.sales.eu").isEmpty(),
                "子域不借用父域的知识体：那会让子域的文档写进父域那台机器");
    }

    @Test
    @DisplayName("绑的体已被删：绑定留着（可恢复），但本轮不检索")
    void deletedBody_resolvesToEmpty() {
        bind("default.sales", "kb-gone");

        assertTrue(resolver.bodiesOf("default.sales").isEmpty());
        assertEquals("kb-gone", resolver.bodyIdOf("default.sales"),
                "绑定不清：先删体再建同名体应当能恢复");
    }

    @Test
    @DisplayName("平台默认体：取第一个这一路配了且连接可用的体")
    void platformBody_picksFirstUsable() {
        RetrievalPlan.BodyBinding keyword = resolver.platformBody(InfraSettings.KIND_KEYWORD);
        assertEquals("kb-kw", keyword.bodyId());
        assertEquals("es-main", keyword.connectionId());

        RetrievalPlan.BodyBinding vector = resolver.platformBody(InfraSettings.KIND_VECTOR);
        assertEquals("kb-vec", vector.bodyId());
        assertEquals("qdrant-vec", vector.connectionId());
    }

    @Test
    @DisplayName("平台默认体跳过坏槽：唯一配了向量的体连接不可用 → 继续找下一个")
    void platformBody_skipsDeadSlot() {
        // kb-vec 在前且可用；把它改坏之后，剩下的向量体只有 kb-mixed（可用）
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        InfraSettings broken = settings();
        broken.connection("qdrant-vec").getVector().setUri("  ");
        KnowledgeProperties knowledge = new KnowledgeProperties();
        InfraSettingsHolder holder = new InfraSettingsHolder(
                new ElasticsearchProperties(), new RedisProperties(), knowledge,
                new InfraSettingsStore(new StorageLocations(env)));
        holder.apply(broken);
        KnowledgeBodyResolver resolver = new KnowledgeBodyResolver(
                holder, new DomainStore(new StorageLocations(env)), knowledge, new RetrievalProperties());

        assertNull(resolver.platformBody(InfraSettings.KIND_VECTOR),
                "kb-vec / kb-mixed / kb-half 的向量连接全都不可用，没有可用体");
        assertEquals("kb-kw", resolver.platformBody(InfraSettings.KIND_KEYWORD).bodyId());
    }

    @Test
    @DisplayName("绑定随域配置落盘：重启（新实例重读）后还在，解绑后消失")
    void bindingSurvivesReload() {
        bind("default.sales", "kb-kw");

        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        DomainStore reloaded = new DomainStore(new StorageLocations(env));
        reloaded.load();
        assertEquals("kb-kw", reloaded.bodyOf("default.sales"));

        // 解绑（传 null）也要落得住：否则删掉体之后域上会留一个自己都看不见的绑定
        reloaded.setBody("default.sales", null);
        reloaded.saveDeclarations(Map.of("default.sales", true));
        DomainStore again = new DomainStore(new StorageLocations(env));
        again.load();
        assertNull(again.bodyOf("default.sales"));
    }
}
