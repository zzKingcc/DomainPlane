package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.model.VisionTarget;
import com.zzkingcc.domainplane.runtime.tool.ToolExecutorService;
import com.zzkingcc.domainplane.runtime.vision.ImageAlphaFlattener;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.StoredAttachment;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * seeImage —— 把"看图"这件事委派给同域的视觉模型。
 *
 * <p><b>它要解决两件事</b>：</p>
 *
 * <p>① 域链主模型是纯文本模型时（**必须**）：图片不能进它的消息上下文（服务商要么报 400，
 * 要么静默忽略）。于是本轮图片不走消息，而是暂存成一个引用名，由主模型在需要时调用本工具，
 * 把"看图"这一步交给同域的视觉模型做一次 completion，拿回文字结果。图片像素自始至终
 * 没进过主模型的上下文。</p>
 *
 * <p>② 主模型自己就是视觉模型时（**可以**）：图已经进了它的上下文，它能直接看；但平台仍把
 * 同一张图暂存一份、并给出引用名，于是它还多两个选择：把图交给同域的视觉子模型细看
 * （例如逐字识别），或在后续轮次里按引用名回看这一轮的图。这类轮次的通知措辞是
 * "可以直接看，必要时才调用"，与 ① 的"必须先调用"刻意不同。</p>
 *
 * <p><b>不做子代理</b>：这里只发起一次"给一段消息、拿一条回答"的调用，
 * 不给子模型任何工具。因此不存在递归委派、审批挂起、工具越权这一整类问题：
 * 它是一次纯粹的模型能力借用。</p>
 *
 * <p><b>只处理图片</b>：音视频同样会进附件区（归档要留一个可取的引用），于是它们的
 * 引用名也会出现在附件清单里，模型完全可能拿一个音频名来调本工具。这里对非图片显式拒绝
 * 并说明原因：放过去就是把那段字节按错的模态推给上游，而上游可能报错、也可能静默返回胡话。
 * "把音视频重新送进上下文"目前没有通道，那是另一件事，不该由本工具假装能做。</p>
 *
 * <p><b>可见性</b>：挂根域（全树可见），执行时按本轮域校验有没有可用的视觉模型。
 * 让所有域的模型都看到这个工具，比"只有配了视觉模型的域才看见"更好排障：
 * 后者在没配的域上表现为"模型根本不知道有这条路"，用户看到的是模型答非所问，
 * 而不是一句明确的"本域没有视觉模型"。</p>
 *
 * <p><b>域从哪来</b>：读 {@link RetrievalScope}。本地工具执行被挪到隔离线程后
 * ThreadLocal 不跟随，但工具执行的隔离层已经显式把它搬运过去了（见
 * {@code ToolExecutorService.ContextSnapshot}），所以这里读到的域与本轮主模型所处的一致，
 * 不需要在工具签名里再加一个域参数：加了反而给了模型一个"指定别的域"的口子。</p>
 *
 * <p><b>绝不外泄字节</b>：入参只可能是引用名或公网 URL，工具结果只有文本。
 * 运行期拼出来的 data URL 只存在于构造 {@link ImageContent} 的那一瞬，
 * 不进返回值、不进日志、不进工具调用事件：那三条都是会落库的通道。</p>
 *
 * @author zzkingcc
 */
@Component
public class VisionDelegateTool {

    private static final Logger log = LoggerFactory.getLogger(VisionDelegateTool.class);

    /** 工具名：与 {@code seeImage} 的调用约定一致，事件与提示里都用它 */
    private static final String TOOL_NAME = "seeImage";

    /**
     * 子模型的系统提示词 —— 固定，不由域提示词覆盖。
     *
     * <p>域提示词是为"这个域的业务"写的（流程、口径、话术），把它塞给一次纯粹看图
     * 的调用只会干扰：子模型该做的是描述它看到的，不是替主模型回答业务问题。
     * 末尾那句"不要猜"是硬要求：视觉模型最常见的失败模式是看到模糊图就编一个具体结论，
     * 而主模型无从分辨那是在描述还是在猜。</p>
     */
    private static final String SYSTEM_PROMPT =
            "你是视觉助手。只回答与图片相关的问题，简洁准确；图上看不出来的内容直接说看不到，不要猜。";

    private final MediaAttachmentStore store;
    private final ModelResolver modelResolver;
    private final VisionCallExecutor executor;

    public VisionDelegateTool(MediaAttachmentStore store,
                              ModelResolver modelResolver,
                              VisionCallExecutor executor) {
        this.store = store;
        this.modelResolver = modelResolver;
        this.executor = executor;
    }

    @Tool(value = TOOL_NAME,
            desc = "把图片交给同域的视觉模型细看。"
                    + "先看你的消息里有没有图片：有就直接查看并据此回答，不要调用本工具；"
                    + "只有当你确实看不到图（图只以引用名给出、或用户只给了图片地址）时才调用。"
                    + "参数 image 只能填本轮附件清单里形如 img_xxxxxxxx 的引用名（不接受图片地址）；"
                    + "参数 question 填你要从图里得到什么（如'这张发票的金额是多少'）。"
                    + "本工具会把图交给同域的视觉模型，返回对图片的文字描述或它对你问题的回答。"
                    + "凡涉及图片中具体内容而你又看不到像素时，必须先调用本工具取证，不要凭猜测作答。",
            effect = Tool.Effect.READ)
    public String see(@ToolParam("本轮附件清单里的图片引用名（形如 img_xxxxxxxx）；"
            + "不接受图片地址") String image,
                      @ToolParam("你要从图片中获取什么内容：一个具体的问题或描述要求，"
                              + "如'这张发票的金额和开票日期是多少'") String question) {

        if (isBlank(image)) {
            return "没有指定要查看的图片。请传入本轮附件清单里的引用名（形如 img_xxxxxxxx）。";
        }
        if (isBlank(question)) {
            return "没有说明要从图片中获取什么。请把你要问图的问题写在 question 参数里。";
        }

        String reference = image.trim();
        /* 形态先卡一道：本工具只认引用名，不认图片地址：直传 URL 意味着服务端会按
           模型给出的地址去取图，而那个地址是模型（可能被用户话术诱导）现编的，
           可以指向任意内网地址或超大文件。字节来源必须可信，故只认暂存区里的引用名。 */
        if (isHttpUrl(reference)) {
            // 单独给一句明确的文案，而不是让它落到下面的"找不到附件"：
            // 模型给的是地址、回的是"找不到 xxx"，它不会意识到"传地址这件事本身就不允许"，
            // 下一轮还会再传一次地址
            return "本工具只接受附件清单里的引用名（形如 img_xxxxxxxx），不接受图片地址。"
                    + "请改用清单里的引用名；清单里没有用户提到的那张图时，"
                    + "如实告知用户你无法查看它。";
        }

        /* 取一次附件，三件事共用这一次读取：它存在吗 / 它是不是图片 / 用什么构造内容。
           顺序上刻意排在"解析视觉模型"之前：拿一个音频引用名来问，回"本域没有视觉模型"
           是错误诊断：真正的问题是这个名字不指向图片，而那与域上绑没绑模型无关。 */
        StoredAttachment stored = store.get(reference).orElse(null);
        if (stored == null) {
            // 引用名不存在 / 附件已过期：都是"这份内容取不到"，
            // 回喂模型让它如实转告用户重发，而不是抛出去变成一句笼统的工具执行失败
            log.warn("[视觉委派] 附件引用 {} 取不到（不存在或已过期）", reference);
            return "找不到附件 " + reference + "：它可能已过期（附件是临时暂存的），"
                    + "或这个引用不存在。请让用户重新发送。";
        }
        if (!stored.isImage()) {
            /* 音视频也会进附件区（归档要留一个可取的引用，见 MediaAttachmentStore），
               于是它们的引用名同样会出现在附件清单里：模型完全可能拿一个音频名来调本工具。
               这里必须显式拒绝：放过去的话，那段字节会被当作图片推给视觉模型，
               而这不是"失败"，是发错了模态：上游可能报错，也可能静默返回胡话。 */
            log.warn("[视觉委派] 引用 {} 是{}，seeImage 只处理图片", reference, stored.kind().label());
            return "引用名 " + reference + " 是一段" + stored.kind().label() + "，本工具只能查看图片。"
                    + "请如实告知用户你无法查看这段" + stored.kind().label() + "，不要猜测其中的内容。";
        }

        String domain = RetrievalScope.current();
        Optional<VisionTarget> resolved = modelResolver == null
                ? Optional.empty() : modelResolver.visionModel(domain);
        if (resolved.isEmpty()) {
            log.warn("[视觉委派] 域 {} 没有可用的视觉模型，seeImage 无从委派", domain);
            return "当前域没有配置可用的视觉模型，无法查看图片内容。"
                    + "请如实告知用户：这个场景暂时看不了图片，需要在管控台「域空间」为本域"
                    + "绑定一个输入模态含 image 的模型。不要尝试猜测图片内容。";
        }
        VisionTarget target = resolved.get();

        /* 刻意不排除"目标就是主模型自己"这一种。seeImage 内部那次调用不带任何工具，
           所以委派给它自己不会递归；而排除掉它会让"域里只绑了一个视觉模型"这种最常见的
           配置下本工具完全不可用：用户明明有视觉能力，却被告知没有。
           典型场景：主模型是视觉模型，用户在文字里贴了一个图片地址（没走内容块），
           主模型看不到那个地址背后的像素，正需要这一步。 */
        Content imageContent;
        try {
            imageContent = toImageContent(stored, reference);
        } catch (IllegalArgumentException e) {
            log.warn("[视觉委派] 无法构造图片内容 {}：{}", reference, e.getMessage());
            return e.getMessage();
        }

        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(List.of(TextContent.from(question.trim()), imageContent)));

        log.info("[视觉委派] 域 {} 委派视觉模型查看图片：目标={}（model={}），引用={}",
                domain, target.alias(), target.modelName(), image);
        try {
            String answer = executor.call(TOOL_NAME, () -> {
                ChatResponse response = target.model().chat(messages);
                AiMessage ai = response == null ? null : response.aiMessage();
                String text = ai == null ? null : ai.text();
                return isBlank(text) ? "视觉模型没有返回内容。" : text;
            });
            log.info("[视觉委派] 域 {} 视觉模型返回 {} 字", domain, answer.length());
            return answer;
        } catch (ToolExecutorService.ToolTimeoutException e) {
            // 超时要把上限带出来："看图超时"与"看图超时（60s）"对排障是两条不同的线索
            log.warn("[视觉委派] 域 {} 查看图片超过 {}ms 上限", domain, e.timeoutMs());
            return "查看图片超时（超过 " + e.timeoutMs() + "ms），请告知用户稍后重试。";
        } catch (ToolExecutorService.ToolRejectedException e) {
            log.warn("[视觉委派] 域 {} 视觉委派被拒：看图隔离池已满", domain);
            return "系统繁忙，暂时无法查看图片，请稍后重试。";
        } catch (Exception e) {
            log.error("[视觉委派] 域 {} 委派视觉模型失败（目标={}）", domain, target.alias(), e);
            return "查看图片失败：" + rootMessage(e) + "。请如实告知用户这一步没成功，不要猜测图片内容。";
        }
    }

    /**
     * 已取回的附件 → 可发往视觉模型的图片内容。
     *
     * <p>调用方已经确认过 {@code stored.isImage()}：这里只负责把它变成
     * {@link ImageContent}，不再重复判类型：判据只在一处（见上面的 kind 分支）。</p>
     *
     * <p>base64 图在<b>运行时</b>拼成 data URL 推给服务商：内网部署下服务商回连不到
     * 平台地址，只有"推"这一条路走得通。</p>
     */
    private static Content toImageContent(StoredAttachment stored, String reference) {
        if (stored.hasUrl()) {
            return ImageContent.from(stored.url(), ImageContent.DetailLevel.AUTO);
        }
        if (stored.hasBytes()) {
            // 与 INLINE 路径同一道处理：透明底 PNG 先合成白底再推（服务商丢 alpha 时
            // 笔画全在 alpha 里的图会被看成纯黑）。无 alpha 的图原样不动。
            ImageAlphaFlattener.Flattened flat =
                    ImageAlphaFlattener.flatten(stored.base64(), mimeOf(stored.mimeType()));
            return ImageContent.from(flat.base64(), flat.mime(), ImageContent.DetailLevel.AUTO);
        }
        throw new IllegalArgumentException("图片 " + reference + " 没有可用的内容（既没有地址也没有字节），无法查看。");
    }

    /**
     * base64 图必须有 mime 才能拼 data URL；暂存时没带 mime 说明上游构造内容块时就漏了。
     *
     * <p>兜底给 {@code image/png} 而不是抛错：宁可让服务商因为类型不合拒掉这一张图，
     * 也不要让一个"少个字段"的输入直接把整轮对话打断。</p>
     */
    private static String mimeOf(String mimeType) {
        return isBlank(mimeType) ? "image/png" : mimeType.trim();
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 异常链最内层的原因：包装异常（如 langchain4j 的客户端异常）真正有用的信息在 cause 上 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return isBlank(message) ? cur.getClass().getSimpleName() : message;
    }
}
