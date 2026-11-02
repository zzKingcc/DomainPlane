package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 媒体暂存区的键与引用名派生规则 —— 新旧实现共用的唯一出处。
 *
 * <p>拆出来的理由：升级后同一份内容在升级前后必须得到同一个引用名
 * （哈希算法一分叉，暂存区就会出现同一张图两个名字，而模型只认它见过的那个），
 * 键前缀同样如此（老实现写进 Redis 的存量还要被新实现读出来）。
 * 这些规则散在两个实现里，迟早对不上——对不上的那一刻没有报错，只有静默的丢附件。</p>
 *
 * @author zzkingcc
 */
final class MediaIds {

    /**
     * 登记表键前缀：与记忆、检查点同在 {@code domainplane:} 命名空间下，便于按命名空间排查与清理。
     *
     * <p>从 {@code domainplane:image:attachment:} 改名而来：这个键空间现在装的是三种媒体，
     * 继续叫 image 会让排障的人以为音视频在别处。改名的影响面只有"重启那一刻仍在暂存期
     * 内的图片会取不到"（TTL 30 分钟，且取不到时的文案本就是"可能已过期"），故不留兼容键。</p>
     */
    static final String KEY_PREFIX = "domainplane:media:attachment:";

    /**
     * blob 存储里的键前缀：按用途分目录。附件是一族；文档原文（知识库波次）另起一族，
     * 回收扫描只扫自己那一族，不会碰到别人的对象。
     */
    static final String BLOB_PREFIX = "attachments/";

    private MediaIds() {
    }

    /**
     * 引用名 = 类型前缀 + 内容哈希前 16 位。
     *
     * <p>同一份内容永远得到同一个名字：重复上行只占一个键（幂等），且名字与内容
     * 一一对应——模型在提示里看到的名字与它后来传给工具的名字必然对得上。</p>
     */
    static String derive(Kind kind, String content) {
        return kind.idPrefix() + sha256(content).substring(0, 16);
    }

    /** 附件引用名 → blob 键 */
    static String blobKeyOf(String attachmentId) {
        return BLOB_PREFIX + attachmentId;
    }

    /** blob 键 → 附件引用名（调用方保证键带 BLOB_PREFIX） */
    static String attachmentIdOf(String blobKey) {
        return blobKey.substring(BLOB_PREFIX.length());
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极不可能（JDK 必带 SHA-256）；退化为内容自身，宁可引用名变长也不能抛
            return raw;
        }
    }
}
