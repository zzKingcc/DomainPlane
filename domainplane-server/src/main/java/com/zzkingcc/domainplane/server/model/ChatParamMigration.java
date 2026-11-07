package com.zzkingcc.domainplane.server.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存量对话档案的「参数键」一次性归一 —— 把适配 schema 时代的 UI 名换成线上字段名。
 *
 * <p>为什么需要它：适配 schema 在时，档案 {@code params} 的键是 UI 侧标识（{@code reasoningEffort}），
 * 注入前由 {@code ModelClientFactory#effectiveParams} 按 schema 翻成线上字段（{@code reasoning_effort}）。
 * 对话 schema 下架后那条翻译没了，而<b>存量的键还是 UI 名</b> —— 不迁移就等于把
 * {@code reasoningEffort} 原样发出去，上游静默忽略、HTTP 200 照回，表现为"卡片写着已配置、
 * 实际一个字都没生效"。这类故障在对话时才暴露，比启动报错难查得多。</p>
 *
 * <p>迁移只做一次、必须幂等：跑第二遍时 map 里已经没有旧键，返回 {@code null} 表示"无需改写"。
 * 键名已经是线上字段名的档案一个字节都不动。</p>
 *
 * <p>映射表刻意只收 {@code adapters/deepseek-flash.json} 曾经登记过、且 UI 名与 wire 名不同的四项：
 * 别的一看就知道线上键（{@code stop} / {@code logprobs}），{@code temperature} / {@code maxTokens}
 * 走档案老字段本来就不在 {@code params} 里。表里多写一条就多一次误伤用户手写键的机会。</p>
 *
 * @author zzkingcc
 */
final class ChatParamMigration {

    private ChatParamMigration() {
    }

    /**
     * 一条映射：{@code key} 是旧 UI 名，{@code wire} 是线上字段名。
     *
     * @param wrap 非空时把标量包一层单键对象（如 {@code responseFormat: "json_object"}
     *             → {@code response_format: {"type": "json_object"}}）；适配 schema 时代这层包装
     *             由 {@code wireWrap} 声明，翻译层删掉后只剩这张表记得这件事
     */
    private record Rule(String key, String wire, String wrap) {
    }

    private static final java.util.List<Rule> RULES = java.util.List.of(
            new Rule("reasoningEffort", "reasoning_effort", null),
            new Rule("topP", "top_p", null),
            new Rule("topLogprobs", "top_logprobs", null),
            new Rule("responseFormat", "response_format", "type"));

    /**
     * 归一一份 params。
     *
     * @return 改写后的新 map；没有任何旧键时返回 {@code null}（调用方据此决定要不要落盘）
     */
    static Map<String, Object> migrate(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        boolean hit = false;
        for (Rule rule : RULES) {
            if (params.containsKey(rule.key())) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Rule rule = ruleOf(entry.getKey());
            if (rule == null) {
                out.put(entry.getKey(), entry.getValue());
                continue;
            }
            /* 线上键已经在了：以它为准，旧键直接丢。两条并存时谁生效取决于服务商读哪个，
               平台能确定的只有"不该同时存在" */
            if (params.containsKey(rule.wire())) {
                continue;
            }
            Object value = entry.getValue();
            if (rule.wrap() != null && value != null && !(value instanceof Map<?, ?>)) {
                value = Map.of(rule.wrap(), value);
            }
            out.put(rule.wire(), value);
        }
        return out;
    }

    private static Rule ruleOf(String key) {
        for (Rule rule : RULES) {
            if (rule.key().equals(key)) {
                return rule;
            }
        }
        return null;
    }
}
