package com.zzkingcc.domainplane.server.model;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按档案构建模型客户端，并按指纹缓存。
 *
 * 缓存设计的两个要点：
 *
 *   • 键是档案指纹（含 Key 哈希与协议类型）—— 改了 Key、端点或协议类型，指纹变，
 *     自然拿到新实例，不需要手工清缓存；
 *   • 每个别名只保留当前指纹那一个实例：旧实例在下次取用时被清掉，
 *     避免反复改配置堆实例。
 *
 * 协议分派：档案的 {@link ModelProfile#providerType()} 决定交给哪个适配器。
 * 这里只做"选谁"，不做协议转换 —— URL、鉴权头、请求体结构这些差异
 * 是 langchain4j 各适配模块自己的职责；硬写一遍等于重造 HTTP 客户端，
 * 且必然漏字段（每个服务商的私有参数都在持续增加）。
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelClientFactory {

    /**
     * 思考开关的请求体字段名（仅 OpenAI 兼容协议）。
     *
     * {@code enable_thinking} 是硅基流动 / 百炼这条线（Qwen 系）实际认的顶层字段，
     * 实测能关掉思考。只在档案显式声明时才发：未声明就不发这个字段，
     * 由服务商默认决定 —— 给不认识的端点塞扩展字段轻则被忽略、重则被拒，不该替用户赌。
     */
    private static final String THINKING_FIELD = "enable_thinking";

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

        StreamingChatModel existing = versions.get(fingerprint);
        if (existing != null) {
            return existing;
        }
        // 顺序很重要：先构建、再清理旧指纹、最后放入 —— 不能在 map.compute 里改同一个 map
        StreamingChatModel built = buildStreaming(profile);
        evictOthers(versions, fingerprint);
        versions.put(fingerprint, built);
        log.info("[模型档案] 构建流式客户端：alias={} provider={} model={} baseUrl={}（指纹 {}）",
                profile.alias(), profile.providerType(), profile.modelName(), profile.baseUrl(), fingerprint);
        return built;
    }

    /**
     * 取（或构建）该档案的非流式对话模型（用于连通性测试等短调用）。
     */
    public ChatModel chat(ModelProfile profile) {
        String fingerprint = profile.fingerprint();
        Map<String, ChatModel> versions =
                chatByAlias.computeIfAbsent(profile.alias(), alias -> new ConcurrentHashMap<>());

        ChatModel existing = versions.get(fingerprint);
        if (existing != null) {
            return existing;
        }
        ChatModel built = buildChat(profile);
        evictOthers(versions, fingerprint);
        versions.put(fingerprint, built);
        return built;
    }

    /** 只保留当前指纹，其余清掉（旧实例交给 GC；无长连接需要显式关闭） */
    private static <T> void evictOthers(Map<String, T> versions, String keepFingerprint) {
        versions.keySet().removeIf(fp -> !fp.equals(keepFingerprint));
    }

    // ==================== 流式 ====================

    private StreamingChatModel buildStreaming(ModelProfile profile) {
        return switch (profile.providerType()) {
            case ANTHROPIC -> {
                var b = AnthropicStreamingChatModel.builder()
                        .apiKey(profile.apiKey())
                        .modelName(profile.modelName());
                if (hasText(profile.baseUrl())) {
                    b.baseUrl(profile.baseUrl());
                }
                applyTemperature(b::temperature, profile);
                applyMaxTokens(b::maxTokens, profile);
                applyReturnThinking(b::returnThinking, profile);
                numParam(b::topP, profile, "chat.topP");
                intParam(b::topK, profile, "chat.topK");
                intParam(b::thinkingBudgetTokens, profile, "chat.thinkingBudgetTokens");
                listParam(b::stopSequences, profile, "chat.stop");
                applySendThinking(b::sendThinking, profile);
                yield b.build();
            }
            case OLLAMA -> {
                var b = OllamaStreamingChatModel.builder()
                        /* Ollama 的 baseUrl 不带尾斜杠：带上了会被拼成 http://host//api/chat */
                        .baseUrl(trimTrailingSlash(profile.baseUrl()))
                        .modelName(profile.modelName());
                applyTemperature(b::temperature, profile);
                /* Ollama 没有 maxTokens，对应参数叫 numPredict —— 名字不同但语义一致 */
                Integer maxTokens = profile.effectiveMaxTokens();
                if (maxTokens != null) {
                    b.numPredict(maxTokens);
                }
                /* Ollama 的思考开关有原生入口 think(Boolean)，不需要 customParameters */
                if (profile.thinking() != null) {
                    b.think(profile.thinking());
                }
                applyReturnThinking(b::returnThinking, profile);
                numParam(b::topP, profile, "chat.topP");
                intParam(b::topK, profile, "chat.topK");
                intParam(b::numCtx, profile, "chat.numCtx");
                numParam(b::repeatPenalty, profile, "chat.repeatPenalty");
                intParam(b::seed, profile, "chat.seed");
                listParam(b::stop, profile, "chat.stop");
                yield b.build();
            }
            case OPENAI_COMPATIBLE -> {
                var b = OpenAiStreamingChatModel.builder()
                        .baseUrl(profile.baseUrl())
                        .apiKey(profile.apiKey())
                        .modelName(profile.modelName());
                applyTemperature(b::temperature, profile);
                applyMaxTokens(b::maxTokens, profile);
                applyOpenAiThinking(b::customParameters, b::returnThinking, profile);
                numParam(b::topP, profile, "chat.topP");
                numParam(b::presencePenalty, profile, "chat.presencePenalty");
                numParam(b::frequencyPenalty, profile, "chat.frequencyPenalty");
                intParam(b::seed, profile, "chat.seed");
                flagParam(b::parallelToolCalls, profile, "chat.parallelToolCalls");
                textParam(b::reasoningEffort, profile, "chat.reasoningEffort");
                listParam(b::stop, profile, "chat.stop");
                yield b.build();
            }
            case DASHSCOPE -> throw new IllegalStateException(dashscopeUnsupported());
        };
    }

    // ==================== 非流式 ====================

    private ChatModel buildChat(ModelProfile profile) {
        return switch (profile.providerType()) {
            case ANTHROPIC -> {
                var b = AnthropicChatModel.builder()
                        .apiKey(profile.apiKey())
                        .modelName(profile.modelName());
                if (hasText(profile.baseUrl())) {
                    b.baseUrl(profile.baseUrl());
                }
                applyTemperature(b::temperature, profile);
                applyMaxTokens(b::maxTokens, profile);
                applyReturnThinking(b::returnThinking, profile);
                numParam(b::topP, profile, "chat.topP");
                intParam(b::topK, profile, "chat.topK");
                intParam(b::thinkingBudgetTokens, profile, "chat.thinkingBudgetTokens");
                listParam(b::stopSequences, profile, "chat.stop");
                applySendThinking(b::sendThinking, profile);
                yield b.build();
            }
            case OLLAMA -> {
                var b = OllamaChatModel.builder()
                        .baseUrl(trimTrailingSlash(profile.baseUrl()))
                        .modelName(profile.modelName());
                applyTemperature(b::temperature, profile);
                Integer maxTokens = profile.effectiveMaxTokens();
                if (maxTokens != null) {
                    b.numPredict(maxTokens);
                }
                if (profile.thinking() != null) {
                    b.think(profile.thinking());
                }
                applyReturnThinking(b::returnThinking, profile);
                numParam(b::topP, profile, "chat.topP");
                intParam(b::topK, profile, "chat.topK");
                intParam(b::numCtx, profile, "chat.numCtx");
                numParam(b::repeatPenalty, profile, "chat.repeatPenalty");
                intParam(b::seed, profile, "chat.seed");
                listParam(b::stop, profile, "chat.stop");
                yield b.build();
            }
            case OPENAI_COMPATIBLE -> {
                var b = OpenAiChatModel.builder()
                        .baseUrl(profile.baseUrl())
                        .apiKey(profile.apiKey())
                        .modelName(profile.modelName());
                applyTemperature(b::temperature, profile);
                applyMaxTokens(b::maxTokens, profile);
                applyOpenAiThinking(b::customParameters, b::returnThinking, profile);
                numParam(b::topP, profile, "chat.topP");
                numParam(b::presencePenalty, profile, "chat.presencePenalty");
                numParam(b::frequencyPenalty, profile, "chat.frequencyPenalty");
                intParam(b::seed, profile, "chat.seed");
                flagParam(b::parallelToolCalls, profile, "chat.parallelToolCalls");
                textParam(b::reasoningEffort, profile, "chat.reasoningEffort");
                listParam(b::stop, profile, "chat.stop");
                yield b.build();
            }
            case DASHSCOPE -> throw new IllegalStateException(dashscopeUnsupported());
        };
    }

    // ==================== 参数施加 ====================

    /**
     * 温度：留空就不发送，由服务商默认决定。
     *
     * 不再注入平台自造的默认值 —— 那会让"没填"实际等于"被平台改成了某个数"，
     * 而用户界面上看不到这个数；换个服务商，行为还会跟着变。
     */
    private static void applyTemperature(java.util.function.Consumer<Double> setter, ModelProfile profile) {
        if (profile.temperature() != null) {
            setter.accept(profile.temperature());
        }
    }

    // ==================== 调优参数（前缀键，留空即不发） ====================

    /** 数值参数 */
    private static void numParam(java.util.function.Consumer<Double> setter, ModelProfile profile, String key) {
        Double value = profile.numParam(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /** 整数参数 */
    private static void intParam(java.util.function.Consumer<Integer> setter, ModelProfile profile, String key) {
        Integer value = profile.intParam(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /** 文本参数 */
    private static void textParam(java.util.function.Consumer<String> setter, ModelProfile profile, String key) {
        String value = profile.strParam(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /** 布尔参数 */
    private static void flagParam(java.util.function.Consumer<Boolean> setter, ModelProfile profile, String key) {
        Boolean value = profile.boolParam(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /** 字符串数组参数（停止序列） */
    private static void listParam(java.util.function.Consumer<java.util.List<String>> setter,
                                  ModelProfile profile, String key) {
        java.util.List<String> value = profile.listParam(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Anthropic 的思考开关。
     *
     * 与 OpenAI 兼容的 {@code enable_thinking} 不同：Anthropic 的开关走 {@code sendThinking}，
     * 预算另有 {@code thinkingBudgetTokens} 入口（见 chat.thinkingBudgetTokens 参数）。
     */
    private static void applySendThinking(java.util.function.Consumer<Boolean> setter, ModelProfile profile) {
        if (profile.thinking() != null) {
            setter.accept(profile.thinking());
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
     * 是否把思考内容回传给平台 —— 落在框架侧，三家都有这个开关。
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
     * OpenAI 兼容协议的思考开关。
     *
     * 这里的 {@code thinking} 与 {@code returnThinking} 都是发给服务商的字段，
     * 其中 {@code enable_thinking} 是硅基流动 / 百炼这条线（Qwen 系）实际认的顶层字段。
     *
     * 为什么只有 OpenAI 兼容走customParameters：另两家的开关形态不同 ——
     * Anthropic 用带 budget_tokens 的 {@code thinking} 对象（塞布尔会被拒），
     * Ollama 有独立的 {@code think} 入口。两者都在各自分支里单独处理，不走这里。
     *
     * OpenRouter 是个已知缺口：它走 {@code reasoning} 对象且分 effort 档位，
     * 与 {@code enable_thinking} 不同构，因此当前在 OpenAI 兼容协议下也不生效。
     */
    private static void applyOpenAiThinking(java.util.function.Consumer<Map<String, Object>> customParameters,
                                            java.util.function.Consumer<Boolean> returnThinking,
                                            ModelProfile profile) {
        if (profile.thinking() != null) {
            customParameters.accept(Map.of(THINKING_FIELD, profile.thinking()));
        }
        applyReturnThinking(returnThinking, profile);
    }

    // ==================== 小工具 ====================

    /** Ollama 专用：去掉尾部斜杠，避免拼出 {@code //api/chat} */
    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String dashscopeUnsupported() {
        return "阿里云百炼的原生适配暂不可用：langchain4j 的 dashscope 模块停更在 0.36.2，"
                + "与当前 1.18.x 不在同一依赖世代。请把该档案的协议类型改成"
                + "「OpenAI 兼容」（百炼的 compatible-mode/v1 入口可直接使用）。";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}