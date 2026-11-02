package com.zzkingcc.domainplane.runtime.orchestration;

import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.config.Timeouts;
import com.zzkingcc.domainplane.runtime.conversation.ContextAssembler;
import com.zzkingcc.domainplane.runtime.conversation.RollingSummaryService;
import com.zzkingcc.domainplane.runtime.model.ModelResolver;
import com.zzkingcc.domainplane.runtime.prompt.SystemPromptResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolCallEchoResolver;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

import java.util.concurrent.Executor;

/**
 * 编排的全部装配依赖。
 *
 * <p>它存在的原因不是"少写几个参数"，而是<b>让"编排要哪些东西"成为一个可以被整体传递、
 * 可被逐个搬运的对象</b>。此前这里是一条 8 级构造器阶梯（最长 16 参、195 行机械代码），
 * 每加一个可选能力就多一级；更要紧的是它会把"拆不拆得动"这件事绑死在参数列表上 ——
 * 不先把装配收成一个对象就动手拆协作者，每个新类都要把 16 个参数再抄一遍。</p>
 *
 * <p>除 {@code toolRouter} 与 {@code promptResolver}（由主构造器强制非空）外，
 * 其余组件允许为 {@code null}：那是"未装配的部署"的既有语义
 * （见各字段注释：不归档 / 不摘要 / 不检索 / 不暂存，其余行为分毫不变）。</p>
 *
 * @param toolRouter       工具路由（按域过滤的唯一入口）；构造期只用它的统计与域清单，不能为空
 */
public record OrchestrationDeps(
        StreamingChatModel streamingChatModel,
        ChatMemoryProvider chatMemoryProvider,
        ToolRouter toolRouter,
        SystemPromptResolver promptResolver,
        BaseCheckpointSaver checkpointSaver,
        ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
        CancellationRegistry cancellationRegistry,
        Executor executor,
        ModelResolver modelResolver,
        ToolCallEchoResolver toolCallEchoResolver,
        MediaAttachmentStore mediaAttachmentStore,
        ConversationArchive conversationArchive,
        ContextAssembler contextAssembler,
        HistorySearch historySearch,
        RollingSummaryService rollingSummaryService,
        AssemblyReportStore assemblyReportStore,
        Timeouts timeouts,
        PlanningOptions planningOptions,
        PlanDisplayResolver planDisplayResolver) {

    /**
     * 兼容构造器：不关心规划前置能力的装配方走这里 —— 规划关闭、计划展示关闭，
     * 其余组件原样。历史调用点（各已废弃构造器与最小装配）不需要逐个补两个参数。
     */
    public OrchestrationDeps(StreamingChatModel streamingChatModel,
                             ChatMemoryProvider chatMemoryProvider,
                             ToolRouter toolRouter,
                             SystemPromptResolver promptResolver,
                             BaseCheckpointSaver checkpointSaver,
                             ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
                             CancellationRegistry cancellationRegistry,
                             Executor executor,
                             ModelResolver modelResolver,
                             ToolCallEchoResolver toolCallEchoResolver,
                             MediaAttachmentStore mediaAttachmentStore,
                             ConversationArchive conversationArchive,
                             ContextAssembler contextAssembler,
                             HistorySearch historySearch,
                             RollingSummaryService rollingSummaryService,
                             AssemblyReportStore assemblyReportStore,
                             Timeouts timeouts) {
        this(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                modelResolver, toolCallEchoResolver, mediaAttachmentStore, conversationArchive,
                contextAssembler, historySearch, rollingSummaryService, assemblyReportStore,
                timeouts, PlanningOptions.disabled(), null);
    }

    /**
     * 最小装配：只有八项必需组件，其余全部为 {@code null}。
     *
     * <p>编排类只在乎一个完整的 {@code OrchestrationDeps}，所以"缺的可选能力"表达为
     * 一个 {@code null} 而不是再开一级构造器重载。这样新增可选能力时不必再给编排类加签名，
     * "16 个参数传错位还编译得过"这种事故也就没有了。</p>
     */
    static OrchestrationDeps minimal(StreamingChatModel streamingChatModel,
                                     ChatMemoryProvider chatMemoryProvider,
                                     ToolRouter toolRouter,
                                     SystemPromptResolver promptResolver,
                                     BaseCheckpointSaver checkpointSaver,
                                     ObjectStreamStateSerializer<MessagesState<ChatMessage>> stateSerializer,
                                     CancellationRegistry cancellationRegistry,
                                     Executor executor) {
        return new OrchestrationDeps(streamingChatModel, chatMemoryProvider, toolRouter, promptResolver,
                checkpointSaver, stateSerializer, cancellationRegistry, executor,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * 超时取值的唯一出口：未配置（{@code null}）时给默认值。
     *
     * <p>判空放在这里而不是散在调用处，是为了让"{@code null} = 代码默认"这个约定只有一份。
     * 单元测试走 {@link #minimal} 时拿到的就是默认值 —— 测试要的是"行为不变"，
     * 而不是"每个测试都得先配一遍超时"。</p>
     */
    Timeouts timeoutsOrDefaults() {
        return timeouts == null ? Timeouts.defaults() : timeouts;
    }
}
