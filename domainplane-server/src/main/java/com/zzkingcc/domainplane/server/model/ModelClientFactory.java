package com.zzkingcc.domainplane.server.model;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按档案构建模型客户端，并按指纹缓存。
 *
 * 缓存设计的两个要点：
 *
 *   • 键是档案指纹（含 Key 哈希），改了 Key 或端点，指纹变，
 *     自然拿到新实例，不需要手工清缓存；
 *   • 每个别名只保留当前指纹那一个实例：旧实例在下次取用时被清掉，
 *     避免反复改配置堆实例。
 *
 * 连接基底按档案上的 {@code protocol} 分派两种：
 *
 *   • {@code openai}（默认）—— OpenAI 兼容端点 {@code /chat/completions}，
 *     绝大多数服务商与自建端点（vLLM / Ollama / 各类中转）都走这一条；
 *   • {@code anthropic} —— Anthropic 原生端点 {@code /v1/messages}，
 *     协议与鉴权头均与 OpenAI 不同，<b>不能用 OpenAI 兼容适配器代劳</b>。
 *
 * 分派只在这两个构建方法里发生一次：参数施加（标准参数 / 私有参数 / 思考回写）
 * 两种 builder 的 setter 同名同义，故完全共用 —— 新增参数只写一遍，不会两侧漂移。
 * 服务商差异由对方暴露的 OpenAI 兼容入口吸收，不按服务商开分支。
 *
 * 服务商适配（{@link ModelAdapterRegistry}）落在两个地方：
 *
 *   • 标准参数（temperature / maxTokens）—— 档案老字段 → builder setter；
 *   • 私有参数（如 {@code enable_thinking}）—— 档案 {@code params} → builder
 *     {@code customParameters}（langchain4j 1.18.1 起支持，与标准字段并列合并进请求体），
 *     适配 schema 里声明了 {@code default} 而用户没填的，在此补缺。
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelClientFactory {

    private final ModelAdapterRegistry adapterRegistry;

    public ModelClientFactory(ModelAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    /** 别名 → （指纹 → 实例） */
    private final Map<String, Map<String, StreamingChatModel>> streamingByAlias = new ConcurrentHashMap<>();

    private final Map<String, Map<String, ChatModel>> chatByAlias = new ConcurrentHashMap<>();

    /**
     * 取（或构建）该档案的流式对话模型。
     */
    public StreamingChatModel streamingChat(ModelProfile profile) {
        String fingerprint = profile.fingerprint();
        Map<String, StreamingChatModel> versions =
                streamingByAlias.computeIfAbsent(profile.alias(), alias -> new ConcurrentHashMap<>());

        // 用 computeIfAbsent 而不是"先 get、没有就 build、再 evict+put"：
        // 后者在冷启动的并发首请求下会各建一个实例，随后的 evict 互相把对方清掉，
        // 被清掉的那个连接池没有人关，每次并发都是一份孤儿连接。
        // 驱逐留在 compute 之外做：不能在 compute 的回调里改同一个 map。
        StreamingChatModel built = versions.computeIfAbsent(fingerprint, fp -> {
            StreamingChatModel created = buildStreaming(profile);
            log.info("[模型档案] 构建流式客户端：alias={} model={} baseUrl={}（指纹 {}）",
                    profile.alias(), profile.modelName(), profile.baseUrl(), fp);
            return created;
        });
        evictOthers(versions, fingerprint);
        return built;
    }

    /**
     * 取（或构建）该档案的非流式对话模型（用于连通性测试等短调用）。
     */
    public ChatModel chat(ModelProfile profile) {
        String fingerprint = profile.fingerprint();
        Map<String, ChatModel> versions =
                chatByAlias.computeIfAbsent(profile.alias(), alias -> new ConcurrentHashMap<>());

        ChatModel built = versions.computeIfAbsent(fingerprint, fp -> buildChat(profile));
        evictOthers(versions, fingerprint);
        return built;
    }

    /** 只保留当前指纹，其余清掉（旧实例交给 GC；无长连接需要显式关闭） */
    private static <T> void evictOthers(Map<String, T> versions, String keepFingerprint) {
        versions.keySet().removeIf(fp -> !fp.equals(keepFingerprint));
    }

    // ==================== 构建 ====================

    private StreamingChatModel buildStreaming(ModelProfile profile) {
        /* 按协议分派：Anthropic 的端点（/v1/messages）与鉴权头（x-api-key）都与 OpenAI 不同，
           不能用 OpenAI 兼容适配器代劳 —— 走兼容层的表现是请求发出去了、对方回 401，
           而日志上只看到"鉴权失败"，看不出是协议选错。 */
        if (profile.isAnthropic()) {
            var b = AnthropicStreamingChatModel.builder()
                    .baseUrl(profile.baseUrl())
                    .apiKey(profile.apiKey())
                    .modelName(profile.modelName());
            applyTemperature(b::temperature, profile);
            applyMaxTokens(b::maxTokens, profile);
            applyReturnThinking(b::returnThinking, profile);
            applyCustomParameters(b::customParameters, profile);
            return b.build();
        }
        var b = OpenAiStreamingChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.modelName());
        applyTemperature(b::temperature, profile);
        applyMaxTokens(b::maxTokens, profile);
        applyReturnThinking(b::returnThinking, profile);
        applyCustomParameters(b::customParameters, profile);
        return b.build();
    }

    private ChatModel buildChat(ModelProfile profile) {
        if (profile.isAnthropic()) {
            var b = AnthropicChatModel.builder()
                    .baseUrl(profile.baseUrl())
                    .apiKey(profile.apiKey())
                    .modelName(profile.modelName());
            applyTemperature(b::temperature, profile);
            applyMaxTokens(b::maxTokens, profile);
            applyReturnThinking(b::returnThinking, profile);
            applyCustomParameters(b::customParameters, profile);
            return b.build();
        }
        var b = OpenAiChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.modelName());
        applyTemperature(b::temperature, profile);
        applyMaxTokens(b::maxTokens, profile);
        applyReturnThinking(b::returnThinking, profile);
        applyCustomParameters(b::customParameters, profile);
        return b.build();
    }

    // ==================== 参数施加 ====================

    /**
     * 温度：留空就不发送，由服务商默认决定。
     *
     * 不再注入平台自造的默认值，那会让"没填"实际等于"被平台改成了某个数"，
     * 而用户界面上看不到这个数；换个服务商，行为还会跟着变。
     *
     * <p>曾经这里还要先过一道"适配 schema 的 enabledWhen"（如推理模式下温度不生效）；
     * 对话 schema 下架后那道条件没有了，卡片上的标准字段始终可填、始终下发。</p>
     */
    private static void applyTemperature(java.util.function.Consumer<Double> setter, ModelProfile profile) {
        if (profile.temperature() != null) {
            setter.accept(profile.temperature());
        }
    }

    /**
     * 单次最大输出 token：没声明就不发 = 不限制，由模型上下文决定。
     *
     * "不限制"是刻意保留的默认而不是遗漏，只有显式填了值才会设限。
     */
    private static void applyMaxTokens(java.util.function.Consumer<Integer> setter, ModelProfile profile) {
        Integer value = profile.effectiveMaxTokens();
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * 是否把思考内容回传给平台 —— 落在框架侧。
     *
     * 它与 {@link ModelProfile#thinking()} 作用层不同：后者决定模型跑不跑思考（发给服务商），
     * 这个决定思考内容要不要解析出来交给调用方（框架行为）。默认不解析，思考直接丢弃。
     *
     * 只在显式声明为 true 时下发：留空等价于框架默认的"不返回"。
     */
    private static void applyReturnThinking(java.util.function.Consumer<Boolean> setter, ModelProfile profile) {
        if (Boolean.TRUE.equals(profile.returnThinking())) {
            setter.accept(Boolean.TRUE);
        }
    }

    /**
     * 私有调优参数 → 请求体（builder 的 {@code customParameters}）。
     *
     * 与标准参数同路并列：langchain4j 把 customParameters 与 temperature 等字段
     * 合并进同一个 ChatCompletionRequest，服务商看到的是一个完整请求体。
     *
     * 补缺规则：适配 schema 声明了 {@code default} 而用户没填的键，在此填上，
     * "没填"对用户是不发送，对适配是"按适配登记的默认走"，两者在 default 存在时重合。
     * 未命中适配、或对话族没有 schema 的档案**原样下发** —— 那是用户自助填参的主路径，
     * 保存时由 {@link CustomParamsGate} 把关，到这里已是"准许下发"的值。
     */
    private void applyCustomParameters(java.util.function.Consumer<Map<String, Object>> setter,
                                       ModelProfile profile) {
        Map<String, Object> wire = effectiveParams(profile);
        if (!wire.isEmpty()) {
            setter.accept(wire);
        }
    }

    /**
     * 档案 params → 请求体字段：有 schema 时按 schema 翻译，没有就**原样下发**。
     *
     * <p>对话族的 schema 已经下架（适配文件里 {@code families.chat.params} 为空），
     * 所以对话档案走的永远是"原样下发"这条分支：档案里存的键就是线上字段名
     * （存量档案由 {@code ModelProfileStore} 读盘时归一，见 {@link ChatParamMigration}）。
     * 剩下的 schema 分支只服务仍带参数的族（当前是向量的 {@code dimensions}，
     * 而它压根不经过本方法）。</p>
     *
     * <p>刻意保留而不是整段删掉：schema 机制还有 {@code validateParams} 与向量那一份消费者，
     * 删掉翻译逻辑会让"哪天某个族重新登记参数"变成一次静默的键名错配。</p>
     *
     * <p>标准参数（{@code std=true}）**不走这里**：它的值在 {@link ModelProfile} 的老字段上，
     * 由 {@link #applyTemperature} / {@link #applyMaxTokens} 那条路专管，
     * 否则同一个 {@code temperature} 会既进 customParameters、又进 builder setter，发两遍。</p>
     *
     * <p>包级可见是为了让测试直接钉住"原样下发"这四个字：这条分支以前被注释成
     * "正常应为空"，因为控制器那时拒绝非空；现在它是**唯一**路径，
     * 从"没有测试"变成"必须有测试"。</p>
     */
    Map<String, Object> effectiveParams(ModelProfile profile) {
        ModelAdapterRegistry.MatchResult matched = adapterRegistry.match(profile.baseUrl(), profile.modelName());
        /* 没有 chat schema → 原样下发。判空必须在这里做：命中适配但该族没有参数是现在的**常态**
           （适配文件对 chat 只声明端点族），若顺着下面的循环走，params 会被整个丢掉 —— 
           表现为"填了参数、请求体里一个字都没有" */
        if (!matched.matched() || matched.chatParams().isEmpty()) {
            return profile.params();
        }
        Map<String, Object> declared = profile.params();
        Map<String, Object> out = new LinkedHashMap<>();
        for (ModelAdapterRegistry.ParamSpec spec : matched.chatParams()) {
            /* 标准参数不进 customParameters：值由 applyTemperature / applyMaxTokens 走 builder setter，
               它登记的 default 只用于界面空态提示 */
            if (spec.std()) {
                continue;
            }
            Object value = declared.containsKey(spec.key()) ? declared.get(spec.key()) : spec.defaultValue();
            if (value == null || !ModelAdapterRegistry.enabled(spec, declared)) {
                continue;
            }
            out.put(spec.wire(), spec.wireWrap() == null ? value : Map.of(spec.wireWrap(), value));
        }
        /* 不在 chatParams 里的键直接不发射：保存时 validateParams 已按 chatParams 拦过，
           能走到这里的只可能是内存态被绕过硬造；发一个服务商不认的字段比不发更糟 */
        return out;
    }
}
