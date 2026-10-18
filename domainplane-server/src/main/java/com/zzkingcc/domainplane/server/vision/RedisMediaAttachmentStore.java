package com.zzkingcc.domainplane.server.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 媒体暂存区的 Redis 实现 —— 主模型用不到的那些字节，在这里等 {@code seeImage} 或归档来取。
 *
 * <p>落 Redis 而不是本地文件或进程内 Map：多实例部署下"编排层存、工具线程取"
 * 不落在同一个进程里，存本地就是取不到；而项目本身是 Redis 强依赖，
 * 暂存区跟着它走不需要引入新的基础设施。</p>
 *
 * <p>引用名按内容哈希派生，于是同一份内容重复上行只会占一个键（幂等），
 * 且名字与内容一一对应：模型在提示里看到的名字与它后来传给工具的名字必然对得上，
 * 不存在"同一张图两个名字、模型拿错一个"的情况。</p>
 *
 * <p>TTL 是这个方案的关键：暂存区不是存档，一次对话结束后媒体就没有再被引用的理由。
 * 过期之后 {@link #get} 返回空，{@code seeImage} 会明确回喂"附件已过期"，
 * 而不是把一份取不到的字节当作正常输入发给服务商。</p>
 *
 * <p><b>kind 存一份在 payload 里，而不是靠引用名前缀反推</b>：前缀本来就带着这个信息，
 * 但读侧要用它来判"这份内容能不能进视觉模型"，靠字符串前缀解析等于把一处判据
 * 写成了两处（写侧拼前缀、读侧切前缀），任一处改动都会静默错位。payload 才是数据源。</p>
 *
 * @author zzkingcc
 */
public class RedisMediaAttachmentStore implements MediaAttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMediaAttachmentStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 键前缀：与记忆、检查点同在 {@code domainplane:} 命名空间下，便于按命名空间排查与清理。
     *
     * <p>从 {@code domainplane:image:attachment:} 改名而来：这个键空间现在装的是三种媒体，
     * 继续叫 image 会让排障的人以为音视频在别处。改名的影响面只有"重启那一刻仍在暂存期
     * 内的图片会取不到"（TTL 30 分钟，且取不到时的文案本就是"可能已过期"），故不留兼容键。</p>
     */
    private static final String KEY_PREFIX = "domainplane:media:attachment:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisMediaAttachmentStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public String put(Kind kind, String mimeType, String base64, String url) {
        if (kind == null) {
            throw new IllegalArgumentException("媒体类型不能为空（决定引用名前缀与取回时的用法）");
        }
        boolean hasBase64 = base64 != null && !base64.isBlank();
        boolean hasUrl = url != null && !url.isBlank();
        if (!hasBase64 && !hasUrl) {
            // 二选一是入参约定；两者都空说明上游在构造内容块时就漏填了，这里是最后一道
            throw new IllegalArgumentException(kind.label() + "既没有 base64 也没有 url，无法暂存");
        }
        String id = kind.idPrefix() + sha256(hasBase64 ? base64 : url).substring(0, 16);
        String key = KEY_PREFIX + id;

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("kind", kind.wire());
        if (mimeType != null && !mimeType.isBlank()) {
            payload.put("mime", mimeType.trim());
        }
        if (hasBase64) {
            payload.put("b64", base64);
        }
        if (hasUrl) {
            payload.put("url", url.trim());
        }
        try {
            redis.opsForValue().set(key, MAPPER.writeValueAsString(payload), ttl);
        } catch (Exception e) {
            // 存不进去必须抛：静默吞掉会让主模型拿到一个永远取不到的引用名，
            // 表现为"工具说附件不存在"，而根因其实是 Redis 写失败
            throw new IllegalStateException(kind.label() + "暂存失败（Redis 写入异常）: " + e.getMessage(), e);
        }
        log.debug("[附件暂存] 已暂存 id={} kind={} mime={} url引用={} ttl={}",
                id, kind.wire(), mimeType, !hasBase64, ttl);
        return id;
    }

    @Override
    public Optional<StoredAttachment> get(String attachmentId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            return Optional.empty();
        }
        String id = attachmentId.trim();
        String raw = redis.opsForValue().get(KEY_PREFIX + id);
        if (raw == null) {
            // 取不到 = 从没存过 / 已过期。两种情况对调用方是同一处置（让用户重发），
            // 所以这里不细分，由 seeImage 统一回喂"附件不存在或已过期"
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(raw, Map.class);
            return Optional.of(new StoredAttachment(id, kindOf(id, text(parsed.get("kind"))),
                    text(parsed.get("mime")), text(parsed.get("b64")), text(parsed.get("url"))));
        } catch (Exception e) {
            // 内容损坏 ≠ 取不到：前者是平台的问题，后者是用户的问题，如实抛出以便上层区分
            throw new IllegalStateException("附件暂存内容损坏（id=" + id + "）: " + e.getMessage(), e);
        }
    }

    /**
     * 还原 payload 里的 kind。
     *
     * <p>认不出时回落成图片：唯一会走到这里的只有"payload 里没写 kind"这种内容
     * （手写数据、或更早的写入方），而它们只可能是图片：音视频是随 kind 一起引入的。
     * 回落而不是留空，是因为 {@code StoredAttachment#isImage()} 是"能不能进视觉模型"
     * 的判据，留空会让一份正常的图片附件被拒之门外。</p>
     */
    private static Kind kindOf(String id, String wire) {
        Optional<Kind> parsed = Kind.ofWire(wire);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        log.warn("[附件暂存] 附件 {} 未记录媒体类型（按图片处理）", id);
        return Kind.IMAGE;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 续期：把一组附件的 TTL 重置为完整值（M4-R4 活动续期）。
     *
     * <p>为什么是逐键 {@code EXPIRE} 而不是批量：一批的键数就是一轮里出现过的媒体数
     * （个位数），批量省下的那点往返不值得多一处能错的循环。</p>
     *
     * <p>键不存在（附件从未暂存 / 已过期）时 {@code EXPIRE} 返回 false：这不是错误，
     * 值得区分的是"Redis 抖动"：那种情况只告警，绝不抛出，
     * 否则一次续期故障会把一轮正常对话拖垮，而它本来只是可选的保鲜动作。</p>
     */
    @Override
    public void touch(Collection<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        int renewed = 0;
        int missing = 0;
        for (String id : attachmentIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(redis.expire(KEY_PREFIX + id.trim(), ttl))) {
                    renewed++;
                } else {
                    missing++;
                }
            } catch (Exception e) {
                log.warn("[附件暂存] 附件续期失败（不影响本轮，附件将按原 TTL 过期）：id={} {}",
                        id, e.getMessage());
                return;
            }
        }
        if (renewed > 0 || missing > 0) {
            log.debug("[附件暂存] 附件续期：{} 个重置为 {}，{} 个已不存在", renewed, ttl, missing);
        }
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
