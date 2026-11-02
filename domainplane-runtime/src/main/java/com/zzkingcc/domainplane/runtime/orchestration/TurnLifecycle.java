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
import java.util.function.Supplier;
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
 * 一轮跑完之后（以及跑砸之后）的收尾与持久化：归档落档、倒排索引、装配清单、
 * 滚动摘要排期、附件续期、记忆回滚、检查点释放。
 *
 * <p>这一组方法的共同点是**全部以 {@code Quietly} 结尾**（或本身就该 quietly）——
 * 它们做的事情都不该让一轮对话失败：归档写不进去、摘要排不上、索引建不了，
 * 用户这一轮已经拿到回答了，为了"档案完整性"把回答也赔进去是本末倒置。
 * 所以搬出来的时候连"吞异常"这个行为一起搬，一行都没顺手改成"抛出去"。</p>
 *
 * <p>为什么它们此前粘在编排服务上：每个方法都要"会话键 → 归档 / 索引 / 装配清单"
 * 这几件设施，看起来像是主流程的一部分。但没有人调用主流程的任何状态
 * —— 它们只被主流程在几个固定点位上点一下。</p>
 *
 * @author zzkingcc
 */
@Slf4j
final class TurnLifecycle {

    private final ChatMemoryProvider chatMemoryProvider;
    private final ConversationArchive conversationArchive;
    private final RollingSummaryService rollingSummaryService;
    private final AssemblyReportStore assemblyReportStore;
    private final MediaAttachmentStore mediaAttachmentStore;
    private final BaseCheckpointSaver checkpointSaver;
    private final ContextAssembler contextAssembler;
    private final HistorySearch historySearch;
    /** 本轮用量快照；用量记录留在编排服务（agent 节点也要用），这里只借用一次取值 */
    private final Supplier<TokenUsage> usageOf;
    /** 异常 → 一句人话的根因；留在编排服务（失败矩阵那一半要用），这里只借用 */
    private final Function<Throwable, String> rootCauseMessage;

    TurnLifecycle(ChatMemoryProvider chatMemoryProvider, ConversationArchive conversationArchive,
                  RollingSummaryService rollingSummaryService, AssemblyReportStore assemblyReportStore,
                  MediaAttachmentStore mediaAttachmentStore, BaseCheckpointSaver checkpointSaver,
                  ContextAssembler contextAssembler, HistorySearch historySearch,
                  Supplier<TokenUsage> usageOf, Function<Throwable, String> rootCauseMessage) {
        this.chatMemoryProvider = chatMemoryProvider;
        this.conversationArchive = conversationArchive;
        this.rollingSummaryService = rollingSummaryService;
        this.assemblyReportStore = assemblyReportStore;
        this.mediaAttachmentStore = mediaAttachmentStore;
        this.checkpointSaver = checkpointSaver;
        this.contextAssembler = contextAssembler;
        this.historySearch = historySearch;
        this.usageOf = usageOf;
        this.rootCauseMessage = rootCauseMessage;
    }

    /**
     * 把本轮最终回复写入会话记忆，并闭合归档里的这一轮。
     *
     * @param sessionKey 会话状态键（域|sessionId）
     * @param turnId     本轮归档轮号；{@code <= 0} = 本轮没有开过轮
     *                   （resume 续跑的是"被审批中断的那一轮"，它的轮在那时就开了），
     *                   此时回档里找那条开放轮闭合
     */
    void rememberFinalAnswer(ChatMemory memory, MessagesState<ChatMessage> lastState,
                                     String sessionKey, long turnId) {
        if (memory == null || lastState == null) {
            log.warn("[Agent编排] 无可用状态,跳过记忆写入");
            return;
        }
        var finalMessages = lastState.messages();
        /* 本轮提问 = 状态里最后一条 UserMessage：它之后的 AiMessage /
           ToolExecutionResultMessage 都是本轮的产物。
           orchestrate 与 resume 两条路径都成立，resume 从检查点恢复出的状态同样含完整历史，
           所以闭轮不依赖"本轮有没有开过轮"这件事。 */
        int userIdx = -1;
        for (int i = finalMessages.size() - 1; i >= 0; i--) {
            if (finalMessages.get(i) instanceof UserMessage) {
                userIdx = i;
                break;
            }
        }
        for (int i = finalMessages.size() - 1; i >= 0; i--) {
            if (finalMessages.get(i) instanceof AiMessage ai && !ai.hasToolExecutionRequests()) {
                memory.add(ai);
                closeTurnQuietly(sessionKey, turnId, finalMessages, userIdx, i, ai);
                return;
            }
        }
        log.warn("[Agent编排] 未找到可写入记忆的最终回复");
    }

    /**
     * 闭合归档里的这一轮：抽取全流程 → 生成摘要 → 原位写回。
     *
     * <p>失败只告警。归档是档案不是请求链的一环：写不进去不该让这一轮
     * （已经拿到回答、已经写进记忆）对调用方表现为失败。</p>
     */
    void closeTurnQuietly(String sessionKey, long turnId, List<ChatMessage> messages,
                                  int userIdx, int finalIdx, AiMessage finalAnswer) {
        if (conversationArchive == null) {
            return;
        }
        String sessionId = SessionKeys.sessionIdOf(sessionKey);
        try {
            long id = turnId;
            if (id <= 0) {
                var open = conversationArchive.findOpenTurn(sessionKey);
                if (open.isEmpty()) {
                    log.warn("[Agent编排] 会话[{}] 归档里没有待闭合的开放轮，跳过闭轮", sessionId);
                    return;
                }
                id = open.get().turnId();
            }
            if (userIdx < 0) {
                log.warn("[Agent编排] 会话[{}] 图状态里找不到本轮提问，跳过闭轮", sessionId);
                return;
            }
            // 先取回开放轮再补全：用户输入与开始时间都以开轮时落的那条为准
            // （那份 text 不含平台追加的附件清单提示，进索引与摘要更干净），
            // 顺便确认这一轮确实存在，不校验就 LSET 会在数据被外部改动时写坏别人的轮
            TurnRecord existing = conversationArchive.turnAt(sessionKey, id).orElse(null);
            if (existing == null) {
                log.warn("[Agent编排] 会话[{}] 归档里没有 turnId={} 这一轮，跳过闭轮", sessionId, id);
                return;
            }
            TurnRecorder.FlowSlice slice = TurnRecorder.extractFlow(messages, userIdx + 1, finalIdx);
            String answer = finalAnswer.text();
            TurnRecord closed = new TurnRecord(
                    id, sessionKey, existing.startedAt(), Instant.now(),
                    existing.user(), slice.events(), answer, usageOf.get(),
                    new TurnFlags(slice.truncated(), false, false, null),
                    TurnRecorder.digestOf(existing.user() == null ? "" : existing.user().text(), answer));
            conversationArchive.closeTurn(sessionKey, id, closed);
            indexClosedTurnQuietly(sessionKey, closed);
            // 闭轮即续期（M4-R4）：这一轮从头到尾可能跑了几分钟甚至更久，
            // 续到完整 TTL，让"回答里提到的图"在会后依然取得到
            renewActiveAttachments(sessionKey);
            // 闭轮后按需摘要（M4-R1）：只提交任务，判断与调用都在后台做，
            // 闭轮路径不因此多等一次归档读
            scheduleRollingSummaryQuietly(sessionKey);
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档闭轮失败：{}", sessionId, e.getMessage());
        }
    }

    /**
     * 闭轮后触发一次滚动摘要（M4-R1）。
     *
     * <p>只做提交，不做判断：是否达标、要不要调模型都在后台线程里决定
     * （见 {@link RollingSummaryService#schedule}）。提交失败、未装配、未开启都只当没发生：
     * 摘要是可选增强，装配侧读不到就走 digest 拼接。</p>
     */
    void scheduleRollingSummaryQuietly(String sessionKey) {
        if (rollingSummaryService == null) {
            return;
        }
        try {
            rollingSummaryService.schedule(sessionKey, contextAssembler.windowTurns());
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 提交滚动摘要任务失败：{}",
                    SessionKeys.sessionIdOf(sessionKey), e.getMessage());
        }
    }

    /**
     * 附件续期（M4-R4 活动续期）：把"会话当前仍在引用"的附件存活期重置为完整 TTL。
     *
     * <p><b>续期集合</b> = 近况窗口内各轮 {@code UserInput.media} 里带引用名的附件。
     * 窗口就是装配层每轮真正喂给模型的那批轮次，能被自动装配/被模型直接指到的附件，
     * 就保活；滑出窗口的老附件不再续期，会话沉寂后按 TTL 自然过期。
     * 于是"归档永久"与"附件 30 分钟"这两个时间尺度之间有了过渡：
     * 附件活得比它所属的那一轮久，且活得与会话的活跃窗口一样久。</p>
     *
     * <p><b>不做全会话扫描</b>：把整个会话的附件都续期需要一个"会话 → 附件名"的登记表，
     * 那是一份新的 Redis 键空间，得跟着 R5 的删域级联一起扩，收益（把滑出窗口三个月前的
     * 图也一直保活）与代价（存储不回收、级联多一处）不成比例。这里只续"还在活跃引用"的那批。</p>
     *
     * <p>尽力而为：读窗口失败、续期失败都只告警。附件是派生资源，
     * 最坏结果是它按原 TTL 过期，那等同于"会话没活跃过"，不该让一轮对话失败。</p>
     */
    void renewActiveAttachments(String sessionKey) {
        if (mediaAttachmentStore == null || conversationArchive == null
                || sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        try {
            List<TurnRecord> recent =
                    conversationArchive.recentTurns(sessionKey, contextAssembler.windowTurns());
            for (TurnRecord t : recent) {
                collectAttachmentIds(t, ids);
            }
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 读近况窗口续期附件失败：{}",
                    SessionKeys.sessionIdOf(sessionKey), e.getMessage());
            return;
        }
        if (!ids.isEmpty()) {
            mediaAttachmentStore.touch(ids);
        }
    }

    static void collectAttachmentIds(TurnRecord record, Set<String> sink) {
        if (record == null || record.user() == null || record.user().media() == null) {
            return;
        }
        for (MediaRef media : record.user().media()) {
            if (media.hasAttachment()) {
                sink.add(media.attachmentId());
            }
        }
    }

    /**
     * 闭合轮写入倒排索引（M3）。失败只告警：索引是派生数据，
     * 归档已经落盘，这一轮只是不能被倒排列中，靠子串扫描兜底。
     */
    void indexClosedTurnQuietly(String sessionKey, TurnRecord closed) {
        if (historySearch == null) {
            return;
        }
        try {
            historySearch.indexTurn(sessionKey, closed);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] turnId={} 写检索索引失败，该轮靠子串扫描兜底：{}",
                    SessionKeys.sessionIdOf(sessionKey), closed.turnId(), e.getMessage());
        }
    }

    /**
     * 本轮装配清单落账（F04-a）：记下每段给了多少、砍了多少。
     *
     * <p>"它为什么忘了"只有两种可能：<b>没召回</b>（卡片段压根是空的）或
     * <b>召回了但被预算砍掉</b>（{@link ContextAssemblyReport#lostRecall()}）。
     * 此前这两者在装配完成后无法区分——请求体里只剩幸存者。本方法把分辨它们的账留下来。</p>
     *
     * <p>只告警：账本不是对话的一环，Redis 挂了也不该让这一轮失败。</p>
     */
    void recordAssemblyQuietly(String sessionKey, ContextAssemblyReport report) {
        if (assemblyReportStore == null || report == null) {
            return;
        }
        try {
            assemblyReportStore.save(sessionKey, report);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] turnId={} 装配清单落账失败：{}",
                    SessionKeys.sessionIdOf(sessionKey), report.turnId(), e.getMessage());
        }
    }

    /**
     * 审批被拒：闭合上一轮的开放轮（用户没回审批、直接开了新对话）。
     */
    void rejectOpenTurnQuietly(String sessionKey) {
        if (conversationArchive == null) {
            return;
        }
        try {
            var open = conversationArchive.findOpenTurn(sessionKey);
            if (open.isEmpty()) {
                return;
            }
            conversationArchive.rejectTurn(sessionKey, open.get().turnId());
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档标记审批被拒失败：{}", SessionKeys.sessionIdOf(sessionKey), e.getMessage());
        }
    }

    /**
     * 崩溃恢复：上一轮若仍是未闭合开放轮（进程崩在闭轮前 / 断��已过期），标 aborted 后闭合。
     */
    void recoverStaleOpenTurnQuietly(String sessionKey) {
        if (conversationArchive == null) {
            return;
        }
        try {
            int recovered = conversationArchive.recoverStaleOpenTurn(sessionKey);
            if (recovered > 0) {
                log.warn("[Agent编排] 会话[{}] 发现上一轮未闭合（进程中断），已标记中止",
                        SessionKeys.sessionIdOf(sessionKey));
            }
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档崩溃恢复失败：{}", SessionKeys.sessionIdOf(sessionKey), e.getMessage());
        }
    }

    void abortOpenTurnQuietly(String sessionKey, String reason, String partialAnswer) {
        if (conversationArchive == null) {
            return;
        }
        try {
            var open = conversationArchive.findOpenTurn(sessionKey);
            if (open.isEmpty()) {
                return;
            }
            conversationArchive.abortTurn(sessionKey, open.get().turnId(), reason, partialAnswer);
        } catch (Exception e) {
            log.error("[Agent编排] 会话[{}] 归档中止开放轮失败：{}", SessionKeys.sessionIdOf(sessionKey), e.getMessage());
        }
    }

    /**
     * 失败路径的归档收尾：删（图压根没起来）还是中止（跑了一半）。
     *
     * <p>这一条必须落在错误处理<b>之前</b>：错误处理会回滚记忆，
     * 而归档要保留的正是被回滚掉的那一轮，先判断、先落档，再交回给既有收尾逻辑。</p>
     */
    void finalizeTurnOnFailure(String sessionKey, long turnId, boolean graphStarted,
                                       Throwable e, StreamContext context) {
        if (conversationArchive == null) {
            return;
        }
        String partial = context == null ? null : context.partialOutput();
        if (!graphStarted && turnId > 0) {
            try {
                conversationArchive.deleteTurn(sessionKey, turnId);
                return;
            } catch (Exception ex) {
                log.error("[Agent编排] 会话[{}] 归档删除未启动轮失败：{}", SessionKeys.sessionIdOf(sessionKey),
                        ex.getMessage());
            }
        }
        abortOpenTurnQuietly(sessionKey, "本轮执行失败：" + rootCauseMessage.apply(e), partial);
    }

    /**
     * 把会话记忆回滚到"本轮提问之前"的那一刻。
     *
     * <p>M4-R5：不再持有内容快照，而是按"本轮写入前的条数"截掉本轮追加的那几条，
     * 原先要"读全量快照 + 失败时整体回写"，读写都是 O(全量)；现在一读长度、一剪尾部，
     * 与历史长度无关。语义上等价：记忆只增不减、且本轮只会往尾部追加，
     * 所以"截回原长度"与"还原成原内容"是同一件事。</p>
     *
     * @param memorySizeBefore 本轮写入前的条数；{@code null} 表示写入发生在测量之前（不可能），
     *                         或本次调用方本就不涉及回滚
     */
    void restoreMemory(String key, Integer memorySizeBefore) {
        String sessionId = SessionKeys.sessionIdOf(key);
        try {
            ChatMemory memory = chatMemoryProvider.get(key);
            if (!(memory instanceof DualConstraintChatMemory dual)) {
                log.warn("[Agent编排] 会话[{}] memory 非 DualConstraintChatMemory,无法回滚记忆", sessionId);
                return;
            }
            if (memorySizeBefore == null) {
                // 长度是在写提问之前量的，所以"没量过"= 本轮还没往记忆里写过任何东西 = 无需回滚。
                // 这里绝不能"删最后一条"或"清空"：那会把上一轮的对话内容一起抹掉。
                log.info("[Agent编排] 会话[{}] 无提问前长度（本轮未写入记忆），跳过记忆回滚", sessionId);
                return;
            }
            dual.truncateTo(memorySizeBefore);
            log.info("[Agent编排] 会话[{}] 记忆已回滚到本轮提问前（{} 条）", sessionId, memorySizeBefore);
        } catch (Exception ex) {
            log.error("[Agent编排] 会话[{}] 回滚记忆失败", sessionId, ex);
        }
    }

    void releaseCheckpointQuietly(String key, String reason) {
        String sessionId = SessionKeys.sessionIdOf(key);
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(key).build();
            checkpointSaver.release(config);
            log.debug("[Agent编排] 会话[{}] checkpoint 已清除({})", sessionId, reason);
        } catch (Exception ex) {
            log.warn("[Agent编排] 会话[{}] 清 checkpoint 失败({}): {}", sessionId, reason, ex.getMessage());
        }
    }
}
