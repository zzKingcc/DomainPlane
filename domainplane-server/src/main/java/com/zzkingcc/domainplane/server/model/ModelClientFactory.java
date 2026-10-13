package com.zzkingcc.domainplane.server.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
 * 连接基底只有 OpenAI 兼容一种格式：所有档案都走 OpenAi(Streaming)ChatModel。
 * 服务商差异由对方暴露的 OpenAI 兼容入口吸收，本项目不做协议分派，
 * 后续要接新能力，在这个格式上扩，而不是按服务商开分支。
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
        var b = OpenAiStreamingChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.modelName());
        Set<String> stdWires = allowedStdWires(profile);
        applyTemperature(b::temperature, profile, stdWires);
        applyMaxTokens(b::maxTokens, profile, stdWires);
        applyReturnThinking(b::returnThinking, profile);
        applyCustomParameters(b::customParameters, profile);
        return b.build();
    }

    private ChatModel buildChat(ModelProfile profile) {
        var b = OpenAiChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.modelName());
        Set<String> stdWires = allowedStdWires(profile);
        applyTemperature(b::temperature, profile, stdWires);
        applyMaxTokens(b::maxTokens, profile, stdWires);
        applyReturnThinking(b::returnThinking, profile);
        applyCustomParameters(b::customParameters, profile);
        return b.build();
    }

    // ==================== 参数施加 ====================

    /**
     * 此刻允许下发的**标准参数** wire 集合。
     *
     * 私有参数的过滤在 {@link #effectiveParams} 里做；标准参数走的是框架 setter，不经那条路，
     * 所以单独算一遍，两处都读同一份 enabledWhen，口径不会分叉。
     *
     * 口径是"默认放行、条件不成立才拦"：没登记条件的标准参数一律放行，未命中适配也一律放行
     * （与 {@code effectiveParams} 的"原样下发"一致），于是新逻辑只可能收窄条件参数这一个点，
     * 老档案的行为分毫不动。
     */
    private Set<String> allowedStdWires(ModelProfile profile) {
        Set<String> out = new HashSet<>();
        out.add("temperature");
        out.add("max_tokens");
        ModelAdapterRegistry.MatchResult matched = adapterRegistry.match(profile.baseUrl(), profile.modelName());
        for (ModelAdapterRegistry.ParamSpec spec : matched.chatParams()) {
            if (spec.std() && spec.enabledWhen() != null
                    && !ModelAdapterRegistry.enabled(spec, profile.params())) {
                out.remove(spec.wire());
            }
        }
        return out;
    }

    /**
     * 温度：留空就不发送，由服务商默认决定。
     *
     * 不再注入平台自造的默认值，那会让"没填"实际等于"被平台改成了某个数"，
     * 而用户界面上看不到这个数；换个服务商，行为还会跟着变。
     */
    private static void applyTemperature(java.util.function.Consumer<Double> setter, ModelProfile profile,
                                         Set<String> stdWires) {
        if (profile.temperature() != null && stdWires.contains("temperature")) {
            setter.accept(profile.temperature());
        }
    }

    /**
     * 单次最大输出 token：没声明就不发 = 不限制，由模型上下文决定。
     *
     * "不限制"是刻意保留的默认而不是遗漏，只有显式填了值才会设限。
     */
    private static void applyMaxTokens(java.util.function.Consumer<Integer> setter, ModelProfile profile,
                                       Set<String> stdWires) {
        Integer value = profile.effectiveMaxTokens();
        if (value != null && stdWires.contains("max_tokens")) {
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
     * 未命中适配的档案原样下发（正常应为空，保存时已被拒绝非空）。
     */
    private void applyCustomParameters(java.util.function.Consumer<Map<String, Object>> setter,
                                       ModelProfile profile) {
        Map<String, Object> wire = effectiveParams(profile);
        if (!wire.isEmpty()) {
            setter.accept(wire);
        }
    }

    /**
     * 档案 params → 请求体字段：适配 default 补缺 + 档案键翻译成线上字段名（key → wire）。
     *
     * 档案 params 的键是适配 {@code key}（UI 侧标识，如 {@code reasoningEffort}），
     * 请求体要的是 {@code wire}（OpenAI 字段名，如 {@code reasoning_effort}）。
     * 翻译只此一处：存储 / 校验 / 视图全用 key，注入前才换成 wire。
     *
     * 补缺规则：适配 schema 声明了 {@code default} 而用户没填的键，在此填上，
     * "没填"对用户是不发送，对适配是"按适配登记的默认走"，两者在 default 存在时重合。
     * 条件参数也参与补缺，但补出来的值**同样要过下面那道条件门**：条件不成立时连默认值
     * 都不发射。
     *
     * 标准参数（{@code std=true}）**不走这里**：它的值在 {@link ModelProfile} 的老字段上，
     * 由 {@link #allowedStdWires} + setter 那条路专管（见 {@link #applyTemperature} /
     * {@link #applyMaxTokens}）。default 对标准参数只用于界面空态提示，不参与注入，
     * 否则同一个 {@code temperature} 会既进 customParameters、又进 builder setter，发两遍。
     *
     * 过滤规则：条件不成立的值不发射，正常路径下这种值在保存时就被
     * {@code validateParams} 拒了，这里只兜住绕过控制器写入的档案（改配置文件、旧版本残留）。
     * 发给服务商一个它不看的字段，比不发更糟：看起来配了，实际没生效。
     *
     * 包装规则：{@code wireWrap} 非空的值包一层单键对象（{@code {wireWrap: value}}），
     * 用于厂商把标量塞进子对象的标准写法，如 {@code response_format: {type: "json_object"}}。
     * 未命中适配的档案原样下发（正常应为空，保存时已被拒绝非空）。
     */
    private Map<String, Object> effectiveParams(ModelProfile profile) {
        ModelAdapterRegistry.MatchResult matched = adapterRegistry.match(profile.baseUrl(), profile.modelName());
        if (!matched.matched()) {
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
