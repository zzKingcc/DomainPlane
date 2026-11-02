package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库存储后端与召回策略（{@code domainplane.knowledge.*}，F05 去 ES 强依赖的配置面）。
 *
 * <p>与旧配置的分界：{@code RetrievalProperties}（{@code domainplane.retrieval.*}）里
 * 与"哪个后端、什么策略"相关的四项（{@code vector-top-k} / {@code keyword-top-k} /
 * {@code vector-min-score} / {@code minimum-should-match}）降级为<b>废弃兼容键</b>：
 * 新键未设时原样搬到 ES 后端配置并打 WARN（语义没变，不需要换算），行为完全等价。</p>
 *
 * <p>阈值归属（设计第 5 节）：{@code minScore} / {@code minimumShouldMatch} 是 ES 方言，
 * 关在后端配置里（适配器内部的 {@code +1.0} 偏移等不外泄）；{@code topK} 是通用配置，
 * 同样放在后端段下，但由通道层每次 {@code search} 时传入，不进适配器构造器。</p>
 *
 * <p><b>P0 不动 {@code InfraSettings}</b>（一审修订 A5）：ES 连接地址继续复用
 * {@code InfraSettings.es}，管控台「存储配置」页零改动；本类只描述"用哪个后端、
 * 走什么策略"，不描述"后端在哪"。也因此，{@code type} 与 {@code strategy} 变更
 * 必须重启（bean 图不同，热替换只会造出半新半旧的装配）。</p>
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.knowledge")
public class KnowledgeProperties {

    /** 默认后端 id（P0 唯一后端） */
    public static final String DEFAULT_BACKEND_ID = "es-main";

    /** ES 后端类型 */
    public static final String TYPE_ELASTICSEARCH = "elasticsearch";

    /** Qdrant 后端类型（P1 引入：向量路可切到专用向量库） */
    public static final String TYPE_QDRANT = "qdrant";

    /** 受支持的后端类型白名单（其它值启动即报错并列出支持列表） */
    public static final List<String> SUPPORTED_TYPES = List.of(TYPE_ELASTICSEARCH, TYPE_QDRANT);

    /**
     * 召回策略：keyword / vector / hybrid（默认）。
     *
     * 决定检索计划开出哪几路通道，也决定启动期校验要求哪些能力：
     * keyword 只要求 keyword 能力，vector 要求 vector 能力 + EmbeddingModel，
     * hybrid 两者都要。配不满启动即失败（{@code KNOWLEDGE_BACKEND_MISCONFIGURED}），
     * 不留给运行期——运行期空结果会让"配错了"和"知识库是空的"长得一模一样。
     */
    private Strategy strategy = Strategy.HYBRID;

    /**
     * 融合算法：auto（默认，单来源分数制 / 多来源 RRF 自动切换）/ score / rrf。
     *
     * auto 就是改造前的行为（{@code AdaptiveFusionStrategy}），显式选 score / rrf
     * 可锁死单轨，用于排查"到底走了哪套融合"。
     */
    private Fusion fusion = Fusion.AUTO;

    /** 关键词路的后端 id */
    private String keywordBackend = DEFAULT_BACKEND_ID;

    /** 向量路的后端 id（可与关键词路不同：如关键词留 ES、向量走向量库） */
    private String vectorBackend = DEFAULT_BACKEND_ID;

    /** 后端清单：id → 后端配置。空 = 一个后端都没配（启动期校验点名） */
    private Map<String, Backend> backends = new LinkedHashMap<>();

    /** 召回策略取值。 */
    public enum Strategy {
        KEYWORD, VECTOR, HYBRID
    }

    /** 融合算法取值。 */
    public enum Fusion {
        SCORE, RRF, AUTO
    }

    /**
     * 单个后端的配置。
     *
     * {@code type} 决定用哪个适配器（{@link #SUPPORTED_TYPES} 白名单）；
     * {@code vector} / {@code keyword} 段整体可缺省（缺省即走废弃兼容键回退），
     * 因此是对象引用而不是带默认值的实例——"没写这一项"与"写了默认值"必须可区分，
     * 否则兼容键回退永远不触发。
     *
     * <p>{@code collectionPrefix} 只对非 ES 后端有意义：向量库里的集合名与
     * ES 索引名可能撞车，加前缀隔开。留空则用原名（与 ES 侧索引名一字不差）。</p>
     */
    @Data
    public static class Backend {

        private String type = TYPE_ELASTICSEARCH;

        /**
         * 该后端是否承载向量能力（{@code null} = 沿用默认 {@code true}）。
         *
         * <p><b>纯 keyword 后端</b>：显式 {@code vector: false} 时，后端只提供关键词检索
         * 与文本存储（{@code capabilities.vector=false}），不接向量检索器、写入只落
         * payload 不落向量、不能作为重灌目标（重灌要写向量）。这是 F05 P2 的"关键字后端
         * 可切"——与 SPI 的 {@code KnowledgeBackend} 注释一致（"keyword-only / vector-only
         * 都是合法后端"）。</p>
         *
         * <p>当前仅 {@code type: qdrant} 真正消费此开关（Qdrant 的 payload 全文检索本就
         * 独立于向量）；{@code type: elasticsearch} 显式 {@code vector: false} 会启动失败
         * 并指引改用 qdrant（ES 的 keyword-only 需要改索引 mapping，P2 不做）。</p>
         */
        private Boolean vectorEnabled;

        private Vector vector;

        private Keyword keyword;

        /** 集合名前缀（仅非 ES 后端生效；留空 = 用原名） */
        private String collectionPrefix;
    }

    /** 向量路参数（ES 方言段）。 */
    @Data
    public static class Vector {

        /**
         * 每张索引的向量召回条数；{@code null} = 未设，回退废弃键
         * {@code domainplane.retrieval.vector-top-k}。
         */
        private Integer topK;

        /**
         * 原始余弦阈值（ES 内部 {@code +1.0} 偏移不外泄）；{@code null} = 未设，
         * 回退废弃键 {@code domainplane.retrieval.vector-min-score}。
         */
        private Double minScore;
    }

    /** 关键词路参数（ES 方言段）。 */
    @Data
    public static class Keyword {

        /**
         * 每张索引的关键词召回条数；{@code null} = 未设，回退废弃键
         * {@code domainplane.retrieval.keyword-top-k}。
         */
        private Integer topK;

        /**
         * BM25 的 minimum_should_match；{@code null} = 未设，回退废弃键
         * {@code domainplane.retrieval.minimum-should-match}。
         *
         * 有效区间 40%~50%（ik_smart 把助词也切成词、占额度），别凭直觉调大。
         */
        private String minimumShouldMatch;
    }
}
