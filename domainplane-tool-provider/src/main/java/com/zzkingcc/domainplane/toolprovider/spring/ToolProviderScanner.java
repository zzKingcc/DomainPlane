package com.zzkingcc.domainplane.toolprovider.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolDomains;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.api.tool.ParamSchemaResolver;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.toolprovider.ToolHandler;
import com.zzkingcc.domainplane.toolprovider.ToolRegistrar;
import com.zzkingcc.domainplane.toolprovider.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 注解式工具扫描器 —— 在客户进程里把"方法"直接变成"工具"
 *
 * 解决什么
 * 编程式注册要写 {@code registrar.register(ToolSpec.of(...), this::method)}，
 * 参数 schema、副作用等级、审批策略全都堆在一段与业务方法分离的代码里。
 * 本扫描器让这些治理信息回到方法本身：
 *
 * @Tool(desc = "退款", value = "refundOrder", domains = {"default.admin"},
 *           effect = Tool.Effect.WRITE, approval = Tool.Approval.ALWAYS,
 *           approvalReason = "退款需人工确认")
 * public String refundOrder(@ToolParam("订单号") String orderNo,
 *                           @ToolParam("退款金额，单位：元") BigDecimal amount) { ... }
 *
 * 方法签名即参数 schema，注解即治理策略，方法体即执行逻辑 —— 三者合一。
 *
 * 什么时候扫
 * 在 {@code ToolInstanceClient} 装配时执行（见 {@link ToolInstanceAutoConfiguration}），
 * 早于心跳启动。扫描只取"类型"做筛选，只对命中的 Bean 触发实例化，不做全容器提前初始化。
 *
 * 失败策略
 * 参数名解析不出、工具名重复、{@code desc} 缺失 —— 全部启动期直接抛异常。
 * 工具声明错了却等到模型来调用才发现，排查成本远高于启动失败。
 *
 * @author zzkingcc
 */
public final class ToolProviderScanner {

    private static final Logger log = LoggerFactory.getLogger(ToolProviderScanner.class);

    private final ListableBeanFactory beanFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public ToolProviderScanner(ListableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory 不能为空");
    }

    /**
     * 扫描容器内所有 Bean，把标注了 {@link Tool} 的方法注册给 registrar。
     *
     * @return 注册成功的工具数量
     */
    public int registerTo(ToolRegistrar registrar) {
        Map<String, String> owners = new LinkedHashMap<>();     // 工具名 → 声明位置（重名检测用）
        List<String> registered = new ArrayList<>();

        for (String beanName : beanFactory.getBeanNamesForType(Object.class, true, false)) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null || isInfrastructure(type)) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);

            List<Method> methods = annotatedMethods(userClass);
            if (methods.isEmpty()) {
                continue;
            }
            // 只有命中注解才取实例：避免为扫描而提前初始化整个容器
            Object bean = beanFactory.getBean(beanName);

            for (Method method : methods) {
                Tool annotation = method.getAnnotation(Tool.class);
                Method target = ClassUtils.getMostSpecificMethod(method, userClass);
                String toolName = annotation.value().isBlank() ? method.getName() : annotation.value().trim();
                String where = userClass.getName() + "#" + method.getName();

                String previous = owners.put(toolName, where);
                if (previous != null) {
                    throw new IllegalStateException("工具名重复：" + toolName
                            + " 同时声明于 " + previous + " 与 " + where
                            + "。工具名全局唯一（同名即同一工具的多个副本），请修改 @Tool 的 value");
                }

                ScanBinding binding = binding(target, where);
                registrar.register(spec(annotation, userClass, target, binding, where), handler(bean, binding));
                registered.add(toolName);
            }
        }

        if (!registered.isEmpty()) {
            log.info("[工具实例] 注解扫描注册 {} 个工具 {}", registered.size(), registered);
        }
        return registered.size();
    }

    // ==================== 扫描 ====================

    /**
     * 收集该类（及其接口）上带 {@link Tool} 的方法。
     *
     * 接口也要扫：JDK 动态代理下注解只留在接口方法上，实现类的方法拿不到。
     */
    private List<Method> annotatedMethods(Class<?> userClass) {
        List<Method> raw = new ArrayList<>(List.of(userClass.getMethods()));
        for (Class<?> itf : ClassUtils.getAllInterfacesForClass(userClass)) {
            raw.addAll(List.of(itf.getMethods()));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Method> methods = new ArrayList<>();
        for (Method method : raw) {
            if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getAnnotation(Tool.class) == null) {
                continue;
            }
            if (seen.add(signature(method))) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static String signature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName());
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append('|').append(paramType.getName());
        }
        return sb.toString();
    }

    /**
     * 跳过 Spring 自身的基础设施 Bean：扫它们只会带来噪音，且可能触发不该触发的初始化。
     */
    private static boolean isInfrastructure(Class<?> type) {
        String name = type.getName();
        // 只排除框架自身：扫它们只会带来噪音，且可能触发不该触发的初始化。
        // 不排除 DomainPlane 的包 —— SDK 自己的 Bean 上不会有 @Tool，按注解命中即可。
        return name.startsWith("org.springframework.") || name.startsWith("java.");
    }

    // ==================== 声明（ToolSpec） ====================

    private ToolSpec spec(Tool annotation, Class<?> userClass, Method method, ScanBinding binding, String where) {
        if (annotation.desc().isBlank()) {
            throw new IllegalStateException("@Tool.desc 必填（它是模型判断何时调用的唯一依据）：" + where);
        }

        // 参数结构一律交给 ParamSchemaResolver —— 与本地 Bean 工具共用同一份实现，
        // 保证"同一段工具代码搬到另一侧得到同一个 schema"（这正是 ⑧ 要消除的不一致）。
        // 顶层参数名以绑定侧（Spring 参数名发现）为准：无 -parameters 时 resolver 只能用 arg0 兜底，
        // 而 handler 靠 Spring 拿到真实名；名字不一致模型就调不对参，所以这里按 binding 重新落键。
        List<ToolDescriptor.Param> resolved = ParamSchemaResolver.resolve(method);
        List<ParamMeta> meta = binding.params();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (int i = 0; i < resolved.size() && i < meta.size(); i++) {
            ToolDescriptor.Param param = resolved.get(i);
            properties.put(meta.get(i).name(), ParamSchemaResolver.toWireNode(param));
            if (param.required()) {
                required.add(meta.get(i).name());
            }
        }

        boolean requiresApproval = annotation.approval() == Tool.Approval.ALWAYS;

        if (annotation.effect() == Tool.Effect.DESTRUCTIVE && !requiresApproval) {
            log.warn("[工具实例] {} 声明为 DESTRUCTIVE 但未配置审批：破坏性操作应当在被执行前中断。"
                            + "请补 @Tool(approval = Tool.Approval.ALWAYS, approvalReason = ...)",
                    where);
        }

        // 可用域：方法级 @Tool(domains=...) 优先；留空时回落到类级 @ToolDomains
        List<String> raw = new ArrayList<>();
        if (annotation.domains().length > 0) {
            raw.addAll(List.of(annotation.domains()));
        } else {
            ToolDomains classLevel = userClass.getAnnotation(ToolDomains.class);
            if (classLevel != null) {
                raw.addAll(List.of(classLevel.value()));
            }
        }
        List<String> domains = raw.stream()
                .map(String::trim)
                .filter(p -> !p.isBlank())
                .distinct()
                .toList();

        // 合法性在本地就拦下来：非法域发到服务端会被整包拒绝，不如启动期就报清楚是哪个方法
        String toolName = annotation.value().isBlank() ? method.getName() : annotation.value().trim();
        for (String domain : domains) {
            String reason = Domains.validatePath(domain);
            if (reason != null) {
                throw new IllegalStateException("@Tool(" + toolName + ", domains = {\"" + domain
                        + "\"}) 不合法（" + reason + "）：域标识须为从 " + Domains.DEFAULT
                        + " 出发的完整路径，如 default.sales");
            }
        }

        return new ToolSpec(
                toolName,
                annotation.desc(),
                "default",
                "1.0.0",
                domains,
                annotation.effect().name(),
                true,
                true,
                requiresApproval,
                requiresApproval ? "ALWAYS" : "",
                annotation.approvalReason(),
                ToolSpec.schema(properties, required.toArray(new String[0])));
    }

    // ==================== 参数绑定 ====================

    /**
     * 解析方法参数：名字、类型、是否必填。
     *
     * 参数名优先取 {@code @ToolParam.name}，其次取编译期元数据 / 调试信息。
     * 取不到就直接失败 —— 用 {@code arg0} 当参数名注册出去，模型会拿着错误的 key 调参，
     * 这种错在联调时极难定位。
     */
    private ScanBinding binding(Method method, String where) {
        Parameter[] parameters = method.getParameters();
        String[] discovered = parameterNames.getParameterNames(method);

        List<ParamMeta> params = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String name = annotation != null && !annotation.name().isBlank()
                    ? annotation.name().trim()
                    : (discovered != null && i < discovered.length ? discovered[i] : null);

            if (name == null || name.isBlank()) {
                throw new IllegalStateException("无法解析参数名：" + where + " 的第 " + (i + 1) + " 个参数（"
                        + parameter.getType().getSimpleName() + "）。"
                        + "请用 @ToolParam(name = \"...\") 显式命名，或给编译器加 -parameters"
                        + "（Spring Boot 父 pom 默认已开启，普通 Maven 工程需自行配置）");
            }
            params.add(new ParamMeta(name, parameter.getParameterizedType(), annotation == null || annotation.required(), annotation));
        }
        return new ScanBinding(method, params, ParamSchemaResolver.ParamOverrides.of(method));
    }

    // ==================== 执行 ====================

    /**
     * 生成一个执行体：参数 JSON → 反射调用 → 结果文本。
     */
    private ToolHandler handler(Object bean, ScanBinding binding) {
        Method method = binding.method();
        ReflectionUtils.makeAccessible(method);

        return argumentsJson -> {
            JsonNode root = parse(argumentsJson);
            Object[] args = new Object[binding.params().size()];
            for (int i = 0; i < args.length; i++) {
                ParamMeta meta = binding.params().get(i);
                JsonNode value = root.get(meta.name());
                if (value == null || value.isNull()) {
                    if (meta.required()) {
                        // 参数缺失本身就是一次正常的工具返回，抛异常会被 SDK 包装成失败原因回喂模型
                        throw new IllegalArgumentException("缺少必填参数: " + meta.name());
                    }
                    args[i] = defaultValue(meta.type());
                } else {
                    args[i] = convert(value, meta.type());
                }
            }

            try {
                return toText(method.invoke(bean, args));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("工具方法不可访问: " + method, e);
            }
        };
    }

    private JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(argumentsJson);
        } catch (Exception e) {
            // 模型偶尔会吐出非法 JSON：按空参处理，让业务方法自己在缺失参数上给出结果
            log.warn("[工具实例] 工具参数不是合法 JSON，按空参数处理: {}", argumentsJson);
            return mapper.createObjectNode();
        }
    }

    private Object convert(JsonNode value, Type type) {
        if (type == String.class) {
            return value.isValueNode() ? value.asText() : value.toString();
        }
        return mapper.convertValue(value, mapper.getTypeFactory().constructType(type));
    }

    private static Object defaultValue(Type type) {
        if (!(type instanceof Class<?> c) || !c.isPrimitive()) {
            return null;
        }
        if (c == boolean.class) {
            return false;
        }
        if (c == char.class) {
            return '\0';
        }
        if (c == float.class) {
            return 0F;
        }
        if (c == double.class) {
            return 0D;
        }
        if (c == long.class) {
            return 0L;
        }
        if (c == int.class) {
            return 0;
        }
        if (c == short.class) {
            return (short) 0;
        }
        if (c == byte.class) {
            return (byte) 0;
        }
        return null;
    }

    /**
     * 返回值转文本：字符串原样返回（绝大多数工具返回的是自然语言结果），其余序列化成 JSON。
     */
    private String toText(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof CharSequence text) {
            return text.toString();
        }
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    // ==================== 内部数据 ====================

    /** 一个方法参数：名字、泛型类型、是否必填、语义注解 */
    private record ParamMeta(String name, Type type, boolean required, ToolParam annotation) {
    }

    /**
     * 一个工具方法的可调用物：{@link Method} + 解析好的参数列表 + 方法级
     * {@code @ToolAdvanced} 的索引视图（示例 / 白名单 / 敏感；与服务端共用同一份解析）。
     */
    private record ScanBinding(Method method, List<ParamMeta> params,
                               ParamSchemaResolver.ParamOverrides overrides) {
    }
}
