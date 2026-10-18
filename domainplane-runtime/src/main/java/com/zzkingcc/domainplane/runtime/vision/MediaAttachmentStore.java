package com.zzkingcc.domainplane.runtime.vision;

import com.zzkingcc.domainplane.api.model.ContentType;

import java.util.Collection;
import java.util.Optional;

/**
 * 媒体附件暂存区 —— 主模型看不到某种媒体时，字节存这里、只把引用名交给模型。
 *
 * <p>为什么需要它：域链主模型是纯文本模型时，图片不能进它的消息上下文，
 * 只能由 {@code seeImage} 工具按引用名取回来转给视觉子模型。而"取回来"必须有个地方存：
 * 存内存则多实例部署下取不到，存文件则要管清理。项目本身是 Redis 强依赖，
 * 暂存区直接落 Redis，靠 TTL 自清，不需要额外的清理任务。</p>
 *
 * <p>为什么接口在内核、实现在服务端：内核（编排层）负责"存媒体并拿到引用名"，
 * 但用什么介质存是部署决策 —— 与 {@code ModelResolver} 同一个理由。</p>
 *
 * <p><b>图 / 音 / 视都存</b>：三条路的目的并不相同，但"归档里留下一个可取的引用"
 * 这一层是共用的 —— 图片是为了 {@code seeImage} 能取到像素，音频是为了 {@code transcribeAudio}
 * 能取到文字，视频则只为了归档不至于只剩一句"曾经有过一段视频"
 * （见 {@link com.zzkingcc.domainplane.api.conversation.MediaRef}）。
 * 也就是说"按名取回"的模型侧通道目前有两条（图 → 看、音 → 转写），视频还没有；
 * 也因此 {@link Kind} 必须在存取两侧都对得上，否则一个图片引用名会被当成音频送进转写、
 * 一个音频引用名会被当成图片送进视觉模型，那是把字节当错了模态发到上游。</p>
 *
 * <p><b>引用名不承载内容</b>：它由内容哈希派生，会出现在系统提示、工具入参、
 * 工具调用事件与会话记忆里。所以它必须是不可反推的短标识，绝不能是 base64 片段，
 * 否则整段媒体数据会顺着这几条可观测通道流出去。</p>
 *
 * @author zzkingcc
 */
public interface MediaAttachmentStore {

    /**
     * 暂存一份媒体，返回引用名。同样的内容重复暂存返回同一个引用名（幂等）。
     *
     * @param kind    媒体类型；决定引用名前缀，也决定取回时该怎么用
     * @param mimeType 媒体类型（base64 块必填，如 {@code image/png} / {@code audio/wav}）
     * @param base64   base64 数据（不含 {@code data:} 前缀）；与 {@code url} 二选一
     * @param url      公网地址；与 {@code base64} 二选一
     * @return 引用名，形如 {@code img_3f2a91c4} / {@code aud_5c1e7b02} / {@code vid_9a3d0f11}
     */
    String put(Kind kind, String mimeType, String base64, String url);

    /**
     * 按引用名取回媒体。
     *
     * <p>取不到返回空 —— 调用方要区分"引用名不存在"（模型编的 / 附件已过期）
     * 与"暂存服务故障"，前者要回喂模型改口，后者要如实说系统问题。</p>
     */
    Optional<StoredAttachment> get(String attachmentId);

    /**
     * 续期：把一组附件的存活期重置为完整 TTL（M4-R4 活动续期）。
     *
     * <p>"暂存区"的 TTL 是清理机制本身，但归档是永久的，两者的时间尺度差了几个数量级，
     * 于是活跃会话里被引用的附件会在 30 分钟后无声消失，而归档里那条引用还在。
     * 续期把"引用还有可能被用到"这件事重新告诉暂存区：会话每走一轮，
     * 就把它当前还在引用的附件（本轮 + 近况窗口内各轮）的存活期重置一次。
     * 会话沉寂、不再有轮次经过，附件自然过期，不需要额外的清理任务。</p>
     *
     * <p>音视频与图片在这里<b>没有区别</b>：续期集合来自归档里"带引用名"的媒体引用，
     * 与类型无关（见 {@code AgentOrchestrationService#renewActiveAttachments}）。</p>
     *
     * <p><b>尽力而为，不抛</b>：续期失败最坏的后果是附件按原 TTL 过期，
     * 而那一刻已经取不到的媒体本来也会被 {@link #get} 判为过期，这是可接受的降级，
     * 不该反过来让一轮正常对话失败。默认空实现：不支持续期的介质（如纯 URL 引用）
     * 天然无需实现。</p>
     *
     * @param attachmentIds 附件引用名集合；{@code null} / 空 / 含空串都按"无可续期"处理
     */
    default void touch(Collection<String> attachmentIds) {
        // 默认无操作：续期是可选的介质能力
    }

    /**
     * 媒体类型 —— 一处定义引用名前缀与归档词表，避免两处各写一份而分叉。
     *
     * <p>{@link #wire()} 的取值刻意与 {@code MediaRef.kind} 完全一致：归档读侧要靠它
     * 还原"这一条是图还是音视频"，两边字符串一旦不一致，档案里就会出现认不出的媒体。</p>
     */
    enum Kind {

        /** 图片 */
        IMAGE("img_", "image", "图片"),
        /** 音频 */
        AUDIO("aud_", "audio", "音频"),
        /** 视频 */
        VIDEO("vid_", "video", "视频");

        private final String idPrefix;
        private final String wire;
        private final String label;

        Kind(String idPrefix, String wire, String label) {
            this.idPrefix = idPrefix;
            this.wire = wire;
            this.label = label;
        }

        /** 引用名前缀 */
        public String idPrefix() {
            return idPrefix;
        }

        /** 归档词表里的取值（= {@code MediaRef.kind}） */
        public String wire() {
            return wire;
        }

        /** 给人看的中文名（拒绝文案里用，别把枚举名拼给用户） */
        public String label() {
            return label;
        }

        /** 内容块类型 → 媒体类型 */
        public static Kind of(ContentType type) {
            if (type == null) {
                throw new IllegalArgumentException("内容块类型不能为空");
            }
            if (type.isImage()) {
                return IMAGE;
            }
            if (type.isAudio()) {
                return AUDIO;
            }
            if (type.isVideo()) {
                return VIDEO;
            }
            throw new IllegalArgumentException("不是媒体块，无法暂存：" + type);
        }

        /** 归档词表取值 → 媒体类型；认不出返回空（老档案 / 手写数据） */
        public static Optional<Kind> ofWire(String wire) {
            if (wire == null || wire.isBlank()) {
                return Optional.empty();
            }
            for (Kind kind : values()) {
                if (kind.wire.equalsIgnoreCase(wire.trim())) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 一次取回的媒体内容。
     *
     * <p>{@code url} 与 {@code base64} 二选一，构造消息时按"哪个可用用哪个"处理：
     * 公网 URL 交给服务商自己回连（省带宽），base64 则要在运行时拼成 data URL 推过去
     * （内网部署下服务商回连不到平台地址，只能推）。</p>
     *
     * @param id       引用名
     * @param kind     媒体类型；决定这份内容能怎么用（图片才能进视觉模型）
     * @param mimeType 媒体类型
     * @param base64   base64 数据；为空表示这是一个 URL 引用
     * @param url      公网地址；为空表示这是一个 base64 内容
     */
    record StoredAttachment(String id, Kind kind, String mimeType, String base64, String url) {

        /** 是否可作为公网 URL 直接交给服务商 */
        public boolean hasUrl() {
            return url != null && !url.isBlank();
        }

        /** 是否自带字节（需要运行时拼 data URL） */
        public boolean hasBytes() {
            return base64 != null && !base64.isBlank();
        }

        /** 是不是图片 —— 只有图片能进 {@code seeImage} */
        public boolean isImage() {
            return kind == Kind.IMAGE;
        }
    }
}
