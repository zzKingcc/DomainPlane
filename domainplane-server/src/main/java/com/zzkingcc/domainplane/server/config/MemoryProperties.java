package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆配置
 *
 * 前缀 {@code domainplane.memory}。阈值统一外置在 yaml，便于调整窗口而无需重新编译。
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.memory")
public class MemoryProperties {

    /** 单会话保留的消息条数上限(约等于 maxMessages/2 轮问答) */
    private int maxMessages = 100;

    /** 单会话消息累计 Token 估算上限 */
    private int maxTokens = 30000;

    /** 会话记忆保留期；{@code null} = 永久不过期（默认）。
     *  记忆是长期存储（靠 Redis RDB+AOF 保住），按缓存对待并不合适：满了也不丢最旧的消息
     *  （静默丢历史会让用户"以为还记得"），更不拒绝开轮，给模型看多少由装配预算裁。 */
    private Duration ttl = null;

    /** 图检查点保留时长;中断后未 resume 的会话靠此兜底清理 */
    private Duration checkpointTtl = Duration.ofHours(24);

    /* ---- M2 召回装配（ContextAssembler）----
       只约束"这轮给模型看多少"，不约束"会话有多少"：会话无限增长，
       到不了上限就逐级降级（砍卡片 → 缩窗口 → 截摘要），永不拒绝开轮。 */

    /** 总装配预算（token） */
    private int assemblyBudget = 24000;

    /** 近况窗口预算占总预算的比例 */
    private double assemblyWindowShare = 0.6;

    /** 早期摘要段预算（token） */
    private int assemblySummaryBudget = 2000;

    /** 近况窗口轮数（一轮 = 提问 + 回答） */
    private int assemblyWindowTurns = 8;

    /** 相关轮卡片条数上限 */
    private int assemblyMaxCards = 5;

    /* ---- M4 召回调优（分词口径）----
       命中率数据在 /admin/metrics 的 recall* 一组里；调这里之前先看数据。
       权重只在查询打分时生效（改完即时生效）；追加停用词同时决定写索引与查询两侧的切词，
       改动后已建索引需重放归档重建。 */

    /** 拉丁/数字整词权重 */
    private int recallWordWeight = 3;

    /** CJK 二字词根权重 */
    private int recallBigramWeight = 2;

    /** 孤立单字权重 */
    private int recallUnigramWeight = 1;

    /** 追加停用词（与内置表取并集）。只做加法：内置表是索引口径的一部分，不可被替换 */
    private List<String> recallExtraStopWords = new ArrayList<>();

    /* ---- M4 滚动摘要（方案 §5.4 方式 B：闭轮后异步 + 阈值门）----
       默认关闭：开启后每积够若干轮就多一次模型调用（成本与延迟都真金白银）。
       关掉不是"没有摘要"，而是退化为 digest 拼接（M2 行为）：历史一条都不会丢。 */

    /** 是否启用模型生成的滚动摘要 */
    private boolean summaryEnabled = false;

    /** 攒够多少"滑出窗口且未进摘要"的轮次才发起一次摘要调用 */
    private int summaryMinNewTurns = 4;

    /** 单次摘要最多喂多少条老轮次素材（成本上界） */
    private int summaryMaxMaterialTurns = 20;

    /** 摘要单次输出上限（token）：摘要段预算只有 ~2000，不设限就可能生成一篇长文 */
    private int summaryMaxOutputTokens = 400;

    /** 摘要线程池大小（同会话由单飞保证串行，多线程只是为了不同会话互不阻塞） */
    private int summaryWorkers = 2;
}
