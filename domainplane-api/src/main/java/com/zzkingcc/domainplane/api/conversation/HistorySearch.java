package com.zzkingcc.domainplane.api.conversation;

import java.util.List;

/**
 * 会话历史的倒排检索（方案 §6.2）：闭轮时写索引，装配层与 {@code recall_history} 查索引。
 *
 * <p>与 {@link ConversationArchive} 的分工：归档是<b>真相</b>（轮记录原文，永不淘汰），
 * 本接口是<b>派生数据</b>（词根 → turnId）。派生意味着可以整个扔掉重建：
 * 重放归档即可，所以写失败只告警、查不到就降级，都不影响正确性。</p>
 *
 * <p><b>键形如</b>：
 * {@code domainplane:conv:idx:{域}|{session}:{词根}}（ZSET，member/score = turnId）、
 * {@code domainplane:conv:terms:{域}|{session}}（Set，登记本会话出现过的词根，
 * 供精确删除，避开按通配符 SCAN：sessionId 可能含 glob 字符）。</p>
 *
 * @author zzkingcc
 */
public interface HistorySearch {

    /** 单轮写入索引的词根上限（与 common 的分词器同口径，防止两处漂移） */
    int MAX_TERMS_PER_TURN = 200;

    /** 每个词根取多少候选（该词根最新的 N 轮） */
    int CANDIDATES_PER_TERM = 50;

    /** 一次查询合并后的候选上限（硬截断，防词根过多把一次查拖慢） */
    int MAX_CANDIDATES = 300;

    /** 索引查无结果时的降级：子串扫描最近这么多轮 */
    int FALLBACK_TAIL = 200;

    /**
     * 把一轮写进倒排索引（闭轮时调用，一个 pipeline）。
     *
     * <p>索引文本 = digest + 用户输入 + 最终回答 + 工具名。<b>思考不进索引</b>：
     * 太长且噪声高，摘要已覆盖其结论。</p>
     *
     * <p>失败由实现方按"只告警 + 不抛"处理：索引是派生数据，归档记录已经落盘，
     * 这一轮只是不能被倒排列中，靠 {@link #FALLBACK_TAIL} 子串扫描兜底。</p>
     *
     * @param sessionKey 会话键（域|sessionId）
     * @param record     已闭合的轮记录；开放轮 / 中止轮调用方不应传进来
     */
    void indexTurn(String sessionKey, TurnRecord record);

    /**
     * 检索相关轮次，返回卡片（不含全文）。
     *
     * <p>链路：分词 → 每词根取候选 → 合并 → 应用内打分
     * （Σ 命中词根权重 + 新近衰减 {@code 0.5^(轮龄/50)}）→ top-k → 读轮记录 → 组装卡片。</p>
     *
     * <p>任何一步不达都不抛：词根为空 / 索引键不存在 / 读档失败 → 走
     * {@link #FALLBACK_TAIL} 子串扫描；仍然没有 → 返回空列表。</p>
     *
     * @param sessionKey 会话键（域|sessionId）
     * @param query      查询文本（通常是本轮用户消息）
     * @param limit      返回条数上限
     * @return 按相关度从高到低排列的卡片；可能为空，但不会为 {@code null}
     */
    List<HistoryCard> search(String sessionKey, String query, int limit);

    /**
     * 删除某个域下的全部索引键（删域级联清理用）。
     *
     * <p>归档含全量对话，漏删等于"删域成功"之后一句旧话还能把整段历史勾回来
     * （方案 §10-R5）。失败必须抛：删域流程靠它中止。</p>
     *
     * @return 删除的键数
     */
    int deleteByDomain(String domain);
}
