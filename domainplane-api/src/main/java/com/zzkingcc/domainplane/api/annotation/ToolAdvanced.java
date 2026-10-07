package com.zzkingcc.domainplane.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具参数的增强描述
 * 对@ToolParam的补充，提供更详细的参数说明。
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ToolAdvanced {

    /**
     * 参数示例值，按 {参数名=示例值} 给出，如 {"orderNo=FR2024001"}。
     */
    String[] example() default {};

    /**
     * 参数约束值，只能取指定值，按 {参数名=值1|值2} 给出，如 {"status=PAID|REFUNDED"}。
     */
    String[] allowValues() default {};

    /**
     * 需要脱敏的参数名清单：在日志、事件、审批 payload 中该参数只显示掩码。
     */
    String[] sensitive() default {};
}
