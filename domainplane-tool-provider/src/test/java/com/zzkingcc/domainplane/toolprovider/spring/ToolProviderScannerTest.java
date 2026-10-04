package com.zzkingcc.domainplane.toolprovider.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolAdvanced;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.toolprovider.ToolHandler;
import com.zzkingcc.domainplane.toolprovider.ToolRegistrar;
import com.zzkingcc.domainplane.toolprovider.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注解扫描器的行为验证：方法签名 → ToolSpec、参数 JSON → 方法调用
 */
class ToolProviderScannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 方法签名变成工具声明与参数schema() {
        Map<String, ToolSpec> specs = new LinkedHashMap<>();
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DemoTools.class)) {
            int count = new ToolProviderScanner(ctx).registerTo(new Collector(specs, handlers));
            assertEquals(4, count);
        }

        ToolSpec note = specs.get("addNote");
        assertEquals("记一条备注", note.description());
        assertEquals(List.of("default.admin"), note.domains());
        assertEquals("WRITE", note.sideEffect());
        assertTrue(note.requiresApproval(), "声明了 ALWAYS 审批就应需要人工确认");
        assertEquals("ALWAYS", note.approvalMode());

        JsonNode parameters = note.parameters();
        assertEquals("object", parameters.path("type").asText());
        assertTrue(parameters.path("required").toString().contains("text"));
        assertEquals("string", parameters.path("properties").path("text").path("type").asText());
        assertEquals("备注内容", parameters.path("properties").path("text").path("description").asText());
    }

    @Test
    void ToolAdvanced的示例白名单与敏感落到schema() {
        Map<String, ToolSpec> specs = new LinkedHashMap<>();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DemoTools.class)) {
            new ToolProviderScanner(ctx).registerTo(new Collector(specs, new LinkedHashMap<>()));
        }

        JsonNode properties = specs.get("rebind").parameters().path("properties");

        // ① 示例：既进 example 字段，也追加到模型唯一看得见的说明里
        assertEquals("FR2024001", properties.path("orderNo").path("example").asText());
        assertEquals("订单号（示例：FR2024001）", properties.path("orderNo").path("description").asText());

        // ② 枚举白名单：成为 schema 的 enum
        assertEquals("string", properties.path("channel").path("type").asText());
        assertEquals("[\"SMS\",\"APP\"]", properties.path("channel").path("enum").toString());

        // ③ 敏感：上报 x-sensitive，服务端据此把值掩码
        assertTrue(properties.path("phone").path("x-sensitive").asBoolean());
        assertEquals("新手机号（示例：13800000000）", properties.path("phone").path("description").asText());

        // 没声明的参数不该被牵连
        assertTrue(properties.path("orderNo").path("x-sensitive").isMissingNode());
        assertTrue(properties.path("channel").path("example").isMissingNode());
    }

    @Test
    void 参数JSON反射调用方法() throws Exception {
        Map<String, ToolSpec> specs = new LinkedHashMap<>();
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DemoTools.class)) {
            new ToolProviderScanner(ctx).registerTo(new Collector(specs, handlers));
        }

        assertEquals("已记录: 张三申请退款",
                handlers.get("addNote").handle("{\"text\":\"张三申请退款\"}"));

        // 带类型参数：int / 枚举 / List 全部由签名推导并绑定
        assertEquals("城市 1 个：杭州，类型 VIP",
                handlers.get("countCities").handle("{\"count\":1,\"city\":\"杭州\",\"level\":\"VIP\"}"));

        // 缺必填参数：抛异常交给 SDK 包装成工具失败原因
        assertThrows(IllegalArgumentException.class, () -> handlers.get("addNote").handle("{}"));
    }

    @Test
    void 工具名重复直接失败() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(DuplicatedTools.class)) {
            assertThrows(IllegalStateException.class,
                    () -> new ToolProviderScanner(ctx).registerTo(new Collector(new LinkedHashMap<>(), new LinkedHashMap<>())));
        }
    }

    // ==================== 被测用的工具 Bean ====================

    static class DemoTools {

        @Tool(desc = "记一条备注", value = "addNote", domains = {"default.admin"},
                effect = Tool.Effect.WRITE, approval = Tool.Approval.ALWAYS,
                approvalReason = "写操作需确认")
        public String addNote(@ToolParam("备注内容") String text) {
            return "已记录: " + text;
        }

        @Tool(desc = "统计城市数量，用于演示类型化参数绑定")
        public String countCities(int count, String city, Level level) {
            return "城市 " + count + " 个：" + city + "，类型 " + level;
        }

        @Tool(desc = "按订单号改绑手机号", value = "rebind", domains = {"default.admin"},
                effect = Tool.Effect.WRITE, approval = Tool.Approval.ALWAYS,
                approvalReason = "改绑需人工核对")
        @ToolAdvanced(example = {"orderNo=FR2024001", "phone=13800000000"},
                allowValues = {"channel=SMS|APP"},
                sensitive = {"phone"})
        public String rebind(@ToolParam("订单号") String orderNo,
                             @ToolParam("通知渠道") String channel,
                             @ToolParam("新手机号") String phone) {
            return "已改绑: " + orderNo + "/" + channel + "/" + phone;
        }

        @Tool(desc = "无参数工具")
        public String ping() {
            return "pong";
        }
    }

    enum Level {
        NORMAL, VIP
    }

    static class DuplicatedTools {

        @Tool(value = "sameName", desc = "第一个")
        public String first() {
            return "1";
        }

        @Tool(value = "sameName", desc = "第二个")
        public String second() {
            return "2";
        }
    }

    /** 把注册结果收进 Map，便于断言 */
    private record Collector(Map<String, ToolSpec> specs, Map<String, ToolHandler> handlers) implements ToolRegistrar {
        @Override
        public ToolRegistrar register(ToolSpec spec, ToolHandler handler) {
            specs.put(spec.name(), spec);
            handlers.put(spec.name(), handler);
            return this;
        }
    }
}
