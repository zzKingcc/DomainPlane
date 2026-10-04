package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 知识库索引命名：<b>一域一索引</b>，索引名由域路径确定性地派生。
 *
 * <p>为什么不用域路径直接当索引名：域路径含 {@code .}，而 ES 索引名里点号在通配、
 * 日期数学、隐藏索引（以点开头）等场景下有歧义；且路径大小写敏感的域（{@code a} 与 {@code A}）
 * 转小写后会撞名。因此这里用「<b>可读前缀 + 路径哈希</b>」：前缀提供人类可读线索，
 * 哈希后缀保证唯一（哈希用的是<b>原始</b>大小写敏感的路径）。</p>
 *
 * <p>命名是<b>纯函数</b>，与 {@link Domains} 一样不依赖任何注册表 —— 基础设施层、
 * 检索层、上传层都能各自算出同一个名字，无需共享状态。</p>
 *
 * @author zzkingcc
 */
public final class KbIndexes {

    /** 知识库索引统一前缀 */
    public static final String PREFIX = "domainplane_kb_";

    /** 通配（枚举全部知识库索引用） */
    public static final String WILDCARD = PREFIX + "*";

    /** 可读前缀的最大长度（防止超长域路径把索引名顶满） */
    private static final int MAX_SAFE_LEN = 80;

    private KbIndexes() {
    }

    /**
     * 域路径 → 索引名，如 {@code default.sales.order} → {@code domainplane_kb_default_sales_order_1a2b3c4d}。
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
