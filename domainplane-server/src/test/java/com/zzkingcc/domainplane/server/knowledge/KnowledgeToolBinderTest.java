package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.domain.capability.knowledge.KnowledgeSearchService;
import com.zzkingcc.domainplane.runtime.tool.AnnotatedToolScanner;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.config.ElasticsearchProperties;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RedisProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSettingsStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识检索工具的<b>可用域</b>：由「域绑了可用知识体」这一事实推出来，而不是注解写死。
 *
 * <p>钉四件事：</p>
 * <ol>
 *   <li>没绑任何域时，工具对<b>任何</b>域都不可见（连根域也不可见）；</li>
 *   <li>绑了可用体 → 只在该域与它的子域出现，兄弟域与根域仍不可见；</li>
 *   <li>解绑 / 体失效 → 工具跟着消失（三个时点：域配置变更、存储配置保存）；</li>
 *   <li>一路连接坏、另一路好 → 仍然出现（可用体按"至少一路"判）。</li>
 * </ol>
 *
 * @author zzkingcc
 */
@DisplayName("KnowledgeToolBinder：检索工具按域绑定动态注册")
class KnowledgeToolBinderTest {

    @TempDir
    Path tempDir;

    private ToolRegistry registry;
    private DomainStore domainStore;
    private InfraSettingsHolder holder;
    private KnowledgeToolBinder binder;

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
        holder = new InfraSettingsHolder(
                new ElasticsearchProperties(), new RedisProperties(), knowledge,
                new InfraSettingsStore(storage));
        holder.apply(settings(true));

        domainStore = new DomainStore(storage);
        domainStore.load();

        registry = new ToolRegistry();
        registry.registerAll(AnnotatedToolScanner.scan(
                new KnowledgeSearchTool(new KnowledgeSearchService((ContentRetriever) q -> List.<Content>of()))));

        binder = new KnowledgeToolBinder(registry, domainStore, holder);
        binder.start();   // 挂监听 + 注册热替换回调 + 算第一次
    }

    /** 一台 ES + 一个只配关键字的知识体；{@code esUsable=false} 时那台 ES 没填地址 */
    private static InfraSettings settings(boolean esUsable) {
        InfraSettings s = new InfraSettings();
        s.setConfigured(true);

        InfraSettings.Connection es = new InfraSettings.Connection();
        es.setId("es-main");
        es.setName("es-main");
        es.setType(InfraSettings.TYPE_ELASTICSEARCH);
        InfraSettings.Elasticsearch cfg = new InfraSettings.Elasticsearch();
        if (esUsable) {
            cfg.setHost("es.internal");
        }
        es.setEs(cfg);

        Map<String, InfraSettings.Connection> conns = new LinkedHashMap<>();
        conns.put(es.getId(), es);
        s.setConnections(conns);

        InfraSettings.KnowledgeBody body = new InfraSettings.KnowledgeBody();
        body.setId("kb-sales");
        body.setName("kb-sales");
        body.setKeyword("es-main");
        Map<String, InfraSettings.KnowledgeBody> bodies = new LinkedHashMap<>();
        bodies.put(body.getId(), body);
        s.setBodies(bodies);
        return s;
    }

    /** 模拟管控台那一步：改绑定 + 落盘（落盘会触发 onChange → 重算可用域） */
    private void bind(String domain, String bodyId) {
        domainStore.setBody(domain, bodyId);
        Map<String, Boolean> callables = new LinkedHashMap<>();
        callables.put(domain, true);
        domainStore.saveDeclarations(callables);
    }

    private boolean visible(String domain) {
        return registry.find(KnowledgeSearchTool.TOOL_NAME)
                .map(r -> r.descriptor().visibleIn(domain))
                .orElse(false);
    }

    @Test
    @DisplayName("没绑任何域：工具对任何域都不可见（根域也一样）")
    void noBinding_toolInvisibleEverywhere() {
        assertFalse(visible("default"), "写死根域就是'每个域都能调一个不存在的知识库'");
        assertFalse(visible("default.sales"));
        assertTrue(binder.visibleDomains().isEmpty());
    }

    @Test
    @DisplayName("绑了可用体：只在该域与它的子域出现")
    void boundDomain_toolVisibleOnDomainAndDescendants() {
        bind("default.sales", "kb-sales");

        assertTrue(visible("default.sales"), "绑定的域必须有工具");
        assertTrue(visible("default.sales.eu"), "子域查得到父域数据，工具也该有（声明沿链累加）");
        assertFalse(visible("default"), "父域没绑体：不该因为子域绑了就冒出来");
        assertFalse(visible("default.other"), "兄弟域不继承");
        assertEquals(List.of("default.sales"), binder.visibleDomains());
    }

    @Test
    @DisplayName("解绑：工具跟着消失（域配置落盘即回算）")
    void unbind_toolDisappears() {
        bind("default.sales", "kb-sales");
        assertTrue(visible("default.sales"));

        bind("default.sales", null);

        assertFalse(visible("default.sales"), "解绑后还留着工具 = 模型会去调一个没有知识库的域");
    }

    @Test
    @DisplayName("连接被清空：保存存储配置后工具自动消失（热替换回调）")
    void connectionGone_toolDisappears() {
        bind("default.sales", "kb-sales");
        assertTrue(visible("default.sales"));

        holder.apply(settings(false));   // 地址被清空：体还在，但一路都不可用

        assertFalse(visible("default.sales"), "体不可用时按'没绑定'同口径处理：工具直接消失");
        assertEquals(List.of("default.sales"), binder.danglingDomains(),
                "配了但坏了要能单独报出来：用户要去修，而不是以为本来就没有知识库");
    }

    @Test
    @DisplayName("一路好一路坏：工具仍在（可用体按至少一路判，不因一路坏就整块下线）")
    void oneUsableSlot_toolStays() {
        // 关键字那台好、向量那台没填地址
        InfraSettings s = settings(true);
        InfraSettings.Connection dead = new InfraSettings.Connection();
        dead.setId("qdrant-dead");
        dead.setName("qdrant-dead");
        dead.setType(InfraSettings.TYPE_QDRANT);
        dead.setVector(new InfraSettings.VectorStore());
        s.getConnections().put(dead.getId(), dead);
        s.body("kb-sales").setVector(dead.getId());
        holder.apply(s);

        bind("default.sales", "kb-sales");

        assertTrue(visible("default.sales"),
                "向量路坏了但关键字路还在：这个域仍能检索，工具不该消失");
        assertTrue(binder.danglingDomains().isEmpty(), "体可用，不算悬空绑定");
    }
}
