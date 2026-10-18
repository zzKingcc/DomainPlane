package com.zzkingcc.domainplane.server.capability;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.server.model.DefaultModelResolver;
import com.zzkingcc.domainplane.server.model.ModelEndpointClient;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * generateVideo / checkVideo —— 同域绑定的生视频模型，提交任务与查询进度。
 *
 * <p><b>为什么是两个工具而不是一个</b>：视频生成动辄 60–180 秒，而一次工具调用的上限是
 * {@code domainplane.capability.timeout-ms}（默认 120s）。把整段等待塞进一次调用，
 * 快的时候白等、慢的时候必然超时且超时后进度就丢了。所以拆开：
 * {@code generateVideo} 提交后<b>只等一小段</b>（{@code video-wait-ms}，默认 90s），
 * 出成品就直接给编号，没出就把任务号交回给模型；{@code checkVideo} 接着查。</p>
 *
 * <p><b>刻意不记任务状态</b>：{@code checkVideo} 不需要"提交它的那条记录"：
 * 任务号是服务商发的，拿它去问同一个生视频端点即可。这带来两个好处：
 * 多实例部署下查询不会被路由到"没提交过这个任务的节点"而查不到；
 * 也不需要为它引入一份带 TTL 的持久状态。代价是"提交后改绑了生视频模型"会让查询打空，
 * 此时服务商会返回任务不存在，如实回喂即可，比伪造一个状态好。</p>
 *
 * <p><b>产出落暂存区</b>：与生图 / 语音一致，成品以 {@code vid_xxxxxxxx} 存进
 * {@link MediaAttachmentStore}，返回值只有引用名与说明。服务商给了成品地址时优先按
 * <b>地址引用</b>存（省掉一次整段下载与大块字节进 Redis）；只有拿不到地址才把字节取回来存。</p>
 *
 * @author zzkingcc
 */
@Component
public class VideoGenTool extends CapabilityToolSupport {

    private static final String SUBMIT_TOOL = "generateVideo";
    private static final String CHECK_TOOL = "checkVideo";

    /** 轮询间隔：视频任务动辄几分钟，3 秒一次足够及时又不会把服务商打爆 */
    private static final long POLL_INTERVAL_MS = 3_000L;

    /**
     * 允许落暂存区的成品上限。
     *
     * <p>暂存区是 Redis 且 TTL 30 分钟，一段未压缩的高码率视频轻易上百兆：
     * 那种体量进去会把 Redis 的内存打成不可控。超过就如实拒收并留存服务商地址，
     * 而不是硬塞。</p>
     */
    private static final long MAX_STORE_BYTES = 32L * 1024 * 1024;

    private final long waitMs;

    public VideoGenTool(DefaultModelResolver resolver,
                        ModelEndpointClient client,
                        MediaAttachmentStore store,
                        CapabilityCallExecutor executor,
                        @Value("${domainplane.capability.video-wait-ms:90000}") long waitMs) {
        super(resolver, client, store, executor);
        this.waitMs = Math.max(0, waitMs);
    }

    @Tool(value = SUBMIT_TOOL,
            desc = "根据文字描述生成一段视频。"
                    + "当用户要求生成视频、做一段动画 / 短片 / 动态画面时调用。"
                    + "参数 prompt 填视频内容描述（主体、动作、场景、风格、运镜）。"
                    + "视频生成较慢：本工具会先等一段时间，出成品就直接返回视频编号；"
                    + "还没出会返回一个任务号，此时必须稍后用 checkVideo 带上该任务号继续查询，"
                    + "不要凭空告诉用户视频已经生成好了。"
                    + "本工具依赖当前域已绑定的生视频模型；没有时会明确告知。",
            effect = Tool.Effect.WRITE)
    public String generate(@ToolParam("视频内容描述：出现什么、做什么动作、什么场景与风格、"
            + "镜头怎么走。例如'一只柴犬在草地上追飞盘，午后暖光，镜头缓慢跟随'") String prompt,
                           @ToolParam(value = "分辨率 / 尺寸，如 1280x720；"
                                   + "不确定就留空，由服务商默认", required = false) String size,
                           @ToolParam(value = "视频时长（秒），如 5 / 10；"
                                   + "留空由服务商默认", required = false) Integer seconds) {

        if (isBlank(prompt)) {
            return "没有说明要生成什么视频。请把视频内容描述写在 prompt 参数里。";
        }
        String trimmedSize = isBlank(size) ? null : size.trim();

        return run(SUBMIT_TOOL, "生成视频", ModelProfile.EP_VIDEO, (profile, domain) -> {
            ModelEndpointClient.VideoTask task = client.createVideo(profile,
                    new ModelEndpointClient.VideoRequest(prompt.trim(), trimmedSize, seconds));
            if (task == null) {
                return "生视频模型没有返回任务信息。请如实告知用户这一步没提交成功。";
            }
            if (task.failed()) {
                return "生视频任务提交后立即失败：" + failureReason(task)
                        + "。请如实告知用户这次生成没成功。";
            }

            task = waitForCompletion(profile, task);
            if (task.failed()) {
                return "生视频任务失败：" + failureReason(task) + "。请如实告知用户这次生成没成功。";
            }
            if (isDone(task)) {
                return storeResult(profile, task);
            }
            if (isBlank(task.taskId())) {
                return "生视频模型没有返回任务号（当前状态：" + describeStatus(task) + "），"
                        + "无法继续查询。请如实告知用户这段视频暂时拿不到。";
            }
            return "视频任务已提交，还在生成中（当前状态：" + describeStatus(task) + "）。"
                    + "任务号：" + task.taskId() + "。"
                    + "请先告诉用户正在生成，稍后再调用 checkVideo（taskId=" + task.taskId()
                    + "）查询结果；拿到视频编号后再告诉用户。";
        });
    }

    @Tool(value = CHECK_TOOL,
            desc = "查询之前用 generateVideo 提交的视频生成任务是否完成。"
                    + "参数 taskId 填 generateVideo 返回的任务号。"
                    + "任务已完成时返回视频编号，未完成时返回当前状态并提示稍后再查，失败时返回失败原因。"
                    + "在 generateVideo 返回了任务号（而不是视频编号）之后才需要调用本工具。",
            effect = Tool.Effect.READ)
    public String check(@ToolParam("generateVideo 返回的任务号") String taskId) {
        if (isBlank(taskId)) {
            return "没有指定任务号。请把 generateVideo 返回的 taskId 传进来。";
        }
        String trimmed = taskId.trim();

        return run(CHECK_TOOL, "查询视频任务", ModelProfile.EP_VIDEO, (profile, domain) -> {
            ModelEndpointClient.VideoTask task = client.videoStatus(profile, trimmed);
            if (task == null) {
                return "查询视频任务没有返回信息。请稍后再试。";
            }
            if (task.failed()) {
                return "视频任务 " + trimmed + " 失败：" + failureReason(task)
                        + "。请如实告知用户这次生成没成功。";
            }
            if (!isDone(task)) {
                return "视频任务 " + trimmed + " 还在进行中（当前状态：" + describeStatus(task)
                        + "）。请让用户稍等，之后再调用本工具查询。";
            }
            return storeResult(profile, task);
        });
    }

    // ==================== 内部 ====================

    /**
     * 提交后等待一小段：视频能在这个窗口内出成品是最顺的情况。
     *
     * <p>只等不重试：出错就让调用方看到真实错误。等待期间线程被占住是刻意的取舍：
     * 这是"父轮阻塞等待的调用"，与看图一致；隔离池独立正是为了不让它拖累别的工具。</p>
     */
    private ModelEndpointClient.VideoTask waitForCompletion(ModelProfile profile,
                                                            ModelEndpointClient.VideoTask task) {
        if (isDone(task) || isBlank(task.taskId())) {
            return task;
        }
        long deadline = System.currentTimeMillis() + waitMs;
        ModelEndpointClient.VideoTask current = task;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                /* 被隔离层中断（超时放弃）：恢复中断标志后原样返回当前状态，
                   让外层把"还没查完"如实讲出去，而不是吞成一个假的失败 */
                Thread.currentThread().interrupt();
                return current;
            }
            current = client.videoStatus(profile, task.taskId());
            if (current == null || isDone(current) || current.failed()) {
                return current == null ? task : current;
            }
        }
        return current;
    }

    /** 成品落暂存区：有地址就按地址存，没地址才把字节拉回来 */
    private String storeResult(ModelProfile profile, ModelEndpointClient.VideoTask task) {
        if (!isBlank(task.url())) {
            String reference = store.put(Kind.VIDEO, null, null, task.url());
            return "视频已生成，编号：" + reference + "。编号就是这段视频，可以直接告诉用户。";
        }
        if (isBlank(task.taskId())) {
            return "视频任务已完成，但服务商既没有返回地址也没有返回任务号，拿不到成品。"
                    + "请如实告知用户。";
        }
        // 带上限去取：超限时下载端直接停，不会先把整个成品读进堆里再判大小
        ModelEndpointClient.VideoResult result =
                client.downloadVideo(profile, task.taskId(), MAX_STORE_BYTES);
        if (result.tooLarge()) {
            return "视频已生成，但体积超过平台暂存上限（" + (MAX_STORE_BYTES / (1024 * 1024)) + " MB），无法转入。"
                    + "请如实告知用户视频已生成但平台暂时无法转存。";
        }
        byte[] content = result.content();
        if (content == null || content.length == 0) {
            return "视频任务已完成，但取回的成品是空的。请如实告知用户。";
        }
        String mime = isBlank(result.contentType()) ? "video/mp4" : result.contentType();
        String reference = store.put(Kind.VIDEO, mime,
                Base64.getEncoder().encodeToString(content), null);
        return "视频已生成，编号：" + reference
                + "（约 " + (content.length / 1024) + " KB）。编号就是这段视频，可以直接告诉用户。";
    }

    /** 完成判据：状态是完成：或者服务商压根不给状态、直接给了成品地址 */
    private static boolean isDone(ModelEndpointClient.VideoTask task) {
        if (task == null) {
            return false;
        }
        return task.completed() || (isBlank(task.status()) && !isBlank(task.url()));
    }

    private static String describeStatus(ModelEndpointClient.VideoTask task) {
        if (task == null) {
            return "未知";
        }
        return isBlank(task.status()) ? "处理中" : task.status();
    }

    private static String failureReason(ModelEndpointClient.VideoTask task) {
        if (task == null) {
            return "未返回失败原因";
        }
        return isBlank(task.error()) ? "服务商未给出原因" : task.error();
    }
}
