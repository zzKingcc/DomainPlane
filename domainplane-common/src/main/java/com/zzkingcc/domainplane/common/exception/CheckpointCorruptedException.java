package com.zzkingcc.domainplane.common.exception;

import com.zzkingcc.domainplane.api.code.ErrorCode;

/**
 * 检查点<b>数据损坏</b>（读得出来键、解不出内容）。
 *
 * <p>它与"Redis 连不上 / 超时"是两种完全不同的失败，处理方式也必须不同：</p>
 * <ul>
 *   <li>瞬时故障：重试同一条请求有可能成功，整轮拒绝并标记可重试是对的。</li>
 *   <li>数据损坏：重试一万次也是同一个坏键，这个会话会<b>永久不可用</b>，
 *       正确动作是把坏键清掉让会话自愈（清掉之后下一轮就是全新的空检查点）。</li>
 * </ul>
 *
 * <p>清键在安全侧：断点没了，那个"待审批"的动作就永远不会被执行，等价于审批被拒。
 * 危险的是反过来：读不出来却当成"没有待审批"，会让本该停下等授权的一轮以"完成"收尾。</p>
 *
 * @author zzkingcc
 */
public class CheckpointCorruptedException extends ChatMemoryException {

    public CheckpointCorruptedException(String detail, Throwable cause) {
        super(ErrorCode.CHECKPOINT_ERROR, detail, cause);
    }
}
