package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地工具执行的<b>超时与隔离</b>。
 *
 * <p>要盯的不只是"超时能不能触发"，还有一个更隐蔽的问题：工具执行被挪到隔离线程后
 * {@code ThreadLocal} 不跟随。其中 {@link RetrievalScope} 丢了会让知识库检索从
 * "本轮域 + 祖先链"退化成<b>全库检索</b>：那是越权读，而且不会报任何错。
 * 所以上下文传播与执行后清理都是这里的必测项。</p>
 */
class ToolExecutorServiceTest {

    // ==================== 超时 ====================

    @Test
    @DisplayName("工具卡死时放弃等待并抛超时，编排线程被释放")
    void timeoutAbandonsWait() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(4, 200)) {
            long start = System.currentTimeMillis();
            ToolExecutorService.ToolTimeoutException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    ToolExecutorService.ToolTimeoutException.class,
                    () -> service.call("stuck", () -> {
                        Thread.sleep(60_000);
                        return "never";
                    }));
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(thrown.getMessage().contains("stuck"), "应指明是哪个工具超时：" + thrown.getMessage());
            assertEquals(200L, thrown.timeoutMs());
            assertTrue(elapsed < 5_000, "应在上限附近就返回，实际耗时 " + elapsed + "ms");
        }
    }

    @Test
    @DisplayName("超时后编排线程的中断标志不被污染")
    void timeoutDoesNotPolluteInterruptFlag() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(4, 150)) {
            org.junit.jupiter.api.Assertions.assertThrows(ToolExecutorService.ToolTimeoutException.class,
                    () -> service.call("stuck", () -> {
                        Thread.sleep(60_000);
                        return "never";
                    }));
            assertFalse(Thread.currentThread().isInterrupted(),
                    "超时是业务失败，不能顺手把编排线程的中断标志置上——那会让后续所有阻塞调用立刻抛 InterruptedException");
        }
    }

    @Test
    @DisplayName("响应中断的工具能真正退出，不白占隔离池")
    void interruptibleToolReleasesThread() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(2, 200)) {
            AtomicBoolean interrupted = new AtomicBoolean(false);
            CountDownLatch entered = new CountDownLatch(1);
            org.junit.jupiter.api.Assertions.assertThrows(
                    ToolExecutorService.ToolTimeoutException.class,
                    () -> service.call("sleepy", () -> {
                        entered.countDown();
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            interrupted.set(true);
                            throw e;
                        }
                        return "never";
                    }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            // 中断发出后线程应当很快退出；池的全部容量随之恢复
            long deadline = System.currentTimeMillis() + 5_000;
            while (!interrupted.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(interrupted.get(), "cancel(true) 应当让 sleep 中的工具收到中断");
        }
    }

    // ==================== 池满即拒 ====================

    @Test
    @DisplayName("池满时直接拒绝而不是排队")
    void rejectsWhenPoolFull() throws Exception {
        // 单线程池 + 零队列：第一个任务占住后，第二个必然被拒
        try (ToolExecutorService service = ToolExecutorService.create(1, 5_000)) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);

            Thread holder = new Thread(() -> {
                try {
                    service.call("holder", () -> {
                        entered.countDown();
                        release.await();
                        return "ok";
                    });
                } catch (Exception ignored) {
                    // 测试线程，只等信号
                }
            });
            holder.setDaemon(true);
            holder.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS), "占位任务没进池");

            ToolExecutorService.ToolRejectedException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    ToolExecutorService.ToolRejectedException.class,
                    () -> service.call("second", () -> "should never run"));
            assertTrue(thrown.getMessage().contains("busy") || thrown.getMessage().contains("忙"),
                    "回喂模型的文案要说清是繁忙：实际=" + thrown.getMessage());
            release.countDown();
        }
    }

    // ==================== 异常透传 ====================

    @Test
    @DisplayName("工具自身抛出的异常原样上抛，不被包装")
    void toolExceptionPropagates() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(2, 5_000)) {
            IllegalStateException boom = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> service.call("boom", () -> {
                        throw new IllegalStateException("内部故障");
                    }));
            assertEquals("内部故障", boom.getMessage());
        }
    }

    @Test
    @DisplayName("工具抛出的 Error 不被吞成普通失败")
    void toolErrorPropagates() {
        try (ToolExecutorService service = ToolExecutorService.create(2, 5_000)) {
            org.junit.jupiter.api.Assertions.assertThrows(StackOverflowError.class,
                    () -> service.call("boom", () -> {
                        throw new StackOverflowError();
                    }));
        }
    }

    // ==================== 上下文传播（本类的核心） ====================

    @Test
    @DisplayName("上下文都要搬到隔离线程上")
    void contextIsCarriedToToolThread() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(2, 5_000)) {
            AtomicReference<String> domain = new AtomicReference<>();
            AtomicReference<String> traceId = new AtomicReference<>();

            ToolInvocationContext.bind("trace-xyz");
            RetrievalScope.bind("sales.order");
            try {
                service.call("probe", () -> {
                    traceId.set(ToolInvocationContext.traceId());
                    domain.set(RetrievalScope.current());
                    return "ok";
                });
            } finally {
                ToolInvocationContext.clear();
                RetrievalScope.clear();
            }

            assertEquals("trace-xyz", traceId.get(), "traceId 丢失会让工具侧日志与这次调用对不上");
            assertEquals("sales.order", domain.get(),
                    "检索域丢失会让知识库从「本轮域 + 祖先链」退化成全库检索（越权读）");
        }
    }

    @Test
    @DisplayName("TraceId 的 ThreadLocal 传到工具线程上")
    void traceIdThreadLocalIsCarried() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(2, 5_000)) {
            AtomicReference<String> fromThreadLocal = new AtomicReference<>();
            ToolInvocationContext.bind("trace-mdc");
            try {
                service.call("probe", () -> {
                    fromThreadLocal.set(TraceId.current());
                    return "ok";
                });
            } finally {
                ToolInvocationContext.clear();
            }
            assertEquals("trace-mdc", fromThreadLocal.get());
        }
    }

    @Test
    @DisplayName("MDC 同步传递 —— 仅在有 slf4j 绑定时校验")
    void mdcCarriesTraceId() throws Exception {
        // domainplane-runtime 只依赖 slf4j-api，测试期没有 logback 实现，MDC 是空实现。
        // 与其假设它在，不如先探一下：写进去能读回来才说明这个环境真接了 MDC
        MDC.put("probe", "1");
        boolean mdcWorks = "1".equals(MDC.get("probe"));
        MDC.remove("probe");
        org.junit.jupiter.api.Assumptions.assumeTrue(mdcWorks,
                "当前类路径无 slf4j 绑定实现，MDC 是空实现，MDC 传递无法在此校验");

        try (ToolExecutorService service = ToolExecutorService.create(2, 5_000)) {
            AtomicReference<String> fromMdc = new AtomicReference<>();
            ToolInvocationContext.bind("trace-mdc");
            try {
                service.call("probe", () -> {
                    fromMdc.set(MDC.get(TraceId.MDC_KEY));
                    return "ok";
                });
            } finally {
                ToolInvocationContext.clear();
            }
            assertEquals("trace-mdc", fromMdc.get(),
                    "TraceId 是 ThreadLocal + MDC 双重机制，只补一个日志里 %X{traceId} 就是空的");
        }
    }

    @Test
    @DisplayName("执行结束后隔离线程被清干净，不把这一轮的上下文留给下一轮")
    void contextIsClearedAfterExecution() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(1, 5_000)) {
            ToolInvocationContext.bind("trace-A");
            RetrievalScope.bind("domain.a");
            service.call("first", () -> "ok");
            ToolInvocationContext.clear();
            RetrievalScope.clear();

            // 单线程池：第二次一定复用同一个线程。上下文若没清，这里就会读到 A 的值
            AtomicReference<String> domain = new AtomicReference<>("sentinel");
            AtomicReference<String> traceId = new AtomicReference<>("sentinel");
            service.call("second", () -> {
                domain.set(RetrievalScope.current());
                traceId.set(ToolInvocationContext.traceId());
                return "ok";
            });

            assertNull(domain.get(), "上一轮检索域残留会让本轮读到不属于它的知识库");
            assertNull(traceId.get(), "上一轮 traceId 残留会让两轮日志混成一条链路");
        }
    }

    @Test
    @DisplayName("调用线程没绑上下文时，隔离线程上就是干净的全空")
    void noContextStaysClean() throws Exception {
        try (ToolExecutorService service = ToolExecutorService.create(1, 5_000)) {
            AtomicReference<String> traceId = new AtomicReference<>("sentinel");
            AtomicReference<String> domain = new AtomicReference<>("sentinel");
            service.call("probe", () -> {
                traceId.set(ToolInvocationContext.traceId());
                domain.set(RetrievalScope.current());
                return "ok";
            });
            assertNull(traceId.get());
            assertNull(domain.get());
        }
    }

    // ==================== 与 ToolRouter 的接线 ====================

    @Test
    @DisplayName("超时的工具经路由后回喂模型的文本指向「别原样重试」")
    void routerFeedsBackTimeoutText() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(local("slow", request -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "never";
        }));

        try (ToolExecutorService service = ToolExecutorService.create(4, 200)) {
            ToolRouter router = new ToolRouter(registry, null, service);
            String out = router.execute(req("slow", "{}"));

            assertTrue(out.contains("slow"), "应指明是哪个工具：" + out);
            assertTrue(out.contains("超时"), "应说明是超时而不是工具内部报错：" + out);
            assertFalse(out.contains("Exception"), "不该把异常类型喂给模型：" + out);
            assertTrue(out.contains("不要"), "应明确指示不要编造结果：" + out);
        }
    }

    @Test
    @DisplayName("池满时回喂模型的文本说明是系统繁忙")
    void routerFeedsBackBusyText() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        ToolRegistry registry = new ToolRegistry();
        registry.register(local("blocker", request -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        }));

        try (ToolExecutorService service = ToolExecutorService.create(1, 10_000)) {
            ToolRouter router = new ToolRouter(registry, null, service);
            Thread holder = new Thread(() -> router.execute(req("blocker", "{}")));
            holder.setDaemon(true);
            holder.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            String out = router.execute(req("blocker", "{}"));
            assertTrue(out.contains("繁忙"), "应说明是繁忙（可稍后重试），而不是工具坏了：" + out);
            release.countDown();
        }
    }

    @Test
    @DisplayName("正常工具的返回值原样通过")
    void normalResultPassesThrough() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(local("echo", request -> "结果:" + request.arguments()));

        try (ToolExecutorService service = ToolExecutorService.create(2, 5_000)) {
            ToolRouter router = new ToolRouter(registry, null, service);
            assertEquals("结果:{}", router.execute(req("echo", "{}")));
        }
    }

    // ==================== 夹具 ====================

    /** {@code ToolExecutionRequest} 没有公开构造器（langchain4j 内部类），只能走 builder */
    private static ToolExecutionRequest req(String name, String arguments) {
        return ToolExecutionRequest.builder().name(name).arguments(arguments).build();
    }

    /**
     * 单参函数式接口。
     *
     * <p>{@code ToolExecutor} 有两个参数（request + memoryId）不是函数式接口，不能直接写 lambda；
     * 而夹具里 memoryId 恒为 null，用不到它：这里收一个单参接口再包一层。</p>
     */
    @FunctionalInterface
    private interface ToolBody {
        String run(ToolExecutionRequest request);
    }

    private static ToolRegistry.Registered local(String name, ToolBody body) {
        ToolDescriptor descriptor = new ToolDescriptor(name, "测试工具", "test", "1",
                Tool.Effect.READ, true, true, List.of(), List.of(), null, "test#" + name);
        ToolSpecification spec = ToolSpecification.builder().name(name).description("测试工具").build();
        return ToolRegistry.Registered.local(descriptor, spec, (request, memoryId) -> body.run(request));
    }

    /** 断言用的是"是否包含"，避免把文案写两遍导致改文案就红测试 */
    @Test
    @DisplayName("默认共享实例可用且线程名带前缀（排障能一眼认出是工具线程）")
    void sharedDefaultUsesPrefixedThreads() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        ToolExecutorService.sharedDefault().call("probe", () -> {
            threadName.set(Thread.currentThread().getName());
            return "ok";
        });
        assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("domainplane-tool-"),
                "工具线程应可辨识，实际线程名=" + threadName.get());
    }
}