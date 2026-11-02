package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

/**
 * 语义分块的运行期开关与全局取值点（F05 P2）。
 *
 * 策略实例是无参构造的单例，拿不到 Spring 的配置对象，所以把
 * {@code domainplane.rag.chunking.semantic.*} 在这里落一份，由服务端启动时
 * {@link com.zzkingcc.domainplane.server.config.ChunkingConfiguration} 注入一次。
 *
 * 默认关闭：关闭时 {@link #resolve(int)} 返回 null，{@link TxtSplitter} 与
 * {@link com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockSplitter} 回退到
 * {@link Chunker}（结构感知切片），行为与改造前一字不变。
 *
 * @author zzkingcc
 */
@Slf4j
public final class SemanticChunking {

    private static volatile EmbeddingModel model;
    private static volatile boolean enabled;
    private static volatile double similarityThreshold = SemanticChunker.DEFAULT_SIMILARITY_THRESHOLD;
    private static volatile int minSentences = SemanticChunker.DEFAULT_MIN_SENTENCES;

    private SemanticChunking() {
    }

    /**
     * 启动时注入一次；传 {@code null} 的项保持默认值。
     *
     * @param model             句子嵌入模型；为 null 时语义分块自动降级为结构感知切片
     * @param enabled           是否开启语义分块
     * @param similarityThreshold 相邻句子余弦相似度低于此值时断开（0~1）
     * @param minSentences      每片最少句子数（≥1）
     */
    public static void configure(EmbeddingModel model, Boolean enabled, Double similarityThreshold,
                                 Integer minSentences) {
        SemanticChunking.model = model;
        SemanticChunking.enabled = enabled != null && enabled;
        if (similarityThreshold != null) {
            SemanticChunking.similarityThreshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        }
        if (minSentences != null && minSentences > 0) {
            SemanticChunking.minSentences = minSentences;
        }
        log.info("[语义分块] 已就绪：enabled={}，similarity-threshold={}，min-sentences={}，model={}",
                SemanticChunking.enabled, SemanticChunking.similarityThreshold,
                SemanticChunking.minSentences, model == null ? "无（已降级）" : describe(model));
    }

    /**
     * 日志用的模型名，取不到就退成一句说明。
     *
     * ⚠️ 为什么必须包这一层：传进来的 {@link EmbeddingModel} 是服务端那个延迟解析的代理
     * （配置随时可在管控台改），向量模型还没配时它的 {@code modelName()} 是抛异常的
     * —— 而这里只是想在启动日志里写一行名字。为打日志把整个应用带崩极不划算：
     * 全新部署（一个模型都没配）会连启动都做不到，只能先去别处造一份配置再回来起服务。
     * 语义分块自己有降级（{@link #resolve(int)} 在无模型时返回 null，回退结构感知切片），
     * 未配置记一行"尚未就绪"即可，等真正切片时再由调用点拿到明确的未配置异常。
     */
    private static String describe(EmbeddingModel model) {
        try {
            return model.modelName();
        } catch (RuntimeException e) {
            return "尚未就绪（" + e.getMessage() + "）";
        }
    }

    /**
     * 取语义分块器；未启用或无模型时返回 null，调用方回退到结构感知切片。
     */
    public static TextChunker resolve(int maxChars) {
        if (!enabled || model == null) {
            return null;
        }
        return new SemanticChunker(model, similarityThreshold, minSentences, maxChars);
    }
}
