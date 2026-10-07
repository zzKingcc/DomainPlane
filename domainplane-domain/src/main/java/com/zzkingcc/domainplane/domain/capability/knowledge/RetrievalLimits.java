package com.zzkingcc.domainplane.domain.capability.knowledge;

/**
 * 检索结果注入侧的两个上限（条数 / 字符预算）的全局取值点。
 *
 * 为什么要一个静态取值点：{@link KnowledgeSearchService} 是无状态组件、构造参数只有检索器，
 * 而这两个值来自 server 模块的 {@code domainplane.retrieval.*}（domain 不能反向依赖 server）。
 * 由 server 侧的配置类启动时调一次 {@link #configure} 注入 —— 与切片参数
 * （{@code TxtChunking}）同一套路。
 *
 * 取默认值时与升级前行为一致（条数 8、预算 3000 字符）；配置类没被加载时仍可用。
 *
 * @author zzkingcc
 */
public final class RetrievalLimits {

    private static volatile int injectTopN = 8;

    private static volatile int maxContextChars = 3000;

    private RetrievalLimits() {
    }

    /** 启动时由配置类注入一次；非法值（≤0）保持原值不覆盖。 */
    public static void configure(int topN, int contextChars) {
        if (topN > 0) {
            injectTopN = topN;
        }
        if (contextChars > 0) {
            maxContextChars = contextChars;
        }
    }

    /** 每次检索最多注入几片 */
    public static int injectTopN() {
        return injectTopN;
    }

    /** 单次检索注入的正文总字符预算 */
    public static int maxContextChars() {
        return maxContextChars;
    }
}
