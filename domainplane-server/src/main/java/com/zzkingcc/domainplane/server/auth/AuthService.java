package com.zzkingcc.domainplane.server.auth;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号与凭证的唯一业务入口。
 * @author zzkingcc
 */
@Slf4j
@Service
public class AuthService {

    /** 账号名长度上限。它会进入凭证 payload，过长没有实际意义，也让异常输入在设置期就被拦住 */
    private static final int MAX_USERNAME_LENGTH = 64;

    private final AccountStore accountStore;
    private final CredentialService credentialService;

    /**
     * "读-改-写"整段的互斥锁。
     *
     * {@link AccountStore#save} 自身同步只能保证单次写入不交错；改密与初始化都是
     * "读当前账号 → 改 → 写回"，两个并发请求会各自读到旧值再各自写回，两边都回成功而后者覆盖前者。
     */
    private final Object writeLock = new Object();

    /** "当前是否仍是默认密码"的缓存（BCrypt 校验要 ~100ms，不该每次开页面都重算） */
    private volatile Boolean defaultCredential;
    private volatile String defaultCredentialCheckedFor;

    public AuthService(AccountStore accountStore, CredentialService credentialService) {
        this.accountStore = accountStore;
        this.credentialService = credentialService;
    }

    /** 是否已完成初始化（有可用账号） */
    public boolean isInitialized() {
        return accountStore.state() == AccountStore.StoreState.OK;
    }

    /**
     * 账号文件是否已损坏（文件存在、但读不出来）。
     *
     * 与"未初始化"是两个相反的处置：本状态必须拒绝一切受保护请求，
     * 且不开放初始化入口——账号数据已经在里面了，允许重新初始化等于允许任何人接管实例。
     */
    public boolean isStoreCorrupted() {
        return accountStore.state() == AccountStore.StoreState.CORRUPTED;
    }

    /**
     * 首次初始化：设置账号与密码。
     *
     * @throws AuthException 账号已存在（{@link ErrorCode#AUTH_ALREADY_INITIALIZED}）或
     *                       账号文件已损坏（{@link ErrorCode#AUTH_STORE_CORRUPTED}）
     */
    public AccountSettings initialize(String username, String password) {
        synchronized (writeLock) {
            if (isStoreCorrupted()) {
                throw new AuthException(ErrorCode.AUTH_STORE_CORRUPTED,
                        "账号文件已存在但无法解析，初始化入口不可用：请修复或删除该文件后重启服务");
            }
            if (isInitialized()) {
                throw new AuthException(ErrorCode.AUTH_ALREADY_INITIALIZED);
            }
            String name = validatedUsername(username);
            if (password == null || password.isBlank()) {
                throw new AuthException(ErrorCode.INVALID_PARAMETER, "密码不能为空");
            }

            AccountSettings account = new AccountSettings();
            account.setUsername(name);
            account.setPasswordHash(PasswordHasher.hash(password));
            account.setSigningKey(AccountStore.newMasterKey());
            account.setCreatedAt(Instant.now().toString());
            accountStore.save(account);
            invalidateDefaultCredentialCache();
            log.warn("[账号] 管控台完成首次初始化：账号 [{}]，落盘 {}",
                    account.getUsername(), accountStore.filePath());
            return account;
        }
    }

    /**
     * 登录：校验账号密码并签发凭证。
     *
     * @param from 调用来源（IP），仅记录
     * @return 签名凭证
     * @throws AuthException 账号未初始化 / 账号文件损坏 / 账号或密码错误
     */
    public String login(String username, String password, String from) {
        AccountSettings account = accountStore.current();
        if (account == null) {
            if (isStoreCorrupted()) {
                throw new AuthException(ErrorCode.AUTH_STORE_CORRUPTED,
                        "账号文件已存在但无法解析：请修复或删除该文件后重启服务");
            }
            throw new AuthException(ErrorCode.AUTH_NOT_INITIALIZED,
                    "服务端还没有账号：请打开管控台完成首次初始化");
        }
        boolean ok = account.getUsername().equals(username == null ? null : username.trim())
                && PasswordHasher.matches(password, account.getPasswordHash());
        if (!ok) {
            log.warn("[账号] 登录失败：账号={}，来源={}（账号不存在与密码错误的返回一致，避免账号枚举）",
                    username, from);
            throw new AuthException(ErrorCode.AUTH_FAILED);
        }

        recordLogin(from);
        log.info("[账号] 登录成功：账号={}，来源={}", account.getUsername(), from);
        return credentialService.issue(account);
    }

    /**
     * 记录最后登录信息。
     *
     * 尽力而为，不阻断登录：这是审计信息，丢了不影响鉴权；而账号目录只读（容器挂载
     * 只读卷、权限收紧）时若照旧抛错，会出现"密码正确却永远登不进管控台，还报系统内部错误"——
     * 那才是真的把人锁在门外。真正必须报错的写盘是改密码：静默失败会让用户以为新密码已生效。
     */
    private void recordLogin(String from) {
        // 锁内重读当前账号再写回：与 changePassword 并发时，后者已落盘的新哈希不能被这次
        // "读-改(只改登录信息)-写"覆盖回旧值，否则会出现"改密看似成功、下次却登不进"。
        try {
            synchronized (writeLock) {
                AccountSettings current = accountStore.current();
                if (current == null) {
                    return;
                }
                AccountSettings updated = current.copy();
                updated.setLastLoginAt(Instant.now().toString());
                updated.setLastLoginFrom(from);
                accountStore.save(updated);
            }
        } catch (RuntimeException e) {
            log.warn("[账号] 最后登录信息写盘失败，本次登录继续（账号目录可能不可写）：{}", e.getMessage());
        }
    }

    /** 校验凭证是否有效（凭证缺失 / 伪造 / 密码已改 → false） */
    public boolean verify(String credential) {
        return credentialService.verify(accountStore.current(), credential);
    }

    /**
     * 改密码（需已登录 + 验证旧密码）。
     *
     * @throws AuthException 账号未初始化 / 旧密码错误 / 新密码非法
     */
    public void changePassword(String oldPassword, String newPassword) {
        synchronized (writeLock) {
            AccountSettings account = accountStore.current();
            if (account == null) {
                if (isStoreCorrupted()) {
                    throw new AuthException(ErrorCode.AUTH_STORE_CORRUPTED,
                            "账号文件已存在但无法解析：请修复或删除该文件后重启服务");
                }
                throw new AuthException(ErrorCode.AUTH_NOT_INITIALIZED);
            }
            if (!PasswordHasher.matches(oldPassword, account.getPasswordHash())) {
                log.warn("[账号] 改密码失败：旧密码不正确");
                throw new AuthException(ErrorCode.AUTH_FAILED, "旧密码不正确");
            }
            if (newPassword == null || newPassword.isBlank()) {
                throw new AuthException(ErrorCode.INVALID_PARAMETER, "新密码不能为空");
            }
            if (PasswordHasher.matches(newPassword, account.getPasswordHash())) {
                throw new AuthException(ErrorCode.INVALID_PARAMETER, "新密码与旧密码相同");
            }

            AccountSettings updated = account.copy();
            updated.setPasswordHash(PasswordHasher.hash(newPassword));
            accountStore.save(updated);
            invalidateDefaultCredentialCache();
            log.warn("[账号] 密码已修改：账号={}。全部旧凭证立即失效（含本次登录态），需重新登录",
                    account.getUsername());
        }
    }

    /**
     * 当前登录态（供管控台页面渲染）。
     */
    public Map<String, Object> sessionInfo(String credential) {
        AccountSettings account = accountStore.current();
        boolean corrupted = isStoreCorrupted();
        boolean authenticated = account != null && credentialService.verify(account, credential);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("initialized", account != null);
        body.put("corrupted", corrupted);
        body.put("authenticated", authenticated);
        if (account == null || authenticated) {
            body.put("accountFile", accountStore.filePath());
        }
        if (authenticated) {
            body.put("username", account.getUsername());
            body.put("lastLoginAt", account.getLastLoginAt());
            body.put("lastLoginFrom", account.getLastLoginFrom());
            body.put("defaultCredential", isDefaultCredential(account));
        }
        return body;
    }

    /** 当前账号是否仍用默认密码（供登录响应与账号页提示） */
    public boolean isDefaultCredential() {
        return isDefaultCredential(accountStore.current());
    }

    /**
     * 是否仍在使用种子给定的默认密码。
     */
    public boolean isDefaultCredential(AccountSettings account) {
        if (account == null) {
            return false;
        }
        String hash = account.getPasswordHash();
        if (hash.equals(defaultCredentialCheckedFor) && defaultCredential != null) {
            return defaultCredential;
        }
        String seedDefault = AccountStore.defaultPassword();
        if (seedDefault == null) {
            return false;
        }
        boolean isDefault = PasswordHasher.matches(seedDefault, hash);
        defaultCredentialCheckedFor = hash;
        defaultCredential = isDefault;
        return isDefault;
    }

    /**
     * 校验并规整账号名。
     */
    private static String validatedUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new AuthException(ErrorCode.INVALID_PARAMETER, "账号不能为空");
        }
        String trimmed = username.trim();
        if (trimmed.length() > MAX_USERNAME_LENGTH) {
            throw new AuthException(ErrorCode.INVALID_PARAMETER,
                    "账号长度不能超过 " + MAX_USERNAME_LENGTH + " 个字符");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new AuthException(ErrorCode.INVALID_PARAMETER, "账号不能包含控制字符");
            }
        }
        return trimmed;
    }

    private void invalidateDefaultCredentialCache() {
        defaultCredential = null;
        defaultCredentialCheckedFor = null;
    }
}
