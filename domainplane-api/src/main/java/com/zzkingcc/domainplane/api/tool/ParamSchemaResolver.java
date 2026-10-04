package com.zzkingcc.domainplane.api.tool;

import com.zzkingcc.domainplane.api.annotation.ToolAdvanced;
import com.zzkingcc.domainplane.api.annotation.ToolParam;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 方法签名 + 注解 → 参数结构（含 DTO 递归展开）。
 *
 * <p>为什么单独抽出来：<b>服务端（本地 Bean 工具）与工具实例 SDK 各有一份扫描器</b>，
 * 两边如果各自实现一遍，同一段工具代码搬到另一侧就会得到不同的参数 schema ——
 * 这正好违背"工具代码在两侧搬迁不用改一个字"的承诺。这里就是那份<b>共用的真相</b>。</p>
 *
 * <p>只产出平台自己的中间结构（{@link ToolDescriptor.Param} 树），<b>不依赖任何模型框架</b>；
 * 两端各自把它转成自己需要的 schema（服务端转 langchain4j Schema，工具实例转上报 JSON）。</p>
 *
 * <p>支持两种载体：形参前的 {@code @ToolParam}，以及参数 DTO 字段上的 {@code @ToolParam}；
 * 形参优先（就近覆盖）。</p>
 *
 * <p>方法级的 {@link ToolAdvanced} 在树建好之后按<b>参数名</b>统一套上（示例 / 枚举白名单 / 敏感），
 * 名字既可是形参名也可是 DTO 展开出的字段名 —— 因此 {@code @ToolAdvanced} 的约定是"按名字对应，
 * 不做位置对齐"。</p>
 *
 * @author zzkingcc
 */
public final class ParamSchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(ParamSchemaResolver.class);

    /**
     * 嵌套展开的深度上限 —— 超过即停止展开。
     * 深结构会让模型难以正确构造，循环引用更是会在扫描期爆栈，两者都必须挡住。
     */
    public static final int MAX_DEPTH = 5;

    /** 未写说明时的占位文案；{@link #hasDescription} 以它为判据 */
    private static final String UN_DESCRIBED = "（未描述）";

    private ParamSchemaResolver() {
    }

    /**
     * 解析一个方法的全部参数。
     */
    public static List<ToolDescriptor.Param> resolve(Method method) {
        Parameter[] parameters = method.getParameters();
        List<ToolDescriptor.Param> out = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String name;
            if (annotation != null && !annotation.name().isBlank()) {
                name = annotation.name().trim();
            } else {
                try {
                    name = fallbackParamName(parameter, i);
                } catch (IllegalArgumentException e) {
                    // 补上方法标识：光说"第 N 个参数"没人知道是哪个工具
                    throw new IllegalArgumentException(e.getMessage()
                            + "（方法 " + method.getDeclaringClass().getSimpleName()
                            + "#" + method.getName() + "）", e);
                }
            }
            // Optional<T> 天然可选；其余按注解，缺省必填
            boolean required = (annotation == null || annotation.required())
                    && parameter.getType() != Optional.class;
            out.add(resolveOne(name, parameter.getParameterizedType(), annotation,
                    required, 0, new HashSet<>()));
        }

        // 方法级 @ToolAdvanced 在树建好后按参数名套上（示例 / 白名单 / 敏感）；
        // 说明的"示例后缀"也在这一步统一处理，两侧（服务端与工具实例）必须得到同一段文本
        return List.copyOf(applyOverrides(out, ParamOverrides.of(method)));
    }

    /** 递归解析一个参数（可能是 DTO） */
    private static ToolDescriptor.Param resolveOne(String name,
                                                   Type generic,
                                                   ToolParam annotation,
                                                   boolean required,
                                                   int depth,
                                                   Set<Class<?>> visiting) {
        Class<?> raw = rawTypeOf(generic);

        // 白名单先取"类型自带的"（枚举常量）；@ToolAdvanced.allowValues 在整棵树建好后按名字套上
        List<String> allowValues = new ArrayList<>();
        if (raw != null && raw.isEnum()) {
            allowValues.addAll(enumValues(raw));
        }

        String type = allowValues.isEmpty() ? jsonType(raw) : "enum";
        String description = descriptionOf(annotation);

        List<ToolDescriptor.Param> children = List.of();
        List<ToolDescriptor.Param> items = List.of();
        if ("object".equals(type)) {
            children = propertiesOf(raw, depth, visiting);
            if (children.isEmpty()) {
                // 展开不出来只有两种原因：到深度上限了，或撞上循环引用。
                // 与其让模型以为这是个自由对象，不如把原因写进说明里。
                description = description + "（" + (depth >= MAX_DEPTH
                        ? "嵌套超过 " + MAX_DEPTH + " 层，未展开"
                        : "未展开出字段（可能是循环引用或字段全被跳过）") + "）";
            }
        } else if ("array".equals(type)) {
            // 数组元素结构：和 object 的子字段是同一类规则 —— 元素若是 DTO，也要展开，
            // 否则模型在数组里只会看到"字符串"，构造不出元素。没有泛型实参（裸 Collection）则展不开。
            Type elementType = elementTypeOf(generic, raw);
            if (elementType != null && depth < MAX_DEPTH) {
                items = List.of(resolveOne(null, elementType, null, false, depth + 1, visiting));
            }
        }

        return new ToolDescriptor.Param(name, type, description, required,
                List.copyOf(allowValues), "", false, children, items);
    }

    /**
     * 从参数泛型里取出"数组 / 集合的元素类型"。
     *
     * <p>{@code List<Foo>} / {@code Set<Foo>} 取第一个类型实参；{@code Foo[]} 取组件类型；
     * 裸 {@code Collection}（无泛型）或纯数组之外的类型返回 {@code null} —— 元素结构展不开时，
     * 调用方会把数组当成"元素未知"，由下游按字符串兜底。</p>
     */
    private static Type elementTypeOf(Type generic, Class<?> raw) {
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> rawClass
                && Collection.class.isAssignableFrom(rawClass)
                && parameterized.getActualTypeArguments().length > 0) {
            return parameterized.getActualTypeArguments()[0];
        }
        if (raw != null && raw.isArray()) {
            return raw.getComponentType();
        }
        return null;
    }

    /**
     * DTO 的子字段：record 走组件（名字可靠），普通类走声明字段。
     *
     * @return 子字段列表；空列表表示"未展开"
     */
    private static List<ToolDescriptor.Param> propertiesOf(Class<?> raw, int depth, Set<Class<?>> visiting) {
        if (raw == null || depth >= MAX_DEPTH || !visiting.add(raw)) {
            return List.of();
        }
        try {
            if (raw.isRecord()) {
                List<ToolDescriptor.Param> out = new ArrayList<>();
                for (RecordComponent component : raw.getRecordComponents()) {
                    out.add(fieldParam(component.getName(), component.getGenericType(),
                            component.getAnnotation(ToolParam.class), depth, visiting));
                }
                return List.copyOf(out);
            }

            List<ToolDescriptor.Param> out = new ArrayList<>();
            for (Field field : raw.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                out.add(fieldParam(field.getName(), field.getGenericType(),
                        field.getAnnotation(ToolParam.class), depth, visiting));
            }
            return List.copyOf(out);
        } finally {
            visiting.remove(raw);
        }
    }

    private static ToolDescriptor.Param fieldParam(String fallbackName,
                                                   Type generic,
                                                   ToolParam annotation,
                                                   int depth,
                                                   Set<Class<?>> visiting) {
        String name = annotation != null && !annotation.name().isBlank()
                ? annotation.name().trim()
                : fallbackName;
        boolean required = annotation == null || annotation.required();
        return resolveOne(name, generic, annotation, required, depth + 1, visiting);
    }

    /**
     * 参数说明：{@code value} 是唯一来源。没写就是"未描述" ——
     * 不阻断注册，但会由扫描器统计出来并打 WARN（模型只能靠参数名猜）。
     */
    private static String descriptionOf(ToolParam annotation) {
        if (annotation == null || annotation.value().isBlank()) {
            return UN_DESCRIBED;
        }
        return annotation.value().trim();
    }

    /**
     * 把 {@link ToolAdvanced} 按参数名套到已建好的参数树上。
     *
     * <p>递归整棵树：名字既可能是形参名，也可能是 DTO 展开出的字段名，两者一视同仁。
     * 顺带处理"白名单把类型变成 enum"与"示例并入说明"两件事 —— 它们是这三个字段能被模型看见的
     * 唯一途径（底层 schema 只有 description 一个自由文本位，没有独立的 example 槽）。</p>
     */
    static List<ToolDescriptor.Param> applyOverrides(List<ToolDescriptor.Param> params,
                                                     ParamOverrides overrides) {
        List<ToolDescriptor.Param> out = new ArrayList<>(params.size());
        for (ToolDescriptor.Param param : params) {
            List<String> allowValues = overrides.allowValuesOf(param.name());
            boolean gainedOptions = !allowValues.isEmpty();
            String example = overrides.exampleOf(param.name());

            List<ToolDescriptor.Param> children = param.properties().isEmpty()
                    ? param.properties()
                    : applyOverrides(param.properties(), overrides);
            // 数组元素结构里的字段同样要套 @ToolAdvanced（示例 / 白名单 / 敏感），否则
            // DTO 一旦被放进数组，子字段上的治理信息就会凭空消失
            List<ToolDescriptor.Param> items = param.items().isEmpty()
                    ? param.items()
                    : applyOverrides(param.items(), overrides);

            out.add(new ToolDescriptor.Param(
                    param.name(),
                    gainedOptions ? "enum" : param.type(),
                    overrides.describe(param.name(), param.description()),
                    param.required(),
                    gainedOptions ? allowValues : param.allowValues(),
                    example,
                    overrides.isSensitive(param.name()),
                    children,
                    items));
        }
        return out;
    }

    /**
     * 把参数树渲染成"上报 JSON Schema"的 {@link Map} 形式（不依赖任何 JSON 库，纯 {@link Map}）。
     *
     * <p>工具实例侧用它生成要发给服务端的报文；服务端 {@code ToolParamSchema} 认的就是这套键
     * （{@code type / description / example / enum / x-sensitive / properties / required / items}）。
     * 两端共用这一份渲染，保证"工具实例上报的"与"服务端本地扫描的"是同一棵树、同一种格式 ——
     * 同一段工具代码搬到另一侧，模型看到的 schema 一字不差。</p>
     */
    public static Map<String, Object> toWireSchema(List<ToolDescriptor.Param> params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolDescriptor.Param param : params) {
            properties.put(param.name(), toWireNode(param));
            if (param.required()) {
                required.add(param.name());
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        if (!required.isEmpty()) {
            root.put("required", required.toArray(new String[0]));
        }
        return root;
    }

    /** 单个参数 → 上报 JSON 节点（递归保留 object 的 properties 与 array 的 items） */
    public static Map<String, Object> toWireNode(ToolDescriptor.Param param) {
        Map<String, Object> node = new LinkedHashMap<>();
        if (!param.allowValues().isEmpty()) {
            // 白名单把类型抬成 enum（string + enum 取值），与 @ToolAdvanced.allowValues 的口径一致
            node.put("type", "string");
            node.put("enum", new ArrayList<>(param.allowValues()));
        } else {
            node.put("type", param.type());
        }
        node.put("description", param.description());
        if (param.example() != null && !param.example().isBlank()) {
            node.put("example", param.example());
        }
        if (param.sensitive()) {
            // 服务端 ToolParamSchema 认这个键，脱敏清单必须跟着 schema 一起上报
            node.put("x-sensitive", true);
        }
        if ("object".equals(param.type())) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (ToolDescriptor.Param child : param.properties()) {
                properties.put(child.name(), toWireNode(child));
                if (child.required()) {
                    required.add(child.name());
                }
            }
            node.put("properties", properties);
            if (!required.isEmpty()) {
                node.put("required", required.toArray(new String[0]));
            }
        } else if ("array".equals(param.type()) && !param.items().isEmpty()) {
            // 数组元素结构：和 properties 一样不能退化成字符串
            node.put("items", toWireNode(param.items().get(0)));
        }
        return node;
    }

    /**
     * 没写 {@code @ToolParam("名称")} 时的参数名兜底：只认编译期元数据。
     *
     * <p>取不到就<b>直接失败</b>，不编造名字：
     * 未开启 {@code -parameters} 时反射拿到的是 {@code arg0} / {@code arg1}，编个 {@code param1}
     * 出去，模型就会拿着错误的 key 调参 —— 这种错在联调时极难定位。
     * 工具实例侧（{@code toolprovider} 的 {@code binding}）本来就是这么做的，两侧口径必须一致；
     * {@code INSTANCE.md} 描述的也是这条。</p>
     *
     * <p>早前的判据是 {@code startsWith("arg")}，把 {@code argName} / {@code argument} /
     * {@code args} 这些完全正常的业务参数名也误伤了 —— 判据只该匹配占位形态本身。</p>
     */
    private static String fallbackParamName(Parameter parameter, int index) {
        String reflected = parameter.getName();
        if (reflected == null || reflected.isBlank() || ARG_PLACEHOLDER.matcher(reflected).matches()) {
            throw new IllegalArgumentException("无法解析第 " + (index + 1) + " 个参数的名称（类型 "
                    + parameter.getType().getSimpleName() + "）。请用 @ToolParam(\"名称\") 显式命名，"
                    + "或给编译器加 -parameters（Spring Boot 父 pom 默认已开启，普通 Maven 工程需自行配置）");
        }
        return reflected;
    }

    /** 形参名占位形态：{@code arg} + 数字（{@code -parameters} 未开启时的反射产物） */
    private static final java.util.regex.Pattern ARG_PLACEHOLDER =
            java.util.regex.Pattern.compile("arg\\d+");

    private static List<String> enumValues(Class<?> type) {
        return Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
    }

    /**
     * Java 类型 → JSON Schema 类型名。
     *
     * <p>复杂对象（DTO / record）展开为 {@code object} 并递归其字段，让模型能构造出结构化参数。</p>
     */
    private static String jsonType(Class<?> type) {
        if (type == null) {
            return "object";
        }
        if (CharSequence.class.isAssignableFrom(type) || type == Character.class || type == char.class
                || type == UUID.class || Temporal.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type) || type == URI.class || type == URL.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class
                || type == BigInteger.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class
                || type == BigDecimal.class || Number.class.isAssignableFrom(type)) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        // DTO / Map / 其它复合类型
        return "object";
    }

    /** 拿裸类型：Class 直接返回；ParameterizedType 取 rawType；数组取元素类型 */
    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            return raw instanceof Class<?> clazz ? clazz : null;
        }
        if (type instanceof GenericArrayType array) {
            Type component = array.getGenericComponentType();
            return component instanceof Class<?> clazz ? clazz : null;
        }
        return null;
    }

    /** 参数说明缺失的提示（给管控台与启动日志用） */
    public static boolean hasDescription(ToolDescriptor.Param param) {
        return param != null
                && param.description() != null
                && !param.description().isBlank()
                && !param.description().startsWith(UN_DESCRIBED);
    }

    /** 递归统计"缺说明的参数"数量（含嵌套字段） */
    public static int countMissingDescription(List<ToolDescriptor.Param> params) {
        int missing = 0;
        for (ToolDescriptor.Param param : params) {
            if (!hasDescription(param)) {
                missing++;
            }
            missing += countMissingDescription(param.properties());
            missing += countMissingDescription(param.items());
        }
        return missing;
    }

    // ==================== @ToolAdvanced 的索引化视图 ====================

    /**
     * {@link ToolAdvanced} 按参数名索引后的形态：查名字即可拿到示例 / 白名单 / 是否敏感。
     *
     * <p>公开出来是给<b>工具实例侧</b>复用的 —— 两侧必须用同一套解析与同一段说明文本，
     * 否则同一段工具代码搬到另一侧就会得到不同的 schema（这正是本类要消除的问题）。</p>
     *
     * <p>写在 {@code 参数名=值} 里的格式错误（缺 {@code =}、参数名为空）会被<b>忽略并 WARN</b> ——
     * 静默丢弃会让"写了但没生效"变成一个查不出来的问题。</p>
     *
     * @param examples    参数名 → 示例值
     * @param allowValues 参数名 → 枚举白名单
     * @param sensitive   需要掩码的参数名
     */
    public record ParamOverrides(Map<String, String> examples,
                                 Map<String, List<String>> allowValues,
                                 Set<String> sensitive) {

        public static ParamOverrides of(Method method) {
            ToolAdvanced annotation = method == null ? null : method.getAnnotation(ToolAdvanced.class);
            if (annotation == null) {
                return new ParamOverrides(Map.of(), Map.of(), Set.of());
            }

            Map<String, String> examples = new LinkedHashMap<>();
            for (String entry : annotation.example()) {
                nameValue(entry, (name, value) -> examples.putIfAbsent(name, value));
            }

            Map<String, List<String>> allowValues = new LinkedHashMap<>();
            for (String entry : annotation.allowValues()) {
                nameValue(entry, (name, value) -> allowValues.put(name, Arrays.stream(value.split("\\|"))
                        .map(String::trim)
                        .filter(v -> !v.isBlank())
                        .toList()));
            }

            Set<String> sensitive = new LinkedHashSet<>();
            for (String name : annotation.sensitive()) {
                if (name != null && !name.isBlank()) {
                    sensitive.add(name.trim());
                } else {
                    log.warn("[参数解析] @ToolAdvanced.sensitive 含空白项，已忽略");
                }
            }
            return new ParamOverrides(examples, allowValues, sensitive);
        }

        public String exampleOf(String name) {
            // Map.of() 是 null 敌意的：匿名节点（如数组元素）没有名字，必须先挡掉
            return name == null ? "" : examples.getOrDefault(name, "");
        }

        public List<String> allowValuesOf(String name) {
            return name == null ? List.of() : allowValues.getOrDefault(name, List.of());
        }

        public boolean isSensitive(String name) {
            return name != null && sensitive.contains(name);
        }

        /**
         * 参数说明的最终形态：留空 → {@code （未描述）}；有示例则追加到末尾。
         *
         * <p>示例之所以并进说明而不是单独占一个字段：底层模型 schema（langchain4j 的
         * {@code JsonXxxSchema}）只有 {@code description} 一个自由文本位，额外字段会被丢掉 ——
         * 也就到不了模型眼前。已是"未描述"时改用分号连写，避免出现两对括号。</p>
         */
        public String describe(String name, String rawDescription) {
            String base = rawDescription == null || rawDescription.isBlank()
                    ? UN_DESCRIBED : rawDescription.trim();
            String example = exampleOf(name);
            if (example.isBlank() || base.contains(example)) {
                return base;
            }
            if (UN_DESCRIBED.equals(base)) {
                return UN_DESCRIBED + "；示例：" + example + "）";
            }
            return base + "（示例：" + example + "）";
        }

        /** 拆一个 {@code 参数名=值} 条目；不合法就 WARN 后跳过 */
        private static void nameValue(String entry, BiConsumer<String, String> sink) {
            if (entry == null) {
                return;
            }
            int split = entry.indexOf('=');
            if (split <= 0 || split == entry.length() - 1) {
                warn(entry);
                return;
            }
            String name = entry.substring(0, split).trim();
            String value = entry.substring(split + 1).trim();
            if (name.isEmpty() || value.isEmpty()) {
                warn(entry);
                return;
            }
            sink.accept(name, value);
        }

        private static void warn(String entry) {
            log.warn("[参数解析] @ToolAdvanced 的条目「{}」不是「参数名=值」形式，已忽略", entry);
        }
    }
}
