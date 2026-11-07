package com.zzkingcc.domainplane.server.model;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户自助填参的四道闸门。
 *
 * <p>"透传"的意思是用户填什么就往请求体里拼什么 —— 但那不等于请求体的每一处都交给用户。
 * 模型名、地址、协议、凭证必须仍由平台掌握：一旦用户能塞 {@code model} 或覆盖鉴权头，
 * 档案就不再是可信的配置单元，删域 / 审计 / 热替换全部失据。这四道闸门划的就是这条线。</p>
 *
 * <p>四道闸门的判据分别来自三个不同的失效模式，不能互相替代：</p>
 * <ul>
 *   <li><b>① 黑名单</b> —— 防的是"移动权限边界"：{@code model} / {@code messages} 这类键拼上去，
 *       请求就不再是"跟这个模型说话"，而是"改写这次调用本身"；鉴权与地址类键同理。</li>
 *   <li><b>② 形状</b> —— 防的是"配了像没配"：嵌套结构、超长字符串、非法键名拼进请求体，
 *       服务商多半直接 400，而用户在卡片上看不出是自己填错。收窄到标量 / 字符串数组 /
 *       一层扁平对象，是为了让"填错了"这件事在<b>保存时</b>就暴露，而不是等到对话时。</li>
 *   <li><b>③ 量</b> —— 防的是无意识的膨胀：档案是存盘的，键数与体积不限会被一层层累积成
 *       请求体里没人看得懂的一坨。</li>
 *   <li><b>④ 标准键冲突</b> —— 防的是"发两遍"：{@code temperature} 这类标准参数走 builder setter
 *       （构建期），而自定义参数是请求体的另一条路。两条路同时发同一个字段，服务端静默收下，
 *       用户以为在调温度，实际生效的是另一路。与其静默，不如在保存时把话说清楚。</li>
 * </ul>
 *
 * <p><b>对话档案一律走这里</b>，包括命中适配的档案：对话的适配 schema 已经下架
 * （参数面改成「标准字段 + 用户自填 JSON」），只剩下向量的 {@code dimensions} 还留在 schema 上。
 * 命中但该族没有 schema 的档案也走这里，所以 {@link ModelAdapterRegistry#validateParams}
 * 与这四道闸门的分界不再是"命中与否"，而是"该族**有没有** schema"。</p>
 */
public final class CustomParamsGate {

    private CustomParamsGate() {
    }

    /** 自定义参数的键数上限 */
    public static final int MAX_KEYS = 32;
    /** 序列化后的整体体积上限（字节） */
    public static final int MAX_BYTES = 4096;
    /** 单个字符串值的长度上限 */
    public static final int MAX_STRING_LENGTH = 1024;
    /** 数组的元素数上限 */
    public static final int MAX_ARRAY_ITEMS = 64;
    /** 一层扁平对象的键数上限 */
    public static final int MAX_OBJECT_KEYS = 16;

    /**
     * 黑名单：这些键一旦被拼进请求体，这次调用的语义或凭据就不再是平台的了。
     *
     * <p>分三类，都是"拼上去 = 夺走控制权"：① 请求骨架（model / messages / stream …）；
     * ② 凭据（api_key / authorization …）；③ 地址与协议（base_url / url / endpoint …）。</p>
     */
    private static final Set<String> BLACKLIST = Set.of(
            "model", "messages", "prompt", "input", "stream", "stream_options",
            "api_key", "apikey", "api-key", "key", "authorization", "auth", "token",
            "bearer", "x-api-key", "secret", "password", "credential",
            "base_url", "baseurl", "base-url", "url", "endpoint", "uri", "host", "api_base");

    /**
     * 标准参数：它们在别处已经有入口（builder setter / 档案老字段），自定义里出现即冲突。
     *
     * <p>只收真正另有入口的三个键：{@code temperature} / {@code max_tokens} 走档案老字段
     * 与卡片上的标准字段，{@code dimensions} 走向量维度专用字段（索引契约）。
     * {@code top_p} / {@code seed} / {@code stop} / {@code response_format} / penalties
     * 这些**曾经**也在这张表里，但它们的入口是适配 schema —— schema 下架后若继续拒，
     * 就成了"哪条路都填不了"的能力净损失，故一并放开，与普通私有参数同待遇。</p>
     *
     * <p>拒绝而不是"后者覆盖前者"：覆盖意味着两条路的优先级成了隐式约定，
     * 而它连在日志里都看不出来 —— 保存时报一句话，比事后查"为什么温度不生效"便宜得多。</p>
     */
    private static final Set<String> STD_CONFLICTS = Set.of(
            "temperature", "max_tokens", "dimensions");

    /** 合法键名：字母或下划线开头，后续允许数字与 . - _（厂商私有参数里点号常见） */
    private static final Pattern KEY_SHAPE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$");

    /**
     * 复核一份自定义参数。
     *
     * @return {@code null} = 通过；否则为可直接展示给用户的拒绝原因
     */
    public static String validate(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }

        // ③ 量（键数与体积）：先看整体，免得对一大坨内容逐个走形状校验
        if (params.size() > MAX_KEYS) {
            return "自定义参数最多 " + MAX_KEYS + " 个键，当前 " + params.size() + " 个";
        }
        int bytes = approxBytes(params);
        if (bytes > MAX_BYTES) {
            return "自定义参数总体积上限 " + MAX_BYTES + " 字节，当前约 " + bytes + " 字节";
        }

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String rawKey = entry.getKey();
            if (rawKey == null || rawKey.isBlank()) {
                return "自定义参数存在空键名";
            }
            String key = rawKey.trim().toLowerCase(Locale.ROOT);

            // ① 黑名单
            if (BLACKLIST.contains(key)) {
                return "自定义参数不允许使用键「" + rawKey + "」：模型名、地址、凭证与请求骨架由平台掌管，"
                        + "不开放给用户填写";
            }
            // ④ 标准键冲突
            if (STD_CONFLICTS.contains(key)) {
                return "自定义参数不允许使用键「" + rawKey + "」：它是标准字段（温度 / 单次最大输出 / 向量维度）"
                        + "，请用卡片上对应的输入框填写"
                        + "（两处都填会让同一个值发两条路，生效的是哪一路不可见）";
            }
            // ② 形状（键名）
            if (!KEY_SHAPE.matcher(rawKey.trim()).matches()) {
                return "自定义参数键名「" + rawKey + "」不合法：只能是字母或下划线开头，"
                        + "后续为字母、数字、点号、连字符或下划线，最长 64 个字符";
            }
            // ② 形状（值）
            String valueError = checkValue(rawKey, entry.getValue(), 0);
            if (valueError != null) {
                return valueError;
            }
        }
        return null;
    }

    /**
     * 值的形状校验：收标量、字符串数组、一层扁平对象，其余一律拒绝。
     *
     * <p>允许"一层扁平对象"而不是只收标量，是因为厂商私有参数里 {@code {type: "json_object"}}
     * 这种写法普遍存在，一律禁掉会把相当一部分合法用法挡在门外。再深一层就没有真实用例了，
     * 反而给"看不出哪里错了"留出空间 —— 所以停在一层。</p>
     */
    private static String checkValue(String key, Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            if (s.length() > MAX_STRING_LENGTH) {
                return "自定义参数「" + key + "」的值超长（上限 " + MAX_STRING_LENGTH + " 字符，当前 "
                        + s.length() + "）";
            }
            return null;
        }
        if (value instanceof Boolean || value instanceof Number) {
            /* NaN / Infinity 不是合法 JSON：Jackson 序列化时会写出裸标识符，
               服务商收到的是一段它无法解析的请求体，而错误文案里看不到是哪个键 */
            if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
                return "自定义参数「" + key + "」的值不是合法数字（NaN / Infinity）";
            }
            if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
                return "自定义参数「" + key + "」的值不是合法数字（NaN / Infinity）";
            }
            return null;
        }
        if (value instanceof Collection<?> items) {
            if (depth > 0) {
                return "自定义参数「" + key + "」嵌套过深：数组只能出现在最外层";
            }
            if (items.size() > MAX_ARRAY_ITEMS) {
                return "自定义参数「" + key + "」的数组最多 " + MAX_ARRAY_ITEMS + " 项，当前 " + items.size() + " 项";
            }
            for (Object item : items) {
                if (!(item instanceof String s)) {
                    return "自定义参数「" + key + "」的数组只允许字符串元素";
                }
                if (s.length() > MAX_STRING_LENGTH) {
                    return "自定义参数「" + key + "」的数组元素超长（上限 " + MAX_STRING_LENGTH + " 字符）";
                }
            }
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            if (depth > 0) {
                return "自定义参数「" + key + "」嵌套过深：对象只能有一层，且不能再套对象 / 数组";
            }
            if (map.size() > MAX_OBJECT_KEYS) {
                return "自定义参数「" + key + "」的对象最多 " + MAX_OBJECT_KEYS + " 个键，当前 " + map.size() + " 个";
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    return "自定义参数「" + key + "」的对象键名必须是字符串";
                }
                String nestedError = checkValue(key + "." + e.getKey(), e.getValue(), depth + 1);
                if (nestedError != null) {
                    return nestedError;
                }
            }
            return null;
        }
        return "自定义参数「" + key + "」的值类型不支持（" + value.getClass().getSimpleName()
                + "）：只允许字符串、数字、布尔、字符串数组与一层扁平对象";
    }

    /**
     * 体积估算：不序列化，只按"键长 + 值的字符串长度"粗算。
     *
     * <p>刻意不引入 Jackson 把值序列化一遍：序列化会顺带执行 getter，而自定义参数的取值来自
     * 用户提交的 JSON，理论上可以是 Jackson 反序列化出的任意类型。为算个体积去触发未知代码，
     * 与"闸门本身该是纯计算"相悖。粗算偏大就够了 —— 上限是防膨胀，不是精确计费。</p>
     */
    private static int approxBytes(Map<String, Object> params) {
        int total = 0;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            total += lengthOf(entry.getKey()) + lengthOf(entry.getValue()) + 8;
        }
        return total;
    }

    /** 单个值按"写成 JSON 大概占多少"粗算；未知类型按一个保守常数计，不会漏算成 0 */
    private static int lengthOf(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof String s) {
            return s.length() + 2;
        }
        if (value instanceof Collection<?> items) {
            int total = 2;
            for (Object item : items) {
                total += lengthOf(item) + 1;
            }
            return total;
        }
        if (value instanceof Map<?, ?> map) {
            int total = 2;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                total += lengthOf(e.getKey()) + lengthOf(e.getValue()) + 3;
            }
            return total;
        }
        return String.valueOf(value).length();
    }

    /** 拒绝原因里要带的合法键提示（黑名单之外的说明文案共用一份口径） */
    public static List<String> blacklistedKeys() {
        return BLACKLIST.stream().sorted().toList();
    }

    /** 标准参数清单：卡片提示"这些键有专门字段"时用，与 {@link #validate} 同一份来源 */
    public static List<String> stdKeys() {
        return STD_CONFLICTS.stream().sorted().toList();
    }
}
