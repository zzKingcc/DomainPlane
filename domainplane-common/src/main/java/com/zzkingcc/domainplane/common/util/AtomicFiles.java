package com.zzkingcc.domainplane.common.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 覆盖写文件的原子化写法。
 *
 * @author zzkingcc
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * 以原子方式把 {@code content} 写入 {@code target}。
     *
     * @throws IOException 写临时文件或改名失败。异常发生在改名之前时，目标文件保持原值不变
     */
    public static void write(Path target, byte[] content) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, absolute.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, absolute,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
