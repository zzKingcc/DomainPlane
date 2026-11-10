package com.zzkingcc.domainplane.server.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTextStoreTest {

    @TempDir
    Path temp;

    private ToolTextStore store() {
        return new ToolTextStore(temp.resolve("tool-text.json"));
    }

    private static ToolTextStore.Entry entry(String desc, String paramDesc) {
        return new ToolTextStore.Entry(desc, Map.of("q", paramDesc),
                new ToolTextStore.Baseline("原说明", Map.of("q", "原参数说明")));
    }

    @Test
    @DisplayName("文件不存在时返回空覆盖")
    void missingFile() {
        assertTrue(store().load().tools().isEmpty());
    }

    @Test
    @DisplayName("保存后读回，说明与基线都在")
    void roundTrip() {
        ToolTextStore store = store();
        store.update("inner.search", entry("新说明", "新参数说明"));

        ToolTextStore.Entry read = store.load().tools().get("inner.search");
        assertEquals("新说明", read.description());
        assertEquals("新参数说明", read.paramDescriptions().get("q"));
        assertEquals("原说明", read.baseline().description());
        assertEquals("原参数说明", read.baseline().paramDescriptions().get("q"));
    }

    @Test
    @DisplayName("改一个工具不会把另一个工具的覆盖冲掉")
    void twoTools() {
        ToolTextStore store = store();
        store.update("a", entry("A", "pa"));
        store.update("b", entry("B", "pb"));
        store.update("a", entry("A2", "pa2"));

        assertEquals("A2", store.load().tools().get("a").description());
        assertEquals("B", store.load().tools().get("b").description());
    }

    @Test
    @DisplayName("传 null 是删除整条覆盖")
    void remove() {
        ToolTextStore store = store();
        store.update("a", entry("A", "pa"));
        store.update("a", null);
        assertFalse(store.load().tools().containsKey("a"));
    }

    @Test
    @DisplayName("文件损坏时禁止写入，避免把坏文件修成空文件")
    void brokenFileRefusesWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        ToolTextStore store = new ToolTextStore(file);

        assertTrue(store.load().tools().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> store.update("a", entry("A", "pa")),
                "读盘失败后必须拒绝覆盖写入");
        assertEquals("{ this is not json", Files.readString(file, StandardCharsets.UTF_8),
                "原文件必须原样保留");
    }
}
