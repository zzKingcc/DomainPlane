package com.zzkingcc.domainplane.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

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
     * <p>写入分三步：临时文件 → {@code force} 刷盘 → 原子改名。中间的 {@code force} 不能省：
     * 只做改名的话"原子性"有而"持久性"没有：掉电后可能拿到一个长度 0 的新 inode，
     * 而旧版本已经被替换掉了，文件就此永久损坏。走这条路径的恰恰是账号、上游 API Key
     * 这类凭据文件，丢一次就得人工重建。</p>
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
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // 元数据一起刷：文件长度也在元数据里，只刷数据不刷元数据仍可能读到长度 0
                channel.force(true);
            }
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
