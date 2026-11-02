package com.zzkingcc.domainplane.server.storage;

import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 本地文件系统 blob 存储 —— 默认实现，零外部依赖。
 *
 * <p>键即根目录下的相对路径（{@code attachments/img_3f2a91c4} 这种），
 * 按用途分目录，目录不存在随写入创建。</p>
 *
 * <p><b>写走临时文件 + 移动</b>：直接往目标文件里写，读方可能读到半截内容
 * （媒体场景就是把半截 base64 推给服务商）。先写同目录临时文件再移动上线，
 * 读方只会看到"旧的完整对象"或"新的完整对象"。移动优先 ATOMIC_MOVE，
 * 不支持的文件系统（部分网络挂载）退化为普通移动。</p>
 *
 * <p><b>键做目录穿越防护</b>：键来自调用方（引用名由内容哈希派生，本无恶意，
 * 但接口是通用的），空段 / {@code .} / {@code ..} / 绝对路径一律拒，
 * 解析后还要过一遍"必须仍在根目录内"——两层校验，少一层都睡不踏实。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class LocalBlobStore implements BlobStore {

    private final Path root;

    public LocalBlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream data) {
        if (data == null) {
            throw new IllegalArgumentException("字节流不能为空（key=" + key + "）");
        }
        Path target = resolve(key);
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            try (InputStream in = data;
                 OutputStream out = Files.newOutputStream(tmp,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (UnsupportedOperationException | IOException e) {
                // ATOMIC_MOVE 不被支持时抛的是 AtomicMoveNotSupportedException（IOException 子类）；
                // 个别文件系统连普通 move 都只给 UnsupportedOperationException。两种都退化为普通移动
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 半截临时文件不能留在目录里——它会混在 listKeys 里冒充对象，
            // 也会让下一轮回收多删一个不存在的东西
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理失败只留一个孤儿临时文件，由回收扫描兜底
                }
            }
            throw new UncheckedIOException("blob 写入失败（key=" + key + "）: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<InputStream> open(String key) {
        Path target = resolve(key);
        try {
            return Files.isRegularFile(target)
                    ? Optional.of(Files.newInputStream(target))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("blob 读取失败（key=" + key + "）: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("blob 删除失败（key=" + key + "）: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public List<String> listKeys(String prefix) {
        String wanted = prefix == null ? "" : prefix.replace('\\', '/');
        if (!wanted.isEmpty()) {
            validateSegments(wanted, prefix);
        }
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace(File.separatorChar, '/'))
                    .filter(key -> key.startsWith(wanted))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("blob 列举失败（prefix=" + prefix + "）: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Instant> lastModified(String key) {
        try {
            Path target = resolve(key);
            return Files.exists(target)
                    ? Optional.of(Files.getLastModifiedTime(target).toInstant())
                    : Optional.empty();
        } catch (IOException e) {
            // 取不到时间不当成存储故障：年龄只是回收护栏，不是判据
            return Optional.empty();
        }
    }

    /**
     * 键 → 根目录下的绝对路径；非法键直接抛，不静默修正。
     *
     * <p>反斜杠一并按分隔符处理：Windows 上手写或跨端同步进来的键可能带 {@code \}，
     * 不归一化的话 {@code ..\x} 在 Linux 上是个合法文件名、在 Windows 上是穿越，
     * 两边行为不一致本身就是隐患。</p>
     */
    private Path resolve(String key) {
        validateSegments(key, key);
        String normalized = key.replace('\\', '/');
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法 blob 键（越出根目录）: " + key);
        }
        return resolved;
    }

    /** 分段校验：空段（含绝对路径与前导/尾随分隔符）、{@code .}、{@code ..}、NUL 全拒 */
    private static void validateSegments(String key, String original) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("blob 键不能为空");
        }
        if (key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法 blob 键（含 NUL）: " + original);
        }
        for (String segment : key.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "非法 blob 键（空段 / . / .. / 绝对路径均不接受）: " + original);
            }
        }
    }

    /** 根目录（测试与排查用） */
    public Path root() {
        return root;
    }
}
