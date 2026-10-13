package com.zzkingcc.domainplane.server.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

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
}
