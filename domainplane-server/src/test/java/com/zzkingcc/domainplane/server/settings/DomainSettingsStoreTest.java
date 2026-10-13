package com.zzkingcc.domainplane.server.settings;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域提示词的落盘与删域清理。
 *
 * <p>不清理的话，被删域的片段会变成孤儿键 —— 同路径域将来重建时会<b>静默复活</b>旧提示词。</p>
 */
class DomainSettingsStoreTest {

    @TempDir
    Path tempDir;

    private DomainSettingsStore store() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new DomainSettingsStore(
                new com.zzkingcc.domainplane.server.env.StorageLocations(env));
    }

    private Path settingsFile() {
        return tempDir.resolve("config").resolve("prompts.json");
    }

    private void writePrompts(String json) throws IOException {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), json, StandardCharsets.UTF_8);
    }

    @Test
    void removePromptsDropsOnlyTheDeletedDomains() throws IOException {
        writePrompts("""
                {"prompts":{"default":"根提示词","default.sales":"销售提示词","default.hr":"人事提示词"}}
                """);

        DomainSettingsStore store = store();
        store.removePrompts(List.of("default.sales"));

        DomainSettings reloaded = store().load();
        assertFalse(reloaded.getPrompts().containsKey("default.sales"), "被删域的片段必须清掉");
        assertEquals("根提示词", reloaded.getPrompts().get("default"));
        assertEquals("人事提示词", reloaded.getPrompts().get("default.hr"));
    }

    @Test
    void removePromptsDropsTheWholeDeletedSubtree() throws IOException {
        writePrompts("""
                {"prompts":{"default.sales":"销售","default.sales.order":"订单","default.hr":"人事"}}
                """);

        // 删域是递归的：自身 + 全部子孙一起清
        store().removePrompts(List.of("default.sales", "default.sales.order"));

        DomainSettings reloaded = store().load();
        assertTrue(reloaded.getPrompts().keySet().stream()
                .noneMatch(k -> k.startsWith("default.sales")), "整棵子树都要清掉");
        assertEquals("人事", reloaded.getPrompts().get("default.hr"));
    }

    @Test
    void removePromptsWithNothingToDoKeepsFileIntact() throws IOException {
        writePrompts("""
                {"prompts":{"default":"根提示词"}}
                """);

        store().removePrompts(List.of("default.ghost"));

        assertEquals("根提示词", store().load().getPrompts().get("default"));
    }

    @Test
    void removeToolCallEchoDropsOnlyTheDeletedDomains() throws IOException {
        writePrompts("""
                {"prompts":{"default":"根"},"toolCallEcho":{"default.sales":true,"default.hr":false,"default.it":true}}
                """);

        store().removeToolCallEcho(List.of("default.sales"));

        DomainSettings reloaded = store().load();
        assertFalse(reloaded.getToolCallEcho().containsKey("default.sales"), "被删域的开关必须清掉");
        assertEquals(Boolean.TRUE, reloaded.getToolCallEcho().get("default.it"), "没被删的域原样保留");
        assertEquals(Boolean.FALSE, reloaded.getToolCallEcho().get("default.hr"), "显式关也是值，同样保留");
        assertEquals("根", reloaded.getPrompts().get("default"), "提示词不受影响");
    }

    @Test
    void removeToolCallEchoDropsTheWholeDeletedSubtree() throws IOException {
        writePrompts("""
                {"toolCallEcho":{"default.sales":true,"default.sales.order":true,"default.hr":true}}
                """);

        store().removeToolCallEcho(List.of("default.sales", "default.sales.order"));

        DomainSettings reloaded = store().load();
        assertTrue(reloaded.getToolCallEcho().keySet().stream()
                .noneMatch(k -> k.startsWith("default.sales")), "整棵子树都要清掉");
        assertEquals(Boolean.TRUE, reloaded.getToolCallEcho().get("default.hr"));
    }
}
