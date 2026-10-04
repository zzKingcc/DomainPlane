package com.zzkingcc.domainplane.server.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

/**
 * 密码哈希（BCrypt）。
 *
 * <p>单一职责：只管"明文 ↔ 哈希"的转换，不碰存储与凭证。</p>
 * @author zzkingcc
 */
public final class PasswordHasher {

    private static final int COST = 10;

    /**
     * BCrypt 的输入上限是 72 字节。
     */
    private static final int MAX_BYTES = 72;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(COST);

    private PasswordHasher() {
    }

    /**
     * 生成哈希。
     *
     * @throws IllegalArgumentException 密码为空或超过 72 字节
     */
    public static String hash(String rawPassword) {
        validate(rawPassword);
        return ENCODER.encode(rawPassword);
    }

    /**
     * 校验明文与哈希是否匹配。
     *
     * <p>哈希为空 / 格式非法一律返回 false，<b>不抛异常</b>——损坏的账号文件不应把登录接口打成 500。</p>
     */
    public static boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        try {
            return ENCODER.matches(rawPassword, passwordHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("密码过长（最多 " + MAX_BYTES + " 字节，UTF-8 下中文约 24 个字符）");
        }
    }
}
