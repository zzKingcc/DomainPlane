package com.zzkingcc.domainplane.server.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端账号（落盘 {@code config/accounts.json}）。
 *
 * @author zzkingcc
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountSettings {

    /** 账号（唯一） */
    private String username;

    /** BCrypt 哈希；算法与 cost 编码在哈希串内部，无需额外字段 */
    private String passwordHash;

    /**
     * 凭证签名主密钥（Base64，随机 32 字节，持久化）。
     *
     * 它不能直接用来签名：实际签名密钥由 {@code HMAC(主密钥, passwordHash)} 派生，
     * 这样改密码就自动让所有旧凭证失效（零存储实现"改密即踢人"）。若直接用它签名，
     * 改密码将不会使旧凭证失效。
     */
    private String signingKey;

    /** 创建时间（ISO-8601） */
    private String createdAt;

    /** 最后登录时间（ISO-8601） */
    private String lastLoginAt;

    /** 最后登录来源（IP，仅记录不校验：网络安全边界交部署方） */
    private String lastLoginFrom;

    /**
     * 登录记录（新的在前），每次成功登录追加一条，上限见 {@link AuthService#LOGIN_RECORD_LIMIT}。
     *
     * <p>必须有界：账号文件是鉴权关键路径上的文件，无界增长会让每次登录的
     * "读-改-写"越来越重。旧账号文件没有这个字段，读出来是 null，按空表处理。
     */
    private List<LoginRecord> loginRecords;

    /**
     * 复制一份。
     *
     * {@code AccountStore} 返回的是内存缓存对象本身，就地修改会连带改掉缓存；一旦随后的落盘
     * 失败，内存与磁盘就不一致（内存里已是新密码、磁盘上还是旧的，重启即"密码改回去"）。
     * 因此所有改写路径都先复制、改副本、再整体落盘。
     */
    public AccountSettings copy() {
        AccountSettings copy = new AccountSettings();
        copy.username = username;
        copy.passwordHash = passwordHash;
        copy.signingKey = signingKey;
        copy.createdAt = createdAt;
        copy.lastLoginAt = lastLoginAt;
        copy.lastLoginFrom = lastLoginFrom;
        // 列表整体复制：引用共享会让"改副本再落盘"的把戏失效——append 到副本会连缓存里的
        // 旧账号一起改，而落盘一旦失败，内存与磁盘就说不清了。record 元素不可变，浅拷元素安全。
        copy.loginRecords = loginRecords == null ? null : new ArrayList<>(loginRecords);
        return copy;
    }

    /** 一条登录记录：时间（ISO-8601）+ 来源 IP。record 不可变，快照共享无隐患 */
    public record LoginRecord(String at, String from) {
    }

    /** 接口回显用：账号是否存在（序列化时忽略，避免误把哈希带出去） */
    @JsonIgnore
    public boolean isUsable() {
        return username != null && !username.isBlank()
                && passwordHash != null && !passwordHash.isBlank()
                && signingKey != null && !signingKey.isBlank();
    }
}
