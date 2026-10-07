package com.zzkingcc.domainplane.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 敏感参数值掩码 —— 把工具调用参数里声明的敏感项替换成 {@link #MASK}。
 *
 * 为什么需要它：{@code @ToolAdvanced.sensitive} 声明的是"这些参数的值不要外露"，
 * 但参数原文一定会出现在两个地方 —— 推给客户端的工具调用事件与挂起等授权的审批 payload。
 * 掩码只作用于这两处的展示文本；真正交给工具执行、以及回喂模型的参数始终是原值，
 * 否则工具会拿到 {@code ***} 而彻底失效。
 *
 * 匹配是递归按键名的：敏感项既可能是顶层形参，也可能是 DTO 展开出的字段
 * （如 {@code args.phone}），逐层下钻才盖得住。名字命中就把整个值（标量 / 对象 / 数组）
 * 一起替换成掩码，宁可多盖也不漏。
 *
 * @author zzkingcc
 */
public final class SensitiveMasker {

    private static final Logger log = LoggerFactory.getLogger(SensitiveMasker.class);

    /** 掩码文案；固定长度、不含原值任何信息 */
    public static final String MASK = "***";

    /** 参数无法解析为 JSON 时的整段占位：不含任何原文，且明确告诉阅读者"这里本该有内容" */
    public static final String UNPARSEABLE = "[参数无法解析，已整体屏蔽]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SensitiveMasker() {
    }

    /**
     * 掩码参数 JSON。
     *
     * 解析失败时整段屏蔽，绝不原样返回。模型生成的 {@code arguments} 是畸形 JSON
     * 的常态，此时若"为了不丢原文"而原样返回，恰好把最需要保护的那批值（身份证、手机号、
     * 银行卡）明文送进工具调用事件与审批 payload —— 而这两处正是脱敏要守的地方。
     * 保护机制在失败时退化成不保护，比不保护更危险。
     *
     * 整段屏蔽而非逐字段兜底：解析不出来就无从知道哪些字段敏感，索性不给内容。
     * 代价是审批人看不到参数，但看不到比误批安全 —— {@link #isMasked(String)}
     * 能让调用方识别出这种情况并拒绝自动批准。
     *
     * @param argumentsJson  工具调用参数（可能为 null / 空白 / 非 JSON）
     * @param sensitiveNames 需要掩码的参数名（空即原样返回）
     * @return 掩码后的 JSON 文本
     */
    public static String mask(String argumentsJson, Set<String> sensitiveNames) {
        if (argumentsJson == null || argumentsJson.isBlank()
                || sensitiveNames == null || sensitiveNames.isEmpty()) {
            return argumentsJson;
        }
        try {
            JsonNode masked = maskNode(MAPPER.readTree(argumentsJson), sensitiveNames);
            return MAPPER.writeValueAsString(masked);
        } catch (Exception e) {
            // 留痕但不外泄原文：只记长度与工具无关的上下文
            log.warn("[脱敏] 工具参数不是合法 JSON（长度 {}），已整段屏蔽而非原样输出。"
                    + "解析失败时原样返回会让敏感值明文进入调用事件与审批 payload", argumentsJson.length());
            return UNPARSEABLE;
        }
    }

    /**
     * 这次展示文本是否被整体屏蔽了 —— 调用方据此拒绝"盲批"。
     *
     * 没有它，宿主看到的就是一个"参数为空"的审批项，与"参数本来就短"无法区分。
     */
    public static boolean isMasked(String maskedText) {
        return UNPARSEABLE.equals(maskedText);
    }

    private static JsonNode maskNode(JsonNode node, Set<String> sensitiveNames) {
        if (node instanceof ObjectNode object) {
            ObjectNode result = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (sensitiveNames.contains(field.getKey())) {
                    result.put(field.getKey(), MASK);
                } else {
                    result.set(field.getKey(), maskNode(field.getValue(), sensitiveNames));
                }
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = MAPPER.createArrayNode();
            for (JsonNode element : array) {
                result.add(maskNode(element, sensitiveNames));
            }
            return result;
        }
        return node;
    }
}
