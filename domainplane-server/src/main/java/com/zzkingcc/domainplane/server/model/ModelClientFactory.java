package com.zzkingcc.domainplane.server.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按档案构建模型客户端，并按<b>指纹</b>缓存。
 *
 * <p>缓存设计的两个要点：</p>
 * <ul>
 *   <li>键是档案指纹（含 Key 哈希）——改了 Key 或端点，指纹变，自然拿到新实例，不需要手工清缓存；</li>
 *   <li>每个别名<b>只保留当前指纹那一个实例</b>：旧实例在下次取用时被清掉，避免反复改配置堆实例。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelClientFactory {

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
                .modelName(profile.modelName());
        if (profile.temperature() != null) {
            builder.temperature(profile.temperature());
        }
        if (profile.maxTokens() != null) {
            builder.maxTokens(profile.maxTokens());
        }
        return builder.build();
    }

    private static ChatModel buildChat(ModelProfile profile) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(profile.baseUrl())
                .apiKey(profile.apiKey())
                .modelName(profile.modelName());
        if (profile.temperature() != null) {
            builder.temperature(profile.temperature());
        }
        if (profile.maxTokens() != null) {
            builder.maxTokens(profile.maxTokens());
        }
        return builder.build();
    }
}
