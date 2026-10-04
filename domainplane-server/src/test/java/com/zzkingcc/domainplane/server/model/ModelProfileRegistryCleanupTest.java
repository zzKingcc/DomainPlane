package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删域时清理模型绑定。
 *
 * <p>不清理的话，绑定会变成孤儿键 —— 同路径域将来重建时会沿链<b>静默继承</b>旧绑定。</p>
 */
class ModelProfileRegistryCleanupTest {

    @TempDir
    Path tempDir;

    private ModelProfileStore store() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new ModelProfileStore(
                new com.zzkingcc.domainplane.server.env.StorageLocations(env));
    }

    private void seedBindings(String json) throws IOException {
        Path file = tempDir.resolve("config").resolve("models.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    @Test
    void unbindDomainsRemovesOnlyTheDeletedDomainsAndPersists() throws IOException {
        seedBindings("""
                {"profiles":{},"domainBindings":{"default.sales":["m1"],"default.hr":["m2"]}}
                """);
        ModelProfileStore store = store();
        ModelProfileRegistry registry = new ModelProfileRegistry(store);

        registry.unbindDomains(List.of("default.sales"));

        assertFalse(registry.domainBindings().containsKey("default.sales"));
        assertTrue(registry.domainBindings().containsKey("default.hr"), "旁支绑定不该受影响");
        // 落盘也要干净：换一个注册表实例读同一个文件
        assertFalse(new ModelProfileRegistry(store).domainBindings().containsKey("default.sales"),
                "清理必须落盘，否则重启后旧绑定会复活");
    }

    @Test
    void unbindDomainsDropsTheWholeDeletedSubtree() throws IOException {
        seedBindings("""
                {"profiles":{},"domainBindings":{"default.sales":["m1"],"default.sales.order":["m2"]}}
                """);
        ModelProfileRegistry registry = new ModelProfileRegistry(store());

        registry.unbindDomains(List.of("default.sales", "default.sales.order"));

        assertTrue(registry.domainBindings().isEmpty(), "整棵子树的绑定都要摘掉");
    }

    @Test
    void unbindDomainsWithNothingToDoKeepsOtherBindings() throws IOException {
        seedBindings("""
                {"profiles":{},"domainBindings":{"default.sales":["m1"]}}
                """);
        ModelProfileRegistry registry = new ModelProfileRegistry(store());

        registry.unbindDomains(List.of("default.ghost"));

        assertTrue(registry.domainBindings().containsKey("default.sales"));
    }
}
