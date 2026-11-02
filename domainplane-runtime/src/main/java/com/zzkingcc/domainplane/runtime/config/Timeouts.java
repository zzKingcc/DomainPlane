package com.zzkingcc.domainplane.runtime.config;

/**
 * 超时总表 —— 全平台"等多久算太长"的唯一口径。
 *
 * <p>收这张表的起因是超时散在三个模块、五种写法里：编排的等模型上限与分片是
 * {@code static final} 常量，工具 / 多模态 / 能力调用各是一个 {@code @Value} 注入的裸数字，
 * 知识检索又另有一个 5s。同一个问题（"等多久"）有五份答案，改一处漏一处是必然的，
 * 而漏掉那一处的表现是"改了配置没生效"，最难查的一种。</p>
 *
 * <p>收的是<b>配置表</b>，不是执行单元：三个执行池（本地工具 / 看图 / 能力调用）
 * 继续各是各的线程池 —— 它们隔离的是不同资源，"生图不饿死检索"这条靠池独立而不是靠
 * 超时不同来保证。同理，池的"满即拒"（零队列）语义也不在这张表里，别顺手改成排队。</p>
 *
 * <p>放在 runtime 而不是 server：消费这些值的地方（编排、工具执行）全在 runtime，
 * server 只是配值的那一侧。为了让 runtime 不依赖 Spring，这里刻意是一个**纯值对象**，
 * 绑 {@code domainplane.timeout.*} 的 {@code @ConfigurationProperties} 在 server 侧。</p>
 *
 * <p>与 {@code domain.instance.timeout-seconds}（实例心跳超时）、
 * {@code memory.checkpoint-ttl}、{@code domain.delete-grace} 是同一族（"多久算过期"），
 * 但那三者的语义是"生命周期"，这里是"单次调用的上限"，改一个不影响另一个。</p>
 *
 * @param modelWaitMs      等模型终态的总上限：兜住"上游既不吐 token 也不报错"的半开连接
 * @param modelWaitSliceMs 等模型终态的单次阻塞上限：每片醒来先看停止标志，
 *                         用户点停止最迟一个切片生效 —— <b>这个数决定停止的响应延迟</b>，
 *                         调大省 CPU、调小更跟手，但不影响总上限
 * @param plannerWaitMs    规划前置调用的总上限：规划卡住不值得拖住整轮，到点降级直进 loop，
 *                         故远短于 modelWaitMs；分片复用 modelWaitSliceMs（停止响应口径一致）
 * @param toolInvokeMs     本地工具单次调用上限（有界池执行，超时即中断）
 * @param visionCallMs     看图调用上限（一次请求要过模型，比本地工具慢）
 * @param capabilityCallMs 能力调用上限（生图动辄 30–90 秒，故最长）
 */
public record Timeouts(long modelWaitMs,
                       long modelWaitSliceMs,
                       long plannerWaitMs,
                       long toolInvokeMs,
                       long visionCallMs,
                       long capabilityCallMs) {

    /** 等模型终态的默认总上限：10 分钟 */
    public static final long DEFAULT_MODEL_WAIT_MS = 10 * 60 * 1000L;
    /** 等模型终态的默认分片：200ms —— 停止的响应延迟就是这个数 */
    public static final long DEFAULT_MODEL_WAIT_SLICE_MS = 200L;
    /** 规划前置调用的默认上限：60s（超时即降级直进 loop，不拖累本轮） */
    public static final long DEFAULT_PLANNER_WAIT_MS = 60_000L;
    /** 本地工具单次调用的默认上限：30s（与远程工具实例调用对齐） */
    public static final long DEFAULT_TOOL_INVOKE_MS = 30_000L;
    /** 看图调用的默认上限：60s */
    public static final long DEFAULT_VISION_CALL_MS = 60_000L;
    /** 能力调用的默认上限：120s */
    public static final long DEFAULT_CAPABILITY_CALL_MS = 120_000L;

    /** 分片的下限：再低就是拿 CPU 换"停止更跟手"，1ms 已经足够快 */
    private static final long MIN_SLICE_MS = 1L;

    public static Timeouts defaults() {
        return new Timeouts(DEFAULT_MODEL_WAIT_MS, DEFAULT_MODEL_WAIT_SLICE_MS,
                DEFAULT_PLANNER_WAIT_MS, DEFAULT_TOOL_INVOKE_MS, DEFAULT_VISION_CALL_MS,
                DEFAULT_CAPABILITY_CALL_MS);
    }

    public Timeouts {
        /* 全部取正：0 或负数在这几处的含义各不相同（有的会变成"永不超时"、
           有的会立刻超时），统一挡在入口，让"配错"在启动时暴露而不是在调用时表现成怪事 */
        requirePositive(modelWaitMs, "model-wait-ms");
        requirePositive(modelWaitSliceMs, "model-wait-slice-ms");
        requirePositive(plannerWaitMs, "planner-wait-ms");
        requirePositive(toolInvokeMs, "tool-invoke-ms");
        requirePositive(visionCallMs, "vision-call-ms");
        requirePositive(capabilityCallMs, "capability-call-ms");
        if (modelWaitSliceMs < MIN_SLICE_MS) {
            throw new IllegalArgumentException("domainplane.timeout.model-wait-slice-ms 不得小于 "
                    + MIN_SLICE_MS + "ms（当前 " + modelWaitSliceMs + "）");
        }
        if (modelWaitSliceMs > modelWaitMs) {
            throw new IllegalArgumentException("domainplane.timeout.model-wait-slice-ms（"
                    + modelWaitSliceMs + "）不得大于 model-wait-ms（" + modelWaitMs
                    + "）：分片比总上限还长的话，第一次醒来就已经超时了");
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException("domainplane.timeout." + key + " 必须为正数（当前 " + value + "）");
        }
    }
}
