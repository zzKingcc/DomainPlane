package com.zzkingcc.domainplane.server.capability;

import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.runtime.tool.ToolExecutorService;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.server.model.DefaultModelResolver;
import com.zzkingcc.domainplane.server.model.ModelEndpointClient;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * 能力工具（生图 / 语音 / 视频）的共同骨架：解析档案 → 隔离执行 → 统一失败话术。
 *
 * <p>四个工具真正不同的只有三件事：<b>查哪个族</b>、<b>调客户端的哪个方法</b>、
 * <b>产出怎么落暂存区</b>。其余（取本轮域、沿域链解析、超时 / 满池 / 异常各自回喂什么话、
 * 绝不把字节拼进返回值）完全一样，所以收在这里一份。抄四份的话，改一处漏三处几乎是必然。</p>
 *
 * <p><b>域从哪来</b>：读 {@link RetrievalScope}，与 {@code seeImage} 同一口径：
 * 工具执行的隔离层已经把域搬到工具线程上，因此不需要在工具签名里加域参数；
 * 加了反而给了模型一个"调用别的域的模型"的口子。</p>
 *
 * <p><b>失败一律回喂文本，不抛</b>：工具抛异常会被执行框架转成一句笼统的失败，
 * 模型看不出"是没配模型"还是"服务商拒了"，下一轮还会用同样的方式再试一次。
 * 回喂一句能读懂的话，模型才有可能改口或如实告知用户。</p>
 *
 * @author zzkingcc
 */
abstract class CapabilityToolSupport {

    private static final Logger log = LoggerFactory.getLogger(CapabilityToolSupport.class);

    protected final DefaultModelResolver resolver;
    protected final ModelEndpointClient client;
    protected final MediaAttachmentStore store;
    protected final CapabilityCallExecutor executor;

    protected CapabilityToolSupport(DefaultModelResolver resolver,
                                    ModelEndpointClient client,
                                    MediaAttachmentStore store,
                                    CapabilityCallExecutor executor) {
        this.resolver = resolver;
        this.client = client;
        this.store = store;
        this.executor = executor;
    }

    /** 各族工具共同的失败话术里的一个动作名（如"生成图片"），用于拼"生成图片超时"这类句子 */
    protected interface CapabilityAction {
        String run(ModelProfile profile, String domain);
    }

    /**
     * 解析该域的能力档案并执行。
     *
     * @param toolName 工具名（日志与超时文案用）
     * @param action   人的动作名，如"生成图片"（拼失败文案用）
     * @param family   能力族（{@link ModelProfile#EP_IMAGES} 等）
     */
    protected String run(String toolName, String action, String family, CapabilityAction body) {
        String domain = RetrievalScope.current();
        ModelProfile profile;
        try {
            profile = resolver.endpointModel(domain, family);
        } catch (NotConfiguredException e) {
            /* 已经是给用户看的话术（"请到管控台「域空间」…"），原样回喂让模型如实转达 */
            log.warn("[能力调用] 域 {} 无法调用{}：{}", domain, action, e.getMessage());
            return e.getMessage();
        }
        try {
            return executor.call(toolName, () -> body.run(profile, domain));
        } catch (ToolExecutorService.ToolTimeoutException e) {
            log.warn("[能力调用] 域 {} {}超过 {}ms 上限", domain, action, e.timeoutMs());
            return action + "超时（超过 " + e.timeoutMs() + "ms），请如实告知用户稍后重试。";
        } catch (ToolExecutorService.ToolRejectedException e) {
            log.warn("[能力调用] 域 {} {}被拒：能力隔离池已满", domain, action);
            return "系统繁忙，暂时无法" + action + "，请稍后重试。";
        } catch (Exception e) {
            log.error("[能力调用] 域 {} {}失败（档案 {}）", domain, action, profile.alias(), e);
            return action + "失败：" + rootMessage(e) + "。请如实告知用户这一步没成功。";
        }
    }

    /** 异常链最内层的原因：包装异常（如客户端异常）真正有用的信息在 cause 上 */
    protected static String rootMessage(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return isBlank(message) ? cur.getClass().getSimpleName() : message;
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
