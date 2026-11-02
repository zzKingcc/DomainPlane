package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.config.DomainProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.settings.DomainPurgeService;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.DomainVersionSignal;
import com.zzkingcc.domainplane.server.settings.DomainVersionWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F03 软删三阶段在管控端口的行为面：删除=软删、可撤销、到期不可撤销、
 * 版本冲突拒写、历史库可回滚。
 *
 * <p>级联清理（硬删）的顺序由 {@code AdminDomainDeleteOrderTest} 钉死，
 * 本用例只关心"删之前"发生了什么——软删一个域，Redis / 知识库 / 域属性
 * 一样都不该被碰，域要完整留在注册表里。</p>
 *
 * @author zzkingcc
 */
@DisplayName("F03 软删：删除=标记，宽限期内可撤销，回滚=新版本")
class AdminDomainSoftDeleteTest {

    @TempDir
    Path tempDir;

    private DomainRegistry registry;

    private DomainStore store;

    private AdminDomainController controller;

    private DomainProperties properties;

    @BeforeEach
    void setUp() {
        registry = new DomainRegistry();
        registry.create("default.sales");
        registry.create("default.sales.order");
        store = new DomainStore(storageLocations());
        properties = new DomainProperties();
        // 软删路径不触发级联清理，DomainPurgeService 的协作者留空即可；
        // 真被调用时会以 NPE 抗议——那正说明软删不该走到硬删
        DomainPurgeService purgeService = new DomainPurgeService(
                registry, store, null, null, null, null,
                new ToolRegistry(), null, null, null, null, null, null, null);
        controller = new AdminDomainController(
                registry,
                store,
                new ToolRegistry(),
                new DomainSettingsStore(storageLocations()),
                purgeService,
                properties,
                new DomainVersionWatcher(store, new DomainVersionSignal(null, null)),
                // 软删用例不绑知识体，存储配置持有者传 null
                null);
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

    private Path settingsFile() {
        return tempDir.resolve("config").resolve("domains.json");
    }

    @Test
    @DisplayName("软删：整支树打标记，Redis/知识库/域属性一样不碰，域仍在注册表")
    void softDeleteMarksSubtreeWithoutCascade() {
        Map<String, Object> view = controller.delete("default.sales", false, null);

        assertEquals("soft-deleted", view.get("action"));
        assertNotNull(view.get("deletedAt"), "要带回删除时刻，管控台据此显示倒计时");
        assertEquals(24L * 3600 * 1000, view.get("graceMs"), "默认宽限期 24h");

        // 域完整留在注册表，结构判据原样
        assertTrue(registry.contains("default.sales"));
        assertTrue(registry.contains("default.sales.order"));
        assertTrue(registry.isCallable("default.sales.order"),
                "软删不改结构判据，否则 resume 收尾会被入口闸门误杀");
        assertTrue(registry.isPendingDelete("default.sales"));
        assertTrue(registry.isPendingDelete("default.sales.order"),
                "整支树一起标记：只标父会留下仍可调用的子孙");

        // 落盘 = version+1 的普通变更，且带历史快照
        assertEquals(1L, store.currentVersion());
        assertTrue(store.isPendingDelete("default.sales"));
        DomainStore.StoredDomains reloaded = new DomainStore(storageLocations()).load();
        assertEquals(1L, reloaded.version());
        assertTrue(reloaded.pendingDeletes().containsKey("default.sales"));
        assertTrue(reloaded.pendingDeletes().containsKey("default.sales.order"));
    }

    @Test
    @DisplayName("宽限期内重复软删：幂等返回现状，不刷新倒计时")
    void repeatedSoftDeleteIsIdempotent() {
        Map<String, Object> first = controller.delete("default.sales", false, null);
        long versionAfterFirst = store.currentVersion();

        Map<String, Object> second = controller.delete("default.sales", false, null);

        assertEquals(first.get("deletedAt"), second.get("deletedAt"),
                "重复删除不刷新倒计时——刷新等于给拖延续命");
        assertEquals(versionAfterFirst, store.currentVersion(), "幂等路径不该再落一次盘");
    }

    @Test
    @DisplayName("撤销软删：清除标记即恢复，也是一次 version+1 的变更")
    void restoreClearsMarkers() {
        controller.delete("default.sales", false, null);

        Map<String, Object> view = controller.restore("default.sales", null);

        assertEquals("restored", view.get("action"));
        assertFalse(registry.isPendingDelete("default.sales"));
        assertFalse(registry.isPendingDelete("default.sales.order"));
        assertFalse(store.isPendingDelete("default.sales"));
        assertEquals(2L, store.currentVersion(), "撤销也是一次普通变更");
        DomainStore.StoredDomains reloaded = new DomainStore(storageLocations()).load();
        assertTrue(reloaded.pendingDeletes().isEmpty(), "重启后标记也不该复活");
    }

    @Test
    @DisplayName("没有软删标记的域不许撤销")
    void restoreRejectsWhenNotPending() {
        BaseException e = assertThrows(BaseException.class,
                () -> controller.restore("default.sales", null));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("不在删除宽限期内"));
    }

    @Test
    @DisplayName("宽限期已到：撤销被拒（DOMAIN_DELETE_BLOCKED），恢复请走回滚")
    void restoreRejectsAfterGraceExpired() {
        properties.setDeleteGrace(Duration.ofMillis(10));
        controller.delete("default.sales", false, null);
        sleepPastGrace();

        BaseException e = assertThrows(BaseException.class,
                () -> controller.restore("default.sales", null));

        assertEquals(ErrorCode.DOMAIN_DELETE_BLOCKED, e.getErrorCode(),
                "宽限期已到，调度器随时可能开跑，撤销它等于和定时任务赛跑");
    }

    @Test
    @DisplayName("乐观并发：baseVersion 过期即 409，杜绝后写覆盖先写")
    void staleBaseVersionIsRejected() {
        BaseException e = assertThrows(BaseException.class,
                () -> controller.delete("default.sales", false, 99L));

        assertEquals(ErrorCode.VERSION_CONFLICT, e.getErrorCode());
        assertTrue(e.getMessage().contains("v99"), "文案要说明基于哪个过期版本");
    }

    @Test
    @DisplayName("历史版本库：每次变更落一份快照，清单可查")
    void historyListsSnapshots() {
        controller.delete("default.sales", false, null);   // v1 软删
        controller.restore("default.sales", null);          // v2 撤销

        Map<String, Object> view = controller.history();

        assertEquals(2L, view.get("currentVersion"));
        assertEquals(2, ((java.util.List<?>) view.get("entries")).size(),
                "两次变更两份快照，最新的标记为 current");
        Map<?, ?> latest = (Map<?, ?>) ((java.util.List<?>) view.get("entries")).get(0);
        assertEquals(2L, latest.get("version"));
        assertEquals(Boolean.TRUE, latest.get("current"));
    }

    @Test
    @DisplayName("回滚：快照内容作为新版本落盘，注册表整体重建")
    void rollbackAppliesSnapshotAsNewVersion() {
        // 经管控器创建才落盘：registry.create 只动内存，版本号是文件的事
        AdminDomainController.CreateBody hr = new AdminDomainController.CreateBody();
        hr.setId("default.hr");
        controller.create(hr);                             // v1：sales + hr
        controller.delete("default.sales", false, null);   // v2：软删 sales
        assertEquals(2L, store.currentVersion());

        AdminDomainController.RollbackBody rollback = new AdminDomainController.RollbackBody();
        rollback.setVersion(1L);
        Map<String, Object> view = controller.rollback(rollback);

        assertEquals("rolled-back", view.get("action"));
        assertEquals(1L, view.get("rolledBackFrom"));
        assertEquals(3L, view.get("newVersion"), "回滚是新变更，不是时间倒流");
        assertTrue(registry.contains("default.hr"));
        assertFalse(registry.isPendingDelete("default.sales"),
                "快照里没有软删标记，回滚后标记随之消失");
        DomainStore.StoredDomains reloaded = new DomainStore(storageLocations()).load();
        assertEquals(3L, reloaded.version());
        assertTrue(reloaded.pendingDeletes().isEmpty());
    }

    @Test
    @DisplayName("存储信息端点：版本号、路径、宽限期配置、软删清单")
    void storageEndpointReportsState() {
        controller.delete("default.sales", false, null);

        Map<String, Object> view = controller.storage();

        assertEquals(1L, view.get("version"));
        assertEquals(Boolean.FALSE, view.get("behind"));
        assertEquals(24L * 3600 * 1000, view.get("deleteGraceMs"));
        assertTrue(((Map<?, ?>) view.get("pendingDeletes")).containsKey("default.sales"));
    }

    @Test
    @DisplayName("权威文件损坏时从历史快照恢复（服务不中断）")
    void corruptFileRecoversFromHistory() throws Exception {
        controller.delete("default.sales", false, null);
        controller.restore("default.sales", null);
        // 破坏权威文件，但保留历史库
        java.nio.file.Files.writeString(settingsFile(), "{ truncated", java.nio.charset.StandardCharsets.UTF_8);

        DomainStore recovering = new DomainStore(storageLocations());
        DomainStore.StoredDomains stored = recovering.load();

        assertEquals(DomainStore.StoredDomains.Status.RECOVERED, stored.status());
        assertTrue(stored.callables().containsKey("default.sales"),
                "恢复出的配置是完整的，不是空配置");
        assertTrue(stored.pendingDeletes().isEmpty(), "v2 是撤销后的状态");
        // 恢复后允许写盘：下一次保存即修复权威文件
        recovering.saveDeclarations(Map.of("default.sales", true));
        assertEquals(DomainStore.StoredDomains.Status.OK,
                new DomainStore(storageLocations()).load().status(),
                "写盘后权威文件已修复");
    }

    private static void sleepPastGrace() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
