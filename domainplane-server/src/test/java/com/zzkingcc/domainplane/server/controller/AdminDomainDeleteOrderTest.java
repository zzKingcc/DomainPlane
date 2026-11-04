package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationArchive;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationSummary;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisHistorySearch;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.config.DomainProperties;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.DomainChannelProvider;
import com.zzkingcc.domainplane.server.knowledge.MapKnowledgeBackendRegistry;
import com.zzkingcc.domainplane.server.knowledge.RetrievalPlan;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import com.zzkingcc.domainplane.server.mcp.McpConnectionStore;
import com.zzkingcc.domainplane.server.settings.DomainPurgeService;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.DomainVersionSignal;
import com.zzkingcc.domainplane.server.settings.DomainVersionWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删域的顺序与"不碰知识库"的纪律。
 *
 * <p>v2 改了语义：删域<b>只解绑</b>，不删索引、不删原文、不碰任何后端。所以本用例钉三件事：</p>
 * <ol>
 *   <li>Redis 清理仍排在域属性清理之前（中途失败不能留下"半清空"的域）；</li>
 *   <li>整个流程<b>一次知识库调用都没有</b>——存储不可达也必须能删掉域；</li>
 *   <li>返回体带 {@code retainedBodies}：被保留的知识体，页面据此说"知识没丢"。</li>
 * </ol>
 *
 * @author zzkingcc
 */
class AdminDomainDeleteOrderTest {

    private static final List<String> CALLS = new ArrayList<>();

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    private static void record(String call) {
        CALLS.add(call);
    }

    // ==================== 桩 ====================

    private static StorageLocations storage() {
        return new StorageLocations(new StandardEnvironment());
    }

    private static class StubRegistry extends DomainRegistry {
        boolean deleteResult = true;

        StubRegistry() {
            super();
        }

        @Override public List<String> descendantsOf(String domainId) {
            return List.of("default.sales.order");
        }

        @Override public boolean contains(String domainId) {
            return !"default.nope".equals(domainId);
        }

        @Override public boolean isBuiltin(String domainId) {
            return "default".equals(domainId);
        }

        @Override public Set<String> ids() {
            return Set.of("default", "default.sales", "default.sales.order");
        }

        @Override public synchronized DomainDeleteResult delete(String domainId) {
            record("registry.delete");
            return deleteResult ? DomainDeleteResult.ok(List.of("default.sales", "default.sales.order"))
                    : DomainDeleteResult.fail("删除失败");
        }

        @Override public Set<String> manualIds() { return Set.of(); }
        @Override public Set<String> callableIds() { return Set.of(); }
    }

    /**
     * 域存储桩：绑定表给出"这两个域都绑了 kb-sales"。
     *
     * <p>刻意覆盖 {@code bodyOf} 而<b>不</b>覆盖任何存储调用：删域路径只应读绑定，
     * 一旦它去问知识库（删索引 / 删档案），本用例的 CALLS 里就会多出记录。</p>
     */
    private static class StubDomainStore extends DomainStore {
        StubDomainStore() { super(storage()); }

        @Override public String bodyOf(String domainId) { return "kb-sales"; }

        @Override public void saveDeclarations(Map<String, Boolean> callables) { record("store.saveDeclarations"); }

        @Override public String filePath() { return "test/domains.json"; }
    }

    private static class StubSettingsStore extends DomainSettingsStore {
        StubSettingsStore() { super(storage()); }
        @Override public void removeDomains(Collection<String> domains) { record("settings.removeDomains"); }
    }

    private static class StubModelRegistry extends ModelProfileRegistry {
        StubModelRegistry() { super(new ModelProfileStore(storage())); }
        @Override public synchronized void unbindDomains(Collection<String> domains) { record("model.unbindDomains"); }
    }

    private static class StubToolRegistry extends ToolRegistry {
        @Override public void forgetDomains(Collection<String> domains) { record("tool.forgetDomains"); }
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

    private static class StubConversationArchive extends RedisConversationArchive {
        RuntimeException failure;
        int returnValue = 3;

        StubConversationArchive() { super(null); }

        @Override public int deleteByDomain(String domain) {
            record("archive.deleteByDomain:" + domain);
            if (failure != null) {
                throw failure;
            }
            return returnValue;
        }
    }

    private static class StubHistorySearch extends RedisHistorySearch {
        RuntimeException failure;
        int returnValue = 7;

        StubHistorySearch() { super(null, null); }

        @Override public int deleteByDomain(String domain) {
            record("index.deleteByDomain:" + domain);
            if (failure != null) {
                throw failure;
            }
            return returnValue;
        }
    }

    private static class StubConversationSummary extends RedisConversationSummary {
        RuntimeException failure;
        int returnValue = 2;

        StubConversationSummary() { super(null); }

        @Override public int deleteByDomain(String domain) {
            record("summary.deleteByDomain:" + domain);
            if (failure != null) {
                throw failure;
            }
            return returnValue;
        }
    }

    private record Harness(AdminDomainController controller, StubRegistry registry,
                           StubMemoryStore memory, StubConversationArchive archive,
                           StubHistorySearch index, StubConversationSummary summary,
                           StubCheckpointSaver checkpoint) {}

    private static Harness harness() {
        CALLS.clear();
        StubRegistry registry = new StubRegistry();
        StubMemoryStore memory = new StubMemoryStore();
        StubConversationArchive archive = new StubConversationArchive();
        StubHistorySearch index = new StubHistorySearch();
        StubConversationSummary summary = new StubConversationSummary();
        StubCheckpointSaver checkpoint = new StubCheckpointSaver();
        StubDomainStore store = new StubDomainStore();
        StubSettingsStore settingsStore = new StubSettingsStore();
        StubToolRegistry toolRegistry = new StubToolRegistry();
        DomainPurgeService purgeService = new DomainPurgeService(
                registry,
                store,
                settingsStore,
                new StubModelRegistry(),
                new McpConnectionStore(storage()),
                toolRegistry,
                memory,
                checkpoint,
                archive,
                index,
                summary,
                // 删域前会先停掉该域在飞的会话；本用例不关注，传真实实例即可（无 running key）
                new CancellationRegistry());
        AdminDomainController controller = new AdminDomainController(
                registry,
                store,
                toolRegistry,
                settingsStore,
                purgeService,
                new DomainProperties(),
                new DomainVersionWatcher(store, new DomainVersionSignal(null, null)),
                // 绑知识体那条路径本用例不走，存储配置持有者传 null
                null);
        return new Harness(controller, registry, memory, archive, index, summary, checkpoint);
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
    @DisplayName("完整删除的动作顺序：Redis → 域属性 → 删域；知识库一次都没被调用")
    void fullDeleteFollowsRequiredOrderAndNeverTouchesKnowledge() {
        Harness h = harness();

        h.controller().delete("default.sales", true, null);

        int firstMemory = indexOf("memory.deleteByDomain");
        int prompts = indexOf("settings.removeDomains");
        int unbind = indexOf("model.unbindDomains");
        int forget = indexOf("tool.forgetDomains");
        int save = indexOf("store.saveDeclarations");
        int delete = indexOf("registry.delete");

        assertTrue(firstMemory >= 0, "必须清记忆：" + CALLS);
        assertTrue(prompts > firstMemory, "域属性清理在 Redis 清理之后：" + CALLS);
        assertTrue(unbind > firstMemory, "模型解绑在 Redis 清理之后：" + CALLS);
        assertTrue(forget > firstMemory, "工具声明剥离在 Redis 清理之后：" + CALLS);
        assertTrue(delete > prompts && delete > unbind && delete > forget,
                "删域必须在三类域属性清理之后：" + CALLS);
        assertTrue(save > delete, "声明落盘在删域之后（落盘的是删除后的结果）：" + CALLS);

        // 最重要的那条：整条路径没有任何知识库动作（不删索引、不删档案、不连后端）
        assertTrue(CALLS.stream().noneMatch(c -> c.startsWith("kb.")),
                "删域不该碰知识库存储：" + CALLS);
    }

    @Test
    @DisplayName("记忆 → 检查点 → 归档 → 检索索引 → 滚动摘要，逐域成套执行（自身 + 全部子孙）")
    void memoryCheckpointAndArchiveArePairedPerDomain() {
        Harness h = harness();

        h.controller().delete("default.sales", true, null);

        List<String> redisCalls = CALLS.stream()
                .filter(c -> c.startsWith("memory.") || c.startsWith("checkpoint.")
                        || c.startsWith("archive.") || c.startsWith("index.")
                        || c.startsWith("summary."))
                .toList();
        assertEquals(10, redisCalls.size(), "2 个域（自身 + 1 个子孙）× 5 项清理：" + redisCalls);
        assertEquals("memory.deleteByDomain:default.sales.order", redisCalls.get(0));
        assertEquals("checkpoint.deleteByDomain:default.sales.order", redisCalls.get(1));
        assertEquals("archive.deleteByDomain:default.sales.order", redisCalls.get(2));
        assertEquals("index.deleteByDomain:default.sales.order", redisCalls.get(3));
        assertEquals("summary.deleteByDomain:default.sales.order", redisCalls.get(4));
        assertEquals("memory.deleteByDomain:default.sales", redisCalls.get(5));
        assertEquals("checkpoint.deleteByDomain:default.sales", redisCalls.get(6));
        assertEquals("archive.deleteByDomain:default.sales", redisCalls.get(7));
        assertEquals("index.deleteByDomain:default.sales", redisCalls.get(8));
        assertEquals("summary.deleteByDomain:default.sales", redisCalls.get(9));
    }

    // ==================== 中止点 ====================

    @Test
    @DisplayName("记忆清理失败：中止，域属性与域都没有被碰过")
    void abortsBeforeAnythingIsDeletedWhenMemoryFails() {
        Harness h = harness();
        h.memory().failure = new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "Redis 挂了");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales", true, null));

        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertTrue(e.getMessage().contains("已中止、域未删除"), "文案要说明中止：" + e.getMessage());
        assertEquals(-1, indexOf("registry.delete"), "域还没被删：" + CALLS);
        assertEquals(-1, indexOf("settings.removeDomains"), "域属性还没被动：" + CALLS);
    }

    @Test
    @DisplayName("检索索引清理失败：同样中止在域属性之前")
    void abortsBeforeDomainCleanupWhenIndexCleanupFails() {
        Harness h = harness();
        h.index().failure = new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "索引损坏");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales", true, null));

        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertEquals(-1, indexOf("registry.delete"), "域还没被删：" + CALLS);
        assertEquals(-1, indexOf("settings.removeDomains"), "域属性还没被动：" + CALLS);
    }

    @Test
    @DisplayName("检查点清理失败：同样中止在域属性之前")
    void abortsBeforeDomainCleanupWhenCheckpointFails() {
        Harness h = harness();
        h.checkpoint().failure = new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "断点损坏");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales", true, null));

        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertEquals(-1, indexOf("registry.delete"), "域还没被删：" + CALLS);
    }

    @Test
    @DisplayName("归档清理失败：同样中止在域属性之前")
    void abortsBeforeDomainCleanupWhenArchiveFails() {
        Harness h = harness();
        h.archive().failure = new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "归档损坏");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales", true, null));

        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertEquals(-1, indexOf("settings.removeDomains"), "域属性还没被动：" + CALLS);
    }

    @Test
    @DisplayName("滚动摘要清理失败：同样中止在域属性之前")
    void abortsBeforeDomainCleanupWhenSummaryCleanupFails() {
        Harness h = harness();
        h.summary().failure = new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "摘要损坏");

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales", true, null));

        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertEquals(-1, indexOf("registry.delete"), "域还没被删：" + CALLS);
    }

    // ==================== 前置校验 ====================

    @Test
    @DisplayName("域不存在 / 根域：直接拒绝，一个 Redis 命令都不发")
    void rejectsBeforeTouchingAnything() {
        Harness missing = harness();
        assertThrows(BaseException.class, () -> missing.controller().delete("default.nope", true, null));
        assertTrue(CALLS.stream().noneMatch(c -> c.startsWith("memory.") || c.startsWith("checkpoint.")
                        || c.startsWith("archive.") || c.startsWith("index.") || c.startsWith("summary.")
                        || c.startsWith("kb.")),
                "前置校验失败不该有任何副作用：" + CALLS);

        Harness root = harness();
        BaseException e = assertThrows(BaseException.class, () -> root.controller().delete("default", true, null));
        assertTrue(e.getMessage().contains("根域不可删除"));
        assertEquals(-1, indexOf("registry.delete"), "根域不可删：" + CALLS);
    }

    @Test
    @DisplayName("registry.delete 返回失败时也报错（不能当成删成功）")
    void reportsRegistryDeleteFailure() {
        Harness h = harness();
        h.registry().deleteResult = false;

        BaseException e = assertThrows(BaseException.class, () -> h.controller().delete("default.sales", true, null));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
    }

    @Test
    @DisplayName("成功路径的返回值结构完整，且带出被保留的知识体")
    void successReturnsFullView() {
        Harness h = harness();

        Map<String, Object> view = h.controller().delete("default.sales", true, null);

        assertEquals(0, view.get("code"));
        assertEquals("deleted", view.get("action"));
        assertEquals("default.sales", view.get("domain"));
        assertEquals(List.of("default.sales", "default.sales.order"), view.get("removed"));
        // v2：删域只解绑，体与数据都保留（同一体被两个域绑，去重后只出现一次）
        assertEquals(List.of("kb-sales"), view.get("retainedBodies"));
        assertFalse(view.containsKey("removedIndices"), "不再有'删掉几个索引'的概念：" + view.keySet());
        assertEquals("test/domains.json", view.get("settingsFile"));
        assertTrue(CALLS.contains("store.saveDeclarations"), "删除声明要落盘：" + CALLS);
    }
}
