package com.zzkingcc.domainplane.server.vision;

import com.zzkingcc.domainplane.runtime.tool.ToolExecutorService;

import java.util.concurrent.Callable;

/**
 * 视觉委派的专用隔离层 —— 有界池 + 超时 + 满即拒，与本地工具池分开。
 *
 * <p>为什么不复用 {@code ToolExecutorService} 那个 Bean，而要另起一个实例：</p>
 *
 * <ul>
 *   <li><b>上限不同</b>。本地工具默认 30s，而一次带图 completion 在图片大、模型慢时
 *       轻易超过：复用就等于把"看图"卡在一个它天生会超的时限上，且失败信息会写成
 *       一句笼统的"工具超时"，看不出是图太大还是模型太慢；</li>
 *   <li><b>池必须独立</b>。看图是父轮阻塞等待的长调用，与本地工具共池时，
 *       几个并发的看图请求就能把工具池占满，把不相关的检索 / 计算类工具一起拒掉：
 *       一个域在看图，另一个域的工具跟着不可用，这种耦合查起来非常费劲。</li>
 * </ul>
 *
 * <p>这里只是把内核那个通用执行器包一层，<b>不复制它的实现</b>：
 * 超时、中断、满即拒、ThreadLocal 搬运（域 / traceId）那套逻辑只该有一份，
 * 拷贝一份出来迟早两处修一处漏。包一层还顺带解决了 Spring 的按类型注入歧义：
 * 两个 {@code ToolExecutorService} 同类型会让工具路由那处的注入变得要靠名字猜。</p>
 *
 * @author zzkingcc
 */
public class VisionCallExecutor implements AutoCloseable {

    private final ToolExecutorService delegate;

    public VisionCallExecutor(int poolSize, long timeoutMs) {
        this.delegate = ToolExecutorService.create(poolSize, timeoutMs);
    }

    /**
     * 在受控线程上执行一次视觉调用。
     *
     * @param operation 用于日志与超时文案的操作名（如 {@code seeImage}）
     * @param task      调用体；其内部读到的域 / traceId 与调用线程一致
     * @throws ToolExecutorService.ToolTimeoutException  超过上限（已尝试中断）
     * @throws ToolExecutorService.ToolRejectedException 隔离池已满（不排队，直接拒）
     */
    public String call(String operation, Callable<String> task) throws Exception {
        return delegate.call(operation, task);
    }

    /** 执行上限（毫秒），供启动日志与排障 */
    public long timeoutMs() {
        return delegate.timeoutMs();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
