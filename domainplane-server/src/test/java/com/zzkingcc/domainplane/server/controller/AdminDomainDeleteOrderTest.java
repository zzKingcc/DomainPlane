package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.knowledge.DomainChannelProvider;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-04 的另一半：删域流程里的<b>执行顺序</b>。
 *
 * <p>I-03/I-04 把两处 Redis 清理改成"失败即抛"之后，"抛出会中止在哪里"就成了必须锁死的行为。
 * 原顺序是「删索引 → 域属性 → Redis → 删域」：Redis 失败时中止，索引已经删掉、域却还在，
 * 留下一个比直接失败更难收拾的半完成状态（域还在、知识库空了，用户只能再点一次删除，
 * 而此时索引已经不存在 → 第二步直接跳过 Redis 之外的一切）。</p>
 *
 * <p>现在的顺序是「<b>Redis 记忆与检查点 → 知识库索引 → 域属性 → 删域</b>」，
 * 中止点永远落在"还没有任何东西被删"的位置。</p>
 *
 * <p>本测试用<b>调用序列</b>把这条顺序钉死，而不是只断言结果 —— 结果断言在两种顺序下都会绿。</p>
 *
 * @author zzkingcc
 */
@DisplayName("I-04 删域顺序：Redis 清理最先，失败时索引与域都还没被动过")
class AdminDomainDeleteOrderTest {

    // ==================== 桩 ====================

    /** 记录动作先后顺序的日志 */
    private static final List<String> CALLS = new ArrayList<>();

    private static void record(String what) {
        CALLS.add(what);
    }

    private static StorageLocations storage() {
        // 三个目录指到不存在的路径：StorageLocations 会 createDirectories，
        // 但本测试从不真正读写文件（所有涉及落盘的方法都被桩覆盖）
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", "target/test-storage/settings");
        props.put("domainplane.logging.path", "target/test-storage/logs");
        props.put("domainplane.export.path", "target/test-storage/export");
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new StorageLocations(env);
    }

    private static class StubRegistry extends DomainRegistry {
        private final Set<String> existing = new LinkedHashSet<>(List.of("default", "default.sales", "default.sales.order"));
        boolean deleteResult = true;

        @Override public boolean contains(String domainId) { return existing.contains(domainId); }
        @Override public boolean isBuiltin(String domainId) { return "default".equals(domainId); }
        @Override public List<String> descendantsOf(String domainId) {
            record("registry.descendantsOf");
            // 真实实现是"严格子孙"（前缀 id + SEPARATOR），不含自身
            return List.of("default.sales.order");
        }
        @Override public DomainDeleteResult delete(String domainId) {
            record("registry.delete");
            return deleteResult ? DomainDeleteResult.ok(List.of("default.sales", "default.sales.order"))
                    : DomainDeleteResult.fail("删除失败");
        }
        @Override public Set<String> manualIds() { return Set.of(); }
        @Override public Set<String> callableIds() { return Set.of(); }
    }

    private static class StubKnowledgeBase extends KnowledgeBaseService {
        RuntimeException failure;

        StubKnowledgeBase() {
            // RagProperties 不能传 null：KnowledgeBaseService 构造器会真的按它建导入线程池
            super(null, null, new RagProperties(), null, null, null);
        }

        @Override public List<String> deleteIndices(Collection<String> domains) {
            record("kb.deleteIndices");
            if (failure != null) {
                throw failure;
            }
            return List.of("idx-a", "idx-b");
        }

        @Override public void shutdown() { /* 不关别人的池 */ }
    }

    private static class StubDomainStore extends DomainStore {
        StubDomainStore() { super(storage()); }
        @Override public void saveDeclarations(Map<String, Boolean> callables) { record("store.saveDeclarations"); }
        @Override public String filePath() { return "test/domains.json"; }
    }

    private static class StubSettingsStore extends DomainSettingsStore {
        StubSettingsStore() { super(storage()); }
        @Override public void removePrompts(Collection<String> domains) { record("settings.removePrompts"); }
    }

    private static class StubModelRegistry extends ModelProfileRegistry {
        StubModelRegistry() { super(new ModelProfileStore(storage())); }
        @Override public synchronized void unbindDomains(Collection<String> domains) { record("model.unbindDomains"); }
    }

    private static class StubToolRegistry extends ToolRegistry {
        @Override public void forgetDomains(Collection<String> domains) { record("tool.forgetDomains"); }
    }

    private static class StubChannelProvider extends DomainChannelProvider {
        StubChannelProvider() { super(null, (EmbeddingModel) null, null); }
        @Override public void evictIndices(Collection<String> indexNames) { record("channel.evictIndices"); }
    }

    private static class StubMemoryStore extends RedisChatMemoryStore {
        RuntimeException failure;
        int returnValue = 2;

        StubMemoryStore() { super(null, null); }

        @Override public int deleteByDomain(String domain) {
            record("memory.deleteByDomain:" + domain);
            if (failure != null) {
                throw failure;
            }
            return returnValue;
        }
    }

    private static class StubCheckpointSaver extends RedisCheckpointSaver {
        RuntimeException failure;
        int returnValue = 1;

        StubCheckpointSaver() { super(null, null); }

        @Override public int deleteByDomain(String domain) {
            record("checkpoint.deleteByDomain:" + domain);
            if (failure != null) {
                throw failure;
            }
            return returnValue;
        }
    }

    private record Harness(AdminDomainController controller, StubRegistry registry,
                           StubKnowledgeBase kb, StubMemoryStore memory,
                           StubCheckpointSaver checkpoint) {}

    private static Harness harness() {
        CALLS.clear();
        StubRegistry registry = new StubRegistry();
        StubKnowledgeBase kb = new StubKnowledgeBase();
        StubMemoryStore memory = new StubMemoryStore();
        StubCheckpointSaver checkpoint = new StubCheckpointSaver();
        AdminDomainController controller = new AdminDomainController(
                registry,
                new StubDomainStore(),
                kb,
                new StubSettingsStore(),
                new StubModelRegistry(),
                new StubToolRegistry(),
                new StubChannelProvider(),
                memory,
                checkpoint,
                // 删域前会先停掉该域在跑的会话；本用例不关注，传真实实例即可（无 running key）
                new CancellationRegistry());
        return new Harness(controller, registry, kb, memory, checkpoint);
    }

    private static int indexOf(String prefix) {
        for (int i = 0; i < CALLS.size(); i++) {
            if (CALLS.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 顺序 ====================

    @Test
    @DisplayName("完整删除的动作顺序：Redis → 索引 → 域属性 → 删域")
    void fullDeleteFollowsRequiredOrder() {
        Harness h = harness();

        h.controller().delete("default.sales");

        int firstMemory = indexOf("memory.deleteByDomain");
        int firstIndex = indexOf("kb.deleteIndices");
        int evict = indexOf("channel.evictIndices");
        int prompts = indexOf("settings.removePrompts");
        int unbind = indexOf("model.unbindDomains");
        int forget = indexOf("tool.forgetDomains");
        int save = indexOf("store.saveDeclarations");
        int delete = indexOf("registry.delete");

        assertTrue(firstMemory >= 0, "必须清记忆：" + CALLS);
        assertTrue(firstIndex > firstMemory,
                "索引删除必须排在 Redis 清理之后，实际：" + CALLS);
        assertTrue(evict > firstIndex, "缓存失效在索引删除之后：" + CALLS);
        assertTrue(prompts > firstIndex, "域属性清理在索引删除之后：" + CALLS);
        assertTrue(unbind > firstIndex, "解绑在索引删除之后：" + CALLS);
        assertTrue(forget > firstIndex, "工具声明剥离在索引删除之后：" + CALLS);
        // saveDeclarations 落盘的是"删完之后"的域声明，所以它必须排在 delete 之后
        assertTrue(delete > prompts && delete > unbind && delete > forget,
                "删域必须在三类域属性清理之后：" + CALLS);
        assertTrue(save > delete, "声明落盘在删域之后（落盘的是删除后的结果）：" + CALLS);
    }

    @Test
    @DisplayName("记忆先于检查点清理，且逐域成对执行（自身 + 全部子孙）")
    void memoryAndCheckpointArePairedPerDomain() {
        Harness h = harness();

        h.controller().delete("default.sales");

        // 每个域都是 memory 紧跟 checkpoint，不交叉
        List<String> redisCalls = CALLS.stream()
                .filter(c -> c.startsWith("memory.") || c.startsWith("checkpoint."))
                .toList();
        assertEquals(4, redisCalls.size(), "2 个域（自身 + 1 个子孙）× 2 项清理：" + redisCalls);
        assertEquals("memory.deleteByDomain:default.sales.order", redisCalls.get(0));
        assertEquals("checkpoint.deleteByDomain:default.sales.order", redisCalls.get(1));
        assertEquals("memory.deleteByDomain:default.sales", redisCalls.get(2));
        assertEquals("checkpoint.deleteByDomain:default.sales", redisCalls.get(3));
    }

    // ==================== 中止点 ====================

    @Test
    @DisplayName("记忆清理失败：中止，且索引与域都没有被碰过")
    void abortsBeforeAnythingIsDeletedWhenMemoryFails() {
        Harness h = harness();
        h.memory().failure = new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "Redis 挂了");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales"));

        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertTrue(e.getMessage().contains("已中止、域未删除"), "文案要说明中止：" + e.getMessage());
        assertEquals(-1, indexOf("kb.deleteIndices"), "索引还没被删：" + CALLS);
        assertEquals(-1, indexOf("registry.delete"), "域还没被删：" + CALLS);
        assertEquals(-1, indexOf("settings.removePrompts"), "域属性还没被动：" + CALLS);
    }

    @Test
    @DisplayName("检查点清理失败：同样中止在索引之前")
    void abortsBeforeAnythingIsDeletedWhenCheckpointFails() {
        Harness h = harness();
        h.checkpoint().failure = new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "断点损坏");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales"));

        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertEquals(-1, indexOf("kb.deleteIndices"), "索引还没被删：" + CALLS);
        assertEquals(-1, indexOf("registry.delete"), "域还没被删：" + CALLS);
    }

    @Test
    @DisplayName("索引清理失败：中止，域属性与域都保留（Redis 已清是既定代价，域还在）")
    void abortsBeforeDomainRemovalWhenIndexCleanupFails() {
        Harness h = harness();
        h.kb().failure = new com.zzkingcc.domainplane.common.exception.KnowledgeBaseException(
                ErrorCode.KNOWLEDGE_BASE_ERROR, "ES 不可达");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales"));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_ERROR, e.getErrorCode());
        assertEquals(-1, indexOf("settings.removePrompts"), "域属性还没被动：" + CALLS);
        assertEquals(-1, indexOf("registry.delete"), "域还没被删：" + CALLS);
    }

    // ==================== 前置校验 ====================

    @Test
    @DisplayName("域不存在 / 根域：直接拒绝，一个 Redis 命令都不发")
    void rejectsBeforeTouchingAnything() {
        Harness missing = harness();
        assertThrows(BaseException.class, () -> missing.controller().delete("default.nope"));
        assertTrue(CALLS.stream().noneMatch(c -> c.startsWith("memory.") || c.startsWith("checkpoint.")
                || c.startsWith("kb.")), "前置校验失败不该有任何副作用：" + CALLS);

        Harness root = harness();
        BaseException e = assertThrows(BaseException.class, () -> root.controller().delete("default"));
        assertTrue(e.getMessage().contains("根域不可删除"));
        assertFalse(CALLS.contains("kb.deleteIndices"), "根域不可删时不该删索引：" + CALLS);
    }

    @Test
    @DisplayName("registry.delete 返回失败时也报错（不能当成删成功）")
    void reportsRegistryDeleteFailure() {
        Harness h = harness();
        h.registry().deleteResult = false;

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales"));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
    }

    @Test
    @DisplayName("成功路径的返回值结构完整（管控台靠它一次刷新）")
    void successReturnsFullView() {
        Harness h = harness();

        Map<String, Object> view = h.controller().delete("default.sales");

        assertEquals(0, view.get("code"));
        assertEquals("deleted", view.get("action"));
        assertEquals("default.sales", view.get("domain"));
        assertEquals(List.of("default.sales", "default.sales.order"), view.get("removed"));
        assertEquals(List.of("idx-a", "idx-b"), view.get("removedIndices"));
        assertEquals("test/domains.json", view.get("settingsFile"));
        assertTrue(CALLS.contains("store.saveDeclarations"), "删除声明要落盘：" + CALLS);
    }
}
