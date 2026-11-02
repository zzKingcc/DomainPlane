package com.zzkingcc.domainplane.domain.rag.model;

import dev.langchain4j.rag.content.Content;

import java.util.List;

/**
 * 一份排名表：某个来源上某一路模态的召回结果，已按该路自己的分数降序排列。
 *
 * 多来源召回时，每个「来源 × 模态」通道产出一份排名表。融合策略据此：
 *
 *   • 分数制（{@code DefaultFusionStrategy}）—— 按模态把多表拼回两路，再归一化加权；
 *   • 排名制（{@code RrfFusionStrategy}）—— 只看各表内的名次，天然免疫跨来源分数量纲不一致。
 *
 * @param sourceId    来源标识：**知识来源**（域）。{@code AdaptiveFusionStrategy} 按它的
 *                    去重计数判"有命中的来源数"，是分数制 / RRF 二选一的总开关——
 *                    所以它不许漂成物理位置：混合后端下同一域的关键词路与向量路
 *                    必须算同一个来源，否则一律走 RRF，分数制白留一轨（一审修订 A1）。
 *                    单后端下它与 collection 名一一对应，与改造前逐字节等价
 * @param locationId  物理位置（{@code backendId|collection}），只用于日志、缓存 key
 *                    与排障；融合层不读它（A1：融合层零改动）
 * @param modality    模态
 * @param depth       该来源距查询域的距离：0 = 查询域自身，1 = 父域，2 = 祖父域……
 *                    合成单来源（通配检索）时恒为 0。排名制融合据此做层级衰减，
 *                    让"本域自有知识"优先于"从祖先继承来的知识"
 * @param contents    该来源该模态的召回结果，按分数降序
 *
 * @author zzkingcc
 */
public record RankedList(String sourceId, String locationId, Modality modality, int depth, List<Content> contents) {

    public RankedList {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    /**
     * 旧四参构造：{@code locationId} 缺省为 {@code sourceId}。
     *
     * 保留是为了不打断既有调用方（测试与单来源场景）：单后端下二者同值，
     * 语义无损。新代码（多后端）请显式传 locationId。
     */
    public RankedList(String sourceId, Modality modality, int depth, List<Content> contents) {
        this(sourceId, sourceId, modality, depth, contents);
    }

    /** 单来源（或来源即查询域自身）的简写。 */
    public RankedList(String sourceId, Modality modality, List<Content> contents) {
        this(sourceId, sourceId, modality, 0, contents);
    }

    /** 该表是否有命中 —— 空表不参与权重分摊，否则会把权重摊给空气。 */
    public boolean isEmpty() {
        return contents.isEmpty();
    }
}
