package com.zzkingcc.domainplane.server.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域声明落盘：可调用性必须能跨重启保持，且旧格式能被读进来（升级不丢域）。
 */
class DomainStoreTest {

    @TempDir
    Path tempDir;

    private DomainStore store() {
        return new DomainStore(storageLocations());
    }

    private com.zzkingcc.domainplane.server.env.StorageLocations storageLocations() {
        Environment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        ((StandardEnvironment) env).getPropertySources().addFirst(new MapPropertySource("test", props));
        return new com.zzkingcc.domainplane.server.env.StorageLocations(env);
    }

    private Path settingsFile() {
        return tempDir.resolve("config").resolve("domains.json");
    }

    @Test
    void missingFileIsAnEmptyConfigThatStillNeedsMigration() {
        DomainStore.StoredDomains stored = store().load();

        assertTrue(stored.callables().isEmpty());
        assertFalse(stored.callableMigrated(), "从未落盘过 → 迁移尚未执行");
    }

    @Test
    void declarationsRoundTripWithCallableFlag() {
        DomainStore store = store();
        Map<String, Boolean> callables = new LinkedHashMap<>();
        callables.put("default.sales", true);
        callables.put("default.sales.order", false);

        store.save(callables, true);
        DomainStore.StoredDomains stored = store().load();

        assertTrue(stored.callableMigrated());
        assertEquals(Boolean.TRUE, stored.callables().get("default.sales"));
        assertEquals(Boolean.FALSE, stored.callables().get("default.sales.order"),
                "装配节点必须原样读回来，否则重启后又会变成可调用");
    }

    @Test
    void legacyIdentifierListIsReadAsCallable() throws IOException {
        // 上一版只存一个标识列表；升级后这些域一律按可调用处理，再由一次性迁移收掉父域
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(),
                "{\n  \"manualDomains\" : [ \"default.sales\", \"default.hr\" ]\n}\n",
                StandardCharsets.UTF_8);

        DomainStore.StoredDomains stored = store().load();

        assertEquals(Boolean.TRUE, stored.callables().get("default.sales"));
        assertEquals(Boolean.TRUE, stored.callables().get("default.hr"));
        assertFalse(stored.callableMigrated(), "旧格式没有迁移标记 → 需要跑一次迁移");
    }

    @Test
    void brokenFileFallsBackToEmptyInsteadOfFailingStartup() throws IOException {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), "{ this is not json", StandardCharsets.UTF_8);

        DomainStore.StoredDomains stored = store().load();

        assertTrue(stored.callables().isEmpty(), "读不动就按空配置启动，由管控台重建");
    }

    /**
     * 损坏必须与"文件不存在"区分开。
     *
     * <p>两者都返回空集合时，启动期的一次性迁移会把这份空配置当成权威写回磁盘，
     * 人工建的域全部消失、迁移标记被置 true 且再也不会重来：一次磁盘故障升级成永久数据丢失。</p>
     */
    @Test
    void corruptFileIsDistinguishedFromMissingFile() throws IOException {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), "{ this is not json", StandardCharsets.UTF_8);

        DomainStore.StoredDomains stored = store().load();

        assertEquals(DomainStore.StoredDomains.Status.CORRUPT, stored.status());
        assertFalse(stored.usable(), "损坏状态下的空集合不可被误当成有效配置");
    }

    @Test
    void missingFileIsAbsentNotCorrupt() {
        assertEquals(DomainStore.StoredDomains.Status.ABSENT, store().load().status());
    }

    /**
     * 损坏时拒绝覆盖写：挡住"把空配置写回磁盘、抹掉人工域"这一不可逆动作。
     */
    @Test
    void corruptFileRefusesToBeOverwritten() throws IOException {
        Files.createDirectories(settingsFile().getParent());
        String original = "{ this is not json";
        Files.writeString(settingsFile(), original, StandardCharsets.UTF_8);

        DomainStore store = store();
        store.load();

        assertThrows(IllegalStateException.class,
                () -> store.save(Map.of("default.sales", true), true),
                "损坏时写入必须失败，而不是用内存里的空注册表覆盖掉原始文件");

        assertEquals(original, Files.readString(settingsFile(), StandardCharsets.UTF_8),
                "原始文件必须原样保留 —— 它可能还能被修复");
    }

    /** 管理端改域时走的路径同样不能覆盖损坏文件 */
    @Test
    void saveDeclarationsAlsoRefusesWhenCorrupt() throws IOException {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), "{ broken", StandardCharsets.UTF_8);

        DomainStore store = store();
        store.load();

        assertThrows(IllegalStateException.class,
                () -> store.saveDeclarations(Map.of("default.sales", true)));
    }

    @Test
    void saveDeclarationsKeepsTheMigrationFlag() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);

        store.saveDeclarations(Map.of("default.sales", true, "default.hr", false));

        DomainStore.StoredDomains stored = store().load();
        assertTrue(stored.callableMigrated(), "管理端改域不该把迁移标记清掉，否则每次重启都会重跑迁移");
        assertEquals(Boolean.FALSE, stored.callables().get("default.hr"));
    }

    /**
     * 迁移标记不再靠"重读文件"保留：文件损坏时它必须保持原值，
     * 否则运维手工切过的可调用性会在下次重启被静默推翻。
     */
    @Test
    void migrationFlagSurvivesAWriteWithoutRereadingTheFile() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);

        // 进程内连续多次管理端写，标记都应保持
        store.saveDeclarations(Map.of("default.sales", false));
        store.saveDeclarations(Map.of("default.sales", true));

        assertTrue(store().load().callableMigrated());
    }

    // ==================== 版本与历史版本库 ====================

    @Test
    void versionStartsAtOneAndBumpsOnEverySave() {
        DomainStore store = store();

        store.save(Map.of("default.sales", true), true);
        assertEquals(1L, store.currentVersion());

        store.saveDeclarations(Map.of("default.sales", true, "default.hr", false));
        assertEquals(2L, store.currentVersion(), "每次变更 version+1");
        assertEquals(2L, store().load().version(), "版本号要能跨重启读回");
    }

    @Test
    void everySaveLeavesAHistorySnapshot() throws IOException {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);
        store.saveDeclarations(Map.of("default.sales", true, "default.hr", false));

        Path historyDir = tempDir.resolve("config").resolve("domains-history");
        assertTrue(Files.exists(historyDir.resolve("v1.json")), "第一次变更落 v1");
        assertTrue(Files.exists(historyDir.resolve("v2.json")), "第二次变更落 v2");
        // 历史快照与权威文件内容一致（含当时的软删标记）
        assertEquals(Files.readString(settingsFile(), StandardCharsets.UTF_8),
                Files.readString(historyDir.resolve("v2.json"), StandardCharsets.UTF_8));
    }

    @Test
    void historyListReportsEntriesWithCurrentFlag() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);
        store.saveDeclarations(Map.of("default.sales", true, "default.hr", false));

        java.util.List<DomainStore.HistoryEntry> entries = store.listHistory();

        assertEquals(2, entries.size());
        assertEquals(2L, entries.get(0).version(), "版本从新到旧");
        assertTrue(entries.get(0).current(), "最新版本即当前");
        assertEquals(2, entries.get(0).domainCount());
        assertEquals(1, entries.get(0).callableCount(), "default.hr 是装配节点");
    }

    @Test
    void corruptFileRecoversFromLatestSnapshot() throws IOException {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);
        store.saveDeclarations(Map.of("default.sales", true, "default.hr", false));
        Files.writeString(settingsFile(), "{ truncated", StandardCharsets.UTF_8);

        DomainStore.StoredDomains stored = store().load();

        assertEquals(DomainStore.StoredDomains.Status.RECOVERED, stored.status());
        assertEquals(2L, stored.version(), "恢复到的版本 = 最新快照的版本");
        assertEquals(Boolean.FALSE, stored.callables().get("default.hr"));
    }

    /**
     * 有快照却全部读不出 = 存储介质层面坏了：拒绝启动（fail-closed），
     * 不放行"带着空注册表跑"——同路径域重建会让旧对话历史与待执行的破坏性调用复活。
     */
    @Test
    void corruptFileWithAllSnapshotsUnreadableRefusesToStart() throws IOException {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);
        Files.writeString(settingsFile(), "{ truncated", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("config").resolve("domains-history").resolve("v1.json"),
                "{ also broken", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> store().load(),
                "快照全坏必须拒绝启动，而不是静默按空配置放行");
    }

    @Test
    void recoveredStateIsWritableSoNextSaveRepairsTheFile() throws IOException {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);
        Files.writeString(settingsFile(), "{ truncated", StandardCharsets.UTF_8);

        DomainStore recovering = store();
        recovering.load();
        // 恢复出的配置是完整的：写盘是修复权威文件，不是用空配置覆盖
        recovering.saveDeclarations(Map.of("default.sales", false));

        assertEquals(DomainStore.StoredDomains.Status.OK, store().load().status());
        assertEquals(Boolean.FALSE, store().load().callables().get("default.sales"));
    }

    // ==================== 软删标记 ====================

    @Test
    void pendingDeleteMarkersRoundTripThroughDisk() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);
        store.markDeleted("default.sales");
        store.saveDeclarations(Map.of("default.sales", true));

        DomainStore.StoredDomains stored = store().load();

        assertTrue(stored.pendingDeletes().containsKey("default.sales"),
                "软删标记必须落盘：否则重启后宽限期状态丢失，删了一半的域又开始接新客");
        DomainStore reloaded = store();
        reloaded.load();
        assertTrue(reloaded.isPendingDelete("default.sales"));
    }

    @Test
    void clearDeletedRemovesMarkerOnNextSave() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);
        store.markDeleted("default.sales");
        store.saveDeclarations(Map.of("default.sales", true));

        store.clearDeleted("default.sales");
        store.saveDeclarations(Map.of("default.sales", true));

        assertTrue(store().load().pendingDeletes().isEmpty(), "撤销软删后标记不得复活");
    }

    @Test
    void rollbackCommitReplacesMarkersAndBumpsVersion() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);       // v1
        store.markDeleted("default.sales");
        store.saveDeclarations(Map.of("default.sales", true)); // v2 带软删标记

        DomainStore.DomainDeclarations snapshot = store.readSnapshot(1L);
        store.commitRollback(DomainStore.callablesOf(snapshot),
                DomainStore.markersOf(snapshot), snapshot.isCallableMigrated());

        assertEquals(3L, store.currentVersion(), "回滚是新变更，不是时间倒流");
        assertTrue(store.pendingDeletes().isEmpty(), "v1 快照没有标记，回滚后随之消失");
        assertTrue(store().load().callables().containsKey("default.sales"));
    }

    @Test
    void readSnapshotRejectsUnknownVersion() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);

        assertThrows(IllegalArgumentException.class, () -> store.readSnapshot(99L));
    }

    @Test
    void historyRollsOverBeyondKeepLimit() {
        DomainStore store = store();
        for (int i = 0; i < 105; i++) {
            store.saveDeclarations(Map.of("default.sales", i % 2 == 0));
        }
        assertEquals(105L, store.currentVersion());

        java.util.List<DomainStore.HistoryEntry> entries = store.listHistory();

        assertEquals(100, entries.size(), "保留最近 100 份，超出删最旧");
        assertEquals(105L, entries.get(0).version(), "最新的必须保住");
        assertEquals(6L, entries.get(entries.size() - 1).version(), "最旧的五个被清掉");
        assertFalse(Files.exists(tempDir.resolve("config").resolve("domains-history").resolve("v5.json")),
                "被删的快照文件也要真的消失");
    }

    @Test
    void everyChangeListenerRunsAndOneFailureDoesNotBlockTheRest() {
        DomainStore store = store();
        StringBuilder seen = new StringBuilder();
        store.onChange(() -> seen.append("tool,"));
        store.onChange(() -> {
            throw new IllegalStateException("派生视图算炸了");
        });
        store.onChange(() -> seen.append("stats,"));

        Map<String, Boolean> callables = new LinkedHashMap<>();
        callables.put("default.sales", true);
        store.save(callables, true);

        assertEquals("tool,stats,", seen.toString(),
                "多个消费者必须都收到通知：单监听位会让后注册的把先注册的顶掉，"
                        + "现象是某块派生视图再也不刷新；单个监听器抛异常也不能影响其余");
    }

    // ==================== 内置域的知识体绑定 ====================

    /**
     * 内置域（根域 default）绑知识体必须当场可读、且跨"重启"存活。
     *
     * <p>历史缺陷：{@code save} 的存活域收敛只认 callables（手动域），内置域刚写入的
     * 绑定在紧接着的 {@code saveDeclarations} 里就被 retainAll 抹掉——绑定接口返回成功、
     * 页面刷新又变回"未绑定"；且内置域不落 domains.json，重启更是必丢。</p>
     */
    @Test
    void builtinDomainBodyBindingSurvivesSaveAndRestart() {
        DomainStore store = new DomainStore(storageLocations());
        store.setDomainSettingsStore(new DomainSettingsStore(storageLocations()));
        DomainRegistry registry = new DomainRegistry();   // 构造期自带内置根域 default
        store.setDomainRegistry(registry);

        store.setBody("default", "kb-1");
        store.saveDeclarations(registry.manualConfig());  // 与控制器相同的落盘路径

        assertEquals("kb-1", store.bodyOf("default"),
                "绑定不许被存活域收敛当场抹掉——接口成功、页面刷新又变回未绑定就是这个病");

        // 模拟重启：全新实例 + 全新注册表，从磁盘读回
        DomainStore reborn = new DomainStore(storageLocations());
        reborn.setDomainSettingsStore(new DomainSettingsStore(storageLocations()));
        reborn.load();

        assertEquals("kb-1", reborn.bodyOf("default"),
                "内置域不落 domains.json，绑定必须走 prompts.json 才能跨重启存活");
    }

    /** 解绑内置域同样要落盘：重启后不得复活旧绑定 */
    @Test
    void builtinDomainBodyUnbindSurvivesRestart() {
        DomainStore store = new DomainStore(storageLocations());
        store.setDomainSettingsStore(new DomainSettingsStore(storageLocations()));
        DomainRegistry registry = new DomainRegistry();
        store.setDomainRegistry(registry);

        store.setBody("default", "kb-1");
        store.saveDeclarations(registry.manualConfig());
        store.setBody("default", null);
        store.saveDeclarations(registry.manualConfig());

        DomainStore reborn = new DomainStore(storageLocations());
        reborn.setDomainSettingsStore(new DomainSettingsStore(storageLocations()));
        reborn.load();

        assertEquals(null, reborn.bodyOf("default"), "解绑必须落盘，重启后不得复活旧绑定");
    }
}
