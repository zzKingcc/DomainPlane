package com.zzkingcc.domainplane.server.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
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
 *   • 键是档案指纹（含 Key 哈希）——改了 Key 或端点，指纹变，自然拿到新实例，不需要手工清缓存；
 *   • 每个别名只保留当前指纹那一个实例：旧实例在下次取用时被清掉，避免反复改配置堆实例。
 *
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelClientFactory {

    /**
     * 思考开关的请求体字段名。
     *
     * 目前只认这一个：{@code enable_thinking} 是硅基流动 / 百炼这条线（Qwen 系）
     * 实际认的顶层字段，实测能关掉思考。别家叫法不同（OpenRouter 走 {@code reasoning} 对象、
     * Anthropic 风格走 {@code thinking}），那属于"按服务商适配"，本轮不做。
     *
     * 只在档案显式声明时才发：未声明就不发这个字段，由服务商默认决定 ——
     * 给不认识的端点塞扩展字段轻则被忽略、重则被拒，不该替用户赌。
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
        log.info("[模型档案] 构建流式客户端：alias={} model={} baseUrl={}（指纹 {}）",
                profile.alias(), profile.modelName(), profile.baseUrl(), fingerprint);
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

    private static StreamingChatModel buildStreaming(ModelProfile profile) {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.modelName())
                /* 温度走 effectiveTemperature()：没声明时按 0.5 发出去，而不是让各家端点
                   各自决定（常见 0.7 / 1.0）——同一个档案换一家服务商行为就变了且无提示。 */
                .temperature(profile.effectiveTemperature());
        /* 最大 token 走 effectiveMaxTokens()：没声明就不发 max_tokens = 不限制，
           由模型上下文决定；只有显式填了值才会设限。 */
        Integer maxTokens = profile.effectiveMaxTokens();
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        applyThinking(builder::customParameters, builder::returnThinking, profile);
        return builder.build();
    }

    private static ChatModel buildChat(ModelProfile profile) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.modelName())
                .temperature(profile.effectiveTemperature());
        Integer maxTokens = profile.effectiveMaxTokens();
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        applyThinking(builder::customParameters, builder::returnThinking, profile);
        return builder.build();
    }

    /**
     * 把档案里的两个思考开关落到客户端 builder 上。
     *
     * 两个开关的作用层不同，别混为一谈：
     *
     *   • {@code thinking} —— 发到服务商（{@code enable_thinking}），决定模型跑不跑思考。
     *       关掉它才是真正省钱省时的那个；未声明就不发。
     *   • {@code returnThinking} —— 落在框架（langchain4j 的 {@code returnThinking}），
     *       决定思考内容要不要解析出来交给调用方。默认不解析，思考直接丢弃；
     *
     *
     * 反射式传参的用意：两个 builder 是不同类型的 Lambda，但方法签名恰好一致
     * （{@code Map<String,Object>} 与 {@code Boolean}），所以共用一份逻辑 —— 避免
     * "流式接了这个开关、非流式漏了"这种只在测试连通性时才暴露的偏差。
     */
    private static void applyThinking(java.util.function.Consumer<Map<String, Object>> customParameters,
                                      java.util.function.Consumer<Boolean> returnThinking,
                                      ModelProfile profile) {
        if (profile.thinking() != null) {
            customParameters.accept(Map.of(THINKING_FIELD, profile.thinking()));
        }
        if (Boolean.TRUE.equals(profile.returnThinking())) {
            returnThinking.accept(Boolean.TRUE);
        }
    }
}
