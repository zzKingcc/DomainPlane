package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolAdvanced;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.api.tool.ParamSchemaResolver;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两端 schema 产出统一（⑧）的"对拍"保证。
 *
 * <p>核心断言：工具实例侧（{@link ParamSchemaResolver#toWireSchema} 渲染出的上报报文）
 * 被服务端（{@link ToolParamSchema#toParams}）解析后，得到的参数树必须<b>逐棵</b>等于
 * {@link ParamSchemaResolver#resolve} 算出来的那棵树 —— 即"工具实例上报的"与"服务端本地扫描的"
 * 是同一份真相，同一段工具代码搬到另一侧不会得到不同的 schema。</p>
 *
 * <p>覆盖的口径：标量、整型、布尔、枚举白名单、数组（{@code List<DTO>} 与 {@code List<String>}）、
 * 嵌套 DTO、以及 {@code @ToolAdvanced} 的示例 / 白名单 / 敏感（含命中 DTO 字段名）。</p>
 */
class SchemaUnificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Address(@ToolParam("省") String province, @ToolParam("市") String city) {
    }

    public record Contact(@ToolParam("手机号") String phone) {
    }

    static class UnifiedTools {

        @Tool(desc = "演示统一的参数树：覆盖各类形态")
        @ToolAdvanced(example = {"name=张三"}, allowValues = {"channel=SMS|APP"}, sensitive = {"phone"})
        public String demo(@ToolParam(name = "name", value = "姓名") String name,
                          @ToolParam(name = "count", value = "数量") int count,
                          @ToolParam(name = "enabled", value = "是否启用") boolean enabled,
                          @ToolParam(name = "channel", value = "通知渠道") String channel,
                          @ToolParam(name = "contact", value = "联系方式") Contact contact,
                          @ToolParam(name = "addresses", value = "地址列表") List<Address> addresses,
                          @ToolParam(name = "tags", value = "标签") List<String> tags) {
            return "ok";
        }
    }

    @Test
    void 上报报文被服务端原样解析回同一棵参数树() throws Exception {
        Method method = UnifiedTools.class.getMethod("demo", String.class, int.class, boolean.class,
                String.class, Contact.class, List.class, List.class);

        List<ToolDescriptor.Param> resolved = ParamSchemaResolver.resolve(method);
        Map<String, Object> wire = ParamSchemaResolver.toWireSchema(resolved);

        // 经 Jackson 归一化后再解析，避免 Map 实现差异干扰
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(wire));
        List<ToolDescriptor.Param> parsed = ToolParamSchema.toParams(json);

        assertEquals(resolved, parsed,
                "工具实例上报的参数树必须被服务端逐棵解析回来（统一的核心保证）");
    }

    @Test
    void 上报报文的线格式正确且稳定() throws Exception {
        Method method = UnifiedTools.class.getMethod("demo", String.class, int.class, boolean.class,
                String.class, Contact.class, List.class, List.class);

        Map<String, Object> wire = ParamSchemaResolver.toWireSchema(ParamSchemaResolver.resolve(method));
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(wire));
        JsonNode properties = json.path("properties");

        // ① 示例：进 example 字段，也并进模型唯一看得见的说明
        assertEquals("张三", properties.path("name").path("example").asText());
        assertTrue(properties.path("name").path("description").asText().contains("张三"));

        // ② 枚举白名单：成为 schema 的 enum
        assertEquals("string", properties.path("channel").path("type").asText());
        assertEquals("[\"SMS\",\"APP\"]", properties.path("channel").path("enum").toString());

        // ③ 敏感：命中 DTO 字段名，上报 x-sensitive
        assertTrue(properties.path("contact").path("properties").path("phone")
                .path("x-sensitive").asBoolean());

        // ④ 数组（List<DTO>）：元素结构必须展开成对象，不能退化成字符串
        assertEquals("array", properties.path("addresses").path("type").asText());
        assertEquals("object", properties.path("addresses").path("items").path("type").asText());
        assertTrue(properties.path("addresses").path("items").path("properties").has("province"));
        assertTrue(properties.path("addresses").path("items").path("properties").has("city"));

        // ⑤ 数组（List<String>）：元素类型一并保留
        assertEquals("array", properties.path("tags").path("type").asText());
        assertEquals("string", properties.path("tags").path("items").path("type").asText());
    }
}
