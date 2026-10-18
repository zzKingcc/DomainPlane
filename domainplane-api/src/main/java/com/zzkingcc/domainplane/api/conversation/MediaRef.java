package com.zzkingcc.domainplane.api.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 归档里对本轮一个媒体附件的<b>引用</b>。
 *
 * <p>只存引用不存字节：归档无限增长，把 base64 图/音/视频原样再复制一份进去，
 * 量级上是事故（见方案 §4.4 / §10-R4）。base64 块的 {@code ref} 形如
 * {@code base64(audio/wav, 约 524288 字节)} —— 记录"有过什么、多大、什么类型"，
 * 像素与字节本身不落档。需要时按引用去附件区取。</p>
 *
 * <p><b>{@code attachmentId}（M4-R4）</b>：媒体被暂存到附件区时会换成一个引用名
 * （形如 {@code img_3f2a91c4} / {@code aud_5c1e7b02}），这里把它记下来：
 * 归档于是从"曾有过一段音频，描述：…"升级为"可按名定位到那份字节"。
 * <b>三种媒体都记</b>：图片的引用名供 {@code seeImage} 取回像素，音频的引用名供
 * {@code transcribeAudio} 取回文字；视频的引用名目前只服务于"这档里真有过这段字节、
 * 它在哪里"，没有取回内容的通道（见 {@link #isImage()}：那个方法判的是"能不能按名取回
 * <b>原始内容</b>"，音频只能取回文字，所以不在此列）。</p>
 *
 * <p>字段可空：URL 块、没装配附件区的部署、暂存失败的那一块都没有引用名，
 * 老档案（无此字段）反序列化后也为 {@code null}，两者同义："没有可取的字节"。
 * 会话活跃期间附件存活期会被续期
 * （见 {@code AgentOrchestrationService#renewActiveAttachments}），沉寂后自然过期。</p>
 *
 * <p>{@code ignoreUnknown} 是给归档擦屁股用的：早先版本把派生方法 {@code isImage()}
 * 当成了属性、多写出一个 {@code "image":true}，读侧（{@code RedisConversationArchive} 的
 * mapper 开着 {@code FAIL_ON_UNKNOWN_PROPERTIES}）于是判整条轮记录未知字段、直接跳过：
 * 表现是"这几轮历史凭空消失 + 图片不再续期"。写侧已由 {@link #isImage()} 上的
 * {@code @JsonIgnore} 堵住，读侧放宽一格让已落档的坏记录还能读回来。</p>
 *
 * @param kind         image / audio / video
 * @param mime         base64 块的媒体类型；URL 块可能为空
 * @param ref          URL 块为原地址；base64 块为形态描述（不含字节）
 * @param note         一句描述：字节是否随档保存、来自哪里
 * @param attachmentId 附件区引用名；无可取字节时为 {@code null}
 * @author zzkingcc
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaRef(
        @JsonProperty("kind") String kind,
        @JsonProperty("mime") String mime,
        @JsonProperty("ref") String ref,
        @JsonProperty("note") String note,
        @JsonProperty("attachmentId") String attachmentId) {

    @JsonCreator
    public MediaRef(@JsonProperty("kind") String kind,
                    @JsonProperty("mime") String mime,
                    @JsonProperty("ref") String ref,
                    @JsonProperty("note") String note,
                    @JsonProperty("attachmentId") String attachmentId) {
        this.kind = kind;
        this.mime = mime;
        this.ref = ref;
        this.note = note;
        this.attachmentId = attachmentId == null || attachmentId.isBlank()
                ? null : attachmentId.trim();
    }

    /** 便捷构造：没有可取的附件（URL 块 / 暂存失败 / 未装配附件区的部署） */
    public MediaRef(String kind, String mime, String ref, String note) {
        this(kind, mime, ref, note, null);
    }

    /** 是否可按名去附件区取回字节 */
    public boolean hasAttachment() {
        return attachmentId != null;
    }

    /**
     * 是不是图片 —— 只有图片有"按名取回<b>原始内容</b>"的模型侧通道（{@code seeImage}）。
     *
     * <p>音频与视频的引用名同样会落档，但承接方不同，拼接时按 {@code kind} 分派：
     * 音频由 {@code transcribeAudio} 承接（只拿回文字，拿不回音频本身），
     * 视频没有任何取回内容的通道。别把任一种指向 {@code seeImage}：那换来的是一句
     * 注定被拒的错误诊断。字面量必须与 {@code MediaAttachmentStore.Kind.IMAGE.wire()} 一致
     * （本模块在 api，词表的定义方在 runtime，反向依赖不成立）。</p>
     *
     * <p>{@code @JsonIgnore} 是必需项，不是洁癖：Jackson 会把 {@code isXxx()} 当成
     * {@code "xxx"} 属性的 getter，于是序列化时多写出一个 {@code "image"} 字段；
     * 而它不在构造器参数里，反序列化时会被判为未知字段并整条失败：
     * 结果就是<b>归档里每一条带媒体的轮记录都读不出来</b>（同 {@code TurnRecord#isOpen()}）。
     * 派生值不该进 JSON。</p>
     */
    @JsonIgnore
    public boolean isImage() {
        return "image".equals(kind);
    }
}
