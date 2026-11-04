package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 知识库索引命名：<b>一个知识体一个索引</b>，索引名由知识体的唯一名派生、创建时冻结。
 *
 * <p>为什么不再按域派生：索引名一旦跟着域走，"这份数据属于哪个域"就由路径唯一确定，
 * 于是同一批资料无法被两个域共用（各域各一份），删域也只能连数据一起删。
 * 改按知识体命名后，多域绑定同一个体 = 查同一个索引 = 真正共用；域删除与数据生命周期解耦。</p>
 *
 * <p>唯一名（slug）在知识体上校验唯一，索引名 = 前缀 + slug + id 哈希前 8 位。
 * 哈希后缀防两件事：slug 安全化后撞名（{@code a.b} 与 {@code a_b}）、超长截断后撞名。
 * 索引名在知识体创建时写入 {@code bodies[].index}，此后<b>永不改动</b>——改名不搬数据。</p>
 *
 * <p>旧命名（{@link #legacyNameOf}）只给迁移器读存量数据用，新代码不应再派生它。</p>
 *
 * @author zzkingcc
 */
public final class KbIndexes {

    /** 知识库索引统一前缀 */
    public static final String PREFIX = "domainplane_kb_";

    /** 通配符，用于枚举全部知识库索引用 */
    public static final String WILDCARD = PREFIX + "*";

    /** 可读前缀的限制长度（旧命名用，新命名由 slug 长度限制兜住） */
    private static final int MAX_SAFE_LEN = 80;

    /** 知识体唯一名：2~40 位小写字母 / 数字 / 连字符，首尾必须是字母或数字 */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");

    private KbIndexes() {}

    /**
     * 知识体 → 索引名（按唯一名派生）。
     *
     * <p>只在知识体创建时调一次，结果存进知识体；不要每次现算——现算等于"改名即改索引"，
     * 而改名不该搬数据。</p>
     *
     * @param slug   知识体唯一名（{@link #normalizeSlug} 之后的值）
     * @param bodyId 知识体 id（稳定，不随改名变化）
     */
    public static String nameOfBody(String slug, String bodyId) {
        String safe = normalizeSlug(slug);
        if (safe.isEmpty()) {
            // 全非法字符（例如 "销售 知识库" 只留得下空格 → 连字符被裁掉）：
            // 不能让索引名变成 PREFIX + "_hash"（前缀后直接下划线，形态不可读也不好排查），
            // 兜一个固定短名；唯一性仍由 id 哈希保证
            safe = "body";
        }
        String idPart = bodyId == null ? "" : bodyId.trim();
        return PREFIX + safe + "_" + shortHash(idPart);
    }

    /**
     * 旧命名：域路径 → 索引名（v1 的一域一索引）。<b>仅迁移器读存量数据时使用。</b>
     */
    public static String legacyNameOf(String domain) {
        String norm = Domains.normalize(domain);
        String safe = norm.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (safe.length() > MAX_SAFE_LEN) {
            safe = safe.substring(0, MAX_SAFE_LEN);
        }
        return PREFIX + safe + "_" + shortHash(norm);
    }

    /** 名字 → 合法 slug：非法字符换成连字符、折叠重复、去首尾连字符、截到 40 位 */
    public static String normalizeSlug(String slug) {
        String raw = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '-' || c == '_' || c == ' ' || c == '.') {
                sb.append('-');
            }
        }
        String out = sb.toString().replaceAll("-{2,}", "-");
        while (out.startsWith("-")) {
            out = out.substring(1);
        }
        while (out.endsWith("-")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.length() > 40) {
            out = out.substring(0, 40);
        }
        while (out.endsWith("-")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * slug 是否合法（已规范化的形态）。
     *
     * @return 不合法原因；合法返回 {@code null}
     */
    public static String validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return "唯一名不能为空";
        }
        if (slug.length() < 2 || slug.length() > 40) {
            return "唯一名长度须为 2~40 个字符（当前 " + slug.length() + " 个）";
        }
        if (!SLUG.matcher(slug).matches()) {
            return "唯一名只能用小写字母、数字与连字符，且首尾必须是字母或数字：" + slug;
        }
        return null;
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
