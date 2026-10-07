package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

/**
 * 切片参数的全局取值点。
 *
 * 策略工厂是静态的、策略实例是无参构造的单例，拿不到 Spring 的配置对象；
 * 所以切片参数在这里落一份，由服务端启动时从 {@code domainplane.rag.chunking.*} 注入一次。
 *
 * 刻意保持极简：只有三项，其余阈值（页码正则、标题行长、层级数、跨页重复阈值…）
 * 全部是代码里的常量 —— 改就重新构建，避免配置面越摊越大。
 *
 * @author zzkingcc
 */
public final class TxtChunking {

    private static volatile int maxChars = TxtSplitter.DEFAULT_MAX_CHARS;
    private static volatile int overlapSentences = TxtSplitter.DEFAULT_OVERLAP_SENTENCES;
    private static volatile int minChars = TxtSplitter.DEFAULT_MIN_CHARS;

    private TxtChunking() {
    }

    /** 启动时注入一次；传 {@code null} 的项保持默认值 */
    public static void configure(Integer maxChars, Integer overlapSentences, Integer minChars) {
        if (maxChars != null && maxChars > 0) {
            TxtChunking.maxChars = maxChars;
        }
        if (overlapSentences != null && overlapSentences >= 0) {
            TxtChunking.overlapSentences = overlapSentences;
        }
        if (minChars != null && minChars >= 0) {
            TxtChunking.minChars = minChars;
        }
    }

    public static TxtSplitter newSplitter() {
        return new TxtSplitter(maxChars, overlapSentences, minChars);
    }
}
