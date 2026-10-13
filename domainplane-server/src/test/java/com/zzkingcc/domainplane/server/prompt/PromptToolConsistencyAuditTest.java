package com.zzkingcc.domainplane.server.prompt;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词 ↔ 工具可见性自检：只认"提示词里点名了工具名"这一种不一致，且必须按词边界匹配。
 *
 * <p>提示词沿链拼接，因此传入的是每个域<b>合成后</b>的全文：祖先域的片段同样算数。</p>
 */
class PromptToolConsistencyAuditTest {

    /** 与注解扫描同构的工具描述符：只有名字与域声明是变量 */
    private static ToolDescriptor tool(String name, String... domains) {
        return new ToolDescriptor(name, name + " 的说明", "default", "1.0.0",
                Tool.Effect.READ, true, true,
                List.of(), List.of(domains), null, "test#" + name);
    }

    /** 各域合成后的提示词全文 */
    private static Map<String, String> prompts(String text, String... domains) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String domain : domains) {
            out.put(domain, text);
        }
        return out;
    }

    @Test
    void 提示词点名了不可见的工具就报出来() {
        List<ToolDescriptor> tools = List.of(tool("queryOrder", "default.customer"));
        String text = "你可以用 queryOrder 查订单。";

        List<String> mismatches = PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("default.customer", "default.admin"),
                prompts(text, "default.customer", "default.admin"));

        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).contains("queryOrder"));
        assertTrue(mismatches.get(0).contains("default.admin"), "只报它不可见的那个域");
        assertFalse(mismatches.get(0).contains("default.customer"));
    }

    @Test
    void 祖先域的片段会落到后代域身上() {
        // ping 只挂在 default.sales 上，但根域 default 的片段点名了它：
        // 根域片段会拼进每个后代域，于是 default 与 default.hr 都要报
        List<ToolDescriptor> tools = List.of(tool("ping", "default.sales"));
        Map<String, String> promptByDomain = new LinkedHashMap<>();
        promptByDomain.put("default", "用 ping 探活。");
        promptByDomain.put("default.sales", "用 ping 探活。");
        promptByDomain.put("default.hr", "用 ping 探活。");

        List<String> mismatches = PromptToolConsistencyAudit.findMismatches(
                tools, promptByDomain.keySet(), promptByDomain);

        assertEquals(2, mismatches.size(), "default.sales 可见，只有 root 与 hr 该报");
        assertTrue(mismatches.stream().allMatch(m -> m.contains("ping")));
        assertTrue(mismatches.stream().noneMatch(m -> m.contains("default.sales 的提示词")));
    }

    @Test
    void 挂根域的工具处处可见不该报() {
        // 声明留空 = 挂根域 = 对全树可见
        List<ToolDescriptor> tools = List.of(tool("ping"));
        String text = "用 ping 探活。";

        assertTrue(PromptToolConsistencyAudit.findMismatches(tools,
                Set.of("default", "default.sales", "default.hr"),
                prompts(text, "default", "default.sales", "default.hr")).isEmpty());
    }

    @Test
    void 一致时没有输出() {
        List<ToolDescriptor> tools = List.of(tool("queryOrder", "default.customer"), tool("ping"));
        String text = "你可以用 ping 探活。";

        assertTrue(PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("default.customer", "default.admin"),
                prompts(text, "default.customer", "default.admin")).isEmpty());

        assertTrue(PromptToolConsistencyAudit.findMismatches(
                List.of(), Set.of("default"), Map.of()).isEmpty(), "没有工具就没有不一致");
    }

    @Test
    void 提示词里没点名工具时什么都不报() {
        List<ToolDescriptor> tools = List.of(tool("queryOrder", "default.customer"));
        String text = "你可以帮用户查订单（用业务语言描述能力，不写工具名）。";

        assertTrue(PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("default.admin"), prompts(text, "default.admin")).isEmpty(),
                "只认工具名：中文口语描述能力属刻意漏报，避免误报");
    }

    @Test
    void 点名按词边界匹配() {
        assertTrue(PromptToolConsistencyAudit.mentions("调用 queryOrder 查订单", "queryOrder"));
        assertTrue(PromptToolConsistencyAudit.mentions("queryOrder", "queryOrder"), "整串即点名");
        assertTrue(PromptToolConsistencyAudit.mentions("用 `queryOrder` 查", "queryOrder"), "标点包裹算边界");
        assertFalse(PromptToolConsistencyAudit.mentions("调用 queryOrderDetail", "queryOrder"),
                "更长的标识符里出现同名子串不算点名");
        assertFalse(PromptToolConsistencyAudit.mentions("调用 xqueryOrder", "queryOrder"));
        assertFalse(PromptToolConsistencyAudit.mentions(null, "queryOrder"));
        assertFalse(PromptToolConsistencyAudit.mentions("随便写点什么", ""));
    }
}
