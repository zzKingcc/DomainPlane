package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolAdvanced;
import com.zzkingcc.domainplane.api.annotation.ToolDomains;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新注解 {@code @Tool} 与 DTO 参数展开的回归测试。
 *
 * <p>这里盯的是两条曾经出错、且改坏了不会立即被发现的规则：</p>
 * <ol>
 *   <li>DTO / record 参数必须展开成嵌套的 object，模型才能构造出结构化参数；</li>
 *   <li>类级 {@code @ToolDomains} 必须被方法继承。</li>
 * </ol>
 *
 * @author zzkingcc
 */
class ToolAnnotationScanTest {

    /** 参数说明标在 record 的字段上（只写一次，可被多个工具复用） */
    public record OrderQuery(@ToolParam("订单号，如 FR2024001") String orderNo,
                             @ToolParam("是否返回明细") Boolean detail) {
    }

    /** 嵌套载体：字段名（phone）才是 @ToolAdvanced 要对上的名字 */
    public record Contact(@ToolParam("手机号") String phone) {
    }

    @ToolDomains("default.admin")
    static class DemoTools {

        @Tool(desc = "按条件查询订单。用户追问发货/物流时调用")
        public String query(OrderQuery args) {
            return "ok";
        }

        @Tool(desc = "关闭订单。用户明确要求取消时调用",
                effect = Tool.Effect.WRITE,
                approval = Tool.Approval.ALWAYS,
                approvalReason = "关单需人工确认")
        public String close(@ToolParam("订单号") String orderNo) {
            return "ok";
        }

        @Tool(desc = "按订单号改绑手机号")
        @ToolAdvanced(example = {"phone=13800000000"},
                allowValues = {"channel=SMS|APP"},
                sensitive = {"phone"})
        public String rebind(@ToolParam("订单号") String orderNo,
                             @ToolParam("渠道") String channel,
                             @ToolParam("手机号") String phone) {
            return "ok";
        }

        @Tool(desc = "设置联系方式（演示 @ToolAdvanced 命中 DTO 字段名）")
        @ToolAdvanced(sensitive = {"phone"})
        public String setContact(Contact contact) {
            return "ok";
        }

        @Tool(desc = "批量查询订单（演示 List<DTO> 的元素结构在本地也被保留）")
        public String batch(@ToolParam(name = "orders", value = "订单列表") List<OrderQuery> orders) {
            return "ok";
        }
    }

    private static ToolDescriptor find(List<ToolRegistry.Registered> tools, String name) {
        return tools.stream()
                .map(ToolRegistry.Registered::descriptor)
                .filter(descriptor -> name.equals(descriptor.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到工具 " + name));
    }

    @Test
    void registersToolsFromNewAnnotation() {
        List<ToolRegistry.Registered> tools = AnnotatedToolScanner.scan(new DemoTools());
        assertEquals(5, tools.size(), "@Tool 标注的方法都应被注册");
    }

    @Test
    void dtoParameterIsExpandedAsObject() {
        ToolDescriptor query = find(AnnotatedToolScanner.scan(new DemoTools()), "query");

        assertEquals(1, query.params().size());
        ToolDescriptor.Param args = query.params().get(0);

        // 关键断言：DTO 不能被退化成 string，否则模型产出字符串、反序列化必然失败
        assertEquals("object", args.type(), "DTO 参数必须展开为 object");
        assertEquals(2, args.properties().size(), "record 的字段应被展开");

        ToolDescriptor.Param orderNo = args.properties().get(0);
        assertEquals("orderNo", orderNo.name());
        assertEquals("订单号，如 FR2024001", orderNo.description(), "字段上的 @ToolParam 必须被读取");
        assertEquals("string", orderNo.type());
    }

    @Test
    void classLevelDomainsAndApprovalAreInherited() {
        ToolDescriptor close = find(AnnotatedToolScanner.scan(new DemoTools()), "close");

        assertTrue(close.domains().contains("default.admin"), "应继承类级 @ToolDomains");
        assertTrue(close.requiresApproval(), "@Tool(approval = ALWAYS) 应生效");
        assertEquals("关单需人工确认", close.approval().reason());
        assertEquals("WRITE", close.sideEffect().name());
    }

    @Test
    void missingParamDescriptionIsCounted() {
        // 未写参数说明的工具：能注册成功，但缺说明要被统计出来（由扫描器打 WARN）
        ToolDescriptor close = find(AnnotatedToolScanner.scan(new DemoTools()), "close");
        assertFalse(com.zzkingcc.domainplane.api.tool.ParamSchemaResolver
                        .countMissingDescription(List.of(close.params().get(0))) > 0,
                "本例的 close 写了参数说明，不应计为缺失");
    }

    @Test
    void advancedFieldsLandOnTheirParams() {
        ToolDescriptor rebind = find(AnnotatedToolScanner.scan(new DemoTools()), "rebind");
        Map<String, ToolDescriptor.Param> byName = rebind.params().stream()
                .collect(Collectors.toMap(ToolDescriptor.Param::name, param -> param));

        // 示例：进 Param.example，并追加到模型唯一看得见的说明里
        assertEquals("13800000000", byName.get("phone").example(), "@ToolAdvanced.example 应落到对应参数");
        assertEquals("手机号（示例：13800000000）", byName.get("phone").description());
        assertTrue(byName.get("orderNo").example().isEmpty(), "没声明的参数不该被牵连");

        // 白名单：把类型抬成 enum，并带上取值
        assertEquals("enum", byName.get("channel").type());
        assertEquals(List.of("SMS", "APP"), byName.get("channel").allowValues());

        // 敏感：落到 Param.sensitive，供事件与审批 payload 做值掩码
        assertTrue(byName.get("phone").sensitive());
        assertFalse(byName.get("orderNo").sensitive());
    }

    @Test
    void advancedAlsoMatchesNestedDtoFieldNames() {
        ToolDescriptor setContact = find(AnnotatedToolScanner.scan(new DemoTools()), "setContact");

        ToolDescriptor.Param contact = setContact.params().get(0);
        assertEquals("object", contact.type());
        assertEquals(1, contact.properties().size());
        assertTrue(contact.properties().get(0).sensitive(),
                "@ToolAdvanced 的名字要对得上 DTO 展开出的字段名");
    }

    @Test
    void arrayOfDtoKeepsElementSchemaInLocalTree() {
        ToolDescriptor batch = find(AnnotatedToolScanner.scan(new DemoTools()), "batch");
        assertEquals(1, batch.params().size());

        ToolDescriptor.Param orders = batch.params().get(0);
        assertEquals("array", orders.type(), "List<DTO> 在本地也必须是数组");
        assertEquals(1, orders.items().size(), "数组元素结构不能被丢掉（否则模型构造不出元素）");
        ToolDescriptor.Param element = orders.items().get(0);
        assertEquals("object", element.type(), "元素要展开成对象");
        assertEquals(2, element.properties().size(), "元素要能展开出 DTO 子字段");
    }
}
