package com.zzkingcc.domainplane.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个方法注册为 Agent 可调用的工具。
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Tool {

    /**
     * 给模型的工具用途说明 —— 必填
     */
    String desc();

    /** 工具名；留空取方法名，不能重复 */
    String value() default "";

    /**
     * 本工具允许被哪些域使用
     * 留空，挂在根域
     */
    String[] domains() default {};

    /** 副作用等级 */
    Effect effect() default Effect.READ;

    /** 是否需要人工确认 */
    Approval approval() default Approval.NONE;

    /** 展示给审批人的人工确认原因 */
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
     */
    enum Approval {
        /** 不需要确认 */
        NONE,
        /** 每次调用前都需要确认 */
        ALWAYS
    }
}
