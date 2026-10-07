package com.zzkingcc.domainplane.server.settings;

import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.config.AiProperties;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型持有者 —— 支持运行时热替换
 *
 * 向量模型的来源有两条，按显式程度排序：
 *
 *   • 「模型设置」页里被选中为向量模型的那个档案（{@code ModelProfileSettings.embeddingAlias}）；
 *       向量模型全局只有一个，所有域共用；
 *   • 都没选时，回落到部署 yaml（{@code domainplane.ai.embedding.*}）—— 这是部署期兜底，
 *       不是界面上的第二套配置。
 *
 *
 * 关键：选中过档案、但该档案已不可用时，绝不静默回落到 yaml。回落会让用户
 * 以为"已经换成新模型了"，实际检索用的还是老向量 —— 表现是灌库成功、检索结果莫名其妙，
 * 且没有任何报错。这里宁可当成未配置，让调用点显式报错。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class LlmModelHolder {

    private final AiProperties yamlProps;
    private final LlmSettingsStore store;
    /** 向量模型档案注册表；为空（单测）时只看 yaml 兜底 */
    private final ModelProfileRegistry profileRegistry;

    /** 当前生效的设置（yaml 与界面配置合并后的结果） */
    private volatile LlmSettings settings;

    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingChatModel;
    private volatile EmbeddingModel embeddingModel;
    /** 实际生效的向量模型描述（档案别名或 "yaml"），仅供日志与排障 */
    private volatile String embeddingSource;

    /**
     * "尚未配置"的统一文案
     */
    private static final String NOT_CONFIGURED_CHAT =
            "对话模型尚未配置：请先在管控台「模型设置」页填写服务商地址与 API Key（保存后立即生效）";
    private static final String NOT_CONFIGURED_EMBEDDING =
            "向量模型尚未配置：请先在管控台「模型设置」页选一个向量模型（保存后立即生效）";

    /** 单元测试用的简化构造：无档案注册表，只看 yaml 兜底 */
    public LlmModelHolder(AiProperties yamlProps, LlmSettingsStore store) {
        this(yamlProps, store, null);
    }

    /**
     * Spring 注入入口 —— 必须显式标注 {@code @Autowired}。
     *
     * 本类有两个构造器，Spring 只在"唯一构造器"时才隐式选用；有多个且都没标注时，
     * 它会退回去找无参构造器并抛 {@code NoSuchMethodException: LlmModelHolder.<init>()}，
     * 表现为启动时 adminController → openAiEmbeddingModel 连锁创建失败。
     */
    @Autowired
    public LlmModelHolder(AiProperties yamlProps, LlmSettingsStore store,
                          ModelProfileRegistry profileRegistry) {
        this.yamlProps = yamlProps;
        this.store = store;
        this.profileRegistry = profileRegistry;
        reload();
    }

    /** 当前生效的向量模型来源：档案别名 / {@code yaml} / {@code null}（未配置） */
    public String embeddingSource() {
        return embeddingSource;
    }

    // ===== 对外暴露的"稳定代理" =====

    /** ChatModel 代理（类型与 Bean 名不变，内部跟随当前设置） */
    public ChatModel chatModel() {
        return new DelegatingChatModel(this);
    }

    /** StreamingChatModel 代理 */
    public StreamingChatModel streamingChatModel() {
        return new DelegatingStreamingChatModel(this);
    }

    /** EmbeddingModel 代理 */
    public EmbeddingModel embeddingModel() {
        return new DelegatingEmbeddingModel(this);
    }

    // ===== 内部：当前实例 =====

    ChatModel currentChat() {
        return chatModel;
    }

    StreamingChatModel currentStreaming() {
        return streamingChatModel;
    }

    EmbeddingModel currentEmbedding() {
        return embeddingModel;
    }

    /** 当前生效设置（只读副本） */
    public LlmSettings currentSettings() {
        return settings;
    }

    /** 模型是否已配置可用（灌库等操作的前置检查） */
    public boolean isConfigured() {
        return settings != null && settings.isUsable();
    }

    /** 向量能力是否可用 */
    public boolean isEmbeddingConfigured() {
        return embeddingModel != null;
    }

    /**
     * 当前应当用于 ES 索引的向量维度。
     *
     * 取自实际生效的那个向量模型：选中档案时用档案声明的维度（可为空 → 回落实测），
     * 用 yaml 兜底时同理。维度是索引 mapping 的不可变参数，两条来源不能各说各话。
     *
     * @throws NotConfiguredException 向量模型未配置
     */
    public int effectiveEmbeddingDimension() {
        ModelProfile selected = selectedEmbeddingProfile();
        if (selected != null) {
            if (selected.dimensions() != null) {
                return selected.dimensions();
            }
            EmbeddingModel m = embeddingModel;
            if (m == null) {
                throw new NotConfiguredException(
                        "向量模型档案 " + selected.alias() + " 未声明维度，且当前无法实测（模型未装配）");
            }
            return m.dimension();
        }
        LlmSettings s = settings;
        if (s != null && s.getEmbeddingDimensions() != null) {
            return s.getEmbeddingDimensions();
        }
        EmbeddingModel m = embeddingModel;
        if (m == null) {
            throw new NotConfiguredException("向量模型尚未配置，无法确定索引维度"
                    + "（请先在管控台「模型设置」页选一个向量模型）");
        }
        return m.dimension();
    }

    /**
     * 界面上被选中的向量档案；没选则返回 {@code null}。
     *
     * 与 {@code embeddingProfile()} 的区别：这里不做可用性过滤。
     * "选中过一个档案"这件事本身必须被看见 —— 哪怕它此刻已不可用，也要如实反映成
     * "选了但坏了"，而不是悄悄显示成"没配置"或回落 yaml。
     */
    private ModelProfile selectedEmbeddingProfile() {
        if (profileRegistry == null) {
            return null;
        }
        String alias = profileRegistry.embeddingAlias();
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return profileRegistry.profile(alias).orElse(null);
    }

    /**
     * 生效中的向量档案：只有已选中且此刻可用才算。
     *
     * 选中但不可用时返回 {@code null}，而 {@link #selectedEmbeddingProfile()} 仍非空 ——
     * 两者不一致正是"选了档案但它坏了"的判据，{@link #rebuild} 据此拒绝回落 yaml。
     */
    private ModelProfile usableEmbeddingProfile() {
        ModelProfile selected = selectedEmbeddingProfile();
        if (selected == null) {
            return null;
        }
        return selected.isEmbedding() && selected.isUsable() ? selected : null;
    }

    // ===== 装配 =====

    /**
     * 重新加载设置并重建模型。
     */
    public synchronized void reload() {
        LlmSettings merged = merge(store.load());
        this.settings = merged;
        rebuild(merged);
    }

    /** 保存设置并立即生效 */
    public synchronized void apply(LlmSettings incoming) {
        store.save(incoming);
        LlmSettings merged = merge(incoming);
        this.settings = merged;
        rebuild(merged);
    }

    /**
     * 向量档案被切换 / 删除后重新装配向量模型。
     *
     * 不复用 {@link #apply}：向量模型的落盘位置是 {@code models.json} 的 {@code embeddingAlias}，
     * 不在 {@code llm-settings.json} 里，走 apply 会把对话侧的配置无谓重写一遍。
     */
    public synchronized void refreshEmbedding() {
        rebuildEmbedding();
    }

    /**
     * 合并：管控台填写优先，缺失字段回落到 yaml。
     */
    private LlmSettings merge(LlmSettings fromUi) {
        AiProperties.Chat yChat = yamlProps.getChat();
        AiProperties.Embedding yEmb = yamlProps.getEmbedding();

        LlmSettings merged = new LlmSettings();
        merged.setChatBaseUrl(pick(fromUi.getChatBaseUrl(), yChat.getBaseUrl()));
        merged.setChatApiKey(pick(fromUi.getChatApiKey(), yChat.getApiKey()));
        merged.setChatModelName(pick(fromUi.getChatModelName(), yChat.getModelName()));
        merged.setChatTemperature(fromUi.getChatTemperature() != null
                ? fromUi.getChatTemperature() : yChat.getTemperature());
        merged.setChatMaxTokens(fromUi.getChatMaxTokens() != null
                ? fromUi.getChatMaxTokens() : yChat.getMaxTokens());
        merged.setChatCapabilities(copy(fromUi.getChatCapabilities()));

        merged.setEmbeddingBaseUrl(pick(fromUi.getEmbeddingBaseUrl(), yEmb.getBaseUrl()));
        merged.setEmbeddingApiKey(pick(fromUi.getEmbeddingApiKey(), yEmb.getApiKey()));
        merged.setEmbeddingModelName(pick(fromUi.getEmbeddingModelName(), yEmb.getModelName()));
        merged.setEmbeddingDimensions(fromUi.getEmbeddingDimensions() != null
                ? fromUi.getEmbeddingDimensions() : yEmb.getDimensions());
        merged.setEmbeddingCapabilities(copy(fromUi.getEmbeddingCapabilities()));
        return merged;
    }

    /** 能力声明不是"可回落 yaml"的部署参数，原样带过去即可（空表也要带上，别留 null） */
    private static java.util.List<String> copy(java.util.List<String> list) {
        return list == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(list);
    }

    private static String pick(String primary, String fallback) {
        return (primary != null && !primary.isBlank()) ? primary : fallback;
    }

    /** 按当前设置重建三个模型实例 */
    private void rebuild(LlmSettings s) {
        if (!s.isUsable()) {
            // 不抛异常：未配置是合法状态（首次部署、还没配服务商）——按项目日志原则够不上 WARN，
            // 只记 INFO 陈述状态；真正的失败在调用点由委派代理抛 NotConfiguredException。
            // 注意此处只清对话侧：向量模型可以配在另一家服务商上，与对话模型是否可用无关。
            log.info("[模型设置] 对话模型未配置（缺 baseUrl / apiKey / modelName），"
                    + "到管控台 http://localhost:9527/admin.html 的「模型设置」页填写后立即生效");
            this.chatModel = null;
            this.streamingChatModel = null;
            rebuildEmbedding();
            return;
        }

        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(s.getChatBaseUrl())
                .apiKey(s.getChatApiKey())
                .modelName(s.getChatModelName())
                .temperature(s.getChatTemperature())
                .maxTokens(s.getChatMaxTokens())
                .build();

        // 流式与非流式共用同一套服务商配置：同一个 Key 同时具备两种能力是常态，
        // 拆成两套只会让用户在管控台多填一遍
        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(s.getChatBaseUrl())
                .apiKey(s.getChatApiKey())
                .modelName(s.getChatModelName())
                .temperature(s.getChatTemperature())
                .maxTokens(s.getChatMaxTokens())
                .build();

        log.info("[模型设置] 已生效：对话模型 baseUrl={} model={}", s.getChatBaseUrl(), s.getChatModelName());

        rebuildEmbedding();
    }

    /**
     * 按「选中的向量档案 → yaml 兜底」的顺序重建向量模型。
     *
     * 刻意不复用对话模型的就绪判据：向量模型现在完全独立于对话模型，
     * 对话模型没配不该把向量也判成未配置（两者可以是不同服务商、不同模型）。
     */
    private void rebuildEmbedding() {
        ModelProfile selected = selectedEmbeddingProfile();
        if (selected != null) {
            ModelProfile usable = usableEmbeddingProfile();
            if (usable == null) {
                // 选了档案但它不能用：拒绝回落 yaml，宁可未配置也不要静默用错的向量
                this.embeddingModel = null;
                this.embeddingSource = null;
                log.error("[模型设置] 已选中的向量模型档案 {} 当前不可用（模型名={}，embedding 端点={}），"
                                + "向量检索与灌库已停用。不会回落到 yaml 兜底 —— 否则会在你不知情时用上别的向量。"
                                + "请到「模型设置」页重新选用一个向量模型。",
                        selected.alias(), selected.modelName(), selected.isEmbedding());
                return;
            }
            var builder = OpenAiEmbeddingModel.builder()
                    .baseUrl(usable.baseUrl())
                    .apiKey(usable.apiKey())
                    .modelName(usable.modelName());
            if (usable.dimensions() != null) {
                builder.dimensions(usable.dimensions());
            }
            this.embeddingModel = builder.build();
            this.embeddingSource = usable.alias();
            log.info("[模型设置] 已生效：向量模型来自档案 {}（model={}，baseUrl={}，dimensions={}）"
                            + "—— 全域共享，不参与域绑定",
                    usable.alias(), usable.modelName(), usable.baseUrl(),
                    usable.dimensions() == null ? "(未声明，用模型默认)" : usable.dimensions());
            return;
        }

        // 没选档案 → 回落部署 yaml
        LlmSettings s = settings;
        if (s != null && s.isEmbeddingUsable()) {
            // 维度是一项"契约"：填了就随请求下发，让服务商按该维度返回；
            // 留空则不下发，用服务商默认维度（此时 ES 索引维度取实测值）
            var embBuilder = OpenAiEmbeddingModel.builder()
                    .baseUrl(s.effectiveEmbeddingBaseUrl())
                    .apiKey(s.effectiveEmbeddingApiKey())
                    .modelName(s.getEmbeddingModelName());
            if (s.getEmbeddingDimensions() != null) {
                embBuilder.dimensions(s.getEmbeddingDimensions());
            }
            this.embeddingModel = embBuilder.build();
            this.embeddingSource = "yaml";
            log.info("[模型设置] 向量模型未选用档案，回落到部署配置 baseUrl={} model={} dimensions={}",
                    s.effectiveEmbeddingBaseUrl(), s.getEmbeddingModelName(),
                    s.getEmbeddingDimensions() == null ? "(未指定，用服务商默认)" : s.getEmbeddingDimensions());
        } else {
            this.embeddingModel = null;
            this.embeddingSource = null;
            log.info("[模型设置] 向量模型未配置（既未选用档案，部署配置也不完整），"
                    + "知识库检索与灌库暂不可用（到管控台「模型设置」页选一个向量模型后立即生效）");
        }
    }

    // ===== 三个委派代理 =====
    // 只覆盖真正干活的 doXxx 与元信息方法，其余方法走接口 default 实现，
    // 由 default 实现回调到 doXxx —— 因此无需逐个人工转发。

    /**
     * 把携带通用 {@link ChatRequestParameters} 的请求提升为 {@link OpenAiChatRequestParameters}。
     *
     * 提升必须以模型的默认参数为基线：OpenAI 模型在 doChat 里只认 request.parameters()，
     * 不兜自己的默认值；凭空新建一份参数会把 modelName 丢掉。
     */
    // package-private：保留给同包单元测试直接校验"通用参数 → OpenAI 参数"的提升逻辑
    ChatRequest promote(ChatRequest request) {
        ChatRequestParameters p = request.parameters();
        if (p instanceof OpenAiChatRequestParameters) {
            return request;
        }
        OpenAiChatRequestParameters defaults = openAiDefaults();
        return ChatRequest.builder()
                .messages(request.messages())
                .parameters(p == null ? defaults : defaults.overrideWith(p))
                .build();
    }

    /** 当前模型的默认请求参数（modelName 等必填项在上面）；未配置时给空壳，调用点已在 target() 先失败 */
    private OpenAiChatRequestParameters openAiDefaults() {
        ChatRequestParameters defaults = chatModel != null ? chatModel.defaultRequestParameters() : null;
        if (defaults == null && streamingChatModel != null) {
            defaults = streamingChatModel.defaultRequestParameters();
        }
        return defaults instanceof OpenAiChatRequestParameters openAi ? openAi : OpenAiChatRequestParameters.EMPTY;
    }

    /** 委派 ChatModel */
    private static final class DelegatingChatModel implements ChatModel {
        private final LlmModelHolder holder;

        DelegatingChatModel(LlmModelHolder holder) {
            this.holder = holder;
        }

        private ChatModel target() {
            ChatModel m = holder.currentChat();
            if (m == null) {
                throw new NotConfiguredException(NOT_CONFIGURED_CHAT);
            }
            return m;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return target().doChat(holder.promote(request));
        }

        // 默认参数属元信息、不是干活方法，接口 default 给的是空壳；不转发过去，
        // 上层 chat(request) 合并出的参数就没有 modelName
        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return target().defaultRequestParameters();
        }

        @Override
        public ModelProvider provider() {
            return target().provider();
        }
    }

    /** 委派 StreamingChatModel */
    private static final class DelegatingStreamingChatModel implements StreamingChatModel {
        private final LlmModelHolder holder;

        DelegatingStreamingChatModel(LlmModelHolder holder) {
            this.holder = holder;
        }

        private StreamingChatModel target() {
            StreamingChatModel m = holder.currentStreaming();
            if (m == null) {
                throw new NotConfiguredException(NOT_CONFIGURED_CHAT);
            }
            return m;
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            target().doChat(holder.promote(request), handler);
        }

        // 同 DelegatingChatModel：不转发的后果是请求体缺 modelName
        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return target().defaultRequestParameters();
        }

        @Override
        public ModelProvider provider() {
            return target().provider();
        }
    }

    /** 委派 EmbeddingModel */
    private static final class DelegatingEmbeddingModel implements EmbeddingModel {
        private final LlmModelHolder holder;

        DelegatingEmbeddingModel(LlmModelHolder holder) {
            this.holder = holder;
        }

        private EmbeddingModel target() {
            EmbeddingModel m = holder.currentEmbedding();
            if (m == null) {
                throw new NotConfiguredException(NOT_CONFIGURED_EMBEDDING);
            }
            return m;
        }

        @Override
        public EmbeddingResponse doEmbed(EmbeddingRequest request) {
            return target().doEmbed(request);
        }

        @Override
        public int dimension() {
            return target().dimension();
        }

        @Override
        public String modelName() {
            return target().modelName();
        }

        @Override
        public ModelProvider provider() {
            return target().provider();
        }

        // 以下两个方法在接口里是 default，但它们不会回调 doEmbed，
        // 而是各自有独立实现，因此必须显式委派，否则会返回默认/空值
        @Override
        public Response<Embedding> embed(String text) {
            return target().embed(text);
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return target().embed(textSegment);
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return target().embedAll(textSegments);
        }
    }

    /** 供调试输出当前用的是哪个模型的实现类 */
    public String describeCurrent() {
        return streamingChatModel == null ? "未配置" : streamingChatModel.getClass().getSimpleName();
    }

    /**
     * 用"候选设置"临时探测连通性，不影响当前生效配置。
     *
     * @return 模型的实际回复（截断），失败时抛异常由调用方转成提示
     */
    public String testChatConnection(LlmSettings candidate) {
        LlmSettings probe = new LlmSettings();
        probe.setChatBaseUrl(candidate.getChatBaseUrl());
        probe.setChatApiKey(resolveKey(candidate.getChatApiKey(),
                settings == null ? null : settings.getChatApiKey()));
        probe.setChatModelName(candidate.getChatModelName());
        probe.setChatTemperature(candidate.getChatTemperature());
        // 探测只验证"能不能通"，限制 token 数避免浪费额度
        probe.setChatMaxTokens(16);

        if (!probe.isUsable()) {
            throw new IllegalStateException("请先填写服务商地址、API Key 与模型名");
        }

        ChatModel probeModel = OpenAiChatModel.builder()
                .baseUrl(probe.getChatBaseUrl())
                .apiKey(probe.getChatApiKey())
                .modelName(probe.getChatModelName())
                .temperature(probe.getChatTemperature())
                .maxTokens(probe.getChatMaxTokens())
                .build();

        log.info("[模型设置] 连通性测试: baseUrl={} model={}", probe.getChatBaseUrl(), probe.getChatModelName());
        String reply = probeModel.chat("请只回复两个字：正常");
        return reply == null ? "(空回复)" : reply.trim();
    }

    /**
     * 用"候选设置"探测向量模型连通性，并裁决维度契约。
     *
     * @return 实测维度与声明维度的对照结果，由调用方决定如何呈现
     * @throws IllegalStateException 必填项缺失、维度非法，或上游连接/鉴权失败
     */
    public EmbeddingProbe testEmbeddingConnection(LlmSettings candidate) {
        String baseUrl = hasText(candidate.getEmbeddingBaseUrl())
                ? candidate.getEmbeddingBaseUrl() : candidate.getChatBaseUrl();
        String apiKey = resolveKey(candidate.getEmbeddingApiKey(),
                settings == null ? null : settings.effectiveEmbeddingApiKey());
        String modelName = candidate.getEmbeddingModelName();

        if (!hasText(baseUrl) || !hasText(apiKey) || !hasText(modelName)) {
            throw new IllegalStateException("请先填写向量模型的地址、API Key 与模型名");
        }

        Integer declared = candidate.getEmbeddingDimensions();
        if (declared != null && declared <= 0) {
            throw new IllegalStateException("向量维度需为正整数");
        }

        var probeBuilder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName);
        if (declared != null) {
            probeBuilder.dimensions(declared);
        }
        EmbeddingModel probe = probeBuilder.build();

        log.info("[模型设置] 向量连通性测试: baseUrl={} model={} 声明维度={}", baseUrl, modelName, declared);
        Response<Embedding> resp = probe.embed("连通性测试");
        // 只认返回向量的实际长度——这是"维度是否被采纳"的唯一可信证据
        return new EmbeddingProbe(resp.content().dimension(), declared);
    }

    /**
     * 向量连通性测试结果。
     *
     * @param actualDimension   上游实际返回的向量长度（唯一可信判据）
     * @param declaredDimension 用户声明的维度；未声明（留空）为 {@code null}
     */
    public record EmbeddingProbe(int actualDimension, Integer declaredDimension) {

        /**
         * 声明了维度、但上游没按该维度返回（参数被静默忽略）。
         */
        public boolean mismatched() {
            return declaredDimension != null && declaredDimension != actualDimension;
        }
    }

    /** 候选 Key 为空时回落到已保存的 Key（管控台不回填明文，避免用户被要求重填） */
    private static String resolveKey(String candidate, String current) {
        return hasText(candidate) ? candidate : current;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
