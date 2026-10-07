package com.zzkingcc.domainplane.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 类级默认域：这个类里所有 {@link Tool} 方法共同注入指定域。
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ToolDomains {

    /**
     * 默认可用域，每项都是从根域出发的完整路径
     */
    String[] value() default {};
}
