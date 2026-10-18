package com.zzkingcc.domainplane.server.capability;

import com.zzkingcc.domainplane.runtime.tool.ToolExecutorService;

import java.util.concurrent.Callable;

/**
 * 能力调用（生图 / 语音 / 视频）的专用隔离层：有界池 + 超时 + 满即拒。
 *
 * <p>与视觉委派那个 {@code VisionCallExecutor} 同构但<b>池独立、上限独立</b>：</p>
 *
 * <ul>
 *   <li><b>上限不同</b>。生图一次动辄 30–90 秒，本地工具默认 30s 必然超时；而看图上限定在 60s。
 *       两类调用天生不同量级，共用一个上限等于给其中一类设了一个它注定会撞的墙。</li>
 *   <li><b>池必须独立</b>。一次生图会把线程占住一分钟以上，与看图 / 本地工具共池时，
 *       几个并发的生图请求就能把不相关的工具一起拒掉：一个域在生图，另一个域连检索都不可用，
 *       这种耦合查起来非常费劲。</li>
 * </ul>
 *
 * <p>只包一层内核的 {@link ToolExecutorService}，<b>不复制它的实现</b>：超时、中断、满即拒、
 * ThreadLocal 搬运（域 / traceId）那套逻辑只该有一份。</p>
 *
 * @author zzkingcc
 */
public class CapabilityCallExecutor implements AutoCloseable {

    private final ToolExecutorService delegate;

    public CapabilityCallExecutor(int poolSize, long timeoutMs) {
        this.delegate = ToolExecutorService.create(poolSize, timeoutMs);
    }

    /**
     * 在受控线程上执行一次能力调用。
     *
     * @param operation 用于日志与超时文案的操作名（如 {@code generateImage}）
     * @param task      调用体；其内部读到的域 / traceId 与调用线程一致
     * @throws ToolExecutorService.ToolTimeoutException  超过上限（已尝试中断）
     * @throws ToolExecutorService.ToolRejectedException 隔离池已满（不排队，直接拒）
     */
    public String call(String operation, Callable<String> task) throws Exception {
        return delegate.call(operation, task);
    }

    /** 执行上限（毫秒）—— 生视频的"边提交边等"预算要按它来定，不能比它还长 */
    public long timeoutMs() {
        return delegate.timeoutMs();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
