package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolDomains;
import com.zzkingcc.domainplane.api.tool.ParamSchemaResolver;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地 Bean 工具的注解扫描器：扫 {@code @Tool} 方法，产出 {@code ToolDescriptor}
 * 注册进 {@link ToolRegistry}，工具就在本进程内执行。
 *
 * 与 {@code toolprovider} 模块的 {@code ToolProviderScanner} 是两侧：
 * 那侧扫的是"要注册到远端服务端的工具"，产出 {@code ToolSpec} 上报，本地不留副本。
 * 两边职责不同、产物不同，故类名不同，不要当成同一个概念的两份实现。
 *
 * @author zzkingcc
 */
public final class AnnotatedToolScanner {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedToolScanner.class);

    private AnnotatedToolScanner() {
    }

    /**
     * 扫描一个工具提供者实例。
     *
     * 方法上有 {@code @Tool} 即算工具。
     *
     * 注解从「用户类」取，执行走「代理类」：Spring 的 CGLIB / JDK 代理会重写方法，
     * 而方法注解不随重写继承，直接扫 {@code provider.getClass()} 会让
     * {@code @Transactional} / {@code @Async} 切过的 Service 上的 {@code @Tool} 全部扫不到
     * （工具凭空消失，且只留一行 WARN）。反过来若拿用户类的 {@code Method} 去反射调用，
     * 又会绕过代理链、让切面失效。两件事必须分开：元数据查用户类，invoke 用代理类的方法。
     *
     * @param provider 工具提供者 Bean（可能是代理实例）
     * @return 该 Bean 上所有工具方法的注册项（可能为空）
     */
    public static List<ToolRegistry.Registered> scan(Object provider) {
        List<ToolRegistry.Registered> registered = new ArrayList<>();
        if (provider == null) {
            return registered;
        }

        Class<?> proxyClass = provider.getClass();
        Class<?> userClass = userClassOf(proxyClass);

        for (Method declared : annotatedMethods(userClass)) {
            // 执行目标：优先用代理类上真正会被调用的那个方法，保住切面链
            Method executable = executableMethod(provider, proxyClass, declared);
            ToolDescriptor descriptor = describe(userClass, declared);
            if (descriptor == null) {
                continue;
            }
            String name = descriptor.name();

            List<ToolDescriptor.Param> params = descriptor.params();
            int missing = ParamSchemaResolver.countMissingDescription(params);
            if (missing > 0) {
                // 不阻断：缺说明只是"模型要猜"，但必须让人看见
                log.warn("[工具扫描] {} 有 {} 个参数缺少说明，可能影响调用准确率；"
                        + "建议在形参前或参数 DTO 的字段上加 @ToolParam(\"…\")", name, missing);
            }

            ToolSpecification specification = ToolSpecification.builder()
                    .name(name)
                    .description(descriptor.description())
                    .parameters(toJsonSchema(params))
                    .build();

            // 复用 LangChain4j 的参数反序列化：arguments(JSON) → 方法参数
            DefaultToolExecutor executor = new DefaultToolExecutor(provider, executable);

            registered.add(ToolRegistry.Registered.local(descriptor, specification, executor));
        }

        if (registered.isEmpty()) {
            log.warn("[工具扫描] {} 未实现任何 @Tool 方法，已注册 0 个工具",
                    userClass.getName());
        }
        return registered;
    }

    /**
     * 剥掉 Spring 生成的代理类，回到用户类：CGLIB 代理的类名形如 {@code Xxx$$SpringCGLIB$$0}。
     *
     * JDK 动态代理（{@code com.sun.proxy.$ProxyN}）的父类是 {@code java.lang.reflect.Proxy}，
     * 类名里没有 {@code $$}，这里剥不掉，那种情况由 {@link #annotatedMethods} 扫接口兜住。
     */
    private static Class<?> userClassOf(Class<?> candidate) {
        Class<?> current = candidate;
        while (current.getName().contains("$$")) {
            Class<?> parent = current.getSuperclass();
            if (parent == null || parent == Object.class) {
                break;
            }
            current = parent;
        }
        return current;
    }

    /**
     * 收集带 {@link Tool} 的方法：用户类自己的 + 各接口上的，按签名去重。
     *
     * 接口必须一起扫：JDK 动态代理下注解只写在接口方法上，实现类的方法拿不到。
     */
    private static List<Method> annotatedMethods(Class<?> userClass) {
        List<Method> candidates = new ArrayList<>(List.of(userClass.getMethods()));
        for (Class<?> itf : allInterfacesOf(userClass)) {
            candidates.addAll(List.of(itf.getMethods()));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Method> methods = new ArrayList<>();
        for (Method method : candidates) {
            if (method.isSynthetic() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getAnnotation(Tool.class) == null) {
                continue;
            }
            if (seen.add(signatureOf(method))) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static List<Class<?>> allInterfacesOf(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            for (Class<?> itf : current.getInterfaces()) {
                if (seen.add(itf)) {
                    out.add(itf);
                    queue.add(itf);
                }
            }
        }
        return out;
    }

    /**
     * 找到「真正会被调用」的那个方法：代理类上同签名的那一份。
     *
     * CGLIB 代理重写了方法，拿到它才能让调用经过代理链（切面生效）；
     * JDK 代理的代理类上没有实现类的方法，{@code getMethod} 会抛
     * {@link NoSuchMethodException}，此时退回用户类/接口方法，对 JDK 代理而言
     * 接口方法 invoke 同样会经过代理。
     */
    private static Method executableMethod(Object provider, Class<?> proxyClass, Method declared) {
        try {
            Method onProxy = proxyClass.getMethod(declared.getName(), declared.getParameterTypes());
            return onProxy.getDeclaringClass() == Object.class ? declared : onProxy;
        } catch (NoSuchMethodException e) {
            return declared;
        }
    }

    private static String signatureOf(Method method) {
        StringBuilder sb = new StringBuilder(method.getName());
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append('|').append(paramType.getName());
        }
        return sb.toString();
    }

    /**
     * 由方法上的注解解析出工具描述；不是工具方法时返回 {@code null}。
     *
     * 参数结构一律交给 {@link ParamSchemaResolver}：与工具实例 SDK 共用同一份实现，
     * 避免"同一段工具代码搬到另一侧得到不同 schema"。
     */
    private static ToolDescriptor describe(Class<?> declaringClass, Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        if (tool == null) {
            return null;
        }

        String source = declaringClass.getSimpleName() + "#" + method.getName();
        String name = firstNonBlank(tool.value(), method.getName());
        String description = tool.desc();

        List<String> domains = resolveDomains(declaringClass, tool);
        ToolDescriptor.Approval approval = resolveApproval(tool);

        return new ToolDescriptor(
                name,
                description,
                "default",
                "1.0.0",
                tool.effect(),
                true,
                true,
                List.copyOf(ParamSchemaResolver.resolve(method)),
                List.copyOf(domains),
                approval,
                source);
    }

    /** {@code @Tool(approval = ALWAYS)} → Approval 记录 */
    private static ToolDescriptor.Approval resolveApproval(Tool tool) {
        if (tool.approval() != Tool.Approval.ALWAYS) {
            return ToolDescriptor.Approval.none();
        }
        return new ToolDescriptor.Approval("ALWAYS", "", tool.approvalReason(), List.of(), 0);
    }

    /**
     * 可用域：方法级 {@code @Tool#domains()} → 类级 {@code @ToolDomains} → 留空。
     *
     * 这里不在留空时回填根域：留空本身有语义（挂根域、全树可见），由
     * {@link ToolDescriptor#declaredDomains()} 统一解释，避免两处判断各说各话。
     *
     * 声明了就必须是合法完整路径：非法直接中断启动。放过它的话，
     * 这个字符串会照样进"已声明域"集合、让入口放行，等于凭空造出一个可用的域。
     */
    private static List<String> resolveDomains(Class<?> declaringClass, Tool tool) {
        String[] raw = tool.domains().length > 0 ? tool.domains() : null;
        if ((raw == null || raw.length == 0) && declaringClass != null) {
            ToolDomains classLevel = declaringClass.getAnnotation(ToolDomains.class);
            if (classLevel != null && classLevel.value().length > 0) {
                raw = classLevel.value();
            }
        }
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<String> domains = Arrays.stream(raw)
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        for (String domain : domains) {
            String reason = Domains.validatePath(domain);
            if (reason != null) {
                throw new IllegalStateException("@Tool(domains = {\"" + domain + "\"}) 不合法（" + reason
                        + "）：域标识须为从 " + Domains.DEFAULT + " 出发的完整路径，如 default.sales");
            }
        }
        return domains;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    // 参数结构（含 DTO 递归展开、深度与循环防护、形参/字段两种注解载体）统一由 ParamSchemaResolver 提供，
    // 与工具实例 SDK 共用同一份实现：避免同一段工具代码在两侧得到不同的 schema。

    /**
     * 由参数描述生成 JSON Schema
     */
    private static JsonObjectSchema toJsonSchema(List<ToolDescriptor.Param> params) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();

        for (ToolDescriptor.Param param : params) {
            builder.addProperty(param.name(), toSchemaElement(param));
            if (param.required()) {
                required.add(param.name());
            }
        }

        if (!required.isEmpty()) {
            builder.required(required.toArray(new String[0]));
        }
        return builder.build();
    }

    private static JsonSchemaElement toSchemaElement(ToolDescriptor.Param param) {
        String description = param.description();
        return switch (param.type()) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            // DTO / record：把子字段递归展开成嵌套 object，模型才知道该构造什么
            case "object" -> objectSchema(param);
            case "enum" -> JsonEnumSchema.builder()
                    .description(description)
                    .enumValues(param.allowValues())
                    .build();
            case "array" -> {
                // 数组元素结构：本地工具与远端工具必须用同一套口径展开，否则同样是 List<DTO>，
                // 本地给模型看"数组套字符串"、远端给"数组套对象"，同一段工具代码两种形态不一致
                JsonSchemaElement item = param.items().isEmpty()
                        ? JsonStringSchema.builder().build()
                        : toSchemaElement(param.items().get(0));
                yield JsonArraySchema.builder().description(description).items(item).build();
            }
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /**
     * 嵌套对象：子字段递归展开；没有子字段时给一个"空对象"而不是退化成字符串：
     * 退化成字符串会让模型产出字符串，反序列化到 DTO 必然失败。
     */
    private static JsonSchemaElement objectSchema(ToolDescriptor.Param param) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
                .description(param.description());
        List<String> required = new ArrayList<>();
        for (ToolDescriptor.Param child : param.properties()) {
            builder.addProperty(child.name(), toSchemaElement(child));
            if (child.required()) {
                required.add(child.name());
            }
        }
        if (!required.isEmpty()) {
            builder.required(required.toArray(new String[0]));
        }
        return builder.build();
    }
}
