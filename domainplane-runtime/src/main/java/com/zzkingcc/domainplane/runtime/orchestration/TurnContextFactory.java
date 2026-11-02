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
 * 本轮喂给模型的上下文怎么拼 —— 系统提示词、近况窗口、早期摘要段、相关轮卡片段，
 * 以及"这轮到底给多少预算"的那一次收窄。
 *
 * <p>它是 F07 四步拆分切出来的协作者，从编排服务整块搬来，行为一行未改。
 * 边界是刻意的：<b>只做装配胶水</b>，{@link ContextAssembler} 自己的纯函数
 * （{@code assembleWithReport} / {@code summaryWithRolling} / {@code cardsOf}）一个都没动 ——
 * 那些是 F09 的地盘，改了就把两块的边界搅在一起。</p>
 *
 * <p>搬出来的理由：这五个方法只依赖"档案解析器 / 提示词解析器 / 归档 / 摘要 / 检索"
 * 这几件可空退化的设施，与会话状态、检查点、图节点全无关系。
 * 它们之所以看起来粘在编排服务上，仅仅是因为"本轮上下文"这四个字听上去像主流程。</p>
 *
 * @author zzkingcc
 */
@Slf4j
final class TurnContextFactory {

    private final SystemPromptResolver promptResolver;
    private final ContextAssembler contextAssembler;
    private final ModelResolver modelResolver;
    private final ConversationArchive conversationArchive;
    private final RollingSummaryService rollingSummaryService;
    private final HistorySearch historySearch;

    TurnContextFactory(SystemPromptResolver promptResolver, ContextAssembler contextAssembler,
                       ModelResolver modelResolver, ConversationArchive conversationArchive,
                       RollingSummaryService rollingSummaryService, HistorySearch historySearch) {
        this.promptResolver = promptResolver;
        this.contextAssembler = contextAssembler;
        this.modelResolver = modelResolver;
        this.conversationArchive = conversationArchive;
        this.rollingSummaryService = rollingSummaryService;
        this.historySearch = historySearch;
    }

    /**
     * 本轮真正用来装配上下文的 {@link ContextAssembler}。
     *
     * <p>装配实例是启动期按配置造好的单例（预算与模型无关），而模型是<b>按域、按请求</b>
     * 解析的，所以"这轮给模型看多少"必须在此刻重算：取
     * {@code min(配置预算, 模型声明的上下文窗口 - 输出预留 - 安全边际)}。具体算法见
     * {@link ContextBudget}（只封顶、不放大）。</p>
     *
     * <p>刻意不让上限解析失败影响本轮：它只是"能不能更保守"的锦上添花，而
     * "域没绑模型"这件事会在 {@code agentNode} 里由 {@code resolveModel} 如实抛出。
     * 在这里因为取不到上限而失败，会把一个本该成功的轮次变成一个原因可疑的失败。</p>
     *
     * @return 收窄后的装配器；未声明窗口 / 未装解析器 / 解析异常时返回原单例
     */
    ContextAssembler assemblerFor(String sessionId, String domainId) {
        if (modelResolver == null) {
            return contextAssembler;
        }
        ModelLimits limits;
        try {
            limits = modelResolver.modelLimits(domainId).orElse(null);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 域 {} 读取模型上限失败，装配预算维持配置值 {}：{}",
                    sessionId, domainId, contextAssembler.totalBudget(), e.getMessage());
            return contextAssembler;
        }
        if (limits == null) {
            return contextAssembler;
        }
        int budget = ContextBudget.effective(contextAssembler.totalBudget(),
                limits.contextWindow(), limits.maxOutputTokens());
        if (budget >= contextAssembler.totalBudget()) {
            return contextAssembler;
        }
        log.info("[Agent编排] 会话[{}] 域 {} 模型声明的上下文窗口 {}（输出预留 {}）小于装配预算 {}，"
                        + "本轮收紧为 {}",
                sessionId, domainId, limits.contextWindow(), limits.maxOutputTokens(),
                contextAssembler.totalBudget(), budget);
        return contextAssembler.withTotalBudget(budget);
    }

    /**
     * 系统提示词按本轮所处的域解析：公共基线 + 域差异。
     *
     * <p>执行单元内冻结、单元之间用最新，resume 不重跑本方法（它从检查点恢复），
     * 因此这里读到的就是本次执行单元开始时的提示词，天然满足该语义。</p>
     */
    SystemMessage systemMessageOf(String domainId) {
        String text = promptResolver.resolve(domainId);
        return text == null || text.isBlank() ? null : SystemMessage.from(text);
    }

    /**
     * 近况窗口：取最近若干轮的<b>原文</b>。
     *
     * <p>取记忆而不是取归档：归档里媒体只剩引用（不存字节），而窗口里的图片
     * 必须是真内容块，否则模型看不到图。这也正是记忆继续存在的原因：
     * 它是"带内容块近况窗口"的来源，归档是"永久档案"。</p>
     *
     * <p>M4-R5 起只读尾部：窗口要的就是最后几条，让存储侧 {@code LRANGE key -N -1}
     * 一步到位，不必先把整段历史拉回来再 {@code subList}。存储不支持尾部读时
     * （单测的 Map 实现）{@link DualConstraintChatMemory#tail(int)} 内部退回全量取尾，
     * 行为不变。</p>
     */
    List<ChatMessage> windowOf(ChatMemory memory) {
        int limit = MESSAGES_PER_WINDOW_TURN * contextAssembler.windowTurns();
        if (limit <= 0) {
            return List.of();
        }
        if (memory instanceof DualConstraintChatMemory dual) {
            return dual.tail(limit);
        }
        // 非双约束记忆（异常装配）：退回全量读后取尾，行为与改造前一致
        List<ChatMessage> all = memory.messages();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int keep = Math.min(all.size(), limit);
        return List.copyOf(all.subList(all.size() - keep, all.size()));
    }

    /**
     * 当前记忆条数（回滚要记的"写入前长度"）。
     *
     * <p>双约束记忆走存储侧的 O(1) 长度；其他实现只能读全量数一遍，
     * 那是异常装配的兜底路径，行为与改造前一致。</p>
     */
    static int memorySize(ChatMemory memory) {
        if (memory instanceof DualConstraintChatMemory dual) {
            return dual.size();
        }
        List<ChatMessage> all = memory.messages();
        return all == null ? 0 : all.size();
    }

    /** 一轮在记忆里占几条消息（提问 + 回答） */
    static final int MESSAGES_PER_WINDOW_TURN = 2;

    /**
     * 早期摘要段：滑出窗口的老轮次。
     *
     * <p>M4-R1 起两段合一：<b>滚动摘要（模型生成，覆盖到某轮号）+ 该轮号之后又滑出窗口的
     * 那几轮 digest</b>。滚动摘要是异步生成、过了阈值才更新的，天生滞后，光有它，
     * "摘要之后新滑出的那几轮"会既不在窗口里也不在摘要里，凭空消失。所以缺口用现成
     * digest 补齐。没有摘要时退化成纯 digest 拼接（M2 行为），两者都没有则返回 {@code null}。</p>
     *
     * <p>读档失败只告警并返回 {@code null}：摘要不是必需段，卡片和窗口都不依赖它。</p>
     */
    String buildSummarySegment(String sessionKey, int windowTurns) {
        if (conversationArchive == null) {
            return null;
        }
        try {
            List<TurnRecord> tail = conversationArchive.recentTurns(sessionKey,
                    windowTurns + ContextAssembler.SUMMARY_MAX_TURNS);
            if (rollingSummaryService == null) {
                return ContextAssembler.summaryOf(tail, windowTurns);
            }
            var rolling = rollingSummaryService.load(sessionKey).orElse(null);
            String text = rolling == null ? null : rolling.text();
            long upTo = rolling == null ? 0L : rolling.upToTurnId();
            return ContextAssembler.summaryWithRolling(text, tail, windowTurns, upTo);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 读取归档用于摘要失败，本轮不带摘要段：{}",
                    SessionKeys.sessionIdOf(sessionKey), e.getMessage());
            return null;
        }
    }

    /**
     * 相关轮卡片段：按本轮提问检索倒排索引（M3）。
     *
     * <p>检索不到（索引没建 / 老数据 / 查询太短）时退化成"窗口之前最近的老轮次"，
     * 与 M2 的行为一致，卡片段可以空，但不能因为检索不达就整段消失。</p>
     *
     * <p>与近况窗口去重：命中轮若在窗口内（turnId &gt; 当前轮 - W）不进卡片段，
     * 同一轮内容不在一轮请求里出现两次（方案 §5.5）。</p>
     */
    List<HistoryCard> buildCardsSegment(String sessionKey, int windowTurns,
                                                String query, long currentTurnId) {
        int k = contextAssembler.maxCards();
        if (k <= 0) {
            return List.of();
        }
        long windowFloor = currentTurnId > 0 ? currentTurnId - windowTurns : 0;
        // 当前轮号未知（<=0）时不能走检索路径：判重条件是"轮号 <= windowFloor"，
        // 没有当前轮就无从算出窗口下界，写成 MAX_VALUE 会让条件恒真，卡片段与近况窗口
        // 重复输出同一轮内容。此时退化到下面的按位置切窗路径，它不依赖轮号。
        if (currentTurnId > 0 && historySearch != null && query != null && !query.isBlank()) {
            try {
                List<HistoryCard> cards = historySearch.search(sessionKey, query, k + windowTurns);
                List<HistoryCard> deduped = new ArrayList<>();
                for (HistoryCard card : cards) {
                    if (card.turnId() <= windowFloor && deduped.size() < k) {
                        deduped.add(card);
                    }
                }
                if (!deduped.isEmpty()) {
                    return deduped;
                }
            } catch (Exception e) {
                log.warn("[Agent编排] 会话[{}] 倒排检索失败，卡片段退化为最近老轮次：{}",
                        SessionKeys.sessionIdOf(sessionKey), e.getMessage());
            }
        }
        if (conversationArchive == null) {
            return List.of();
        }
        try {
            List<TurnRecord> tail = conversationArchive.recentTurns(sessionKey,
                    windowTurns + k + 1);
            List<TurnRecord> older = new ArrayList<>(tail);
            int skip = Math.max(0, older.size() - windowTurns);
            List<TurnRecord> candidates = older.subList(0, skip);
            // 相关度从高到低：没有检索排序时，"越老越优先"会让最旧的一直占着卡片位，
            // 改成倒序取最近的老轮次，与"模型更可能追问刚才那件事"的直觉一致
            List<TurnRecord> ordered = new ArrayList<>();
            for (int i = candidates.size() - 1; i >= 0 && ordered.size() < k; i--) {
                ordered.add(candidates.get(i));
            }
            return ContextAssembler.cardsOf(ordered);
        } catch (Exception e) {
            log.warn("[Agent编排] 会话[{}] 读取归档用于相关轮卡片失败，本轮不带卡片段：{}",
                    SessionKeys.sessionIdOf(sessionKey), e.getMessage());
            return List.of();
        }
    }
}
