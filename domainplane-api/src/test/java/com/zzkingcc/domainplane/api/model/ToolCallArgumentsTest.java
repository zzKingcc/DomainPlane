package com.zzkingcc.domainplane.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 ToolCall #40：getArguments() 上的 @JsonRawValue 必须把"非合法 JSON"收敛成可安全内联的片段，
 * 否则模型生成的残缺参数拼进响应会让整条 JSON 结构损坏，客户端连正常字段也拿不到。
 *
 * <ul>
 *   <li>null / 空白 -> 序列化为 JSON null；</li>
 *   <li>非法 JSON 片段 -> 转义成 JSON 字符串字面量（响应结构仍合法）；</li>
 *   <li>合法 JSON -> 原样透出（不做任何加引号）。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@DisplayName("ToolCall：getArguments 的 @JsonRawValue 收敛")
class ToolCallArgumentsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 把 ToolCall 整对象序列化后再读回，取出 arguments 节点的 JSON 形态。 */
    private JsonNode argumentsOf(String raw) throws Exception {
        ToolCall call = ToolCall.of("search", raw, false);
        String json = mapper.writeValueAsString(call);
        // 若 arguments 非法且未收敛，这里产出的就是坏 JSON，readTree 会直接抛异常
        return mapper.readTree(json).get("arguments");
    }

    @Test
    @DisplayName("null 参数 -> 序列化为 JSON null（不是字符串 \"null\"）")
    void nullArgumentsSerializesAsJsonNull() throws Exception {
        JsonNode args = argumentsOf(null);
        assertTrue(args.isNull(), "null 参数应序列化为 JSON null");
    }

    @Test
    @DisplayName("空白参数 -> 序列化为 JSON null")
    void blankArgumentsSerializesAsJsonNull() throws Exception {
        JsonNode args = argumentsOf("   \t  ");
        assertTrue(args.isNull(), "空白参数应序列化为 JSON null");
    }

    @Test
    @DisplayName("非法 JSON 片段 -> 转义成 JSON 字符串，响应仍合法")
    void invalidJsonFragmentEscapedAsString() throws Exception {
        JsonNode args = argumentsOf("not a json");
        assertTrue(args.isTextual(), "非法片段应退化为 JSON 字符串，实际: " + args);
        assertEquals("not a json", args.asText());
    }

    @Test
    @DisplayName("合法 JSON 对象 -> 原样透出（raw，不被加引号）")
    void validJsonPassedThroughAsRawObject() throws Exception {
        JsonNode args = argumentsOf("{\"a\":1,\"b\":\"x\"}");
        assertTrue(args.isObject(), "合法 JSON 应原样透出为对象，实际: " + args);
        assertEquals(1, args.get("a").asInt());
        assertEquals("x", args.get("b").asText());
    }
}
