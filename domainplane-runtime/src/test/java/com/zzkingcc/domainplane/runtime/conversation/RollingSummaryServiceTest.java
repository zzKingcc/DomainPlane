package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.ConversationSummaryStore;
import com.zzkingcc.domainplane.api.conversation.ConversationSummaryStore.Stored;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.UserInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-R1 滚动摘要的调度与生成：异步 + 阈值门 + 单飞 + 失败只告警。
 *
 * <p>摘要是一次<b>额外的模型调用</b>，所以这里锁的不是"摘要内容对不对"
 * （那是模型的事），而是几条<b>调度不变式</b>：</p>
 * <ul>
 *   <li><b>不到阈值不调模型</b>：滑出窗口且未进摘要的轮次不够就不发调用（省钱省延迟）；</li>
 *   <li><b>单飞</b>：同一会话同时只跑一个摘要任务，否则连点几轮会并发覆盖，摘要倒退；</li>
 *   <li><b>进度只进不退</b>：{@code upToTurnId} 只会被并入的轮号往前推；已有摘要的进度
 *       以内的轮次绝不重复喂给模型；</li>
 *   <li><b>失败只告警</b>：生成空 / 提交失败 / 读归档失败都不抛，调用方零感知。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("M4-R1 滚动摘要：阈值 / 单飞 / 进度 / 降级")
class RollingSummaryServiceTest {

    private static final String SESSION = "default.sales|s-1";

    // ==================== 桩 ====================

    /** 内存摘要存储 */
    private static class MemoryStore implements ConversationSummaryStore {
        final Map<String, Stored> saved = new HashMap<>();
        RuntimeException loadFailure;
        int saveCalls;

        @Override public Optional<Stored> load(String sessionKey) {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return Optional.ofNullable(saved.get(sessionKey));
        }

        @Override public void save(String sessionKey, Stored summary) {
            saveCalls++;
            saved.put(sessionKey, summary);
        }

        @Override public int deleteByDomain(String domain) {
            return 0;
        }
    }

    /** 归档桩：只实现 recentTurns（按 limit 取尾部），其余方法不许被踩到 */
    private static ConversationArchive archiveOf(List<TurnRecord> turns) {
        return (ConversationArchive) Proxy.newProxyInstance(
                RollingSummaryServiceTest.class.getClassLoader(),
                new Class<?>[]{ConversationArchive.class},
                (proxy, method, args) -> {
                    if ("recentTurns".equals(method.getName())) {
                        int limit = ((Number) args[1]).intValue();
                        if (limit <= 0) {
                            return List.<TurnRecord>of();
                        }
                        int from = Math.max(0, turns.size() - limit);
                        return new ArrayList<>(turns.subList(from, turns.size()));
                    }
                    throw new UnsupportedOperationException("未桩的方法：" + method.getName());
                });
    }

    private static TurnRecord closed(long turnId, String digest) {
        return new TurnRecord(turnId, SESSION, Instant.parse("2026-10-14T10:00:00Z"),
                Instant.parse("2026-10-14T10:00:08Z"), UserInput.of("问" + turnId, List.of()),
                List.of(), "答" + turnId, null, new TurnFlags(false, false, false, null), digest);
    }

    private static TurnRecord open(long turnId) {
        return new TurnRecord(turnId, SESSION, Instant.now(), null,
                UserInput.of("问" + turnId, List.of()), List.of(), null, null, TurnFlags.open(), null);
    }

    private static TurnRecord aborted(long turnId) {
        return new TurnRecord(turnId, SESSION, Instant.now(), Instant.now(),
                UserInput.of("问" + turnId, List.of()), List.of(), null, null,
                new TurnFlags(false, true, false, null), "摘要" + turnId);
    }

    private static List<TurnRecord> turns(long from, long to) {
        List<TurnRecord> out = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            out.add(closed(i, "摘要" + i));
        }
        return out;
    }

    private static RollingSummaryService service(MemoryStore store, ConversationArchive archive,
                                                RollingSummaryService.Summarizer summarizer,
                                                RollingSummaryService.Options options) {
        // 直接执行器：跑成同步语义，测试可断言"必然发生过"
        return new RollingSummaryService(store, archive, summarizer, options, Runnable::run);
    }

    private static final RollingSummaryService.Options ON =
            new RollingSummaryService.Options(true, 2, 20);

    // ==================== 开关 / 降级 ====================

    @Test
    @DisplayName("关闭时：不生成、不写盘，load 也返回空（整段退化为 digest 拼接）")
    void disabledDoesNothing() {
        MemoryStore store = new MemoryStore();
        AtomicInteger calls = new AtomicInteger();
        RollingSummaryService svc = service(store, archiveOf(turns(1, 6)),
                (prev, material) -> { calls.incrementAndGet(); return "摘要"; },
                RollingSummaryService.Options.DISABLED);

        assertFalse(svc.schedule(SESSION, 2));
        assertEquals(0, calls.get(), "关掉就不该发起模型调用");
        assertEquals(0, store.saveCalls);
        assertEquals(Optional.empty(), svc.load(SESSION));
    }

    @Test
    @DisplayName("未注入摘要器时 schedule 返回 false（内核不依赖具体模型）")
    void missingSummarizerDoesNothing() {
        MemoryStore store = new MemoryStore();
        RollingSummaryService svc = service(store, archiveOf(turns(1, 6)), null, ON);

        assertFalse(svc.schedule(SESSION, 2));
        assertEquals(0, store.saveCalls);
    }

    @Test
    @DisplayName("会话键空白直接拒绝；归档为空则空跑一次不落盘")
    void blankSessionOrEmptyArchiveDoesNothing() {
        MemoryStore store = new MemoryStore();
        AtomicInteger calls = new AtomicInteger();
        RollingSummaryService.Summarizer s = (prev, material) -> { calls.incrementAndGet(); return "x"; };

        assertFalse(service(store, archiveOf(turns(1, 6)), s, ON).schedule("  ", 2),
                "空白会话键连任务都不该提交");

        service(store, archiveOf(List.of()), s, ON).schedule(SESSION, 2);
        assertEquals(0, calls.get(), "归档为空时不该调模型");
        assertEquals(0, store.saveCalls);
    }

    @Test
    @DisplayName("读完归档抛异常：只告警，schedule 不炸（异步任务内部吞掉）")
    void archiveReadFailureIsSwallowed() {
        MemoryStore store = new MemoryStore();
        ConversationArchive broken = (ConversationArchive) Proxy.newProxyInstance(
                RollingSummaryServiceTest.class.getClassLoader(),
                new Class<?>[]{ConversationArchive.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("归档不可用");
                });
        RollingSummaryService svc = service(store, broken, (p, m) -> "x", ON);

        // 直接执行器下异常在 schedule 内部被 catch，不应外溢
        svc.schedule(SESSION, 2);
        assertEquals(0, store.saveCalls);
    }

    // ==================== 阈值 ====================

    @Test
    @DisplayName("不到阈值不调模型：滑出窗口且未进摘要的轮次不够就不发调用")
    void belowThresholdSkipsModelCall() {
        MemoryStore store = new MemoryStore();
        AtomicInteger calls = new AtomicInteger();
        // 6 轮、窗口 5 → 只有第 1 轮滑出窗口，低于 minNewTurns=2
        RollingSummaryService svc = service(store, archiveOf(turns(1, 6)),
                (prev, material) -> { calls.incrementAndGet(); return "新摘要"; },
                new RollingSummaryService.Options(true, 2, 20));

        assertTrue(svc.schedule(SESSION, 5));
        assertEquals(0, calls.get(), "只积到 1 轮，不到阈值 2，不该调模型");
        assertEquals(0, store.saveCalls);
    }

    @Test
    @DisplayName("到阈值才调模型：并入滑出窗口的那批轮次，进度推进到最新并入轮号")
    void aboveThresholdGeneratesAndAdvancesProgress() {
        MemoryStore store = new MemoryStore();
        StringBuilder seen = new StringBuilder();
        RollingSummaryService svc = service(store, archiveOf(turns(1, 6)),
                (prev, material) -> { seen.append(material); return "第 1-4 轮：合并摘要"; }, ON);

        assertTrue(svc.schedule(SESSION, 2));

        Stored saved = store.saved.get(SESSION);
        assertNotNull(saved);
        assertEquals("第 1-4 轮：合并摘要", saved.text());
        assertEquals(4L, saved.upToTurnId(), "窗口 2 轮，滑出的是 1..4");
        assertTrue(seen.toString().contains("第 1 轮：摘要1"));
        assertTrue(seen.toString().contains("第 4 轮：摘要4"));
        assertFalse(seen.toString().contains("摘要5"), "窗口内的轮次不喂给模型：" + seen);
    }

    // ==================== 进度（upTo）====================

    @Test
    @DisplayName("已进摘要的轮次不重复喂给模型；只剩不足阈值的缺口时干脆不调")
    void alreadySummarizedTurnsAreNotReFed() {
        MemoryStore store = new MemoryStore();
        store.saved.put(SESSION, new Stored("旧摘要", 3L));
        AtomicInteger calls = new AtomicInteger();
        RollingSummaryService svc = service(store, archiveOf(turns(1, 6)),
                (prev, material) -> { calls.incrementAndGet(); return "新"; }, ON);

        // 滑出窗口的是 1..4，但 1..3 已进摘要 → 只剩第 4 轮，低于阈值 2
        assertTrue(svc.schedule(SESSION, 2));
        assertEquals(0, calls.get(), "缺口只有 1 轮，不该调模型");
        assertEquals("旧摘要", store.saved.get(SESSION).text(), "没更新就保留旧摘要");
        assertEquals(3L, store.saved.get(SESSION).upToTurnId(), "进度不能倒退");
    }

    @Test
    @DisplayName("摘要器返回空：保留旧摘要与旧进度（宁可滞后，不要倒退或清空）")
    void blankSummaryKeepsPrevious() {
        MemoryStore store = new MemoryStore();
        store.saved.put(SESSION, new Stored("旧摘要", 1L));
        RollingSummaryService svc = service(store, archiveOf(turns(1, 6)),
                (prev, material) -> "   ", ON);

        assertTrue(svc.schedule(SESSION, 2));

        assertEquals("旧摘要", store.saved.get(SESSION).text());
        assertEquals(1L, store.saved.get(SESSION).upToTurnId());
    }

    @Test
    @DisplayName("摘要器返回带首尾空白：落盘时 trim，进度仍推进")
    void summaryIsTrimmed() {
        MemoryStore store = new MemoryStore();
        RollingSummaryService svc = service(store, archiveOf(turns(1, 6)),
                (prev, material) -> "\n  合并后的摘要  \n", ON);

        assertTrue(svc.schedule(SESSION, 2));
        assertEquals("合并后的摘要", store.saved.get(SESSION).text());
    }

    // ==================== 排除不可引用轮次 ====================

    @Test
    @DisplayName("开放轮 / 中止轮 / 无 digest 轮都不进素材（半截现场不是可引用内容）")
    void nonCitableTurnsExcludedFromMaterial() {
        MemoryStore store = new MemoryStore();
        List<TurnRecord> records = List.of(
                closed(1, "摘要1"),
                open(2),
                aborted(3),
                closed(4, "  "),          // 空 digest
                closed(5, "摘要5"));
        String[] material = new String[1];
        RollingSummaryService svc = service(store, archiveOf(records),
                (prev, m) -> { material[0] = m; return "合并"; },
                new RollingSummaryService.Options(true, 1, 20));

        // 窗口 0 → 所有轮都滑出窗口
        assertTrue(svc.schedule(SESSION, 0));

        assertNotNull(material[0]);
        assertEquals("第 1 轮：摘要1\n第 5 轮：摘要5", material[0],
                "只有可引用的闭轮进素材：" + material[0]);
        assertEquals(5L, store.saved.get(SESSION).upToTurnId(), "最新并入轮号是 5");
    }

    @Test
    @DisplayName("一次并入的上限：maxMaterialTurns 只影响喂给模型的条数，不影响进度推进")
    void materialCapLimitsWhatIsFed() {
        MemoryStore store = new MemoryStore();
        String[] material = new String[1];
        RollingSummaryService svc = service(store, archiveOf(turns(1, 10)),
                (prev, m) -> { material[0] = m; return "合并"; },
                new RollingSummaryService.Options(true, 1, 3));

        // 窗口 0、上限 3：归档侧只取尾部 3 条（8/9/10），skip = 3 - 0 = 3 → 全部是缺口
        assertTrue(svc.schedule(SESSION, 0));
        assertNotNull(material[0]);
        assertTrue(material[0].contains("第 10 轮"), "取的是最新的那批：" + material[0]);
        assertEquals(3, material[0].split("\n").length, "素材不超过上限 3 条：" + material[0]);
    }

    // ==================== 单飞 ====================

    @Test
    @DisplayName("单飞：同一会话同时只跑一个摘要任务，第二次触发被挡掉")
    void singleFlightPerSession() throws Exception {
        MemoryStore store = new MemoryStore();
        // 可变的活归档：第二次跑之前追加两轮，好让第二次确实有缺口可摘要
        List<TurnRecord> records = new ArrayList<>(turns(1, 6));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RollingSummaryService svc = new RollingSummaryService(store, archiveOf(records),
                    (prev, material) -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "合并";
                    },
                    ON, pool);

            assertTrue(svc.schedule(SESSION, 2), "第一次提交成功");
            assertTrue(entered.await(5, TimeUnit.SECONDS), "后台任务已进入摘要器");
            assertFalse(svc.schedule(SESSION, 2), "同一会话已有任务在跑，第二次必须被挡掉");

            // 追加两条新轮，让第二次真有缺口（否则进度已覆盖，第二次会空跑，测不到"有没有跑"）
            records.add(closed(7, "摘要7"));
            records.add(closed(8, "摘要8"));
            release.countDown();

            // 释放后单飞标记归还，可再次提交（提交成功即证明标记已还）
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean resubmitted = false;
            while (System.nanoTime() < deadline) {
                if (svc.schedule(SESSION, 2)) {
                    resubmitted = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(resubmitted, "上一个任务结束后单飞标记必须归还");

            long deadline2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline2 && calls.get() < 2) {
                Thread.sleep(20);
            }
            assertEquals(2, calls.get(), "两次真正进入摘要器");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("提交被拒：单飞标记必须归还（否则这个会话再也不会被摘要）")
    void rejectedSubmissionReleasesSingleFlight() {
        MemoryStore store = new MemoryStore();
        AtomicInteger submissions = new AtomicInteger();
        // 第一次提交被拒，之后正常执行：用来验证"被拒后标记已归还"
        Executor flaky = command -> {
            if (submissions.incrementAndGet() == 1) {
                throw new RejectedExecutionException("池已满");
            }
            command.run();
        };
        RollingSummaryService svc = new RollingSummaryService(store, archiveOf(turns(1, 6)),
                (p, m) -> "合并", ON, flaky);

        assertFalse(svc.schedule(SESSION, 2), "被拒时返回 false");
        assertTrue(svc.schedule(SESSION, 2), "被拒后标记必须归还，否则这里会被单飞挡掉");
        assertEquals(1, store.saveCalls, "第二次真正生成了一次摘要");
    }

    // ==================== load ====================

    @Test
    @DisplayName("load：读到就返回；存储抛异常时返回空（不能因为摘要不可用让请求失败）")
    void loadDegradesOnFailure() {
        MemoryStore store = new MemoryStore();
        store.saved.put(SESSION, new Stored("正文", 7L));
        RollingSummaryService svc = service(store, archiveOf(List.of()), (p, m) -> "x", ON);
        assertEquals("正文", svc.load(SESSION).orElseThrow().text());

        MemoryStore broken = new MemoryStore();
        broken.loadFailure = new IllegalStateException("Redis 挂了");
        RollingSummaryService svc2 = service(broken, archiveOf(List.of()), (p, m) -> "x", ON);
        assertEquals(Optional.empty(), svc2.load(SESSION));
    }

    // ==================== 素材渲染 ====================

    @Test
    @DisplayName("renderMaterial：一行一轮，带轮号（模型据此保留硬信息定位）")
    void renderMaterialFormat() {
        String material = RollingSummaryService.renderMaterial(List.of(
                closed(3, "问退款"), closed(4, "答已受理")));
        assertEquals("第 3 轮：问退款\n第 4 轮：答已受理", material);
        assertEquals("", RollingSummaryService.renderMaterial(List.of()));
    }

    @Test
    @DisplayName("Options：阈值下限为 1，null 退化为 DISABLED")
    void optionsBounds() {
        RollingSummaryService.Options o = new RollingSummaryService.Options(true, 0, 0);
        assertEquals(1, o.minNewTurns());
        assertEquals(1, o.maxMaterialTurns());

        MemoryStore store = new MemoryStore();
        RollingSummaryService svc = new RollingSummaryService(store, archiveOf(turns(1, 6)),
                (p, m) -> "x", null, Runnable::run);
        assertFalse(svc.schedule(SESSION, 2), "options 为空按 DISABLED 处理");
    }
}
