package com.zzkingcc.domainplane.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个方法注册为 Agent 可调用的工具。
 *
 * <p>设计取向：<b>只有一个必填字段</b>（{@link #desc()}），其余按需写。
 * 凡是"默认值够用"或"当前实现不生效"的字段，都不出现在这个注解上。</p>
 *
 * <pre>{@code
 * // 最小写法
 * @Tool(desc = "按订单号查询订单状态。用户追问发货/物流时调用")
 * public OrderVO queryOrder(String orderNo) { ... }
 *
 * // 带治理与归属（域必须是从根域 default 出发的完整路径）
 * @Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
 *       domains = {"default.admin"}, effect = Tool.Effect.WRITE,
 *       approval = Tool.Approval.ALWAYS, approvalReason = "退款需人工确认")
 * public String refundOrder(@ToolParam("订单号") String orderNo,
 *                           @ToolParam("退款金额，单位：元") BigDecimal amount) { ... }
 * }</pre>
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Tool {

    /**
     * 给模型的用途说明 —— <b>唯一必填字段</b>。
     *
     * <p>模型靠它决定「何时调用 / 何时不要调用」，所以它比参数描述更重要：
     * 没有它，工具等于不存在。建议写法：一句话说清业务动作 + 触发时机。</p>
     */
    String desc();

    /** 工具名；留空取方法名。全局唯一，重名注册直接失败 */
    String value() default "";

    /**
     * 本工具<b>允许被哪些域使用</b> —— 授权边界，不是展示标签。
     *
     * <p>每一项都是<b>从根域出发的完整路径</b>（{@code default.sales.order}），没有通配写法。
     * 判定按<b>累加</b>：声明命中某个域，或命中它的任一祖先，该域就可见 ——
     * 挂在父域上的工具，其所有后代域都能用。可继承类级 {@link ToolDomains}。</p>
     *
     * <p>留空 = 挂在根域 {@code default}；根域在每个域的祖先链里，因此留空即<b>全树可见</b>。
     * 想收紧就显式写出完整路径。</p>
     */
    String[] domains() default {};

    /** 副作用等级；{@code WRITE} / {@code DESTRUCTIVE} 建议同时配 {@link #approval()} */
    Effect effect() default Effect.READ;

    /** 是否需要人工确认（调用前中断，等宿主批准） */
    Approval approval() default Approval.NONE;

    /** 展示给审批人的原因；{@code approval != NONE} 时建议填写 */
    String approvalReason() default "";

    /**
     * 副作用等级
     */
    enum Effect {
        /** 只读，无副作用 */
        READ,
        /** 写操作，可回滚或可重复执行 */
        WRITE,
        /** 破坏性操作，建议配审批 */
        DESTRUCTIVE
    }

    /**
     * 审批模式
     *
     * <p>只保留两种：现有实现里 {@code CONDITIONAL} 与 {@code ONCE_PER_SESSION} 都与
     * {@code ALWAYS} 等价，摆出来只会让人以为它们有区别。</p>
     */
    enum Approval {
        /** 不需要确认 */
        NONE,
        /** 每次调用前都需要确认 */
        ALWAYS
    }
}
