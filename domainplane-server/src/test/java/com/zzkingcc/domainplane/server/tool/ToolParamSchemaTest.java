package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 远端工具上报 schema → 描述符参数：嵌套必须递归展开，否则声明在 DTO 子字段上的
 * 示例 / 白名单 / 敏感会在"远端实例"这种形态下凭空消失（本地 Bean 形态却有）。
 */
class ToolParamSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 嵌套参数被递归展开且语义不丢() throws Exception {
        JsonNode parameters = MAPPER.readTree("""
                {"type":"object","properties":{
                   "orderNo":{"type":"string","description":"订单号（示例：FR1）","example":"FR1"},
                   "channel":{"type":"string","enum":["SMS","APP"]},
                   "contact":{"type":"object","properties":{
                       "phone":{"type":"string","description":"手机号","x-sensitive":true}},
                       "required":["phone"]}},
                 "required":["orderNo"]}
                """);

        List<ToolDescriptor.Param> params = ToolParamSchema.toParams(parameters);

        assertEquals(3, params.size());

        ToolDescriptor.Param orderNo = params.get(0);
        assertEquals("FR1", orderNo.example());
        assertTrue(orderNo.required());

        ToolDescriptor.Param channel = params.get(1);
        assertEquals("enum", channel.type());
        assertEquals(List.of("SMS", "APP"), channel.allowValues());

        ToolDescriptor.Param contact = params.get(2);
        assertEquals("object", contact.type());
        assertEquals(1, contact.properties().size(), "DTO 子字段必须被展开");
        assertEquals("phone", contact.properties().get(0).name());
        assertTrue(contact.properties().get(0).sensitive(), "子字段上的 x-sensitive 也要读出来");
    }

    @Test
    void 非对象或空参数返回空列表() throws Exception {
        assertEquals(List.of(), ToolParamSchema.toParams(null));
        assertEquals(List.of(), ToolParamSchema.toParams(MAPPER.readTree("\"nope\"")));
        assertEquals(List.of(), ToolParamSchema.toParams(MAPPER.readTree("{\"type\":\"object\"}")));
    }
}
