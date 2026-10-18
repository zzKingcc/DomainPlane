package com.zzkingcc.domainplane.api.conversation;

import java.util.Optional;

/**
 * 会话滚动摘要的存取（方案 §4.3 / §5.4）。
 *
 * <p>摘要与倒排索引不同：索引是<b>可重建</b>的派生数据（重放归档即可复原），而摘要是
 * <b>有损压缩</b>的产物：生成它的那次模型调用的结果丢了就没了，重放归档只会得到另一份
 * 措辞不同的摘要。所以它按"档案级"对待（不设 TTL），只有删域时随会话一起清掉。</p>
 *
 * <p><b>为什么单独存、不塞进归档</b>：归档的单位是"轮"，每轮一条记录、只增不改；
 * 而摘要是一条随会话推进不断被改写的值。塞进轮记录会破坏"轮记录不可变"这条前提
 * （闭轮 = LSET 同位写入，之后不再动）。</p>
 *
 * <p>实现可以落在任意介质（Redis Hash / 文件 / 内存），装配层只认这个契约。</p>
 *
 * @author zzkingcc
 */
public interface ConversationSummaryStore {

    /**
     * 读该会话当前的滚动摘要。
     *
     * <p>取不到返回空：调用方要区分"还没有摘要"（退化为 digest 拼接）与
     * "摘要服务故障"（如实告警并按无摘要处理）。</p>
     */
    Optional<Stored> load(String sessionKey);

    /** 写入/覆盖该会话的滚动摘要 */
    void save(String sessionKey, Stored summary);

    /**
     * 删除某个域下的全部摘要（删域级联清理用）。
     *
     * <p>失败必须抛：摘要不清掉的话，同路径域将来重建，上一段对话的摘要会原样复活，
     * 模型开局就"记得"一段用户以为已经删掉的内容。</p>
     *
     * @return 删除的键数
     */
    int deleteByDomain(String domain);

    /**
     * 一份滚动摘要。
     *
     * @param text       摘要正文（模型生成的有损压缩）
     * @param upToTurnId 已经并入这份摘要的最大轮号；{@code <= 0} 表示还没摘要过任何一轮。
     *                   装配时用它算出"哪些滑出窗口的轮次还没进摘要"，再按 digest 补齐。
     */
    record Stored(String text, long upToTurnId) {
    }
}
