package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.server.config.ElasticsearchProperties;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.config.RedisProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSettingsStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库统计缓存层的行为面：TTL 内不重拉、失败进熔断冷却（复用旧快照）、
 * 归属按"文档域 → 当前绑定的知识体"聚合、配置变更即失效。
 *
 * @author zzkingcc
 */
class KnowledgeStatsServiceTest {

    @TempDir
    Path tempDir;

    /** indexOwners() / list(indices) 可编程的桩；调用计数用于断言"没重拉" */
    private static final class StubKbService extends KnowledgeBaseService {

        final AtomicReference<List<DocumentItem>> items = new AtomicReference<>(List.of());
        final AtomicInteger listCalls = new AtomicInteger();
        final AtomicInteger indexCalls = new AtomicInteger();
        final AtomicReference<List<String>> indices = new AtomicReference<>(List.of("kb-1"));
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        StubKbService() {
            // super 只为满足构造链；ingest 线程池需要一份默认 RagProperties，其余依赖本桩不碰
            super(null, new RagProperties(), null, null, null, null, null, null);
        }

        /** 一次枚举同时产出"索引名 + 归属后端"，与真实实现同形 */
        @Override
        public Map<String, List<String>> indexOwners() {
            indexCalls.incrementAndGet();
            RuntimeException e = failure.get();
            if (e != null) {
                throw e;
            }
            Map<String, List<String>> owners = new LinkedHashMap<>();
            for (String index : indices.get()) {
                owners.put(index, List.of("es-main"));
            }
            return owners;
        }

        @Override
        public List<DocumentItem> list(Collection<String> indexNames) {
            listCalls.incrementAndGet();
            RuntimeException e = failure.get();
            if (e != null) {
                throw e;
            }
            return items.get();
        }
    }

    /** 假时钟：测试手动推进，不必 sleep 等真实 TTL / 冷却期 */
    private static final class TestService extends KnowledgeStatsService {

        final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

        TestService(KnowledgeBaseService kb, DomainStore store, InfraSettingsHolder holder) {
            super(kb, store, holder);
        }

        @Override
        protected long now() {
            return clock.get();
        }

        void advance(long ms) {
            clock.addAndGet(ms);
        }
    }

    private DomainStore domainStore;

    private InfraSettingsHolder infraHolder;

    private TestService service(StubKbService kb) {
        domainStore = new DomainStore(storageLocations());
        infraHolder = new InfraSettingsHolder(
                new ElasticsearchProperties(), new RedisProperties(),
                new KnowledgeProperties(), new InfraSettingsStore(storageLocations()));
        return new TestService(kb, domainStore, infraHolder);
    }

    @Test
    @DisplayName("按知识体聚合：文档归到归属域当前绑的体")
    void aggregatesByBoundBody() {
        StubKbService kb = new StubKbService();
        kb.items.set(List.of(
                item("default.sales", 4),
                item("default.sales", 3),
                item("default.other", 2)));
        TestService service = service(kb);
        domainStore.setBody("default.sales", "kb-sales");
        domainStore.setBody("default.other", "kb-other");

        KnowledgeStatsService.Stats stats = service.stats();

        assertEquals(7L, stats.byBody().get("kb-sales")[1]);
        assertEquals(2L, stats.byBody().get("kb-sales")[0], "同域两份文档算两个文档");
        assertEquals(2L, stats.byBody().get("kb-other")[1]);
        // 未绑体的域不冒充任何知识体
        assertEquals(2, stats.byBody().size());
        // 域视图仍在（删域预演用）
        assertEquals(7L, stats.byDomain().get("default.sales")[1]);
        assertFalse(stats.stale());
    }

    @Test
    @DisplayName("TTL 缓存：60s 内重复请求不重拉存储，过期后现算")
    void cachesWithinTtl() {
        StubKbService kb = new StubKbService();
        kb.items.set(List.of(item("default", 1)));
        TestService service = service(kb);

        service.stats();
        service.stats();
        service.stats();
        assertEquals(1, kb.listCalls.get(), "缓存有效期内不应该重拉");

        service.advance(KnowledgeStatsService.CACHE_TTL_MS + 1);
        service.stats();
        assertEquals(2, kb.listCalls.get(), "TTL 过期后要现算");
    }

    @Test
    @DisplayName("失败熔断：冷却期内不再发起调用；有旧快照则复用并标 stale")
    void tripsCooldownAfterFailure() {
        StubKbService kb = new StubKbService();
        kb.items.set(List.of(item("default.sales", 4)));
        TestService service = service(kb);
        domainStore.setBody("default.sales", "kb-a");
        service.stats();
        assertEquals(1, kb.listCalls.get());

        // 缓存过期后存储开始抛：这一次重试失败，进入冷却
        service.advance(KnowledgeStatsService.CACHE_TTL_MS + 1);
        kb.failure.set(new RuntimeException("es down"));
        KnowledgeStatsService.Stats degraded = service.stats();
        assertTrue(degraded.stale(), "失败后的数字要标 stale");
        assertEquals(1, degraded.byDomain().size(), "冷却期内复用旧快照");
        assertEquals(1, kb.listCalls.get(), "枚举这一步就炸了，list 不该再被调用");
        assertEquals(2, kb.indexCalls.get(), "失败只重试一次即进入冷却");

        // 冷却期内连续请求不再发起任何远程调用
        service.advance(1000);
        service.stats();
        assertEquals(2, kb.indexCalls.get(), "冷却期内连续请求不应重拉");

        // 冷却期过后恢复：照常现算，拿到新数据并清掉 stale
        service.advance(KnowledgeStatsService.FAILURE_COOLDOWN_MS + 1);
        kb.failure.set(null);
        assertFalse(service.stats().stale());
        assertEquals(3, kb.indexCalls.get());
        assertEquals(2, kb.listCalls.get());
    }

    @Test
    @DisplayName("索引只枚举一次；枚举为空时不再调 list()（存储黑洞下别白烧第二次超时）")
    void enumeratesIndicesOnceAndSkipsListWhenEmpty() {
        StubKbService kb = new StubKbService();
        kb.indices.set(List.of());
        TestService service = service(kb);

        KnowledgeStatsService.Stats stats = service.stats();

        assertEquals(1, kb.indexCalls.get(), "一次刷新只该枚举一次索引");
        assertEquals(0, kb.listCalls.get(), "索引为空就没有可列举的东西，不该再发第二个请求");
        assertTrue(stats.indices().isEmpty());
        assertFalse(stats.stale(), "枚举成功（结果为空）不算失败，不能标成旧数据");

        // 有索引时：枚举一次 + 列举一次，不多不少
        StubKbService kb2 = new StubKbService();
        kb2.items.set(List.of(item("default", 1)));
        TestService service2 = service(kb2);
        service2.stats();
        assertEquals(1, kb2.indexCalls.get());
        assertEquals(1, kb2.listCalls.get());
    }

    @Test
    @DisplayName("首次就失败：给空快照且标 stale，不抛异常，冷却期内不重试")
    void emptyWhenFirstLoadFails() {
        StubKbService kb = new StubKbService();
        kb.failure.set(new RuntimeException("es down"));
        TestService service = service(kb);

        KnowledgeStatsService.Stats stats = service.stats();
        assertTrue(stats.byBody().isEmpty());
        assertTrue(stats.stale());
        assertTrue(stats.indices().isEmpty());
        service.stats();
        assertEquals(1, kb.indexCalls.get(), "冷却期内不应重试");
        assertEquals(0, kb.listCalls.get());
    }

    @Test
    @DisplayName("失效信号：域绑定变更后下一次现算，数字反映新归属")
    void invalidatesOnDomainChange() {
        StubKbService kb = new StubKbService();
        kb.items.set(List.of(item("default.sales", 4)));
        TestService service = service(kb);
        domainStore.setBody("default.sales", "kb-a");

        assertEquals(4L, service.stats().byBody().get("kb-a")[1]);

        // 改绑到另一个体：onChange 回调清缓存，下一次统计归到新体
        domainStore.setBody("default.sales", "kb-b");
        assertEquals(4L, service.stats().byBody().get("kb-b")[1]);
        assertNull(service.stats().byBody().get("kb-a"), "旧归属不该再出现");
    }

    @Test
    @DisplayName("存储配置保存（swap）同样清缓存")
    void invalidatesOnInfraSwap() {
        StubKbService kb = new StubKbService();
        kb.items.set(List.of(item("default", 1)));
        TestService service = service(kb);
        service.stats();
        int calls = kb.listCalls.get();

        service.swap();
        service.stats();

        assertEquals(calls + 1, kb.listCalls.get(), "swap 后必须现算");
    }

    @Test
    @DisplayName("可检索量：沿链累加，但只算自己绑了知识体的域（子域文档父域查不到）")
    void retrievableFollowsBoundChain() {
        StubKbService kb = new StubKbService();
        kb.items.set(List.of(
                item("default", 4),
                item("default.sales", 3),
                item("default.ops", 1)));
        TestService service = service(kb);
        domainStore.setBody("default", "kb-root");
        domainStore.setBody("default.sales", "kb-sales");
        // default.ops 不绑体：它自己没有知识库，但能查到根域的数据

        Map<String, long[]> byDomain = service.retrievableBy(
                List.of("default", "default.sales", "default.ops", "default.sales.x"));

        assertEquals(1L, byDomain.get("default")[0], "根域只看自己");
        assertEquals(4L, byDomain.get("default")[1]);
        assertEquals(2L, byDomain.get("default.sales")[0], "本域 + 已绑体的根域");
        assertEquals(7L, byDomain.get("default.sales")[1]);
        assertEquals(1L, byDomain.get("default.ops")[0], "没绑体的域不开通道，自己的文档自己查不到");
        assertEquals(4L, byDomain.get("default.ops")[1]);
        assertEquals(2L, byDomain.get("default.sales.x")[0], "未绑体的叶子域继承链上已绑体的祖先");
        assertEquals(7L, byDomain.get("default.sales.x")[1]);
    }

    @Test
    @DisplayName("整页快照：一次枚举同时给出归属表、全平台文档/切片总数")
    void overviewCarriesOwnersAndTotals() {
        StubKbService kb = new StubKbService();
        kb.indices.set(List.of("idx-a", "idx-b"));
        kb.items.set(List.of(item("default.sales", 4), item("default.sales", 3)));
        TestService service = service(kb);
        domainStore.setBody("default.sales", "kb-sales");

        KnowledgeStatsService.Overview o = service.overview();

        assertEquals(1, kb.indexCalls.get(), "整页快照只该枚举一次索引");
        assertEquals(1, kb.listCalls.get());
        assertEquals(2, o.documents(), "文档数按 docId 行数，不按切片");
        assertEquals(7L, o.chunks());
        assertEquals(List.of("es-main"), o.owners().get("idx-a"), "归属后端要一起给出来");
        assertEquals(7L, o.byBody().get("kb-sales")[1]);
        assertFalse(o.stale());
    }

    private KnowledgeBaseService.DocumentItem item(String domain, int chunks) {
        return new KnowledgeBaseService.DocumentItem(
                "doc-" + domain + "-" + chunks, domain + ".md", domain + ".md",
                chunks, domain, "export/" + domain + ".txt");
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
