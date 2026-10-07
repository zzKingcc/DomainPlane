package com.zzkingcc.domainplane.infrastructure.ingestion.block;

/**
 * 结构化（md / docx / pdf）切片参数的全局取值点。
 *
 * 为什么需要它：策略工厂是静态的、策略实例是无参构造的单例，拿不到 Spring 的配置对象，
 * 所以把 {@code domainplane.rag.chunking.*} 在这里落一份，服务端启动时注入一次。
 *
 * 取值来源与 {@code TxtChunking} 完全相同（同一份 rag.chunking 配置），
 * 两个取值点各自持有，是因为 txt 切片器与结构化切片器是两条独立的管线 ——
 * 谁也不该因为对方改参数而变化。
 *
 * @author zzkingcc
 */
public final class BlockChunking {

    public static final int DEFAULT_MAX_CHARS = 400;
    public static final int DEFAULT_OVERLAP_SENTENCES = 1;
    public static final int DEFAULT_MIN_CHARS = 60;

    private static volatile int maxChars = DEFAULT_MAX_CHARS;
    private static volatile int overlapSentences = DEFAULT_OVERLAP_SENTENCES;
    private static volatile int minChars = DEFAULT_MIN_CHARS;

    private BlockChunking() {
    }

    /** 启动时注入一次；传 {@code null} 的项保持默认值 */
    public static void configure(Integer maxChars, Integer overlapSentences, Integer minChars) {
        if (maxChars != null && maxChars > 0) {
            BlockChunking.maxChars = maxChars;
        }
        if (overlapSentences != null && overlapSentences >= 0) {
            BlockChunking.overlapSentences = overlapSentences;
        }
        if (minChars != null && minChars >= 0) {
            BlockChunking.minChars = minChars;
        }
    }

    public static BlockSplitter newSplitter() {
        return new BlockSplitter(maxChars, overlapSentences, minChars);
    }
}
