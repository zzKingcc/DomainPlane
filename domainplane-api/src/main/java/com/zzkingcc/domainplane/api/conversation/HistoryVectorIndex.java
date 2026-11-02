package com.zzkingcc.domainplane.api.conversation;

import java.util.List;

/**
 * 归档轮次的向量索引（F04-b：历史记忆语义化）。
 *
 * <p>与 {@link HistorySearch}（关键词倒排）对称的另一半：<b>关键词路认词形、向量路认语义</b>。
 * 只有关键词时，"退款流程走多久"勾不回"退款需要几个工作日"那一轮——
 * 字面无一重合，但说的同一件事。这正是文档里"倒排索引 30 天过期后降级子串扫描"之外
 * 另一个更隐蔽的召回缺口：<b>不是索引没了，是词对不上</b>。</p>
 *
 * <p>与关键词索引同属<b>派生数据</b>：归档是真相，向量可整体丢弃重建
 * （重放归档 + 重新嵌入即可），所以写失败只告警、查不到就退化成纯关键词检索。</p>
 *
 * <p><b>键形如</b> {@code domainplane:conv:vec:{域}|{session}}（Hash，field = turnId）。</p>
 *
 * @author zzkingcc
 */
public interface HistoryVectorIndex {

    /** 每会话最多留多少轮的向量（超出即从最老的一端裁；老到这个程度的轮次靠关键词与摘要兜底） */
    int MAX_TURNS_PER_SESSION = 500;

    /**
     * 最低余弦相似度：低于它的命中一律当噪声丢掉。
     *
     * <p><b>为什么必须有这道闸</b>：向量检索永远能返回一个"最接近的"，哪怕全部都不相关——
     * 余弦 0.03 的那轮也会排在第一位，因为它总得有个第一。不设阈值的话，
     * 语义路每次都往卡片段里塞几条"勉强算最接近"的老轮次，反而把真正相关的挤下去
     * （卡片段只有 5 张的位置）。<b>检索宁可空手，也不要拿噪声充数</b>：
     * 空手有关键词路与子串兜底顶着，噪声没有。</p>
     */
    float MIN_SIMILARITY = 0.2f;

    /**
     * 写一回合的向量。
     *
     * @param sessionKey 会话键（域|sessionId）
     * @param turnId     轮号
     * @param vector     该轮的向量（由调用方嵌好）；{@code null} / 空则跳过
     */
    void index(String sessionKey, long turnId, float[] vector);

    /**
     * 按向量相似度取最相关的若干轮，<b>相似度高在前</b>。
     *
     * @param sessionKey   会话键
     * @param queryVector  查询向量
     * @param limit        条数上限
     * @return 轮号列表；可能为空，但不会为 {@code null}
     */
    List<Long> search(String sessionKey, float[] queryVector, int limit);

    /** 某会话已建向量的轮数（运维可见：体量监控与"索引有没有建起来"的自查入口） */
    int size(String sessionKey);

    /** 删除某会话的向量索引 */
    int deleteBySession(String sessionKey);

    /** 删除某域下的全部向量索引键（删域级联用） */
    int deleteByDomain(String domain);
}
