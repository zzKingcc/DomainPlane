package com.zzkingcc.domainplane.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具的高级可选参数 —— 承接"偶尔要写、但绝不该默认出现在每个工具上"的字段。
 *
 * <p>为什么单独一个注解：这些字段若塞进 {@link Tool}，就会让每个工具声明都变成
 * "大多数是空、偶尔填一项"的长尾列表。它们放在这里，绝大多数工具<b>永远不用看</b>。</p>
 *
 * <h2>按参数名对应，不做位置对齐</h2>
 * <p>三个字段一律写成 {@code 参数名=值}。<b>位置对齐在参数增删或调序时会静默错位</b>，
 * 而编译器不会提醒 —— 宁可多写一次参数名。</p>
 *
 * <p>名字既可以是方法形参名，也可以是参数 DTO 展开后的<b>字段名</b>（两者一视同仁）。
 * 匹配到同名的参数会一起生效，因此参数名保持唯一最稳妥。</p>
 *
 * <h2>三个字段各自的落点</h2>
 * <ul>
 *   <li>{@link #example()} —— 进 {@code ToolDescriptor.Param.example}，并<b>附加到模型可见的参数说明</b>末尾
 *       （如 {@code 订单号，如 FR2024001（示例：FR2024001）}）。之所以合并进说明，是因为底层模型
 *       schema 只有 description 一个自由文本位，没有独立的 example 槽。</li>
 *   <li>{@link #allowValues()} —— 进 {@code Param.allowValues}，并作为模型可见 schema 的 {@code enum} 白名单。</li>
 *   <li>{@link #sensitive()} —— 进 {@code Param.sensitive}，并在<b>工具调用事件与审批(中断) payload</b>中
 *       把对应参数的<b>值</b>掩码成 {@code ***}。注意掩码的是值，不是参数名本身。</li>
 * </ul>
 *
 * <pre>{@code
 * @Tool(desc = "按订单号改绑手机号")
 * @ToolAdvanced(example = {"orderNo=FR2024001"},
 *               allowValues = {"channel=SMS|APP"},
 *               sensitive = {"phone"})
 * public String rebind(@ToolParam("订单号") String orderNo,
 *                      @ToolParam("渠道") String channel,
 *                      @ToolParam("新手机号") String phone) { ... }
 * }</pre>
 *
 * @author zzkingcc
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ToolAdvanced {

    /**
     * 参数示例，按 {@code 参数名=示例值} 给出，如 {@code {"orderNo=FR2024001"}}。
     */
    String[] example() default {};

    /**
     * 枚举白名单，按 {@code 参数名=值1|值2} 给出，如 {@code {"status=PAID|REFUNDED"}}。
     */
    String[] allowValues() default {};

    /**
     * 需要脱敏的参数名清单：事件与审批 payload 中该参数只显示掩码。
     */
    String[] sensitive() default {};
}
