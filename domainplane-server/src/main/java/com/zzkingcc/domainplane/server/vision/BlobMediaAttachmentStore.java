package com.zzkingcc.domainplane.server.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.StoredAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 媒体暂存区的 blob 实现 —— 登记表留 Redis，字节进 {@link BlobStore}。
 *
 * <p><b>为什么拆开</b>：base64 大 value 进 Redis 是生产隐患（上传上限 5MB，
 * 编码后约 6.7MB 一条，单线程被大 value 卡住时所有命令一起等）。但 Redis 在这个
 * 方案里的价值不止"存字节"——TTL 过期与活动续期都是原生能力，整套暂存机制建立在
 * 上面。所以只把字节搬走：登记表（引用名 → 键 / 类型 / 有效期）继续留在 Redis，
 * 编排层 {@code renewActiveAttachments} 每轮的续期调用零改动。</p>
 *
 * <p><b>反直觉点</b>：远程存储下"续期"不是一条 EXPIRE——S3 改对象元数据要整对象
 * 重新拷贝一遍。拆开之后续期只动登记表，字节一次写入不再回头，这个坑自然不存在。</p>
 *
 * <p><b>新老格式同键空间，靠结构区分</b>：老格式（升级前存量）把字节内联在 payload 里
 * （{@code b64}/{@code url} 字段）；新格式只记 {@code blob} 键，字节去 blob 存储取。
 * 读侧看到 {@code blob} 字段就走新路径，否则按老格式解析——不引入版本号字段，
 * 因为两种格式的结构差异本身就是判据，多一个版本号只是多一处要对齐的东西。</p>
 *
 * <p><b>写序：先字节后登记</b>：字节落盘成功、登记表写入失败，留下一个无主 blob
 * （回收扫描的职责，最坏结果是多占一会儿磁盘）；反过来先登记后落字节，失败时
 * 登记表活着但字节取不到——那是一个"能引用、取不回"的坏附件，比孤儿坏得多。
 * 登记表是提交点。</p>
 *
 * <p><b>字节原样存 base64 文本，不解码</b>：附件契约端到端是 base64，多一层
 * 编解码只多一处能错的地方（畸形输入在哪一层炸都是炸，但炸得越晚离用户越远）。
 * 33% 的体积代价对分钟级暂存区无所谓。</p>
 *
 * @author zzkingcc
 */
public class BlobMediaAttachmentStore implements MediaAttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(BlobMediaAttachmentStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final BlobStore blobStore;

    public BlobMediaAttachmentStore(StringRedisTemplate redis, Duration ttl, BlobStore blobStore) {
        this.redis = redis;
        this.ttl = ttl;
        this.blobStore = blobStore;
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
        String id = MediaIds.derive(kind, hasBase64 ? base64 : url);
        String key = MediaIds.KEY_PREFIX + id;

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("kind", kind.wire());
        if (mimeType != null && !mimeType.isBlank()) {
            payload.put("mime", mimeType.trim());
        }
        if (hasBase64) {
            String blobKey = MediaIds.blobKeyOf(id);
            // ByteArrayInputStream 的 close 是空操作，不需要（也无法）由调用方关闭；
            // 流的消费与关闭由 BlobStore 实现负责
            try {
                blobStore.put(blobKey, new ByteArrayInputStream(base64.getBytes(StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                // 字节没落上，登记表就不该有——否则模型拿到一个永远取不到的引用名，
                // 表现为"工具说附件不存在"，而根因其实是存储写失败
                throw new IllegalStateException(kind.label() + "暂存失败（blob 写入异常）: " + e.getMessage(), e);
            }
            payload.put("blob", blobKey);
        }
        if (hasUrl) {
            payload.put("url", url.trim());
        }
        try {
            redis.opsForValue().set(key, MAPPER.writeValueAsString(payload), ttl);
        } catch (Exception e) {
            // 走到这里字节已经落盘：登记表没写上，字节成为无主 blob，由回收扫描清掉。
            // 抛给调用方——静默吞掉会让主模型拿到一个永远取不到的引用名
            throw new IllegalStateException(kind.label() + "暂存失败（登记表写入异常）: " + e.getMessage(), e);
        }
        log.debug("[附件暂存] 已暂存 id={} kind={} mime={} 落字节={} url引用={} ttl={}",
                id, kind.wire(), mimeType, hasBase64, hasUrl, ttl);
        return id;
    }

    @Override
    public Optional<StoredAttachment> get(String attachmentId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            return Optional.empty();
        }
        String id = attachmentId.trim();
        String raw = redis.opsForValue().get(MediaIds.KEY_PREFIX + id);
        if (raw == null) {
            // 取不到 = 从没存过 / 已过期。两种情况对调用方是同一处置（让用户重发），
            // 所以这里不细分，由 seeImage 统一回喂"附件不存在或已过期"
            return Optional.empty();
        }
        Map<String, Object> parsed;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> read = MAPPER.readValue(raw, Map.class);
            parsed = read;
        } catch (Exception e) {
            // 内容损坏 ≠ 取不到：前者是平台的问题，后者是用户的问题，如实抛出以便上层区分
            throw new IllegalStateException("附件暂存内容损坏（id=" + id + "）: " + e.getMessage(), e);
        }
        String blobKey = text(parsed.get("blob"));
        if (blobKey != null) {
            return readFromBlob(id, blobKey, parsed);
        }
        // 老格式：字节 / 地址内联在 payload 里（升级前的存量）
        return Optional.of(new StoredAttachment(id, kindOf(id, text(parsed.get("kind"))),
                text(parsed.get("mime")), text(parsed.get("b64")), text(parsed.get("url"))));
    }

    /**
     * 新格式：字节在 blob 存储里，取回来原样交出去。
     *
     * <p>存进去的就是 base64 文本（见类注释"字节原样存、不解码"），所以这里
     * <b>不再编码</b>——把 base64 当字节再编一遍是双重编码，拿到的是
     * {@code UVVKRA==} 这种谁也解析不动的东西。</p>
     *
     * <p>登记表活着但字节不在，按"取不到"处置并告警：这不是用户错误（引用名没过期），
     * 只可能是字节被误清或存储出过故障。回喂模型"附件不存在"与如实报系统问题之间
     * 选前者——字节已经没了，让这一轮对话继续比让它失败更符合"附件是派生资源"的定位。</p>
     */
    private Optional<StoredAttachment> readFromBlob(String id, String blobKey, Map<String, Object> parsed) {
        Optional<InputStream> in;
        try {
            in = blobStore.open(blobKey);
        } catch (RuntimeException e) {
            // 存储故障 ≠ 取不到：如实抛，让上层区分"模型编的引用名"与"平台出问题了"
            throw new IllegalStateException("附件字节读取失败（id=" + id + "，blob=" + blobKey + "）: "
                    + e.getMessage(), e);
        }
        if (in.isEmpty()) {
            log.warn("[附件暂存] 登记表存活但字节缺失（疑被误清）：id={} blob={}", id, blobKey);
            return Optional.empty();
        }
        try (InputStream stream = in.get()) {
            String b64 = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(new StoredAttachment(id, kindOf(id, text(parsed.get("kind"))),
                    text(parsed.get("mime")), b64, null));
        } catch (IOException e) {
            throw new IllegalStateException("附件字节读取失败（id=" + id + "）: " + e.getMessage(), e);
        }
    }

    /**
     * 续期：把一组附件的 TTL 重置为完整值（M4-R4 活动续期）。
     *
     * <p>只动登记表的 TTL，与登记表里记的是 blob 键还是内联字节无关——所以新老附件
     * 同一条续期口径，这正是拆分设计要保住的东西。</p>
     *
     * <p>为什么是逐键 {@code EXPIRE} 而不是批量：一批的键数就是一轮里出现过的媒体数
     * （个位数），批量省下的那点往返不值得多一处能错的循环。</p>
     *
     * <p>键不存在（附件从未暂存 / 已过期）时 {@code EXPIRE} 返回 false：这不是错误，
     * 值得区分的是"Redis 抖动"：那种情况只告警，绝不抛出，否则一次续期故障会把一轮
     * 正常对话拖垮，而它本来只是可选的保鲜动作。</p>
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
                if (Boolean.TRUE.equals(redis.expire(MediaIds.KEY_PREFIX + id.trim(), ttl))) {
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

    /**
     * 登记表里这个引用名还活着吗 —— 给孤儿回收对账用。
     *
     * <p>只查登记表，不碰 blob：回收的判据是"还有没有主"，不是"字节在不在"。</p>
     */
    public boolean registered(String attachmentId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(MediaIds.KEY_PREFIX + attachmentId.trim()));
        } catch (Exception e) {
            // 查不动时保守回答"活着"：让这一轮不删。回收可以晚一轮，误删救不回来
            log.warn("[附件暂存] 登记表查询失败（按存活处理，本轮不回收）：id={} {}",
                    attachmentId, e.getMessage());
            return true;
        }
    }

    /**
     * 还原 payload 里的 kind。
     *
     * <p>认不出时回落成图片：唯一会走到这里的只有"payload 里没写 kind"这种内容
     * （手写数据、或更早的写入方），而它们只可能是图片：音视频是随 kind 一起引入的。
     * 回落而不是留空，是因为 {@code StoredAttachment#isImage()} 是"能不能进视觉模型"
     * 的判据，留空会让一份正常的图片附件被拒之门外。与
     * {@code RedisMediaAttachmentStore#kindOf} 同一规则。</p>
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
}
