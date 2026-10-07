package com.zzkingcc.domainplane.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzkingcc.domainplane.common.util.AtomicFiles;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

/**
 * 账号存储：{@code classpath:accounts-seed.json} → {@code <domainplane.settings.path>/accounts.json}（目录默认随运行系统，可用 DOMAINPLANE_SETTINGS_PATH 覆盖）。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class AccountStore {

    /** 种子资源（打在 jar 内，只读） */
    static final String SEED_RESOURCE = "accounts-seed.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 文件不存在（或读不到元数据）时的时间戳哨兵 */
    private static final long MISSING_MTIME = Long.MIN_VALUE;

    /**
     * 账号文件的读取状态。
     */
    public enum StoreState {
        /** 文件存在且可解析，账号可用 */
        OK,
        /** 文件不存在：合法的"待初始化"状态 */
        ABSENT,
        /** 文件存在但读不出来：既不能鉴权，也不能重新初始化 */
        CORRUPTED
    }

    private final Path accountFile;

    /** 账号内存副本；{@code cacheLoaded=false} 表示需回源读盘（见 {@link #current()}） */
    private volatile AccountSettings cache;
    private volatile StoreState state = StoreState.ABSENT;
    /** 缓存对应的文件最后修改时间；{@link #MISSING_MTIME} 表示当时文件不存在 */
    private volatile long cacheMtime = MISSING_MTIME;
    private volatile boolean cacheLoaded;

    public AccountStore(StorageLocations storage) {
        this.accountFile = storage.settingsDir().resolve("accounts.json");
    }

    /**
     * 确保账号可用：外部文件不存在时用种子落盘。
     * @return 落盘后的实际状态；{@link StoreState#ABSENT} 表示连种子都没有，管控台需开放初始化入口
     */
    public synchronized StoreState ensureInitialized() {
        if (Files.exists(accountFile)) {
            refreshCache();
            return state;
        }
        Seed seed = readSeed();
        if (seed == null || isBlank(seed.getUsername()) || isBlank(seed.getPassword())) {
            log.info("[账号] 未找到账号文件与可用种子（{}），将以【无账号】状态启动："
                    + "请打开 http://localhost:9527/console/login.html 完成首次初始化",
                    accountFile.toAbsolutePath());
            refreshCache();
            return state;
        }

        AccountSettings account = new AccountSettings();
        account.setUsername(seed.getUsername().trim());
        account.setPasswordHash(PasswordHasher.hash(seed.getPassword()));
        account.setSigningKey(newMasterKey());
        account.setCreatedAt(Instant.now().toString());
        save(account);

        log.info("[账号] 已用种子初始化账号 [{}] 并落盘 {}；"
                        + "默认密码为种子值，是否修改由部署方决定（不做强制改密）",
                account.getUsername(), accountFile.toAbsolutePath());
        return StoreState.OK;
    }

    /**
     * 当前账号；文件不存在或文件损坏时返回 {@code null}。
     */
    public AccountSettings current() {
        ensureFresh();
        return cache;
    }

    /** 账号文件的当前读取状态（顺带刷新缓存，语义同 {@link #current()}） */
    public StoreState state() {
        ensureFresh();
        return state;
    }

    private void ensureFresh() {
        long mtime = lastModified();
        if (cacheLoaded && mtime == cacheMtime) {
            return;
        }
        synchronized (this) {
            if (!cacheLoaded || lastModified() != cacheMtime) {
                refreshCache();
            }
        }
    }

    private long lastModified() {
        try {
            return Files.getLastModifiedTime(accountFile).toMillis();
        } catch (IOException e) {
            return MISSING_MTIME;
        }
    }

    private void refreshCache() {
        AccountSettings loaded = readFromDisk();
        StoreState next = loaded != null ? StoreState.OK
                : (Files.exists(accountFile) ? StoreState.CORRUPTED : StoreState.ABSENT);
        // 只在"跃迁进损坏态"时报警：本方法可能被高频调用，重复刷同一条 ERROR 只会淹没真日志
        boolean enteringCorrupted = next == StoreState.CORRUPTED && state != StoreState.CORRUPTED;

        cache = loaded;
        state = next;
        cacheMtime = lastModified();
        cacheLoaded = true;

        if (enteringCorrupted) {
            log.error("[账号] 账号文件存在但无法解析，进入【降级】状态：受保护接口一律拒绝、初始化入口关闭。"
                    + "修复或删除该文件后重启服务即可恢复：{}", accountFile.toAbsolutePath());
        }
    }

    /**
     * 读盘并解析。
     */
    private AccountSettings readFromDisk() {
        if (!Files.exists(accountFile)) {
            return null;
        }
        try {
            String json = Files.readString(accountFile, StandardCharsets.UTF_8);
            AccountSettings account = MAPPER.readValue(json, AccountSettings.class);
            if (account == null || !account.isUsable()) {
                log.error("[账号] 账号文件字段不完整（需要 username / passwordHash / signingKey）：{}",
                        accountFile.toAbsolutePath());
                return null;
            }
            return account;
        } catch (IOException e) {
            log.error("[账号] 读取账号文件失败：{}", accountFile.toAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 覆盖写入账号文件（原子改名），并同步刷新缓存。
     *
     * @throws IllegalStateException 写盘失败。必须让调用方感知——静默失败会让用户以为改密已生效
     */
    public synchronized void save(AccountSettings account) {
        try {
            AtomicFiles.write(accountFile, MAPPER.writeValueAsBytes(account));
        } catch (IOException e) {
            throw new IllegalStateException("账号保存失败：" + accountFile.toAbsolutePath(), e);
        }
        cache = account;
        state = StoreState.OK;
        cacheMtime = lastModified();
        cacheLoaded = true;
    }

    /** 账号文件路径（供管控台展示，便于运维确认落盘位置与恢复方式） */
    public String filePath() {
        return accountFile.toAbsolutePath().toString();
    }

    /** 随机主密钥（32 字节 → Base64）。生成一次即持久化，重启后不变 */
    public static String newMasterKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 种子里的默认账号 / 密码（供管控台提示"仍是默认密码，建议修改"）。
     *
     * 只读种子、不做任何持久化：一旦用户改过密码，比对自然不成立。
     */
    public static String defaultUsername() {
        Seed seed = cachedSeed();
        return seed == null ? null : seed.getUsername();
    }

    public static String defaultPassword() {
        Seed seed = cachedSeed();
        return seed == null ? null : seed.getPassword();
    }

    private static volatile Seed seedCache;
    private static volatile boolean seedLoaded;

    private static Seed cachedSeed() {
        if (!seedLoaded) {
            synchronized (AccountStore.class) {
                if (!seedLoaded) {
                    seedCache = readSeed();
                    seedLoaded = true;
                }
            }
        }
        return seedCache;
    }

    private static Seed readSeed() {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return MAPPER.readValue(in, Seed.class);
        } catch (IOException e) {
            log.error("[账号] 种子文件 {} 解析失败：{}", SEED_RESOURCE, e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 种子文件结构（只存在于 jar 内，含明文默认密码） */
    @lombok.Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class Seed {
        private String username;
        private String password;
    }
}
