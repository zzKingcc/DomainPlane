package com.zzkingcc.domainplane.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

/**
 * 解析 LLM 生成的审批工具调用描述的json，用于回写给用户。
 *
 * @author zzkingcc
 */
@Getter
@ToString
public final class ToolCall {

    /**
     * 只用于判定"这段字符串能不能原样内联进 JSON"，不参与任何业务序列化。
     *
     * 打开尾部校验：{@code readTree} 默认吃掉第一个完整值就返回，
     * {@code {}{@code x}} 这种"合法前缀 + 垃圾尾部"会被误判为合法，拼进响应仍是坏 JSON。
     */
    private static final ObjectMapper RAW_CHECKER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final String name;
    /** LLM 生成的原始参数 JSON;为 null 时序列化为 JSON null */
    private final String arguments;
    /** 该工具是否被 {@code @RequireApproval} 标记 */
    private final boolean requireApproval;

    @JsonCreator
    public ToolCall(@JsonProperty("name") String name,
                    @JsonProperty("arguments") String arguments,
                    @JsonProperty("requireApproval") boolean requireApproval) {
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = safeRaw(arguments);
        this.requireApproval = requireApproval;
    }

    public static ToolCall of(String name, String arguments, boolean requireApproval) {
        return new ToolCall(name, arguments, requireApproval);
    }

    /**
     * 可安全内联的参数字符串：合法 JSON 原样返回，null / 空白返回 {@code "null"}，
     * 非法片段降级为 JSON 字符串字面量（见 {@link #safeRaw}）。
     */
    @JsonRawValue
    public String getArguments() {
        return arguments;
    }

    /**
     * 把模型生成的参数字符串收敛成"能直接拼进响应 JSON 的片段"。
     *
     * {@code @JsonRawValue} 不做任何校验，参数原文一旦不是合法 JSON（模型截断、夹带注释、
     * 尾部多写一个逗号都是常态），拼进去的整条响应就结构损坏：客户端解析失败，
     * 连正常字段也拿不到。这里只改"展示形态"，不丢原文：
     * 非法时退化成 JSON 字符串，原文照样可读，响应结构保持合法。
     */
    private static String safeRaw(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "null";
        }
        try {
            RAW_CHECKER.readTree(arguments);
            return arguments;
        } catch (Exception e) {
            try {
                return RAW_CHECKER.writeValueAsString(arguments);
            } catch (Exception ignored) {
                return "\"[参数不是合法 JSON]\"";
            }
        }
    }
}
