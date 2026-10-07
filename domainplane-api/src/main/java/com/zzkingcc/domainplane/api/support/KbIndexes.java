package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 知识库索引命名：一域一索引，索引名由域路径确定性地派生。
 *
 * @author zzkingcc
 */
public final class KbIndexes {

    /** 知识库索引统一前缀 */
    public static final String PREFIX = "domainplane_kb_";

    /** 通配符，用于枚举全部知识库索引用 */
    public static final String WILDCARD = PREFIX + "*";

    /** 可读前缀的限制长度 */
    private static final int MAX_SAFE_LEN = 80;

    private KbIndexes() {}

    /**
     * 域路径 → 索引名
     */
    public static String nameOf(String domain) {
        String norm = Domains.normalize(domain);
        String safe = norm.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (safe.length() > MAX_SAFE_LEN) {
            safe = safe.substring(0, MAX_SAFE_LEN);
        }
        return PREFIX + safe + "_" + shortHash(norm);
    }

    /** 路径哈希（8 位十六进制），大小写敏感。 */
    static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (Exception e) {
            return String.format("%08x", value.hashCode());
        }
    }
}
