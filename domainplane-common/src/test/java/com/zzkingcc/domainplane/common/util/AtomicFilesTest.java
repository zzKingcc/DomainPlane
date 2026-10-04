package com.zzkingcc.domainplane.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落盘工具的契约。
 *
 * <p>这一层的每一条都对应一个真实约束：配置目录可能是 {@code /var/lib/domainplane/config}
 * 这样的深层路径、首次启动时并不存在；而"写一半断掉"会让读方拿到半个 JSON，
 * 在账号文件上那等同于"文件损坏"（10007，且恢复只能动文件系统）。</p>
 * @author zzkingcc
 */
class AtomicFilesTest {

    @Test
    @DisplayName("目标目录不存在时逐级创建（首启落盘种子账号靠它）")
    void createsParentDirectories() throws IOException {
        Path root = Files.createTempDirectory("domainplane-atomic");
        Path target = root.resolve("a/b/c/accounts.json");

        AtomicFiles.write(target, "{\"username\":\"domainplane\"}".getBytes(StandardCharsets.UTF_8));

        assertTrue(Files.exists(target));
        assertEquals("{\"username\":\"domainplane\"}", Files.readString(target));
    }

    @Test
    @DisplayName("覆盖写整体替换，且不残留临时文件")
    void overwritesAndLeavesNoTempFile() throws IOException {
        Path root = Files.createTempDirectory("domainplane-atomic");
        Path target = root.resolve("llm-settings.json");

        AtomicFiles.write(target, "first".getBytes(StandardCharsets.UTF_8));
        AtomicFiles.write(target, "second-longer".getBytes(StandardCharsets.UTF_8));

        assertEquals("second-longer", Files.readString(target));
        try (var files = Files.list(root)) {
            List<Path> leftovers = files
                    .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "不应残留临时文件: " + leftovers);
        }
    }

    @Test
    @DisplayName("写空内容是合法操作（管控台清空配置时会走到）")
    void writesEmptyContent() throws IOException {
        Path root = Files.createTempDirectory("domainplane-atomic");
        Path target = root.resolve("prompts.json");

        AtomicFiles.write(target, new byte[0]);

        assertTrue(Files.exists(target));
        assertEquals(0L, Files.size(target));
    }
}
