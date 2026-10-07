package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 混合检索配置
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.retrieval")
public class RetrievalProperties {

    /** 各路检索是否并行执行;false 退化为串行 */
    private boolean parallel = true;

    /** 单路检索超时(毫秒);超时后该路降级为空结果 */
    private long timeoutMs = 5000;

    /** 检索线程池核心线程数 */
    private int corePoolSize = 4;

    /** 检索线程池最大线程数 */
    private int maxPoolSize = 16;

    /** 检索线程池队列容量 */
    private int queueCapacity = 200;

    /** 向量路每张索引召回条数;必须 ≥ top-n,否则融合阶段没料可排 */
    private int vectorTopK = 30;

    /** 关键词路每张索引召回条数;必须 ≥ top-n */
    private int keywordTopK = 20;

    /** 向量路最低余弦相似度(偏差已 +1.0 回归,这里的值按原始余弦填) */
    private double vectorMinScore = 0.35;

    /**
     * 关键词查询的 minimum_should_match。
     *
     * 按查询切出的词数算比例:"50%"=至少命中一半的词。用于压"只命中一个词"的噪音。
     *
     * 这个默认值是在真实 ES 上量出来的,别凭直觉调大:ik_smart 会把一句
     * "会员退款的时效说明" 切成 5 个词(会员/退款/的/时效/说明)——助词也占额度,
     * 于是 60% 要求命中 3 个词,直接把正常问题打成零命中;而 30% 及以下等于没限制。
     * 有效区间是 40%~50%。留空 = 不限制(退化为旧行为)。
     */
    private String minimumShouldMatch = "50%";

    /** 融合权重:向量 */
    private double vectorWeight = 0.6;

    /** 融合权重:关键词 */
    private double keywordWeight = 0.4;

    /** 标题命中查询关键词时的乘法增益(分 × (1 + 该值)) */
    private double titleBoost = 0.15;

    /** 文件名命中查询关键词时的乘法增益(分 × (1 + 该值)) */
    private double fileNameBoost = 0.10;

    /** 融合重排后返回条数 */
    private int topN = 15;

    /** RRF 平滑常数(仅多索引排名制融合使用);表长普遍在 20~50,过大等于抹平名次差 */
    private int rrfK = 10;

    /**
     * 层级衰减系数(仅多索引排名制融合使用)。
     *
     * 距查询域每远一层,该来源的权重乘一次该值;1.0 = 不衰减(本域与祖先等权)。
     * 与"模型绑定自身优先、无则向上"的语义对齐:本域自有知识优先于继承来的知识。
     */
    private double ancestorDecay = 0.7;

    /** 每次检索最多注入几片给模型 */
    private int injectTopN = 8;

    /**
     * 单次检索注入的正文总字符预算。
     *
     * 条数不是真正的瓶颈,字符数才是:同样的条数,切片长短能差几倍。
     * 逐条累加,超预算即停(第一条无论如何都保留,避免预算过小导致一条都不给)。
     */
    private int maxContextChars = 3000;
}
