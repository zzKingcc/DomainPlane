package com.zzkingcc.domainplane.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述工具参数的语义注解 —— 可标在形参前，也可标在参数 DTO 的字段上
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Documented
public @interface ToolParam {

    /** 参数说明 */
    String value() default "";

    /** 参数名；留空则取形参名 */
    String name() default "";

    /** 是否必须提供给模型说明参数 */
    boolean required() default true;
}
