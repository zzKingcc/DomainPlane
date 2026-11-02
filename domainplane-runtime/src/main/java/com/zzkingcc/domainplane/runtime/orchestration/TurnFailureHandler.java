package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.common.exception.UpstreamLlmFailure;
import com.zzkingcc.domainplane.runtime.stream.StreamContext;
import java.util.concurrent.CancellationException;
import lombok.extern.slf4j.Slf4j;

/**
 * 一轮跑砸之后的收尾：定性（这是哪一类失败）、发什么事件、以及要不要回滚记忆 / 清断点。
 *
 * <p>搬出来的理由与另外几个协作者不同：这不是"搬代码"，是**把出口收敛到一个地方**。
 * 此前这些分支夹在两个轮次主体的中间，改一处错误码要在 2,300 行里翻三个 catch；
 * 现在"这一轮会以什么码回报"这件事全部写在本类的四个方法里。</p>
 *
 * <p>三条出口的优先级不能变：停止（用户主动）→ 未配置（平台没配）→ 通用失败。
 * 顺序错了的表现很隐蔽 —— 用户在模型吐字期间点了停止，若先落到"通用失败"，
 * 流会以 ORCHESTRATION_FAILED（可重试）收尾，前端给出的按钮是"重试"，
 * 而用户要的是"它停下来了"。</p>
 *
 * @author zzkingcc
 */
@Slf4j
final class TurnFailureHandler {

    private final TurnLifecycle lifecycle;

    TurnFailureHandler(TurnLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** 异常链最内层的原因：包装异常（如 langchain4j 的客户端异常）真正有用的信息在 cause 上 */
    static String rootMessage(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    /**
     * 判断异常链中是否包含 CancellationException
     */
    static boolean isCancellationException(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof CancellationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 在异常链上查找 {@link NotConfiguredException}。
     */
    private static NotConfiguredException findNotConfigured(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur instanceof NotConfiguredException nce) {
                return nce;
            }
            Throwable cause = cur.getCause();
            if (cause == cur) {
                break;
            }
            cur = cause;
        }
        return null;
    }

    /**
     * 通用异常兜底的"出口"—— 决定这一轮该以哪个错误码回报。
     *
     * <p>此前这里一律发 {@link ErrorCode#ORCHESTRATION_FAILED}（可重试）。问题是上游模型
     * 明确拒绝的几类（欠费 / 凭证无效 / 请求被拒）重试也不会好，发可重试码等于让前端
     * 一直重发同一个必然失败的请求，而用户永远看不到真因（如 DeepSeek 的 Insufficient Balance）。</p>
     *
     * <p>定性交给 {@link UpstreamLlmFailure}：认出来就发对应的码，并把上游原文一并带出；
     * 认不出来（不是上游模型异常）维持原兜底，不乱归类。</p>
     */
    void emitOrchestrationFailure(String sessionId, StreamContext context, Throwable e) {
        UpstreamLlmFailure.Diagnosis diagnosis = UpstreamLlmFailure.diagnose(e);
        if (diagnosis == null) {
            context.emit(AgentEvent.error(sessionId, ErrorCode.ORCHESTRATION_FAILED, TraceId.currentOrNew()));
            return;
        }
        log.warn("[Agent编排] 会话[{}] traceId={} 上游模型失败，按 {} 回报：{}",
                sessionId, TraceId.current(), diagnosis.code().name(), diagnosis.reason());
        String reason = diagnosis.reason();
        /* 不可重试的几类（欠费 / 鉴权 / 被拒）才把上游原文带进事件体：用户与运维都靠它定位；
           可重试的几类（限流 / 超时 / 上游 5xx）上游原文多是英文网络措辞，不如码的对外文案好懂；
           上游连原因都没给（reason 为 null）时同样退回码的对外文案。 */
        String message = (diagnosis.code().isRetryable() || reason == null)
                ? null
                : diagnosis.code().getUserMessage() + "（上游：" + reason + "）";
        context.emit(AgentEvent.error(sessionId, message, diagnosis.code(), TraceId.currentOrNew()));
    }

    /**
     * "依赖未配置"的专用收尾 —— 把它从通用执行失败里择出来。
     *
     * @param memorySizeBefore 提问前的记忆条数；不为 {@code null} 时一并回滚，
     *                         理由同通用异常路径（提问已写入而回答未写入）
     * @return true 表示已按"未配置"处理并结束流，调用方不要再走通用兜底
     */
    boolean handleNotConfigured(String sessionId, String key, StreamContext context, Throwable e,
                                Integer memorySizeBefore) {
        NotConfiguredException notConfigured = findNotConfigured(e);
        if (notConfigured == null) {
            return false;
        }
        log.warn("[Agent编排] 会话[{}] traceId={} 依赖未配置，按 {} 回报：{}",
                sessionId, TraceId.current(),
                notConfigured.getCodeName(), notConfigured.getMessage());
        lifecycle.releaseCheckpointQuietly(key, "依赖未配置");
        // 同样要回滚记忆：这一轮是以 ERROR 收尾的，没有回答，
        // 留着孤零零一条提问就是永久"有问无答"
        lifecycle.restoreMemory(key, memorySizeBefore);
        // 走 3 参工厂，事件体取码的对外文案。曾经用 4 参把 notConfigured.getMessage()
        // 原样带出去，而各抛出点的 message 写的是「请先在管控台××页填写」：
        // 那是给管理员的路标，终端用户够不着，看到只剩茫然。具体缺什么、
        // 去哪一页配，上面的 warn 日志与码的 action 字段都有，信息不丢。
        context.emit(AgentEvent.error(sessionId,
                notConfigured.getErrorCode(), TraceId.currentOrNew()));
        context.complete();
        return true;
    }

    /**
     * 处理用户主动停止:回滚记忆、清 checkpoint、推送停止事件
     *
     * @param memorySizeBefore 本轮提问前的记忆条数；{@code null} = 本次不涉及记忆回滚
     */
    void handleStop(String sessionId, String key, StreamContext context, String source,
                    Integer memorySizeBefore) {
        log.info("[Agent编排] 会话[{}] 任务被用户停止,source={}, 半截文本长度={}",
                sessionId, source, context == null ? 0 : context.partialOutput().length());

        // 1. 回滚记忆(仅 orchestrate 场景,resume 未写记忆)
        if ("orchestrate".equals(source)) {
            lifecycle.restoreMemory(key, memorySizeBefore);
        }

        // 1.5 归档：记忆回滚掉的是"这一轮问过了但没答"，而现场（问了什么、答到哪儿）
        //      本身就是档案，标记 aborted 后闭合，不删
        lifecycle.abortOpenTurnQuietly(key, "用户主动停止", context == null ? null : context.partialOutput());

        // 2. 清 checkpoint(不可恢复)
        lifecycle.releaseCheckpointQuietly(key, "用户停止");

        // 3. 推送停止事件给调用方
        if (context != null) {
            context.emit(AgentEvent.stopped(sessionId));
            context.complete();
        }
    }
}
