package com.zzkingcc.domainplane.api.conversation;

import java.util.List;
import java.util.Optional;

/**
 * 会话归档层的 SPI 契约：按"轮"存全流程，无淘汰、无封顶（方案 §4）。
 *
 * <p>放在契约层（api）而不是运行层：实现落在基础设施层（Redis），
 * 而基础设施看不到运行层：契约必须落在两边都够得着的地方。</p>
 *
 * <p><b>三层分工里它只负责"存得住、找得回"</b>，不参与每轮请求装配（那是召回层的事），
 * 也不对模型可见（模型要挖细节走 {@code read_history} / {@code recall_history} 工具）。</p>
 *
 * <p><b>开轮 / 闭轮语义</b>（与现有停止/异常回滚路径对齐）：</p>
 * <ul>
 *   <li>开轮：轮开始 {@link #openTurn} 落一条 {@code finishedAt=null} 的开放轮，
 *       返回值即 {@code turnId}：用户输入即刻落盘，回答慢慢跑；</li>
 *   <li>闭轮：轮成功 {@link #closeTurn} 原位（{@code index = turnId - 1}）补全
 *       answer / flow / flags / digest / usage；</li>
 *   <li>停止 / 异常 / 审批被拒：{@link #abortTurn} / {@link #rejectTurn} 标记后闭合，
 *       <b>保留现场，不删</b>。回滚掉的是"这一轮问过了但没答"，而现场本身就是档案；</li>
 *   <li>崩溃恢复：下一次开轮前 {@link #recoverStaleOpenTurn}，发现上一条仍是未闭合开放轮
 *       （进程崩在闭轮之前）先标 aborted 再开新轮：归档里不允许出现两条开放轮；</li>
 *   <li>唯一删除语义：{@link #deleteTurn} 只用于"开放轮写了但图编排压根没起来"
 *       （构造请求前就抛）：那时它等于"没发生过"。</li>
 * </ul>
 *
 * <p><b>turnId 与存储下标的关系</b>：{@code turnId} 从 1 起单调递增，
 * 存储下标恒为 {@code turnId - 1}。这条不变式依赖"同一会话的开轮是串行的"
 * 由编排层的同会话并发控制（一个会话同时只跑一轮）保证，
 * {@code resume} 不开新轮，不破坏它。</p>
 *
 * <p><b>失败语义</b>：归档是档案不是请求链的前置条件。写失败由调用方降级
 * （告警 + 本轮不归档），读失败返回空/降级，都不让一轮对话失败。
 * 唯一例外是 {@link #deleteByDomain}（删域级联）：失败必须抛，
 * 否则"删除成功"会把含全量对话的归档静默留下（方案 §10-R5）。</p>
 *
 * @author zzkingcc
 */
public interface ConversationArchive {

    /**
     * 单个元素的存档上限（字符）：一个 TOOL_RESULT 或一段半截回答超过它就存截断版。
     *
     * <p>防的是"一次工具返回把整条轮记录撑爆"：归档不是日志系统的替身。
     * 定义在契约层是为了让"抽取侧（运行层）"与"落库侧（基础设施层）"用同一个数：
     * 两层互相看不见，各写一份迟早分叉。</p>
     */
    int MAX_ELEMENT_CHARS = 8192;

    /**
     * 开一轮：落一条开放轮（{@code finishedAt=null}），返回本轮 {@code turnId}。
     *
     * @param sessionKey 域|sessionId
     * @param user       本轮用户输入（媒体只存引用）
     * @return turnId（从 1 起单调递增）；失败抛异常，由调用方降级
     */
    long openTurn(String sessionKey, UserInput user);

    /**
     * 闭一轮：原位补全轮记录（answer / flow / flags / digest / usage）。
     *
     * <p>调用方应先 {@link #turnAt} 确认该轮存在且 turnId 一致（防写坏别轮）；
     * 本方法只做同位写入。</p>
     *
     * @param record 完整的闭轮记录（{@code turnId} / {@code sessionKey} 必须与入参一致）
     */
    void closeTurn(String sessionKey, long turnId, TurnRecord record);

    /**
     * 中止一轮：标记 {@code aborted=true} 后闭合，现场保留。
     *
     * @param reason        中止原因（存为 ERROR 流程事件）
     * @param partialAnswer 已产生的半截回答文本；为空则不留
     */
    void abortTurn(String sessionKey, long turnId, String reason, String partialAnswer);

    /**
     * 审批被拒而闭合：上一轮停在审批点、用户没回复审批而是直接开了新对话。
     *
     * <p>与 {@link #abortTurn} 的差别只在 {@code approvalOutcome=REJECTED} 标记。</p>
     */
    void rejectTurn(String sessionKey, long turnId);

    /**
     * 删掉一条开放轮：只用于"写了但图编排压根没起来"，那时它等于没发生过。
     *
     * <p>幂等且带校验：尾部元素不是这条开放轮时拒绝删（宁可留下一条 aborted，
     * 也不能删错元素）。</p>
     */
    void deleteTurn(String sessionKey, long turnId);

    /** 档里最后一条开放轮（不存在返回 {@link Optional#empty()}）。不变式下至多一条，且是尾部 */
    Optional<TurnRecord> findOpenTurn(String sessionKey);

    /**
     * 按轮号取一条。
     *
     * @return 该轮存在才返回；turnId 对不上（数据被外部改动）按不存在处理
     */
    Optional<TurnRecord> turnAt(String sessionKey, long turnId);

    /** 最近 {@code limit} 轮（由旧到新）；坏元素跳过并告警 */
    List<TurnRecord> recentTurns(String sessionKey, int limit);

    /**
     * 取 [{@code fromInclusive}, {@code toInclusive}] 区间的轮（由旧到新）。
     *
     * <p>越界自动收敛：{@code to} 超过总轮数按总轮数，{@code from} 超过总轮数返回空列表。
     * 坏元素跳过并告警。</p>
     */
    List<TurnRecord> turnsBetween(String sessionKey, long fromInclusive, long toInclusive);

    /**
     * 崩溃恢复：若最后一条是未闭合开放轮，标记 aborted 后闭合。
     *
     * @return 1 = 恢复了一条；0 = 没有待恢复的开放轮
     */
    int recoverStaleOpenTurn(String sessionKey);

    /** 清空一个会话的归档（记忆清除的级联；M3 起连同摘要 / 倒排一起清） */
    void clearSession(String sessionKey);

    /**
     * 删除某个域下全部会话的归档（删域级联清理用）。
     *
     * <p>归档含全量对话，漏删 = 删域后一句旧话把它勾回来（方案 §10-R5）。
     * 失败抛异常（删域流程必须能中止），空域拒绝执行。</p>
     *
     * @return 删除的键数
     */
    int deleteByDomain(String domain);
}
