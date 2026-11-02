package com.zzkingcc.domainplane.runtime.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.agent.AgentRequest;
import com.zzkingcc.domainplane.api.agent.AgentService;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.api.conversation.MediaRef;
import com.zzkingcc.domainplane.api.conversation.TokenUsage;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.model.ContentType;
import com.zzkingcc.domainplane.api.model.MessageContent;
import com.zzkingcc.domainplane.api.model.ToolCall;
import com.zzkingcc.domainplane.api.model.ToolCallPayload;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.common.exception.CheckpointCorruptedException;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.common.exception.UpstreamLlmFailure;
import com.zzkingcc.domainplane.domain.memory.DualConstraintChatMemory;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.config.Timeouts;
import com.zzkingcc.domainplane.runtime.conversation.ContextAssembler;
import com.zzkingcc.domainplane.runtime.conversation.ContextBudget;
import com.zzkingcc.domainplane.runtime.conversation.RollingSummaryService;
import com.zzkingcc.domainplane.runtime.conversation.TurnRecorder;
import com.zzkingcc.domainplane.runtime.model.ModelLimits;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.model.VisionTarget;
import com.zzkingcc.domainplane.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.domainplane.runtime.stream.StreamContext;
import com.zzkingcc.domainplane.runtime.stream.StreamSinkRegistry;
import com.zzkingcc.domainplane.runtime.tool.SensitiveMasker;
import com.zzkingcc.domainplane.runtime.tool.ToolCallEchoResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.runtime.usage.TokenUsageRecorder;
import com.zzkingcc.domainplane.runtime.vision.ImageAlphaFlattener;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import reactor.core.publisher.Flux;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 本轮入参的装配 —— 把用户上行的内容块（纯文本 / 图 / 音 / 视）拼成 {@link UserMessage}，
 * 并顺带决定这一轮的图片走哪条路。
 *
 * <p>它是 F07 四步拆分的第 4 步切出来的第一个协作者，从编排服务里整块搬过来，
 * 行为一行未改 —— 拆分的前三步（测试台 / 执行模板 / {@link OrchestrationDeps}）
 * 已经把行为钉住，这一步只挪位置。</p>
 *
 * <p>为什么这一组能整块走：它只依赖"模型档案解析器"与"媒体暂存区"两件外部设施，
 * 且都是可空退化（{@code modelResolver} 为空 → 维持图直接进消息的老行为）。
 * 它不碰会话状态、不碰检查点、不碰记忆 —— 那些才是真正把方法粘在编排服务上的东西。</p>
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
     * 把用户上行的内容块拼成本轮的 {@link UserMessage}。
     *
     * <p>三个易错点，都实测踩过：</p>
     *
     * <ul>
     *   <li>{@code UserMessage.from(String, Content...)} 的第一个 {@code String} 是<b>用户名</b>，
     *       不是文本，拿它拼多模态，文字会静默跑进 {@code name} 字段，
     *       模型只收到图、看不到那句话，而两边都不报错。文本必须以
     *       {@link TextContent} 的形式进内容块。</li>
     *   <li>TEXT 块不能再拼一遍：{@code message} 本来就是各 TEXT 块的合并结果
     *       （见 {@code AgentRequest.getMessage()}），再拼一遍等于把用户的话说两遍。</li>
     *   <li>平台追加的 {@code notice}（附件清单）必须并进同一个文本块，而不是自成一个
     *       {@code TextContent}：多个文本块在部分服务商上会被拼接成一段，
     *       但在另一些上只会取第一块，附件清单被丢掉的话，模型看不到引用名，
     *       既不会去调 {@code seeImage}，也无从解释自己为什么看不到图。</li>
     * </ul>
     *
     * @param notice 平台追加的提示（本轮附件清单），可空；空 = 纯用户输入
     */
    UserMessage buildUserMessage(String message, List<MessageContent> contents, String notice) {
        String text = message == null ? "" : message;
        boolean hasNotice = notice != null && !notice.isBlank();
        if (!hasNotice && (contents == null || contents.isEmpty())) {
            return UserMessage.from(text);
        }
        List<Content> media = new ArrayList<>(contents == null ? 0 : contents.size());
        if (contents != null) {
            for (MessageContent block : contents) {
                // 文本已由 message 承载（合并结果），此处只搬非文本块
                if (block.type() == ContentType.TEXT) {
                    continue;
                }
                media.add(toContent(block));
            }
        }
        String head = combine(text, notice);
        if (media.isEmpty()) {
            // 本轮没有任何媒体块：仍是一条普通文本消息，只是可能多了平台提示
            return UserMessage.from(head);
        }
        if (head.isBlank()) {
            // 只发图不发字是合法输入。此时不能塞一个空 TextContent 占位，
            // 那会让模型收到一个空文本块，部分厂商直接判参数非法。
            return UserMessage.from(media);
        }
        List<Content> blocks = new ArrayList<>(media.size() + 1);
        blocks.add(TextContent.from(head));
        blocks.addAll(media);
        return UserMessage.from(blocks);
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

    /** 内容块 → langchain4j 内容对象 */
    private static Content toContent(MessageContent block) {
        return switch (block.type()) {
            case IMAGE_URL -> ImageContent.from(block.requireUrl(), detailOf(block.detail()));
            case IMAGE_BASE64 -> {
                // 透明底 PNG 先合成白底再推：部分服务商预处理丢 alpha，
                // 笔画全在 alpha 里的图（签名 / 截图 / 印章）会被看成纯黑。无 alpha 的图原样不动。
                ImageAlphaFlattener.Flattened flat = ImageAlphaFlattener.flatten(block.base64(), block.requireMime());
                yield ImageContent.from(flat.base64(), flat.mime(), detailOf(block.detail()));
            }
            // 音频 / 视频：langchain4j 的 OpenAI 适配层把 AudioContent 映射为 input_audio
            // 部件（format 取 mime 子类型，audio/wav → wav）、VideoContent 映射为
            // video_url 部件（base64 自动拼 data URL）。服务商是否支持由域上绑的模型决定，
            // 不支持时错误从模型层明确返回，不在平台侧预设黑白名单。
            case AUDIO_URL -> AudioContent.from(block.requireUrl());
            case AUDIO_BASE64 -> AudioContent.from(block.base64(), block.requireMime());
            case VIDEO_URL -> VideoContent.from(block.requireUrl());
            case VIDEO_BASE64 -> VideoContent.from(block.base64(), block.requireMime());
            default -> throw new IllegalArgumentException("不支持的内容块类型: " + block.type());
        };
    }

    /**
     * 视觉细节档位。空 / 无法识别一律给 {@code AUTO}：交回厂商默认比替它挑一档安全，
     * langchain4j 的 {@code ImageContent.from(...)} 默认给 {@code LOW}（先把图缩到 512²），
     * 用户发张截图上来问字，缩完就看不清了。
     */
    private static ImageContent.DetailLevel detailOf(String detail) {
        if (detail == null || detail.isBlank()) {
            return ImageContent.DetailLevel.AUTO;
        }
        try {
            return ImageContent.DetailLevel.valueOf(detail.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ImageContent.DetailLevel.AUTO;
        }
    }

    /** 本轮内容块里的图片张数（容量判定据此计图片开销） */
    int imageCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isImage);
    }

    /** 本轮内容块里的音频条数（容量判定据此计音频开销） */
    private static int audioCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isAudio);
    }

    /** 本轮内容块里的视频条数（容量判定据此计视频开销） */
    private static int videoCount(List<MessageContent> contents) {
        return countOf(contents, ContentType::isVideo);
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
     * {@code ORCHESTRATION_FAILED}（内部故障），而"base64 图没给 mime"是调用方传错了参数，
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
            // 音频 URL 块：langchain4j 的 OpenAI 适配层映射 AudioContent 时强制要求
            // base64Data（ensureNotBlank(audio.base64Data)），URL 形态的音频到了
            // 请求构造阶段必抛异常，那会以 ORCHESTRATION_FAILED（内部故障）的面目
            // 出现，而这是调用方传错了形态，该在入口就给明确指引。
            if (block.type() == ContentType.AUDIO_URL) {
                return "音频 URL 块暂不支持：兼容适配层只接受 base64 音频，请改用 base64 上传";
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
     * 本轮图片的三条去路。
     *
     * <p>判定必须在写记忆之前完成：它是"这一轮能不能跑"的前置条件，
     * 而不是跑到模型那一步才知道的结果。定在写记忆之后，被拒的那一轮会在记忆里
     * 留下一条永远没有回答的提问，下一轮模型会顺着它答非所问。</p>
     */
    enum VisionRoute {
        /** 主模型本身就是视觉模型：图片直接进它的消息上下文 */
        INLINE,
        /** 主模型是纯文本模型：图片暂存成附件，由 seeImage 委派给同域的视觉子模型 */
        DELEGATE,
        /** 没有可承接的视觉模型：带图请求无处可去，如实拒绝 */
        UNAVAILABLE
    }

    /**
     * 判定本轮图片走哪条路。
     *
     * <p>判据只有一条：<b>域链上第一个可用的视觉模型是不是主模型自己</b>。
     * 是 → 图直接进消息；不是 → 交给那个子模型看；一个都没有 → 拒绝。</p>
     *
     * <p>"拒绝"之前先试解析一次主模型，是为了把两种失败分开：</p>
     *
     * <ul>
     *   <li>域压根没绑模型，那是"依赖未配置"，{@link #resolveModel} 会抛出并被既有收尾接住，
     *       调用方拿到的是"去管控台绑模型"的指引；</li>
     *   <li>绑了模型但整条链都不是视觉的，那才是 {@code MODEL_NO_VISION}（"这个域看不了图"）。</li>
     * </ul>
     *
     * <p>若不分这两步而统一报后者，"域没配模型"的用户会收到"没有视觉模型"的提示，
     * 按提示去绑一个视觉模型，绑完发现还是不能用，因为真正缺的是任何一个模型。</p>
     */
    VisionRoute resolveVisionRoute(String domainId) {
        if (modelResolver == null) {
            // 未装配模型档案的部署（单测、最小装配）：无从判断视觉能力，维持"图直接进消息"的老行为。
            // 判成"不支持"会让这些部署下所有带图请求直接失败，而它们本就不经过档案链。
            return VisionRoute.INLINE;
        }
        Optional<VisionTarget> target = modelResolver.visionModel(domainId);
        if (target.isPresent()) {
            if (target.get().primary()) {
                return VisionRoute.INLINE;
            }
            if (mediaAttachmentStore == null) {
                log.warn("[Agent编排] 域 {} 的视觉模型 {} 不是主模型，但未装配图片暂存区，无法走委派",
                        domainId, target.get().alias());
                return VisionRoute.UNAVAILABLE;
            }
            return VisionRoute.DELEGATE;
        }
        // 没有视觉模型：先确认域是不是真的配了主模型（未配则抛出，走"依赖未配置"那条收尾）
        modelOf.apply(domainId);
        return VisionRoute.UNAVAILABLE;
    }

    /**
     * 一次媒体暂存的结果。
     *
     * @param ids          与内容块里的<b>媒体块</b>按出现顺序一一对应（图 / 音 / 视各占一个号），
     *                     哪一块没存进去，哪个位置就是 {@code null}；长度恒等于媒体块数，
     *                     归档侧要靠同一序号对齐（见 {@link TurnRecorder#mediaRefsOf}）
     * @param imageIds     其中图片的那几个（只含暂存成功的，顺序不变）。附件清单与 {@code seeImage}
     *                     只认这一份：音视频的引用名给模型，等于教它去调一个注定被拒的工具
     * @param imageFailure 第一处图片暂存失败的原因；没有图片失败时为 {@code null}。
     *                     音视频失败不进这里，两者的致命性不同，见 {@link #storeAttachments}
     */
    public record StoredAttachments(List<String> ids, List<String> imageIds, String imageFailure) {
    }

    /**
     * 把本轮全部媒体存进暂存区。
     *
     * <p>三种媒体存的目的不同，但"归档里留下一个可取的引用"这一层是共用的：图片是为了
     * {@code seeImage} 能取到像素，音视频是为了归档不至于只剩一句"曾经有过一段音频"
     * （见 {@link MediaRef}）。</p>
     *
     * <p><b>失败处置按媒体分两种</b>：</p>
     * <ul>
     *   <li><b>图片</b>存不进去由调用方决定致命与否，所以这里只把原因带出去、不抛出。
     *       委派路径下它<b>必须</b>致命：图已被剥出消息，主模型手上只剩引用名清单，
     *       少一个名字就是少一张图，而它无从知道原本有几张，用户问"这两张图有什么区别"，
     *       模型只谈得上一张，还理直气壮。主模型自带视觉时则不必：它本来就能直接看图。</li>
     *   <li><b>音视频</b>存不进去只告警：它们的字节仍在消息里（委派路径只剥图片），
     *       不影响"这一轮能不能跑"，代价仅是归档少一个可取引用。</li>
     * </ul>
     *
     * <p>"跳过失败的那一块"不是一个选项：序号必须与内容块严格对齐，失败了也占号。</p>
     */
    StoredAttachments storeAttachments(List<MessageContent> contents) {
        // 与本类其他内容块方法同一口径：null 视为"没有内容块"，不是异常
        if (contents == null || contents.isEmpty()) {
            return new StoredAttachments(List.of(), List.of(), null);
        }
        List<String> ids = new ArrayList<>();
        List<String> imageIds = new ArrayList<>();
        String imageFailure = null;
        for (MessageContent block : contents) {
            if (block.type() == null || !block.type().isMedia()) {
                continue;
            }
            Kind kind = Kind.of(block.type());
            String id = null;
            try {
                id = switch (block.type()) {
                    case IMAGE_URL, VIDEO_URL ->
                            mediaAttachmentStore.put(kind, null, null, block.requireUrl());
                    case IMAGE_BASE64, AUDIO_BASE64, VIDEO_BASE64 ->
                            mediaAttachmentStore.put(kind, block.requireMime(), block.base64(), null);
                    // AUDIO_URL：入口校验已拒（兼容适配层只收 base64 音频），这里不重复报错
                    default -> null;
                };
            } catch (Exception e) {
                String reason = kind.label() + "暂存失败："
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (kind == Kind.IMAGE) {
                    if (imageFailure == null) {
                        imageFailure = reason;
                    }
                } else {
                    log.warn("[Agent编排] 本轮{}暂存失败（不影响本轮，归档将少一个可取引用）：{}",
                            kind.label(), reason);
                }
            }
            ids.add(id);
            if (kind == Kind.IMAGE && id != null) {
                imageIds.add(id);
            }
        }
        // 不用 List.copyOf：它拒绝 null，而"失败占号"正是靠 null 表达
        return new StoredAttachments(Collections.unmodifiableList(ids),
                Collections.unmodifiableList(imageIds), imageFailure);
    }

    /** 去掉图片块后的内容块列表（委派路径下，图不再进主模型的消息） */
    List<MessageContent> withoutImages(List<MessageContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<MessageContent> out = new ArrayList<>(contents.size());
        for (MessageContent block : contents) {
            if (!block.type().isImage()) {
                out.add(block);
            }
        }
        return out;
    }

    /**
     * 附件清单 —— 主模型是纯文本模型时，这是它唯一能知道"有图可看"的途径。
     *
     * <p>必须显式写出引用名并点明"你看不到图片像素"：不写名字，模型会凭上下文猜一个
     * （猜出来的名字取不到图，工具回一句"找不到图片"，用户看到的是一轮无意义的往返）；
     * 不点明"看不到"，模型会以为自己已经看到了图，直接对着附件名编内容。</p>
     *
     * <p>只列图片：音视频同样有引用名（归档定位用），但它们没有取回通道，
     * 写进"必须先调 seeImage"的清单里就是教模型去调一个注定被拒的工具。</p>
     */
    String attachmentNotice(List<String> imageIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮附带 ").append(imageIds.size())
                .append(" 张图片。你读不到图片像素，图片已暂存为附件，引用名：");
        for (int i = 0; i < imageIds.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(imageIds.get(i));
        }
        sb.append("。回答涉及图片内容的问题前，必须先调用 seeImage 工具")
                .append("（image 参数填上面的引用名，question 参数填你要从图里获取什么），不要凭猜测作答。】");
        return sb.toString();
    }

    /**
     * 视觉主模型的附件清单 —— 与 {@link #attachmentNotice} 的差别只在两个词："必须"与"可以"。
     *
     * <p>主模型自己就能看图（图已在其消息里），所以这条通知的首要任务是**别把它引到工具上**：
     * 先说清"图已直接可见、可以直接回答"，再给出引用名供"想交给同域视觉子模型细看"时使用。
     * 顺序不能反，2026-10-16 实测：只要工具在场，模型就会优先调它，哪怕图就在眼前
     * （同一张图不带工具能准确描述，带上一个同名 seeImage 定义立刻改调工具、并自编引用名）。</p>
     *
     * <p>引用名在这里是**能力**而不是**前提**：给出去是为了让"自主分配任务"这条路走得通，
     * 也让归档记住"这一轮带过图"以便回头按名取回；取不到（TTL 已过）时模型仍能直接看图作答，
     * 不像委派路径那样无路可走。</p>
     */
    String optionalDelegateNotice(List<String> imageIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮附带 ").append(imageIds.size())
                .append(" 张图片，已直接呈现在你的消息里，你可以直接查看并据此回答，无需调用工具。");
        sb.append("仅当需要把图交给同域的视觉子模型细看时（例如逐字识别、更细的版面判断），")
                .append("才调用 seeImage，image 参数填引用名：");
        for (int i = 0; i < imageIds.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(imageIds.get(i));
        }
        sb.append("，question 参数填你要从图里获取什么。】");
        return sb.toString();
    }
}
