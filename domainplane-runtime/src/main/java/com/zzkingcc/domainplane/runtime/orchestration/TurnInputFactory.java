package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.model.ContentType;
import com.zzkingcc.domainplane.api.model.MessageContent;
import com.zzkingcc.domainplane.runtime.conversation.TurnRecorder;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * 本轮入参的装配 —— 把用户上行的文字与平台追加的附件清单拼成 {@link UserMessage}。
 *
 * <p><b>非文本内容块一律不进主模型消息</b>：图 / 音 / 视以及将来新增的其它类型，
 * 字节先落到暂存区换成引用名，本轮只在文本里写清"有哪些附件、各自能凭什么工具取回"。
 * 主模型因此只处理文字；谁来消费字节、用哪个上游部件，由域上绑定的能力工具决定，
 * 服务商是否支持 {@code input_audio} / {@code video_url} 不再影响"用户发没发文件"这件事。</p>
 *
 * <p>这条界线也治住了 2026-11-06 实测的一类故障：音频以 {@code input_audio} 直发主模型，
 * 上游静默丢掉部件，用户消息只剩一句"说了什么？"，模型回答"没收到文件"，
 * 而归档、暂存区、会话记忆里字节全都在。现在字节的落点只有一处，模型看到的东西也只有一处，
 * 两边对不上就只会是"清单里有没有这个名字"这一种问题。</p>
 *
 * <p>它是 F07 四步拆分的第 4 步切出来的第一个协作者。只依赖"模型档案解析器"与
 * "媒体暂存区"两件外部设施；不碰会话状态、不碰检查点、不碰记忆。</p>
 *
 * @author zzkingcc
 */
@Slf4j
final class TurnInputFactory {

    private final ModelResolver modelResolver;
    private final MediaAttachmentStore mediaAttachmentStore;
    /**
     * 「域 → 主模型」的解析动作。
     *
     * 注入而不是拷贝实现：{@code resolveModel} 终将随图工厂搬走，这里若复制一份，
     * 届时就是两处各改一次。它是刻意保留<b>会抛出</b>这一点的 —— 域没绑模型时抛出的
     * 那个异常由编排侧的"依赖未配置"收尾接住，用户拿到的是"去管控台绑模型"，
     * 而把它吞掉会让用户收到"这个域没有视觉模型"，按提示绑完还是不能用。
     */
    private final Function<String, StreamingChatModel> modelOf;

    TurnInputFactory(ModelResolver modelResolver, MediaAttachmentStore mediaAttachmentStore,
                     Function<String, StreamingChatModel> modelOf) {
        this.modelResolver = modelResolver;
        this.mediaAttachmentStore = mediaAttachmentStore;
        this.modelOf = modelOf;
    }

    /**
     * 把用户原话与平台追加的附件清单拼成本轮的 {@link UserMessage}。
     *
     * <p>只有一段文本：原话与清单必须并进同一个文本块，而不是自成一个
     * {@code TextContent} —— 多个文本块在部分服务商上会被拼接成一段，
     * 但在另一些上只会取第一块，清单被丢掉的话模型既看不到引用名，
     * 也无从解释自己为什么看不到附件。</p>
     *
     * <p>刻意不接收内容块列表：非文本块在上游已全部转成附件引用名，
     * 这里若还留一个"顺手把媒体块拼进去"的口子，统一通道迟早会被绕过。</p>
     *
     * @param notice 平台追加的本轮附件清单，可空；空 = 纯用户输入
     */
    UserMessage buildUserMessage(String message, String notice) {
        return UserMessage.from(combine(message, notice));
    }

    /** 用户原话与平台提示拼成一段文本；任一侧空白即取另一侧 */
    private static String combine(String text, String notice) {
        String head = text == null ? "" : text;
        String tail = notice == null ? "" : notice;
        if (head.isBlank()) {
            return tail;
        }
        if (tail.isBlank()) {
            return head;
        }
        return head + "\n\n" + tail;
    }

    /** 本轮内容块里的图片张数（决定要不要先查视觉承接能力） */
    int imageCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isImage);
    }

    /** 本轮内容块里的媒体块总数 */
    int mediaCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isMedia);
    }

    private static int countOf(List<MessageContent> contents, java.util.function.Predicate<ContentType> pick) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        return (int) contents.stream().filter(block -> pick.test(block.type())).count();
    }

    /**
     * 入口校验内容块，返回拒绝原因；通过时返回 {@code null}。
     *
     * 能在这里判出来的问题就不要留到编排线程里去炸：到了那边只会变成一句
     * {@code ORCHESTRATION_FAILED}（内部故障），而"base64 块没给 mime"是调用方传错了参数，
     * 两件事对调用方的处置完全不同，前者要重试/报障，后者要改请求。
     *
     * 校验只分两种形态（URL 块 / base64 块），不按图、音、视分叉：
     * 三者对"必填字段"的要求完全一致，分叉写就意味着将来加第四种媒体时要改三个地方。
     */
    String validateContents(List<MessageContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        for (MessageContent block : contents) {
            // 转写工具需要真实字节（要 multipart 上传给 ASR 端点），只给地址取不到内容。
            // 在入口就拒，别让它在暂存区里存成一条"有引用名、没数据"的死附件。
            if (block.type() == ContentType.AUDIO_URL) {
                return "音频 URL 块暂不支持：转写通道需要音频字节，请改用 base64 上传";
            }
            try {
                if (block.type().isMedia() && block.url() != null && !block.url().isBlank()) {
                    block.requireUrl();
                } else if (block.type().isMedia() && block.carriesBytes()) {
                    if (block.type().isImage() && block.base64() == null) {
                        throw new IllegalArgumentException("base64 图片块缺少 base64 数据");
                    }
                    block.requireMime();
                } else if (block.type().isMedia()) {
                    // 既没给 URL 也没给字节：媒体块两手空
                    throw new IllegalArgumentException("媒体内容块（" + block.type() + "）必须提供 url 或 base64");
                }
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    /**
     * 本轮带图时，域链上有没有能承接 {@code seeImage} 的视觉模型。
     *
     * <p>统一通道下图片不再直接进主模型消息，所以"主模型自己就是视觉模型"不再是
     * 一条独立路径，只是委派目标恰好等于主模型（{@code VisionDelegateTool} 允许这一点，
     * 内部那次调用不带工具，不会递归）。这里只问"有没有"，不再区分谁来看。</p>
     *
     * <p>没有可承接模型时先试解析一次主模型，是为了把两种失败分开：
     * 域压根没绑模型 → {@code modelOf} 抛"依赖未配置"；绑了但整条链都不看图 →
     * 返回 false，由编排侧发 MODEL_NO_VISION。若不分这两步而统一报后者，
     * 用户会去绑一个视觉模型，绑完发现真正缺的是任何一个模型。</p>
     */
    boolean visionAvailable(String domainId) {
        if (modelResolver == null) {
            // 未装配模型档案的最小部署：无从判断能力，不拦这一轮
            return true;
        }
        if (modelResolver.visionModel(domainId).isEmpty()) {
            modelOf.apply(domainId);
            return false;
        }
        if (mediaAttachmentStore == null) {
            log.warn("[Agent编排] 域 {} 有视觉模型但未装配附件暂存区，图片无处存放", domainId);
            return false;
        }
        return true;
    }

    /**
     * 一次媒体暂存的结果。
     *
     * @param ids       与内容块里的<b>媒体块</b>按出现顺序一一对应（图 / 音 / 视各占一个号），
     *                  哪一块没存进去，哪个位置就是 {@code null}；长度恒等于媒体块数，
     *                  归档侧要靠同一序号对齐（见 {@link TurnRecorder#mediaRefsOf}）
     * @param idsByKind 暂存成功的引用名按类型分组，供本轮附件清单分组列示
     * @param failure   第一处暂存失败的原因；全部成功时为 {@code null}
     */
    public record StoredAttachments(List<String> ids, Map<Kind, List<String>> idsByKind, String failure) {
    }

    /**
     * 把本轮全部非文本内容块存进暂存区。
     *
     * <p>所有类型走同一条路、同一套失败口径：字节不再进主模型消息，
     * 少一个引用名就等于少一份附件，而模型无从知道原本有几个。所以任一暂存失败
     * 都必须让本轮失败，不能像旧委派路径那样"只告警、字节反正还在消息里"。</p>
     *
     * <p>"跳过失败的那一块"不是一个选项：序号必须与内容块严格对齐，失败了也占号。</p>
     */
    StoredAttachments storeAttachments(List<MessageContent> contents) {
        // 与本类其他内容块方法同一口径：null 视为"没有内容块"，不是异常
        if (contents == null || contents.isEmpty()) {
            return new StoredAttachments(List.of(), Map.of(), null);
        }
        List<String> ids = new ArrayList<>();
        Map<Kind, List<String>> byKind = new EnumMap<>(Kind.class);
        String failure = null;
        for (MessageContent block : contents) {
            if (block.type() == null || !block.type().isMedia()) {
                continue;
            }
            Kind kind = Kind.of(block.type());
            String id = null;
            try {
                id = switch (block.type()) {
                    case IMAGE_URL, AUDIO_URL, VIDEO_URL ->
                            mediaAttachmentStore.put(kind, null, null, block.requireUrl());
                    case IMAGE_BASE64, AUDIO_BASE64, VIDEO_BASE64 ->
                            mediaAttachmentStore.put(kind, block.requireMime(), block.base64(), null);
                    default -> null;
                };
                if (id == null) {
                    throw new IllegalStateException("暂存区没有返回引用名");
                }
            } catch (Exception e) {
                String reason = kind.label() + "暂存失败："
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (failure == null) {
                    failure = reason;
                }
                log.warn("[Agent编排] 本轮{}：{}", kind.label(), reason);
            }
            ids.add(id);
            if (id != null) {
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(id);
            }
        }
        Map<Kind, List<String>> frozen = new EnumMap<>(Kind.class);
        byKind.forEach((kind, group) -> frozen.put(kind, Collections.unmodifiableList(group)));
        // 不用 List.copyOf：它拒绝 null，而"失败占号"正是靠 null 表达
        return new StoredAttachments(Collections.unmodifiableList(ids),
                Collections.unmodifiableMap(frozen), failure);
    }

    /**
     * 本轮附件清单 —— 主模型唯一能知道"这一轮用户发过什么文件"的途径。
     *
     * <p>三件事必须写全，缺一件模型就会编内容：</p>
     * <ul>
     *   <li>引用名：不写名字，模型会凭上下文猜一个，工具回"找不到附件"，白跑一轮；</li>
     *   <li>取回方式：图片指向 {@code seeImage}、音频指向 {@code transcribeAudio}，
     *       并说清只拿得到文字 / 像素；没有取回通道的类型（当前是视频、文件一类）
     *       <b>如实说没有</b>，绝不写成"可以取回"，那是骗模型；</li>
     *   <li>字节不在消息里：不点明，模型会以为自己已经看过了，直接对着附件名编内容。</li>
     * </ul>
     *
     * <p>按 {@link Kind} 分组而不是按类型写死分支：将来新增一种媒体，
     * 只要 {@code Kind} 里有它，就会自动落到"没有取回内容的工具"这条保守分支，
     * 不需要再改这里。</p>
     *
     * @return 清单文本；一个附件都没暂存成功时返回 {@code null}（由调用方按失败处理）
     */
    String attachmentNotice(StoredAttachments stored) {
        Map<Kind, List<String>> byKind = stored.idsByKind();
        int total = byKind.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮附带 ").append(total)
                .append(" 个附件，原始内容不在你的消息里，必须凭引用名取用：");
        appendGroup(sb, byKind, Kind.IMAGE, "图片", "张",
                "回答涉及图片内容前，必须先调用 seeImage（image 填引用名，question 填你要获取什么）");
        appendGroup(sb, byKind, Kind.AUDIO, "音频", "段",
                "回答涉及音频内容前，必须先调用 transcribeAudio（audio 填引用名，"
                        + "返回说了什么的文字，听不到语气音色）");
        for (Kind kind : Kind.values()) {
            if (kind == Kind.IMAGE || kind == Kind.AUDIO) {
                continue;
            }
            appendGroup(sb, byKind, kind, kind.label(), "份",
                    "目前没有取回内容的工具，不要猜测其内容，如实告知用户");
        }
        sb.append("附件是临时暂存的，引用名可能已过期；取不到时如实告知用户重新发送。"
                + "不要凭文件名或数量编造内容。】");
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, Map<Kind, List<String>> byKind,
                                    Kind kind, String label, String unit, String how) {
        List<String> ids = byKind.get(kind);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        sb.append(label).append(' ').append(ids.size()).append(unit)
                .append("（").append(String.join("、", ids)).append("）：")
                .append(how).append('；');
    }
}
