package com.zzkingcc.domainplane.infrastructure.ingestion;

/**
 * 导入侧的全局限制取值点。
 *
 * 与 {@code TxtChunking} / {@code BlockChunking} 同样的原因：策略工厂是静态的、
 * 策略实例是无参构造的单例，拿不到 Spring 的配置对象，所以把配置在这里落一份。
 *
 * @author zzkingcc
 */
public final class IngestLimits {

    public static final int DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 2000;

    private static volatile int maxChunksPerDocument = DEFAULT_MAX_CHUNKS_PER_DOCUMENT;

    private IngestLimits() {
    }

    /** 启动时注入一次；传 {@code null} 或 ≤0 保持默认值 */
    public static void configure(Integer maxChunksPerDocument) {
        if (maxChunksPerDocument != null && maxChunksPerDocument > 0) {
            IngestLimits.maxChunksPerDocument = maxChunksPerDocument;
        }
    }

    /**
     * 单文件（单次上传）允许的最大切片数。
     *
     * 这不是限制功能，是防雪崩：几百页的文档一次就能产出几千片，
     * 而向量化是分批请求模型的，一次上传就能把导入队列堵死。
     */
    public static int maxChunksPerDocument() {
        return maxChunksPerDocument;
    }
}
